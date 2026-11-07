package io.github.mkp.pfl.camunda;

import java.util.Map;

/**
 * A job handed to a worker.
 *
 * @param jobKey the job's key
 * @param type job type
 * @param processInstanceKey the case
 * @param elementId the BPMN element
 * @param retries retries left
 * @param variables the variables the job sees
 * @param leaseToken the lease token to echo back on completion (Camunda 8.10+), or null
 */
public record ActivatedJob(String jobKey, String type, String processInstanceKey, String elementId, int retries,
    Map<String, Object> variables, String leaseToken) {}
