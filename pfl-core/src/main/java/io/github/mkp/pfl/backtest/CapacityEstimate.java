package io.github.mkp.pfl.backtest;

import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.Event;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.log.Lifecycle;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How many people of each team the history shows at work, as opposed to how many the roster lists.
 *
 * <p>For every working day it takes the largest number of person steps of the team that were in
 * progress at the same moment, and reports the median of those daily peaks. When the team is busy
 * that peak is the number of people present. A roster of seven with one person on long leave
 * shows six, and a backtest that trusts the roster under-predicts every queue.
 */
public final class CapacityEstimate {

  private CapacityEstimate() {}

  /** Median daily peak of concurrent person steps per team; teams with no recorded starts are absent. */
  public static Map<String, Integer> fromHistory(EventLog history, ProcessModel model, ZoneId zone) {
    Map<String, Map<LocalDate, List<long[]>>> intervals = new HashMap<>();
    for (CaseRecord c : history.cases()) {
      Map<String, Instant> open = new HashMap<>();
      for (Event e : c.events()) {
        Node node = model.nodes().get(e.activity());
        if (node == null || node.actor() != Node.Actor.PERSON || node.candidateGroups().isEmpty()) {
          continue;
        }
        if (e.lifecycle() == Lifecycle.START) {
          open.put(e.activity(), e.time());
        } else if ((e.lifecycle() == Lifecycle.COMPLETE || e.lifecycle() == Lifecycle.TERMINATE) && open.containsKey(e.activity())) {
          Instant start = open.remove(e.activity());
          intervals.computeIfAbsent(node.candidateGroups().get(0), k -> new TreeMap<>())
              .computeIfAbsent(start.atZone(zone).toLocalDate(), k -> new ArrayList<>())
              .add(new long[] {start.toEpochMilli(), e.time().toEpochMilli()});
        }
      }
    }
    Map<String, Integer> result = new TreeMap<>();
    intervals.forEach((team, byDay) -> {
      List<Integer> peaks = new ArrayList<>();
      byDay.values().forEach(list -> peaks.add(peak(list)));
      peaks.sort(Integer::compare);
      result.put(team, peaks.get(peaks.size() / 2));
    });
    return result;
  }

  private static int peak(List<long[]> intervals) {
    List<long[]> points = new ArrayList<>();
    for (long[] i : intervals) {
      points.add(new long[] {i[0], 1});
      points.add(new long[] {i[1], -1});
    }
    points.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
    int current = 0;
    int max = 0;
    for (long[] p : points) {
      current += (int) p[1];
      max = Math.max(max, current);
    }
    return max;
  }
}
