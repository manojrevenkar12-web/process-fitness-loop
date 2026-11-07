package io.github.mkp.pfl.backtest;

import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.fitness.CaseOutcome;
import io.github.mkp.pfl.fitness.FitnessConfig;
import io.github.mkp.pfl.fitness.FitnessFunction;
import io.github.mkp.pfl.fitness.FitnessScore;
import io.github.mkp.pfl.fitness.Kpi;
import io.github.mkp.pfl.fitness.Kpis;
import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.SimResult;
import io.github.mkp.pfl.sim.Simulator;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.util.Draws;
import io.github.mkp.pfl.verify.Issue;
import io.github.mkp.pfl.verify.VerificationReport;
import io.github.mkp.pfl.verify.Verifier;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Replays recorded cases through a proposed process and projects what would have happened.
 *
 * <p>The method, in the order it runs:
 *
 * <ol>
 *   <li>Verify the candidate. An unsound model is blocked before anything is simulated.
 *   <li>Plan the evidence for every candidate step. A step nobody can source is refused.
 *   <li>Replay today's process over the recorded cases with the recorded work. This is the
 *       calibration: it shows how far the simulator lands from what really happened before
 *       anything changes.
 *   <li>Simulate the candidate over the same cases, drawing each step from the case's own
 *       history, then similar cases, then the declared assumptions, with the same random draws
 *       as the replay (common random numbers).
 *   <li>Take the change as candidate minus replay, case by case, so the simulator's own error
 *       cancels instead of being reported as a gain.
 *   <li>Resample whole days for the interval, so a busy day's queue stays together, across
 *       replications that vary the assumptions within their stated uncertainty.
 * </ol>
 */
public final class Backtester {

  private final ProcessModel today;
  private final EventLog history;
  private final FitnessFunction fitness;
  private final Staffing staffing;
  private final BacktestSettings settings;
  private final HistoryIndex index;
  private final List<CaseSeed> seeds;
  private final Map<Kpi, Double> observed;
  private final Instant horizon;
  private final Staffing effective;
  private final Map<String, Integer> absent = new TreeMap<>();
  private final List<String> staffingNotes = new ArrayList<>();

  public Backtester(ProcessModel today, EventLog history, FitnessConfig config, Staffing staffing, BacktestSettings settings) {
    this.today = today;
    this.history = history;
    this.staffing = staffing;
    this.settings = settings;
    this.index = new HistoryIndex(history, settings.strata());
    this.seeds = history.cases().stream().map(c -> new CaseSeed(c.caseId(), c.arrival(), c.attributes())).toList();
    Kpis kpis = new Kpis(config);
    this.observed = kpis.compute(CaseOutcome.fromLog(history, today, config.endActivity()));
    this.fitness = new FitnessFunction(config, observed);
    Instant last = history.cases().stream().map(CaseRecord::end).max(Instant::compareTo).orElse(Instant.EPOCH);
    this.horizon = last.plus(Duration.ofDays(60));
    Map<String, Integer> seen = CapacityEstimate.fromHistory(history, today, settings.zone());
    Staffing eff = staffing;
    for (Map.Entry<String, Integer> team : staffing.teams().entrySet()) {
      Integer observedPeople = seen.get(team.getKey());
      if (observedPeople != null && observedPeople < team.getValue()) {
        absent.put(team.getKey(), team.getValue() - observedPeople);
        eff = eff.withTeamSize(team.getKey(), observedPeople);
        staffingNotes.add(String.format(Locale.ROOT,
            "the roster lists %d people in %s, the history shows %d at work on a typical day; the backtest uses %d",
            team.getValue(), team.getKey(), observedPeople, observedPeople));
      }
    }
    this.effective = eff;
  }

  /** Team sizes the history supports, which the simulation uses instead of the roster. */
  public Staffing effectiveStaffing() {
    return effective;
  }

  public List<String> staffingNotes() {
    return staffingNotes;
  }

  public Map<Kpi, Double> observed() {
    return observed;
  }

  public FitnessFunction fitness() {
    return fitness;
  }

  public BacktestResult run(Proposal proposal, ProcessModel candidate) {
    List<String> reasons = new ArrayList<>();
    VerificationReport verification = new Verifier().verify(candidate);
    if (!verification.sound()) {
      verification.issues().stream()
          .filter(i -> i.severity() == Issue.Severity.ERROR || i.code().equals("STATE_LIMIT"))
          .forEach(i -> reasons.add("verifier: " + i.message()));
      return blocked(proposal, reasons);
    }
    EvidencePlan plan;
    try {
      plan = EvidencePlan.build(proposal, candidate, today, index);
    } catch (EvidencePlan.MissingEvidence e) {
      e.problems().forEach(p -> reasons.add("evidence: " + p));
      return blocked(proposal, reasons);
    }
    EvidencePlan replayPlan = EvidencePlan.build(new Proposal("replay", "", "", "", Map.of(), Map.of(), "system"), today, today, index);

    // a proposal states team sizes as the roster would list them; the same people stay absent
    Staffing candidateStaffing = effective;
    for (Map.Entry<String, Integer> t : proposal.teams().entrySet()) {
      candidateStaffing = candidateStaffing.withTeamSize(t.getKey(), Math.max(1, t.getValue() - absent.getOrDefault(t.getKey(), 0)));
    }

    Kpis kpis = fitness.kpis();
    int n = seeds.size();
    List<CaseOutcome[]> replays = new ArrayList<>();
    List<CaseOutcome[]> candidates = new ArrayList<>();
    Map<EvidenceTier, Double> ledger = new EnumMap<>(EvidenceTier.class);
    long incidents = 0;
    Map<Kpi, Double> replayMean = new EnumMap<>(Kpi.class);
    Map<Kpi, Double> candidateMean = new EnumMap<>(Kpi.class);
    for (int r = 0; r < settings.replications(); r++) {
      EvidenceBehaviour replayBehaviour = new EvidenceBehaviour(replayPlan, index, settings.seed(), r, settings.minPool());
      SimResult replay = new Simulator(today, effective).run(seeds, replayBehaviour, horizon);
      EvidenceBehaviour candidateBehaviour = new EvidenceBehaviour(plan, index, settings.seed(), r, settings.minPool());
      SimResult cand = new Simulator(candidate, candidateStaffing).run(seeds, candidateBehaviour, horizon);
      incidents += cand.incidents();
      candidateBehaviour.ledger().forEach((tier, m) -> ledger.merge(tier, m, Double::sum));
      replays.add(replay.outcomes().toArray(CaseOutcome[]::new));
      candidates.add(cand.outcomes().toArray(CaseOutcome[]::new));
      accumulate(replayMean, kpis.compute(replay.outcomes()), settings.replications());
      accumulate(candidateMean, kpis.compute(cand.outcomes()), settings.replications());
    }

    // paired bootstrap over days, spread across replications
    List<int[]> days = dayBlocks();
    int perReplication = settings.bootstrap() / settings.replications();
    Map<Kpi, double[]> deltas = new EnumMap<>(Kpi.class);
    for (Kpi k : Kpi.values()) {
      deltas.put(k, new double[perReplication * settings.replications()]);
    }
    double[] scores = new double[perReplication * settings.replications()];
    int b = 0;
    for (int r = 0; r < settings.replications(); r++) {
      SplittableRandom rng = Draws.rng(settings.seed(), "bootstrap", Integer.toString(r));
      for (int i = 0; i < perReplication; i++, b++) {
        List<CaseOutcome> rep = new ArrayList<>(n);
        List<CaseOutcome> can = new ArrayList<>(n);
        for (int d = 0; d < days.size(); d++) {
          for (int idx : days.get(rng.nextInt(days.size()))) {
            rep.add(replays.get(r)[idx]);
            can.add(candidates.get(r)[idx]);
          }
        }
        Map<Kpi, Double> kr = kpis.compute(rep);
        Map<Kpi, Double> kc = kpis.compute(can);
        Map<Kpi, Double> projected = new EnumMap<>(Kpi.class);
        for (Kpi k : Kpi.values()) {
          double delta = kc.get(k) - kr.get(k);
          deltas.get(k)[b] = delta;
          projected.put(k, observed.get(k) + delta);
        }
        scores[b] = fitness.score(projected).score();
      }
    }

    Map<String, BacktestResult.Interval> delta = new LinkedHashMap<>();
    Map<Kpi, Double> projectedPoint = new EnumMap<>(Kpi.class);
    for (Kpi k : Kpi.values()) {
      double point = candidateMean.get(k) - replayMean.get(k);
      delta.put(k.id(), interval(point, deltas.get(k)));
      projectedPoint.put(k, observed.get(k) + point);
    }
    // hard limits are judged on what the candidate itself does, not on today's violations plus a change
    for (FitnessConfig.Constraint c : fitness.config().constraints()) {
      Kpi k = Kpi.of(c.kpi());
      projectedPoint.put(k, candidateMean.get(k));
    }
    FitnessScore projectedScore = fitness.score(projectedPoint);
    BacktestResult.Interval score = interval(projectedScore.score(), scores);
    double gain = Arrays.stream(scores).filter(s -> s > 0).count() / (double) scores.length;

    double total = ledger.values().stream().mapToDouble(Double::doubleValue).sum();
    Map<EvidenceTier, Double> evidence = new EnumMap<>(EvidenceTier.class);
    for (EvidenceTier t : EvidenceTier.values()) {
      evidence.put(t, total == 0 ? 0 : ledger.getOrDefault(t, 0.0) / total);
    }
    List<String> assumptions = new ArrayList<>();
    List<String> unmeasured = new ArrayList<>();
    plan.sources().forEach((step, s) -> {
      if (s.assumption() != null) {
        assumptions.add(step + ": " + s.assumption().source() + (s.assumption().measured() ? " (measured)" : " (not measured)"));
        if (!s.assumption().measured()) {
          unmeasured.add(step);
        }
      }
    });

    BacktestResult.Verdict verdict;
    if (!projectedScore.feasible() || incidents > 0) {
      verdict = BacktestResult.Verdict.BLOCKED;
      projectedScore.breaches().forEach(x -> reasons.add("hard limit: " + x));
      if (incidents > 0) {
        reasons.add(incidents + " simulated cases got stuck in the candidate process");
      }
    } else if (!unmeasured.isEmpty()) {
      verdict = BacktestResult.Verdict.NEEDS_SHADOW_RUN;
      reasons.add("the projection rests on assumptions nobody has measured yet (" + String.join(", ", unmeasured)
          + "); run the candidate in shadow on live cases and measure them before deciding");
    } else if (evidence.get(EvidenceTier.ASSUMED) > settings.assumedShareLimit()) {
      verdict = BacktestResult.Verdict.NEEDS_SHADOW_RUN;
      reasons.add(String.format(Locale.ROOT, "%.0f%% of the simulated work time comes from assumptions (limit %.0f%%);"
          + " run the candidate in shadow on live cases before deciding", 100 * evidence.get(EvidenceTier.ASSUMED),
          100 * settings.assumedShareLimit()));
    } else if (score.low() > 0) {
      verdict = BacktestResult.Verdict.IMPROVES;
      reasons.add(String.format(Locale.ROOT, "the fitness score improves by %.3f (%.0f%% interval %.3f to %.3f)",
          score.estimate(), 100 * settings.confidence(), score.low(), score.high()));
    } else if (score.high() < 0) {
      verdict = BacktestResult.Verdict.WORSE;
      reasons.add(String.format(Locale.ROOT, "the fitness score drops by %.3f", -score.estimate()));
    } else {
      verdict = BacktestResult.Verdict.UNCLEAR;
      reasons.add(String.format(Locale.ROOT, "the interval of the score change, %.3f to %.3f, includes zero", score.low(), score.high()));
    }

    return new BacktestResult(proposal.id(), proposal.title(), verdict, reasons, n, ids(observed), ids(replayMean), delta,
        ids(projectedPoint), score, gain, evidence, assumptions, projectedScore.breaches(), incidents, staffingNotes);
  }

  private BacktestResult blocked(Proposal proposal, List<String> reasons) {
    BacktestResult.Interval none = new BacktestResult.Interval(Double.NaN, Double.NaN, Double.NaN);
    return new BacktestResult(proposal.id(), proposal.title(), BacktestResult.Verdict.BLOCKED, reasons, seeds.size(),
        ids(observed), Map.of(), Map.of(), Map.of(), none, 0, Map.of(), List.of(), List.of(), 0, staffingNotes);
  }

  private List<int[]> dayBlocks() {
    Map<LocalDate, List<Integer>> byDay = new TreeMap<>();
    for (int i = 0; i < seeds.size(); i++) {
      byDay.computeIfAbsent(seeds.get(i).arrival().atZone(settings.zone()).toLocalDate(), d -> new ArrayList<>()).add(i);
    }
    return byDay.values().stream().map(l -> l.stream().mapToInt(Integer::intValue).toArray()).toList();
  }

  private BacktestResult.Interval interval(double point, double[] samples) {
    double[] s = samples.clone();
    Arrays.sort(s);
    double tail = (1 - settings.confidence()) / 2;
    return new BacktestResult.Interval(point, Kpis.percentile(s, tail), Kpis.percentile(s, 1 - tail));
  }

  private static void accumulate(Map<Kpi, Double> into, Map<Kpi, Double> values, int replications) {
    values.forEach((k, v) -> into.merge(k, v / replications, Double::sum));
  }

  private static Map<String, Double> ids(Map<Kpi, Double> values) {
    Map<String, Double> out = new LinkedHashMap<>();
    values.forEach((k, v) -> out.put(k.id(), v));
    return out;
  }

  public EventLog history() {
    return history;
  }
}
