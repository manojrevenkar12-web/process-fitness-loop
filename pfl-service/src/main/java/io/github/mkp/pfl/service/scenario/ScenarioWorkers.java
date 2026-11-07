package io.github.mkp.pfl.service.scenario;

import io.github.mkp.pfl.camunda.CamundaClient;
import io.github.mkp.pfl.hub.HubHandlers;
import io.github.mkp.pfl.hub.LegacyHubClient;
import io.github.mkp.pfl.worker.JobHandler;
import io.github.mkp.pfl.worker.JobResult;
import java.util.HashMap;
import java.util.Map;

/** Job handlers for every service task of the re-engineered process, driven by {@link Scenario}. */
public final class ScenarioWorkers {

  private ScenarioWorkers() {}

  public static Map<String, JobHandler> handlers(CamundaClient camunda, LegacyHubClient hub) {
    Map<String, JobHandler> h = new HashMap<>(HubHandlers.all(hub));
    h.put("pfl.intake-agent", job -> new JobResult.Complete(Scenario.outputs("classify_inquiry", job.variables())));
    h.put("pfl.gpi.track", job -> new JobResult.Complete(Scenario.outputs("gpi_trace", job.variables())));
    h.put("pfl.notify", job -> new JobResult.Complete(Map.of()));
    h.put("pfl.request-info", job -> {
      // the sender answers at once; the engine buffers the reply until the case waits for it
      camunda.publishMessage("sender-reply", String.valueOf(job.variables().get("caseId")), Map.of());
      return new JobResult.Complete(Map.of());
    });
    return h;
  }
}
