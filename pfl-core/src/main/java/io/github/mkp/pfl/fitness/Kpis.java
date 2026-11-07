package io.github.mkp.pfl.fitness;

import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.feel.Expr;
import io.github.mkp.pfl.feel.Feel;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes every {@link Kpi} from case outcomes, the same way for observed and simulated cases. */
public final class Kpis {

  private final FitnessConfig config;
  private final Expr fourEyesApplies;

  public Kpis(FitnessConfig config) {
    this.config = config;
    this.fourEyesApplies = config.fourEyes() == null ? null : Feel.parse(config.fourEyes().appliesWhen());
  }

  public Map<Kpi, Double> compute(List<CaseOutcome> cases) {
    Map<Kpi, Double> values = new EnumMap<>(Kpi.class);
    List<CaseOutcome> resolved = cases.stream().filter(CaseOutcome::finished).toList();
    double[] cycle = resolved.stream().mapToDouble(CaseOutcome::cycleHours).sorted().toArray();
    values.put(Kpi.CYCLE_P50_HOURS, percentile(cycle, 0.50));
    values.put(Kpi.CYCLE_P90_HOURS, percentile(cycle, 0.90));
    long breaches = resolved.stream().filter(c -> c.cycleHours() > config.slaFor(c.attributes())).count();
    values.put(Kpi.SLA_BREACH_RATE, resolved.isEmpty() ? 0.0 : (double) breaches / resolved.size());
    values.put(Kpi.COST_PER_CASE_EUR, cases.stream().mapToDouble(this::cost).average().orElse(0));
    values.put(Kpi.TOUCHES_PER_CASE,
        cases.stream().mapToDouble(c -> c.work().stream().filter(w -> w.actor() == Node.Actor.PERSON).count()).average().orElse(0));
    values.put(Kpi.PERSON_MINUTES_PER_CASE,
        cases.stream().mapToDouble(c -> c.work().stream().filter(w -> w.actor() == Node.Actor.PERSON)
            .mapToDouble(Work::serviceMinutes).sum()).average().orElse(0));
    values.put(Kpi.FOUR_EYES_VIOLATION_RATE, fourEyesViolationRate(cases));
    values.put(Kpi.OPEN_CASE_RATE, cases.isEmpty() ? 0.0 : 1.0 - (double) resolved.size() / cases.size());
    return values;
  }

  /** Cost of one case: people's time at their team's rate, automated steps per call. */
  public double cost(CaseOutcome c) {
    double eur = 0;
    for (Work w : c.work()) {
      switch (w.actor()) {
        case PERSON -> {
          FitnessConfig.Role role = config.roles().get(w.role());
          if (role == null) {
            throw new IllegalStateException("no hourly cost configured for team '" + w.role() + "'");
          }
          eur += w.serviceMinutes() / 60.0 * role.eurPerHour();
        }
        case AGENT -> eur += config.automation().agentEurPerCall();
        case SYSTEM, RULE -> eur += config.automation().systemEurPerCall();
        case NONE -> {
          // gateways and events cost nothing
        }
      }
    }
    return eur;
  }

  /** True when the case falls under the four-eyes control and a guarded step ran unapproved. */
  public boolean violatesFourEyes(CaseOutcome c) {
    if (fourEyesApplies == null || !Boolean.TRUE.equals(Feel.test(fourEyesApplies, c.attributes()))) {
      return false;
    }
    Set<String> approvals = new HashSet<>(config.fourEyes().approvals());
    Set<String> guarded = new HashSet<>(config.fourEyes().guarded());
    boolean approved = false;
    for (Work w : c.work()) {
      if (approvals.contains(w.activity())) {
        approved = true;
      } else if (guarded.contains(w.activity()) && !approved) {
        return true;
      }
    }
    return false;
  }

  private double fourEyesViolationRate(List<CaseOutcome> cases) {
    if (fourEyesApplies == null) {
      return 0.0;
    }
    List<CaseOutcome> applicable =
        cases.stream().filter(c -> Boolean.TRUE.equals(Feel.test(fourEyesApplies, c.attributes()))).toList();
    if (applicable.isEmpty()) {
      return 0.0;
    }
    return (double) applicable.stream().filter(this::violatesFourEyes).count() / applicable.size();
  }

  /** Linear interpolation between closest ranks (the R-7 / NumPy default definition). */
  public static double percentile(double[] sorted, double q) {
    if (sorted.length == 0) {
      return Double.NaN;
    }
    double[] s = sorted;
    if (!isSorted(s)) {
      s = s.clone();
      Arrays.sort(s);
    }
    double pos = q * (s.length - 1);
    int lo = (int) Math.floor(pos);
    int hi = (int) Math.ceil(pos);
    return s[lo] + (s[hi] - s[lo]) * (pos - lo);
  }

  private static boolean isSorted(double[] a) {
    for (int i = 1; i < a.length; i++) {
      if (a[i] < a[i - 1]) {
        return false;
      }
    }
    return true;
  }
}
