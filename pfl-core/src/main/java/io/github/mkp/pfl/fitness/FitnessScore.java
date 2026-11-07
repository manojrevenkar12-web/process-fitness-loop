package io.github.mkp.pfl.fitness;

import java.util.List;
import java.util.Map;

/**
 * A process variant's score against the fitness function.
 *
 * @param score weighted progress from the baseline (0) towards the targets (1); negative is worse
 *     than today
 * @param values every KPI's value
 * @param progress per weighted KPI: share of the way from baseline to target, capped to [-1, 1]
 * @param breaches the hard limits this variant crosses; any breach blocks a change
 */
public record FitnessScore(double score, Map<Kpi, Double> values, Map<Kpi, Double> progress, List<String> breaches) {

  public FitnessScore {
    values = Map.copyOf(values);
    progress = Map.copyOf(progress);
    breaches = List.copyOf(breaches);
  }

  public boolean feasible() {
    return breaches.isEmpty();
  }
}
