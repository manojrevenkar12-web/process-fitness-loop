package io.github.mkp.pfl.worker;

import io.github.mkp.pfl.camunda.ActivatedJob;

/** Works one job type. Must not throw; failures are returned as {@link JobResult.Fail}. */
@FunctionalInterface
public interface JobHandler {
  JobResult handle(ActivatedJob job);
}
