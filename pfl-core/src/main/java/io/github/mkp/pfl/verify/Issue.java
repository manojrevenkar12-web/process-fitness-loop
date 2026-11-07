package io.github.mkp.pfl.verify;

import java.util.List;

/**
 * One problem found in a process model.
 *
 * @param code stable identifier, e.g. {@code MISSING_JOB_TYPE}
 * @param category what kind of failure this predicts
 * @param severity how bad it is
 * @param elementId the BPMN element the reader should look at, may be null
 * @param message what is wrong, in plain words
 * @param hint how to fix it
 * @param trace for behaviour problems: the elements that run, in order, before the problem appears
 */
public record Issue(
    String code,
    Category category,
    Severity severity,
    String elementId,
    String message,
    String hint,
    List<String> trace) {

  public Issue {
    trace = trace == null ? List.of() : List.copyOf(trace);
  }

  static Issue of(String code, Category category, Severity severity, String elementId, String message, String hint) {
    return new Issue(code, category, severity, elementId, message, hint, List.of());
  }

  /** What an issue predicts. */
  public enum Category {
    /** The engine will refuse to deploy the file. */
    DEPLOY,
    /** The engine deploys it, but some runs will hang, run a step twice or never finish. */
    BEHAVIOUR,
    /** It runs, but a reviewer should look: unreachable steps, deprecated constructs, unknown data. */
    DESIGN
  }

  /** How bad an issue is. */
  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }
}
