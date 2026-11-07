package io.github.mkp.pfl.conformance;

import java.util.List;

/**
 * A way in which recorded cases differ from the model, aggregated over the cases where it happens.
 *
 * @param kind what kind of difference
 * @param elementId the step concerned
 * @param cases how many cases show it
 * @param message what the history shows, in plain words
 * @param examples a few case ids to look at
 */
public record Deviation(Kind kind, String elementId, int cases, String message, List<String> examples) {

  public Deviation {
    examples = List.copyOf(examples);
  }

  /** Kinds of difference between history and model. */
  public enum Kind {
    /** A step ran although the model did not allow it at that point. */
    RAN_WHEN_NOT_ALLOWED,
    /** The case ended while the model still expected a step. */
    LEFT_UNDONE,
    /** The history contains a step the model does not have. */
    NOT_IN_MODEL
  }
}
