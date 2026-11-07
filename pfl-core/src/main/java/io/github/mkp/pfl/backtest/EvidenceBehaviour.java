package io.github.mkp.pfl.backtest;

import io.github.mkp.pfl.sim.Behaviour;
import io.github.mkp.pfl.sim.StepContext;
import io.github.mkp.pfl.util.Draws;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Simulator behaviour drawn from evidence: a step's own case history first, then similar cases,
 * then declared assumptions. Keeps a ledger of how many simulated minutes each tier supplied, so
 * the result can say how much of a projection is evidence and how much is assumption.
 */
final class EvidenceBehaviour implements Behaviour {

  private final EvidencePlan plan;
  private final HistoryIndex history;
  private final long seed;
  private final int minPool;
  private final Map<String, Double> factors = new HashMap<>();
  private final Map<String, Double> agreement = new HashMap<>();
  private final Map<EvidenceTier, Double> minutes = new EnumMap<>(EvidenceTier.class);

  /**
   * @param replication which replication this is; it varies similar-case draws and how far each
   *     assumption's median is from its declared value
   */
  EvidenceBehaviour(EvidencePlan plan, HistoryIndex history, long seed, int replication, int minPool) {
    this.plan = plan;
    this.history = history;
    this.seed = seed * 1_000_003L + replication;
    this.minPool = minPool;
    plan.sources().forEach((step, source) -> {
      if (source.assumption() != null && source.history() == null) {
        double u = StrictMath.log(source.assumption().uncertainty());
        SplittableRandom r = Draws.rng(this.seed, "factor", step);
        factors.put(step, StrictMath.exp(u * (2 * r.nextDouble() - 1)));
      }
      if (source.assumption() != null && source.outputsFrom() != null) {
        List<Double> range = source.assumption().agreement();
        SplittableRandom r = Draws.rng(this.seed, "agreement", step);
        agreement.put(step, range.get(0) + (range.get(1) - range.get(0)) * r.nextDouble());
      }
    });
    for (EvidenceTier t : EvidenceTier.values()) {
      minutes.put(t, 0.0);
    }
  }

  Map<EvidenceTier, Double> ledger() {
    return Map.copyOf(minutes);
  }

  private record Pick(HistoryIndex.Occurrence occurrence, EvidenceTier tier) {}

  private Pick pick(String historyStep, StepContext step) {
    List<HistoryIndex.Occurrence> own = history.ofCase(step.caseId(), historyStep);
    if (!own.isEmpty()) {
      return new Pick(own.get(Math.min(step.occurrence(), own.size()) - 1), EvidenceTier.SAME_CASE);
    }
    List<HistoryIndex.Occurrence> pool = history.similar(step.attributes(), historyStep, minPool);
    SplittableRandom r = Draws.rng(seed, "donor", step.caseId(), step.nodeId(), Integer.toString(step.occurrence()));
    return new Pick(pool.get(r.nextInt(pool.size())), EvidenceTier.SIMILAR_CASES);
  }

  private EvidencePlan.Source source(StepContext step) {
    EvidencePlan.Source s = plan.sources().get(step.nodeId());
    if (s == null) {
      throw new IllegalStateException("no evidence planned for " + step.nodeId());
    }
    return s;
  }

  private Duration timed(StepContext step) {
    EvidencePlan.Source s = source(step);
    double m;
    EvidenceTier tier;
    if (s.history() != null) {
      Pick p = pick(s.history(), step);
      m = p.occurrence().minutes();
      tier = p.tier();
    } else {
      Proposal.Assumption a = s.assumption();
      double f = factors.getOrDefault(step.nodeId(), 1.0);
      SplittableRandom r = Draws.rng(seed, "assumed", step.caseId(), step.nodeId(), Integer.toString(step.occurrence()));
      m = Draws.lognormal(r, a.medianMinutes() * f, a.p90Minutes() * f);
      tier = EvidenceTier.ASSUMED;
    }
    minutes.merge(tier, m, Double::sum);
    return Duration.ofMillis(Math.round(m * 60_000));
  }

  @Override
  public Duration serviceTime(StepContext step) {
    return timed(step);
  }

  @Override
  public Duration messageDelay(StepContext step) {
    return timed(step);
  }

  @Override
  public Map<String, Object> outputs(StepContext step) {
    EvidencePlan.Source s = source(step);
    Map<String, Object> out = new HashMap<>();
    if (s.outputsFrom() != null) {
      out.putAll(pick(s.outputsFrom(), step).occurrence().outputs());
      Double agrees = agreement.get(step.nodeId());
      if (agrees != null && agrees < 1.0) {
        SplittableRandom r = Draws.rng(seed, "disagree", step.caseId(), step.nodeId(), Integer.toString(step.occurrence()));
        if (r.nextDouble() >= agrees) {
          out.replaceAll((k, v) -> v instanceof Boolean b ? !b : v);
        }
      }
    }
    if (s.assumption() != null) {
      s.assumption().outputs().forEach((variable, distribution) -> {
        SplittableRandom r = Draws.rng(seed, "output", step.caseId(), step.nodeId(), variable, Integer.toString(step.occurrence()));
        out.put(variable, parse(sample(r, distribution)));
      });
    }
    return out;
  }

  @Override
  public String error(StepContext step) {
    EvidencePlan.Source s = source(step);
    if (s.assumption() == null || s.assumption().errors().isEmpty()) {
      return null;
    }
    SplittableRandom r = Draws.rng(seed, "error", step.caseId(), step.nodeId(), Integer.toString(step.occurrence()));
    double u = r.nextDouble();
    double cumulative = 0;
    for (Map.Entry<String, Double> e : new java.util.TreeMap<>(s.assumption().errors()).entrySet()) {
      cumulative += e.getValue();
      if (u < cumulative) {
        return e.getKey();
      }
    }
    return null;
  }

  private static String sample(SplittableRandom r, Map<String, Double> distribution) {
    double u = r.nextDouble();
    double cumulative = 0;
    String last = null;
    for (Map.Entry<String, Double> e : new java.util.TreeMap<>(distribution).entrySet()) {
      cumulative += e.getValue();
      last = e.getKey();
      if (u < cumulative) {
        return e.getKey();
      }
    }
    return last;
  }

  /** Assumed output values are written as strings in JSON; booleans and numbers are restored. */
  static Object parse(String value) {
    if ("true".equals(value) || "false".equals(value)) {
      return Boolean.valueOf(value);
    }
    try {
      return new java.math.BigDecimal(value);
    } catch (NumberFormatException e) {
      return value;
    }
  }
}
