package io.github.mkp.pfl.fitness;

import io.github.mkp.pfl.bpmn.Node;
import java.time.Duration;
import java.time.Instant;

/**
 * One step carried out in a case, observed or simulated.
 *
 * @param activity BPMN element id
 * @param actor who did it
 * @param role the team (candidate group) for person steps, the job type for automated ones
 * @param ready when it became due
 * @param start when work began
 * @param end when it finished
 */
public record Work(String activity, Node.Actor actor, String role, Instant ready, Instant start, Instant end) {

  /** Time someone or something actually worked on it, in minutes. */
  public double serviceMinutes() {
    return Duration.between(start, end).toMillis() / 60_000.0;
  }

  /** Time it sat in a queue before work began, in minutes. */
  public double waitMinutes() {
    return Duration.between(ready, start).toMillis() / 60_000.0;
  }
}
