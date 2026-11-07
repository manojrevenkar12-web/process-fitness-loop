package io.github.mkp.pfl.backtest;

import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.Event;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.log.Lifecycle;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recorded work, indexed two ways: by case (what this case's own history says) and by step and
 * kind of case (what similar cases' histories say).
 */
public final class HistoryIndex {

  /**
   * One recorded execution of a step.
   *
   * @param minutes working minutes (start to complete), or ready to complete for waits and for
   *     histories that do not record a start
   * @param outputs variables it set
   */
  public record Occurrence(double minutes, Map<String, Object> outputs) {}

  private final Map<String, Map<String, List<Occurrence>>> byCase = new HashMap<>();
  private final Map<String, Map<String, List<Occurrence>>> byStratum = new HashMap<>();
  private final Map<String, List<Occurrence>> byStep = new HashMap<>();
  private final Map<String, String> stratumOfCase = new HashMap<>();
  private final List<String> strata;

  /** @param strata case attributes that define "similar", e.g. inquiry type and channel */
  public HistoryIndex(EventLog history, List<String> strata) {
    this.strata = List.copyOf(strata);
    for (CaseRecord c : history.cases()) {
      String stratum = stratum(c.attributes());
      stratumOfCase.put(c.caseId(), stratum);
      Map<String, List<Occurrence>> steps = byCase.computeIfAbsent(c.caseId(), k -> new HashMap<>());
      Map<String, Deque<Event>> ready = new HashMap<>();
      Map<String, Deque<Event>> started = new HashMap<>();
      for (Event e : c.events()) {
        switch (e.lifecycle()) {
          case READY -> ready.computeIfAbsent(e.activity(), k -> new ArrayDeque<>()).add(e);
          case START -> started.computeIfAbsent(e.activity(), k -> new ArrayDeque<>()).add(e);
          case TERMINATE -> {
            poll(ready, e.activity());
            poll(started, e.activity());
          }
          case COMPLETE -> {
            Event r = poll(ready, e.activity());
            Event s = poll(started, e.activity());
            Event from = s != null ? s : r;
            if (from == null) {
              continue;
            }
            Occurrence o = new Occurrence(Duration.between(from.time(), e.time()).toMillis() / 60_000.0, e.output());
            steps.computeIfAbsent(e.activity(), k -> new ArrayList<>()).add(o);
            byStratum.computeIfAbsent(e.activity(), k -> new HashMap<>()).computeIfAbsent(stratum, k -> new ArrayList<>()).add(o);
            byStep.computeIfAbsent(e.activity(), k -> new ArrayList<>()).add(o);
          }
        }
      }
    }
  }

  private static Event poll(Map<String, Deque<Event>> m, String k) {
    Deque<Event> q = m.get(k);
    return q == null ? null : q.poll();
  }

  public String stratum(Map<String, Object> attributes) {
    return strata.stream().map(a -> String.valueOf(attributes.get(a))).collect(Collectors.joining("|"));
  }

  public boolean knows(String step) {
    return byStep.containsKey(step);
  }

  public Set<String> steps() {
    return byStep.keySet();
  }

  public List<Occurrence> ofCase(String caseId, String step) {
    return byCase.getOrDefault(caseId, Map.of()).getOrDefault(step, List.of());
  }

  /** Occurrences of a step in cases like this one, or in all cases if fewer than minPool are alike. */
  public List<Occurrence> similar(Map<String, Object> attributes, String step, int minPool) {
    List<Occurrence> pool = byStratum.getOrDefault(step, Map.of()).getOrDefault(stratum(attributes), List.of());
    return pool.size() >= minPool ? pool : byStep.getOrDefault(step, List.of());
  }
}
