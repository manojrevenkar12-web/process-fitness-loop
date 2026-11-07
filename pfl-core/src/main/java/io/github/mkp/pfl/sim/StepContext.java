package io.github.mkp.pfl.sim;

import java.time.Instant;
import java.util.Map;

/**
 * What a {@link Behaviour} knows when it is asked about one execution of one step.
 *
 * @param caseId the case
 * @param attributes the case's start variables
 * @param variables all variables at the moment the step became ready
 * @param nodeId the BPMN element
 * @param occurrence 1 for the first time this element runs in the case, 2 for the second, ...
 * @param readyAt when the step became ready
 */
public record StepContext(
    String caseId, Map<String, Object> attributes, Map<String, Object> variables, String nodeId, int occurrence, Instant readyAt) {}
