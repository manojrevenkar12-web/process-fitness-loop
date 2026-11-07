package io.github.mkp.pfl.fitness;

import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.Event;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.log.Lifecycle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What happened in one case, in the shape the KPIs read. Built from a recorded history or produced
 * by the simulator, so observed and projected numbers come from the same arithmetic.
 *
 * @param caseId business key
 * @param attributes start variables
 * @param arrival when the case started
 * @param end when it finished
 * @param work the steps carried out, in completion order
 * @param finished false when the case was still open at the end of the history
 */
public record CaseOutcome(
    String caseId, Map<String, Object> attributes, Instant arrival, Instant end, List<Work> work, boolean finished) {

  public CaseOutcome {
    attributes = Map.copyOf(attributes);
    work = List.copyOf(work);
  }

  public double cycleHours() {
    return Duration.between(arrival, end).toMillis() / 3_600_000.0;
  }

  /**
   * Pairs the READY, START and COMPLETE events of each step. A step that was never started
   * (histories from the engine only know creation and completion) starts when it became ready.
   */
  public static CaseOutcome from(CaseRecord record, ProcessModel model, String endActivity) {
    Map<String, Deque<Event>> ready = new HashMap<>();
    Map<String, Deque<Event>> started = new HashMap<>();
    List<Work> work = new ArrayList<>();
    boolean finished = false;
    for (Event e : record.events()) {
      switch (e.lifecycle()) {
        case READY -> ready.computeIfAbsent(e.activity(), k -> new ArrayDeque<>()).add(e);
        case START -> started.computeIfAbsent(e.activity(), k -> new ArrayDeque<>()).add(e);
        case COMPLETE -> {
          Event r = poll(ready, e.activity());
          Event s = poll(started, e.activity());
          Instant readyAt = r != null ? r.time() : s != null ? s.time() : e.time();
          Instant startAt = s != null ? s.time() : readyAt;
          Node node = model.nodes().get(e.activity());
          if (node != null && node.kind().isActivity()) {
            String role = node.actor() == Node.Actor.PERSON
                ? (node.candidateGroups().isEmpty() ? "unassigned" : node.candidateGroups().get(0))
                : node.taskType();
            work.add(new Work(e.activity(), node.actor(), role, readyAt, startAt, e.time()));
          }
          if (e.activity().equals(endActivity)) {
            finished = true;
          }
        }
        case TERMINATE -> {
          poll(ready, e.activity());
          poll(started, e.activity());
        }
      }
    }
    return new CaseOutcome(record.caseId(), record.attributes(), record.arrival(), record.end(), work, finished);
  }

  public static List<CaseOutcome> fromLog(EventLog log, ProcessModel model, String endActivity) {
    return log.cases().stream().map(c -> from(c, model, endActivity)).toList();
  }

  private static Event poll(Map<String, Deque<Event>> byActivity, String activity) {
    Deque<Event> q = byActivity.get(activity);
    return q == null ? null : q.poll();
  }
}
