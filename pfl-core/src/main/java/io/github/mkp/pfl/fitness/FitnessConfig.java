package io.github.mkp.pfl.fitness;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mkp.pfl.feel.Feel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The fitness function of one process, as the customer defined it: which KPIs matter, how much,
 * where they should get to, and which limits no change may cross.
 *
 * @param process BPMN process id this applies to
 * @param endActivity the end event that means a case was resolved
 * @param slaHours resolution deadline per inquiry type; {@code "*"} is the fallback
 * @param slaAttribute the case attribute that selects the deadline
 * @param roles cost per hour of each team
 * @param automation cost of one automated step by actor
 * @param fourEyes the four-eyes control the constraints check
 * @param kpis weighted KPIs
 * @param constraints hard limits: a change that crosses one is blocked whatever its score
 */
public record FitnessConfig(
    String process,
    String endActivity,
    Map<String, Double> slaHours,
    String slaAttribute,
    Map<String, Role> roles,
    Automation automation,
    FourEyes fourEyes,
    List<KpiWeight> kpis,
    List<Constraint> constraints) {

  public FitnessConfig {
    slaHours = Map.copyOf(slaHours);
    roles = Map.copyOf(roles);
    kpis = List.copyOf(kpis);
    constraints = List.copyOf(constraints);
    double total = kpis.stream().mapToDouble(KpiWeight::weight).sum();
    if (kpis.isEmpty() || Math.abs(total - 1.0) > 1e-6) {
      throw new IllegalArgumentException("KPI weights must sum to 1, not " + total);
    }
    for (KpiWeight k : kpis) {
      Kpi.valueOf(k.kpi().toUpperCase(java.util.Locale.ROOT));
    }
    for (Constraint c : constraints) {
      Kpi.valueOf(c.kpi().toUpperCase(java.util.Locale.ROOT));
    }
    if (fourEyes != null) {
      Feel.parse(fourEyes.appliesWhen());
    }
  }

  /** Hourly cost of a team. */
  public record Role(double eurPerHour) {}

  /** Cost of one automated step. */
  public record Automation(double agentEurPerCall, double systemEurPerCall) {}

  /**
   * A four-eyes control.
   *
   * @param appliesWhen FEEL condition on the case attributes
   * @param approvals steps that count as the second pair of eyes
   * @param guarded steps that must not run before an approval when the control applies
   */
  public record FourEyes(String appliesWhen, List<String> approvals, List<String> guarded) {}

  /** A KPI and its weight in the score. */
  public record KpiWeight(String kpi, double weight, double target) {}

  /** A hard limit on a KPI. */
  public record Constraint(String kpi, double max) {}

  public double slaFor(Map<String, Object> attributes) {
    Object key = attributes.get(slaAttribute);
    Double hours = key == null ? null : slaHours.get(key.toString());
    return hours != null ? hours : slaHours.getOrDefault("*", Double.POSITIVE_INFINITY);
  }

  public static FitnessConfig load(Path file) throws IOException {
    return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .readValue(file.toFile(), FitnessConfig.class);
  }
}
