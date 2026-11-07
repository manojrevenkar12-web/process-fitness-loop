package io.github.mkp.pfl.sim;

import java.time.Instant;
import java.util.Map;

/** A case to simulate: who it is, when it arrives, what it carries. */
public record CaseSeed(String caseId, Instant arrival, Map<String, Object> attributes) {

  public CaseSeed {
    attributes = Map.copyOf(attributes);
  }
}
