package io.github.mkp.pfl.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.camunda.ActivatedJob;
import io.github.mkp.pfl.worker.JobResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HubHandlersTest {

  static final String UETR = LegacyHubClientTest.UETR;

  static ActivatedJob job(String type, String pi, Map<String, Object> vars) {
    return new ActivatedJob("1", type, pi, "send_recall", 5, vars, null);
  }

  static LegacyHubClient client() {
    return new LegacyHubClient(new InMemoryTransport(new LegacyHub(LegacyHub.Faults.NONE, 1)
        .add(new Payment(UETR, "PH0000000001", new BigDecimal("900.00"), "EUR", LocalDate.of(2026, 7, 1), "ST"))
        .add(new Payment("11111111-2222-4333-8444-555555555555", "PH0000000002", new BigDecimal("50.00"), "EUR",
            LocalDate.of(2026, 7, 1), "PD"))), "PFLWORKER", 2, Duration.ZERO);
  }

  @Test
  void lookupMapsTheHubToProcessOutcomes() {
    var lookup = HubHandlers.lookup(client());
    JobResult found = lookup.handle(job(HubHandlers.LOOKUP, "1", Map.of("uetr", UETR)));
    assertEquals("ST", ((JobResult.Complete) found).variables().get("hubStatus"));
    JobResult missing = lookup.handle(job(HubHandlers.LOOKUP, "1", Map.of("uetr", "00000000-0000-4000-8000-000000000000")));
    assertEquals("PAYMENT_NOT_FOUND", ((JobResult.BpmnError) missing).code());
  }

  @Test
  void recallMapsRejectionsToABusinessErrorAndMissingDataToAFailure() {
    var recall = HubHandlers.recall(client());
    assertInstanceOf(JobResult.Complete.class, recall.handle(job(HubHandlers.RECALL, "7", Map.of("uetr", UETR, "amount", 900, "currency", "EUR"))));
    JobResult pending = recall.handle(job(HubHandlers.RECALL, "8",
        Map.of("uetr", "11111111-2222-4333-8444-555555555555", "amount", "50.00", "currency", "EUR")));
    assertEquals("RECALL_REJECTED", ((JobResult.BpmnError) pending).code());
    assertInstanceOf(JobResult.Fail.class, recall.handle(job(HubHandlers.RECALL, "9", Map.of("uetr", UETR))));
  }

  @Test
  void clientReferenceIsStablePerCaseAndStep() {
    String a = HubHandlers.clientRef(job(HubHandlers.RECALL, "2251799813685249", Map.of()));
    assertEquals(16, a.length());
    assertEquals(a, HubHandlers.clientRef(new ActivatedJob("99", HubHandlers.RECALL, "2251799813685249", "send_recall", 1, Map.of(), null)));
    assertNotEquals(a, HubHandlers.clientRef(job(HubHandlers.RECALL, "2251799813685250", Map.of())));
    assertTrue(a.startsWith("PFL"));
  }
}
