package io.github.mkp.pfl.hub;

/** Sends one request line and returns one response line. */
public interface HubTransport {

  String exchange(String request) throws TransportFailure;

  /** The exchange failed. */
  final class TransportFailure extends Exception {
    private final boolean ambiguous;

    /**
     * @param ambiguous true when the hub may have processed the request before the failure (a
     *     timeout waiting for the answer); false when it certainly did not (refused connection,
     *     503 before processing)
     */
    public TransportFailure(String message, boolean ambiguous) {
      super(message);
      this.ambiguous = ambiguous;
    }

    public boolean ambiguous() {
      return ambiguous;
    }
  }
}
