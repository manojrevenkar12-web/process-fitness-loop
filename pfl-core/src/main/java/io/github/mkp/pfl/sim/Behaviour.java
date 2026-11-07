package io.github.mkp.pfl.sim;

import java.time.Duration;
import java.util.Map;

/**
 * Everything the simulator does not decide itself: how long work takes, what it produces, how long
 * outside parties take to answer, which business errors steps raise.
 *
 * <p>The ground-truth world implements this from hidden parameters; the backtester implements it
 * from recorded history. Implementations must be deterministic for a given context, so two variants
 * of a process simulated over the same cases see the same draws for the same steps (common random
 * numbers), and differences come from the process, not from noise.
 */
public interface Behaviour {

  /** Working time of a task once someone or something starts it. */
  Duration serviceTime(StepContext step);

  /** Variables the step sets when it completes. */
  Map<String, Object> outputs(StepContext step);

  /** How long until the message a catch event waits for arrives. Timers use the model's duration. */
  Duration messageDelay(StepContext step);

  /** The BPMN error code the step throws instead of completing, or null. */
  default String error(StepContext step) {
    return null;
  }
}
