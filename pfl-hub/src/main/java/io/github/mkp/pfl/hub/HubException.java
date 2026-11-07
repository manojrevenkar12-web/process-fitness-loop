package io.github.mkp.pfl.hub;

/** Something the hub said or did that the caller must handle. */
public class HubException extends Exception {

  /** How a caller should react. */
  public enum Kind {
    /** The hub does not know the payment: a business outcome, not a failure. */
    NOT_FOUND,
    /** The hub refused the request as invalid: retrying will not help. */
    REJECTED,
    /** The hub could not be reached or answered with a system error: retry later. */
    UNAVAILABLE
  }

  private final Kind kind;

  public HubException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
