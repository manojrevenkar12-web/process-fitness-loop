package io.github.mkp.pfl.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.Event;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.log.Lifecycle;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.FixedBehaviour;
import io.github.mkp.pfl.sim.Simulator;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.WorkingCalendar;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenReplayTest {

  static final Instant T0 = Instant.parse("2026-07-06T07:00:00Z");

  static ProcessModel load(String name) throws Exception {
    return new BpmnParser().parse(Path.of("../processes", name));
  }

  /** Forty cases of every type, a quarter of them with missing details on first contact. */
  static EventLog simulatedAsIsHistory() throws Exception {
    List<CaseSeed> seeds = new ArrayList<>();
    String[] types = {"RECALL", "NON_RECEIPT", "QUERY"};
    for (int i = 0; i < 40; i++) {
      seeds.add(new CaseSeed("c" + i, T0.plus(Duration.ofMinutes(37L * i)),
          Map.of("inquiryType", types[i % 3], "amount", i % 2 == 0 ? 25_000 : 900, "currency", "EUR")));
    }
    FixedBehaviour behaviour = new FixedBehaviour().outputs(ctx -> {
      if (!ctx.nodeId().equals("register_inquiry")) {
        return Map.of();
      }
      boolean incompleteFirst = Integer.parseInt(ctx.caseId().substring(1)) % 4 == 0;
      return Map.of("infoComplete", !(incompleteFirst && ctx.occurrence() == 1));
    });
    Staffing staffing = new Staffing(Map.of("payments-ops", 3, "team-leads", 1), WorkingCalendar.weekdays("Europe/Berlin", 8, 18));
    return new Simulator(load("payment-investigation-as-is.bpmn"), staffing)
        .run(seeds, behaviour, T0.plus(Duration.ofDays(30))).log();
  }

  @Test
  void historyProducedByTheModelFitsTheModelPerfectly() throws Exception {
    ConformanceReport report = new TokenReplay(load("payment-investigation-as-is.bpmn")).replay(simulatedAsIsHistory());
    assertEquals(1.0, report.fitness(), 1e-12);
    assertEquals(1.0, report.perfectlyFitting(), 1e-12);
    assertTrue(report.deviations().isEmpty(), report.deviations().toString());
  }

  @Test
  void theDocumentedProcessMissesTheReworkLoop() throws Exception {
    ConformanceReport report = new TokenReplay(load("payment-investigation-as-documented.bpmn")).replay(simulatedAsIsHistory());
    assertTrue(report.fitness() < 1.0);
    assertEquals(0.75, report.perfectlyFitting(), 1e-12, "the 10 of 40 cases with a missing-details round trip do not fit");
    assertTrue(report.deviations().stream().allMatch(d -> d.cases() == 10), report.deviations().toString());
    assertTrue(report.deviations().stream().anyMatch(d -> d.kind() == Deviation.Kind.RAN_WHEN_NOT_ALLOWED
        && d.elementId().equals("register_inquiry")), "registering twice is not in the documented process");
    assertTrue(report.deviations().stream().anyMatch(d -> d.kind() == Deviation.Kind.NOT_IN_MODEL && d.elementId().equals("request_info")));
    assertEquals(10, report.deviations().stream().filter(d -> d.elementId().equals("request_info")).findFirst().orElseThrow().cases());
  }

  @Test
  void skippedFourEyesApprovalIsCaughtBecauseGatewaysReadTheData() throws Exception {
    ProcessModel model = load("payment-investigation-as-is.bpmn");
    List<Event> events = new ArrayList<>();
    Instant t = T0;
    for (String step : List.of("register_inquiry", "lookup_payment", "check_funds", "send_recall", "close_case", "resolved")) {
      t = t.plus(Duration.ofMinutes(10));
      events.add(new Event(step, Lifecycle.COMPLETE, t, "ops-01",
          step.equals("register_inquiry") ? Map.of("infoComplete", true) : Map.of()));
    }
    CaseRecord big = new CaseRecord("big", T0, Map.of("inquiryType", "RECALL", "amount", 48_000), events);
    CaseRecord small = new CaseRecord("small", T0, Map.of("inquiryType", "RECALL", "amount", 4_000), events);
    TokenReplay replay = new TokenReplay(model);

    assertTrue(replay.replay(small).perfect(), "below the limit no approval is needed");
    CaseFit fit = replay.replay(big);
    assertEquals(1, fit.missing());
    assertTrue(fit.problems().contains("RAN_WHEN_NOT_ALLOWED:send_recall"), fit.problems().toString());
    assertTrue(fit.problems().contains("LEFT_UNDONE:second_approval"), fit.problems().toString());

    ConformanceReport report = replay.replay(new EventLog("payment-investigation", List.of(big, small)));
    Deviation skipped = report.deviations().stream().filter(d -> d.elementId().equals("send_recall")).findFirst().orElseThrow();
    assertTrue(skipped.message().contains("'Send recall' ran in 1 of 2 cases (50.0%)"), skipped.message());
    assertTrue(skipped.message().contains("'Second approval'"), skipped.message());
  }

  @Test
  void unknownDataLetsEitherBranchFit() throws Exception {
    List<Event> events = new ArrayList<>();
    Instant t = T0;
    for (String step : List.of("register_inquiry", "lookup_payment", "trace_payment", "notify_customer", "close_case")) {
      t = t.plus(Duration.ofMinutes(10));
      events.add(new Event(step, Lifecycle.COMPLETE, t, null, Map.of()));
    }
    CaseFit fit = new TokenReplay(load("payment-investigation-as-is.bpmn"))
        .replay(new CaseRecord("legacy", T0, Map.of(), events));
    assertTrue(fit.perfect(), "a legacy export without variables is not blamed for gateways it cannot decide: " + fit);
  }
}
