package io.github.mkp.pfl.log;

import java.time.Instant;
import java.util.Map;

/**
 * One entry of a case history.
 *
 * @param activity the BPMN element id the history system recorded
 * @param lifecycle what happened
 * @param time when
 * @param resource who did it (an analyst id, a worker name), may be null
 * @param output variables the step set when it completed, empty otherwise
 */
public record Event(String activity, Lifecycle lifecycle, Instant time, String resource, Map<String, Object> output) {

  public Event {
    output = output == null ? Map.of() : Map.copyOf(output);
  }

  public static Event of(String activity, Lifecycle lifecycle, Instant time) {
    return new Event(activity, lifecycle, time, null, Map.of());
  }
}
