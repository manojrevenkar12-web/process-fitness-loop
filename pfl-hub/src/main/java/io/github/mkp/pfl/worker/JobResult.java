package io.github.mkp.pfl.worker;

import java.time.Duration;
import java.util.Map;

/** What a handler decided to do with a job. */
public sealed interface JobResult {

  /** Done; set these variables. */
  record Complete(Map<String, Object> variables) implements JobResult {}

  /** A business outcome the process models with an error boundary event. */
  record BpmnError(String code, String message) implements JobResult {}

  /** A technical failure: try again after the back-off, or raise an incident when retries run out. */
  record Fail(String message, Duration backoff) implements JobResult {}
}
