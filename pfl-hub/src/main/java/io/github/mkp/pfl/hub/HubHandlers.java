package io.github.mkp.pfl.hub;

import io.github.mkp.pfl.camunda.ActivatedJob;
import io.github.mkp.pfl.worker.JobHandler;
import io.github.mkp.pfl.worker.JobResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/** Job handlers for the two hub job types of the investigation process. */
public final class HubHandlers {

  public static final String LOOKUP = "pfl.legacy-hub.lookup";
  public static final String RECALL = "pfl.legacy-hub.recall";

  private HubHandlers() {}

  public static Map<String, JobHandler> all(LegacyHubClient client) {
    return Map.of(LOOKUP, lookup(client), RECALL, recall(client));
  }

  public static JobHandler lookup(LegacyHubClient client) {
    return job -> {
      String uetr = string(job, "uetr");
      if (uetr == null) {
        return new JobResult.BpmnError("PAYMENT_NOT_FOUND", "the case carries no UETR to look up");
      }
      try {
        Payment p = client.lookup(uetr);
        return new JobResult.Complete(Map.of("hubStatus", p.status(), "hubReference", p.hubReference(),
            "valueDate", p.valueDate().toString()));
      } catch (HubException e) {
        return switch (e.kind()) {
          case NOT_FOUND -> new JobResult.BpmnError("PAYMENT_NOT_FOUND", e.getMessage());
          case REJECTED, UNAVAILABLE -> new JobResult.Fail(e.getMessage(), Duration.ofSeconds(30));
        };
      }
    };
  }

  public static JobHandler recall(LegacyHubClient client) {
    return job -> {
      String uetr = string(job, "uetr");
      Object amount = job.variables().get("amount");
      String currency = string(job, "currency");
      if (uetr == null || amount == null || currency == null) {
        return new JobResult.Fail("recall needs uetr, amount and currency", Duration.ofMinutes(5));
      }
      try {
        LegacyHubClient.RecallReceipt receipt = client.recall(uetr, clientRef(job), new BigDecimal(amount.toString()), currency);
        return new JobResult.Complete(Map.of("recallReference", receipt.hubReference(), "recallAlreadyOnFile", receipt.alreadyOnFile()));
      } catch (HubException e) {
        return switch (e.kind()) {
          case NOT_FOUND -> new JobResult.BpmnError("PAYMENT_NOT_FOUND", e.getMessage());
          case REJECTED -> new JobResult.BpmnError("RECALL_REJECTED", e.getMessage());
          case UNAVAILABLE -> new JobResult.Fail(e.getMessage(), Duration.ofSeconds(30));
        };
      }
    };
  }

  /**
   * The hub's idempotency key for a recall: the same for every attempt of the same step of the
   * same case, whichever worker makes it.
   */
  public static String clientRef(ActivatedJob job) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256")
          .digest((job.processInstanceKey() + "/" + job.elementId()).getBytes(StandardCharsets.UTF_8));
      return "PFL" + HexFormat.of().formatHex(h).substring(0, 13).toUpperCase(java.util.Locale.ROOT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String string(ActivatedJob job, String name) {
    Object v = job.variables().get(name);
    return v == null ? null : v.toString();
  }
}
