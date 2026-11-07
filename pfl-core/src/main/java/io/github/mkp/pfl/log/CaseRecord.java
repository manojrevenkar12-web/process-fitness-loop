package io.github.mkp.pfl.log;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The recorded history of one case.
 *
 * @param caseId business key of the case
 * @param arrival when the inquiry arrived
 * @param attributes start variables: inquiry type, amount, currency, channel
 * @param events the history, in time order
 */
public record CaseRecord(String caseId, Instant arrival, Map<String, Object> attributes, List<Event> events) {

  public CaseRecord {
    attributes = Map.copyOf(attributes);
    events = events.stream().sorted(Comparator.comparing(Event::time)).toList();
  }

  /** When the last recorded event happened, or the arrival if nothing happened. */
  public Instant end() {
    return events.isEmpty() ? arrival : events.get(events.size() - 1).time();
  }

  /** Completed steps in order: what replay and the variant analysis read. */
  public List<Event> completions() {
    return events.stream().filter(e -> e.lifecycle() == Lifecycle.COMPLETE).toList();
  }
}
