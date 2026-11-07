package io.github.mkp.pfl.fitness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.Node;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FitnessTest {

  static final Instant T0 = Instant.parse("2026-07-06T07:00:00Z");

  static FitnessConfig config() throws Exception {
    return FitnessConfig.load(Path.of("../config/fitness.json"));
  }

  static Work person(String activity, String team, int startMin, int endMin) {
    return new Work(activity, Node.Actor.PERSON, team, T0, T0.plus(Duration.ofMinutes(startMin)), T0.plus(Duration.ofMinutes(endMin)));
  }

  static CaseOutcome recall(String id, int amount, double hours, List<Work> work) {
    return new CaseOutcome(id, Map.of("inquiryType", "RECALL", "amount", amount), T0,
        T0.plus(Duration.ofMinutes(Math.round(hours * 60))), work, true);
  }

  @Test
  void computesKpisFromOutcomes() throws Exception {
    Kpis kpis = new Kpis(config());
    List<CaseOutcome> cases = List.of(
        recall("a", 500, 10, List.of(person("register_inquiry", "payments-ops", 0, 30))),
        recall("b", 50_000, 30, List.of(person("second_approval", "team-leads", 0, 30), person("send_recall", "payments-ops", 30, 60))),
        recall("c", 50_000, 20, List.of(person("send_recall", "payments-ops", 0, 60))),
        new CaseOutcome("d", Map.of("inquiryType", "QUERY"), T0, T0.plus(Duration.ofHours(1)), List.of(), false));
    Map<Kpi, Double> v = kpis.compute(cases);
    assertEquals(20.0, v.get(Kpi.CYCLE_P50_HOURS), 1e-9, "median of resolved cases 10, 20, 30");
    assertEquals(28.0, v.get(Kpi.CYCLE_P90_HOURS), 1e-9);
    assertEquals(1.0 / 3, v.get(Kpi.SLA_BREACH_RATE), 1e-9, "one of three resolved recalls is over 24 hours");
    assertEquals((29.0 + 46.0 + 29.0 + 58.0) / 4, v.get(Kpi.COST_PER_CASE_EUR), 1e-9);
    assertEquals(1.0, v.get(Kpi.TOUCHES_PER_CASE), 1e-9);
    assertEquals(0.5, v.get(Kpi.FOUR_EYES_VIOLATION_RATE), 1e-9, "c sent a 50,000 recall without approval");
    assertEquals(0.25, v.get(Kpi.OPEN_CASE_RATE), 1e-9);
  }

  @Test
  void baselineScoresZeroTargetsScoreOneAndConstraintsBlock() throws Exception {
    FitnessConfig config = config();
    Map<Kpi, Double> baseline = new EnumMap<>(Kpi.class);
    baseline.put(Kpi.CYCLE_P90_HOURS, 60.0);
    baseline.put(Kpi.SLA_BREACH_RATE, 0.30);
    baseline.put(Kpi.COST_PER_CASE_EUR, 55.0);
    baseline.put(Kpi.TOUCHES_PER_CASE, 5.0);
    FitnessFunction f = new FitnessFunction(config, baseline);

    Map<Kpi, Double> today = new EnumMap<>(baseline);
    today.put(Kpi.FOUR_EYES_VIOLATION_RATE, 0.0);
    today.put(Kpi.OPEN_CASE_RATE, 0.0);
    assertEquals(0.0, f.score(today).score(), 1e-12);

    Map<Kpi, Double> target = new EnumMap<>(today);
    target.put(Kpi.CYCLE_P90_HOURS, 24.0);
    target.put(Kpi.SLA_BREACH_RATE, 0.05);
    target.put(Kpi.COST_PER_CASE_EUR, 25.0);
    target.put(Kpi.TOUCHES_PER_CASE, 2.0);
    FitnessScore atTarget = f.score(target);
    assertEquals(1.0, atTarget.score(), 1e-12);
    assertTrue(atTarget.feasible());

    Map<Kpi, Double> halfway = new EnumMap<>(today);
    halfway.put(Kpi.SLA_BREACH_RATE, 0.175);
    assertEquals(0.35 * 0.5, f.score(halfway).score(), 1e-12, "only the SLA KPI moved, halfway");

    Map<Kpi, Double> worse = new EnumMap<>(today);
    worse.put(Kpi.COST_PER_CASE_EUR, 200.0);
    assertEquals(-0.25, f.score(worse).score(), 1e-12, "progress is capped at -1 per KPI");

    Map<Kpi, Double> unsafe = new EnumMap<>(target);
    unsafe.put(Kpi.FOUR_EYES_VIOLATION_RATE, 0.02);
    FitnessScore blocked = f.score(unsafe);
    assertEquals(1.0, blocked.score(), 1e-12, "the score alone would look perfect");
    assertFalse(blocked.feasible());
    assertTrue(blocked.breaches().get(0).startsWith("four_eyes_violation_rate"));
  }

  @Test
  void refusesAConfigWhoseWeightsDoNotSumToOne() {
    assertThrows(IllegalArgumentException.class, () -> new FitnessConfig("p", "end", Map.of(), "t", Map.of(),
        new FitnessConfig.Automation(0, 0), null, List.of(new FitnessConfig.KpiWeight("cycle_p90_hours", 0.5, 1)), List.of()));
  }

  @Test
  void percentileMatchesTheUsualDefinition() {
    assertEquals(2.5, Kpis.percentile(new double[] {1, 2, 3, 4}, 0.5), 1e-12);
    assertEquals(3.7, Kpis.percentile(new double[] {1, 2, 3, 4}, 0.9), 1e-12);
    assertEquals(7.0, Kpis.percentile(new double[] {7}, 0.9), 1e-12);
  }
}
