package io.github.mkp.pfl.hub;

/** Talks to a {@link LegacyHub} in the same JVM, with its faults turned into transport failures. */
public final class InMemoryTransport implements HubTransport {

  private final LegacyHub hub;

  public InMemoryTransport(LegacyHub hub) {
    this.hub = hub;
  }

  @Override
  public String exchange(String request) throws TransportFailure {
    try {
      return hub.handle(request);
    } catch (LegacyHub.Overloaded e) {
      throw new TransportFailure("hub overloaded", false);
    } catch (LegacyHub.AnswerLost e) {
      throw new TransportFailure("timed out waiting for the hub", true);
    }
  }
}
