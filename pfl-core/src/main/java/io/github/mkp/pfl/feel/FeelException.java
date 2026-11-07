package io.github.mkp.pfl.feel;

/** A condition is not valid FEEL, or uses FEEL this toolkit does not evaluate. */
public class FeelException extends RuntimeException {

  /** Whether the engine would also refuse the expression, or only this toolkit cannot run it. */
  public enum Kind {
    /** Not FEEL at all: an unbalanced bracket, a dangling operator, a missing leading {@code =}. */
    SYNTAX,
    /** Possibly valid FEEL, but outside the subset this toolkit evaluates (functions, lists, ...). */
    UNSUPPORTED
  }

  private final Kind kind;
  private final int position;

  public FeelException(Kind kind, String message, int position) {
    super(message + " (at character " + position + ")");
    this.kind = kind;
    this.position = position;
  }

  public Kind kind() {
    return kind;
  }

  public int position() {
    return position;
  }
}
