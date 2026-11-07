package io.github.mkp.pfl.fitness;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scores process variants against the KPIs the customer weighted, relative to the baseline
 * measured when the engagement started.
 */
public final class FitnessFunction {

  private final FitnessConfig config;
  private final Kpis kpis;
  private final Map<Kpi, Double> baseline;

  /** @param baseline KPI values of today's process, measured from history */
  public FitnessFunction(FitnessConfig config, Map<Kpi, Double> baseline) {
    this.config = config;
    this.kpis = new Kpis(config);
    this.baseline = Map.copyOf(baseline);
  }

  public FitnessConfig config() {
    return config;
  }

  public Map<Kpi, Double> baseline() {
    return baseline;
  }

  public FitnessScore score(List<CaseOutcome> cases) {
    return score(kpis.compute(cases));
  }

  public FitnessScore score(Map<Kpi, Double> values) {
    Map<Kpi, Double> progress = new EnumMap<>(Kpi.class);
    double score = 0;
    for (FitnessConfig.KpiWeight w : config.kpis()) {
      Kpi kpi = Kpi.of(w.kpi());
      double base = baseline.get(kpi);
      double value = values.get(kpi);
      double span = base - w.target();
      double p = Math.abs(span) < 1e-12 ? 0.0 : (base - value) / span;
      p = Math.max(-1.0, Math.min(1.0, p));
      progress.put(kpi, p);
      score += w.weight() * p;
    }
    List<String> breaches = new ArrayList<>();
    for (FitnessConfig.Constraint c : config.constraints()) {
      double value = values.get(Kpi.of(c.kpi()));
      if (value > c.max() + 1e-12) {
        breaches.add(c.kpi().endsWith("_rate")
            ? String.format(Locale.ROOT, "%s would be %.1f%% of cases; the limit is %.1f%%", c.kpi(), 100 * value, 100 * c.max())
            : String.format(Locale.ROOT, "%s would be %.4f; the limit is %.4f", c.kpi(), value, c.max()));
      }
    }
    return new FitnessScore(score, values, progress, breaches);
  }

  public Kpis kpis() {
    return kpis;
  }
}
