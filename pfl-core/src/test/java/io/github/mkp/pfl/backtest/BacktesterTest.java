package io.github.mkp.pfl.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.bpmn.TestBpmn;
import io.github.mkp.pfl.fitness.FitnessConfig;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.FixedBehaviour;
import io.github.mkp.pfl.sim.Simulator;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.WorkingCalendar;
import io.github.mkp.pfl.util.Draws;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BacktesterTest {

  static final Instant MONDAY = Instant.parse("2026-07-06T06:00:00Z");
  static final Staffing ROSTER = new Staffing(Map.of("payments-ops", 3, "team-leads", 1), WorkingCalendar.weekdays("Europe/Berlin", 8, 18));
  static final BacktestSettings FAST = new BacktestSettings(2, 120, 7, List.of("inquiryType", "channel"), 10, 0.9, 0.25,
      ZoneId.of("Europe/Berlin"));

  static ProcessModel asIs;
  static FitnessConfig config;
  static EventLog history;

  @BeforeAll
  static void history() throws Exception {
    asIs = new BpmnParser().parse(Path.of("../processes/payment-investigation-as-is.bpmn"));
    config = FitnessConfig.load(Path.of("../config/fitness.json"));
    List<CaseSeed> seeds = new ArrayList<>();
    String[] types = {"RECALL", "NON_RECEIPT", "QUERY"};
    SplittableRandom r = Draws.rng(1, "test-arrivals");
    Instant t = MONDAY;
    for (int i = 0; i < 400; i++) {
      t = t.plus(Duration.ofMinutes(5 + r.nextInt(40)));
      seeds.add(new CaseSeed("c" + i, t, Map.of("inquiryType", types[r.nextInt(3)], "amount", 1_000 + r.nextInt(40_000),
          "currency", "EUR", "channel", r.nextBoolean() ? "SWIFT" : "EMAIL")));
    }
    // the history was made by two people although the roster lists three
    Staffing real = ROSTER.withTeamSize("payments-ops", 2);
    FixedBehaviour behaviour = new FixedBehaviour().defaultMinutes(9).minutes("lookup_payment", 14).minutes("answer_query", 20)
        .outputs(ctx -> ctx.nodeId().equals("register_inquiry")
            ? Map.of("infoComplete", ctx.occurrence() > 1 || Draws.rng(3, ctx.caseId()).nextDouble() < 0.8)
            : Map.of());
    history = new Simulator(asIs, real).run(seeds, behaviour, t.plus(Duration.ofDays(60))).log();
  }

  static Backtester backtester() {
    return new Backtester(asIs, history, config, ROSTER, FAST);
  }

  static Proposal proposal(String id, Map<String, Proposal.StepEvidence> steps) {
    return new Proposal(id, id, "test", "", Map.of(), steps, "tester");
  }

  @Test
  void readsTheRealTeamSizeFromHistoryNotTheRoster() {
    Backtester b = backtester();
    assertEquals(2, b.effectiveStaffing().teams().get("payments-ops"));
    assertTrue(b.staffingNotes().get(0).contains("roster lists 3 people in payments-ops, the history shows 2"));
  }

  @Test
  void unchangedProcessProjectsNoChange() {
    BacktestResult r = backtester().run(proposal("no-op", Map.of()), asIs);
    assertEquals(BacktestResult.Verdict.UNCLEAR, r.verdict(), r.reasons().toString());
    assertEquals(0.0, r.score().estimate(), 1e-12, "same process, same draws: exactly zero");
    assertEquals(1.0, r.evidence().get(EvidenceTier.SAME_CASE), 1e-12);
  }

  @Test
  void automatingTheBusiestPersonStepImproves() throws Exception {
    ProcessModel candidate = new BpmnParser().parse(Path.of("../proposals/hub-connector.bpmn"));
    Proposal.Assumption connector = new Proposal.Assumption(0.2, 0.6, Map.of("PAYMENT_NOT_FOUND", 0.05), Map.of(), 1.5,
        "staging", true, null);
    BacktestResult r = backtester().run(proposal("hub", Map.of(
        "hub_lookup", new Proposal.StepEvidence(null, null, connector),
        "manual_lookup", new Proposal.StepEvidence("lookup_payment", null, null))), candidate);
    assertEquals(BacktestResult.Verdict.IMPROVES, r.verdict(), r.reasons().toString());
    assertTrue(r.delta().get("cycle_p90_hours").estimate() < 0);
    assertTrue(r.delta().get("touches_per_case").estimate() < -0.8, "almost every case loses a person step");
    assertTrue(r.evidence().get(EvidenceTier.ASSUMED) > 0);
  }

  @Test
  void unmeasuredAssumptionRequiresAShadowRun() throws Exception {
    ProcessModel candidate = new BpmnParser().parse(Path.of("../proposals/agent-intake.bpmn"));
    Proposal.Assumption agent = new Proposal.Assumption(0.5, 1.5, Map.of(), Map.of(), 2.0, "vendor figures", false, List.of(0.9, 1.0));
    BacktestResult r = backtester().run(proposal("agent", Map.of(
        "classify_inquiry", new Proposal.StepEvidence(null, "register_inquiry", agent))), candidate);
    assertEquals(BacktestResult.Verdict.NEEDS_SHADOW_RUN, r.verdict());
    assertTrue(r.reasons().get(0).contains("classify_inquiry"));
  }

  @Test
  void crossingTheFourEyesLimitIsBlockedWhateverTheScore() throws Exception {
    ProcessModel candidate = new BpmnParser().parse(Path.of("../proposals/raise-four-eyes-limit.bpmn"));
    BacktestResult r = backtester().run(proposal("limit", Map.of()), candidate);
    assertEquals(BacktestResult.Verdict.BLOCKED, r.verdict());
    assertTrue(r.breaches().get(0).startsWith("four_eyes_violation_rate"), r.breaches().toString());
  }

  @Test
  void unsoundCandidateIsBlockedBeforeAnythingRuns() {
    ProcessModel broken = TestBpmn.process("p").start("s").xor("g", "f2").userTask("a", "A", "payments-ops")
        .userTask("b", "B", "payments-ops").and("j").end("e")
        .flow("f0", "s", "g").flow("f1", "g", "a", "=amount > 5").flow("f2", "g", "b").flow("f3", "a", "j").flow("f4", "b", "j")
        .flow("f5", "j", "e").parse();
    BacktestResult r = backtester().run(proposal("broken", Map.of()), broken);
    assertEquals(BacktestResult.Verdict.BLOCKED, r.verdict());
    assertTrue(r.reasons().stream().anyMatch(x -> x.startsWith("verifier:")), r.reasons().toString());
  }

  @Test
  void newWorkWithoutEvidenceIsRefused() throws Exception {
    ProcessModel candidate = new BpmnParser().parse(Path.of("../proposals/hub-connector.bpmn"));
    EvidencePlan.MissingEvidence e = assertThrows(EvidencePlan.MissingEvidence.class,
        () -> EvidencePlan.build(proposal("x", Map.of()), candidate, asIs, new HistoryIndex(history, List.of())));
    assertTrue(e.problems().stream().anyMatch(p -> p.startsWith("'hub_lookup'")), e.problems().toString());
    assertTrue(e.problems().stream().anyMatch(p -> p.startsWith("'manual_lookup'")), e.problems().toString());
    BacktestResult r = backtester().run(proposal("x", Map.of()), candidate);
    assertEquals(BacktestResult.Verdict.BLOCKED, r.verdict());
  }

  @Test
  void sameSeedSameResult() throws Exception {
    ProcessModel candidate = new BpmnParser().parse(Path.of("../proposals/raise-four-eyes-limit.bpmn"));
    assertEquals(backtester().run(proposal("a", Map.of()), candidate), backtester().run(proposal("a", Map.of()), candidate));
  }
}
