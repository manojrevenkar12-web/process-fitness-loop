package io.github.mkp.pfl.world.eval;

import io.github.mkp.pfl.backtest.HistoryIndex;
import io.github.mkp.pfl.backtest.Proposal;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.fitness.Kpi;
import io.github.mkp.pfl.fitness.Kpis;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.sim.Behaviour;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.Simulator;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.StepContext;
import io.github.mkp.pfl.sim.WorkingCalendar;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The spreadsheet business case, as a baseline to beat: every step takes its average duration,
 * nobody waits for a free colleague, nobody goes home in the evening. Each case still takes the
 * branches its recorded data selects, so the only thing this leaves out is queueing and working
 * hours.
 */
final class NaiveEstimate {

  private final HistoryIndex index;
  private final Map<String, Double> means = new HashMap<>();
  private final List<CaseSeed> seeds;
  private final Instant horizon;

  NaiveEstimate(EventLog history) {
    this.index = new HistoryIndex(history, List.of());
    for (String step : index.steps()) {
      means.put(step, index.similar(Map.of(), step, 0).stream().mapToDouble(HistoryIndex.Occurrence::minutes).average().orElse(0));
    }
    this.seeds = history.cases().stream().map(c -> new CaseSeed(c.caseId(), c.arrival(), c.attributes())).toList();
    this.horizon = seeds.get(seeds.size() - 1).arrival().plus(Duration.ofDays(60));
  }

  Map<Kpi, Double> delta(ProcessModel today, ProcessModel candidate, Proposal proposal, Kpis kpis) {
    Map<String, Integer> unlimited = new HashMap<>();
    for (String team : List.of("payments-ops", "team-leads")) {
      unlimited.put(team, 10_000);
    }
    Staffing infinite = new Staffing(unlimited, WorkingCalendar.ALWAYS);
    Map<Kpi, Double> base = kpis.compute(new Simulator(today, infinite).run(seeds, new Means(Map.of()), horizon).outcomes());
    Map<Kpi, Double> cand = kpis.compute(new Simulator(candidate, infinite).run(seeds, new Means(proposal.steps()), horizon).outcomes());
    Map<Kpi, Double> delta = new EnumMap<>(Kpi.class);
    for (Kpi k : Kpi.values()) {
      delta.put(k, cand.get(k) - base.get(k));
    }
    return delta;
  }

  private final class Means implements Behaviour {
    private final Map<String, Proposal.StepEvidence> steps;

    Means(Map<String, Proposal.StepEvidence> steps) {
      this.steps = steps;
    }

    private double minutes(StepContext step) {
      Proposal.StepEvidence e = steps.get(step.nodeId());
      if (e != null && e.history() != null) {
        return means.get(e.history());
      }
      if (e != null && e.assume() != null) {
        return e.assume().medianMinutes();
      }
      return means.getOrDefault(step.nodeId(), 0.0);
    }

    @Override
    public Duration serviceTime(StepContext step) {
      return Duration.ofMillis(Math.round(minutes(step) * 60_000));
    }

    @Override
    public Duration messageDelay(StepContext step) {
      return serviceTime(step);
    }

    @Override
    public Map<String, Object> outputs(StepContext step) {
      Proposal.StepEvidence e = steps.get(step.nodeId());
      String from = e != null && e.outputsFrom() != null ? e.outputsFrom() : step.nodeId();
      List<HistoryIndex.Occurrence> own = index.ofCase(step.caseId(), from);
      Map<String, Object> out = new HashMap<>();
      if (!own.isEmpty()) {
        out.putAll(own.get(Math.min(step.occurrence(), own.size()) - 1).outputs());
      }
      if (e != null && e.assume() != null) {
        e.assume().outputs().forEach((v, dist) -> out.put(v, dist.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow()));
      }
      return out;
    }

    @Override
    public String error(StepContext step) {
      return null;
    }
  }
}
