package io.github.mkp.pfl.sim;

import io.github.mkp.pfl.fitness.CaseOutcome;
import io.github.mkp.pfl.log.CaseRecord;
import java.util.List;
import java.util.Map;

/**
 * One simulated case.
 *
 * @param caseId the case
 * @param record its history, in the same shape as a recorded one
 * @param outcome what the KPIs read
 * @param path every element the case passed through, gateways included, in order: what CI compares
 *     with the engine's element instances
 * @param incident why the case got stuck, or null
 * @param variables the case's variables at the end
 */
public record SimCase(
    String caseId, CaseRecord record, CaseOutcome outcome, List<String> path, String incident, Map<String, Object> variables) {

  public SimCase {
    path = List.copyOf(path);
  }
}
