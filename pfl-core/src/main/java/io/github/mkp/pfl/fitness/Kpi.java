package io.github.mkp.pfl.fitness;

/** The KPIs a fitness function can weigh or constrain. All of them are better when lower. */
public enum Kpi {
  /** Median hours from arrival to resolution. */
  CYCLE_P50_HOURS,
  /** 90th percentile of hours from arrival to resolution. */
  CYCLE_P90_HOURS,
  /** Share of resolved cases that took longer than their deadline. */
  SLA_BREACH_RATE,
  /** People's time at their hourly cost plus automated steps at their per-call cost. */
  COST_PER_CASE_EUR,
  /** Steps a person carried out, per case. */
  TOUCHES_PER_CASE,
  /** Minutes of people's working time per case. */
  PERSON_MINUTES_PER_CASE,
  /** Share of cases under the four-eyes control where a guarded step ran without an approval. */
  FOUR_EYES_VIOLATION_RATE,
  /** Share of cases not resolved by the end of the history. */
  OPEN_CASE_RATE;

  public String id() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  public static Kpi of(String id) {
    return valueOf(id.toUpperCase(java.util.Locale.ROOT));
  }
}
