package io.github.mkp.pfl.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.hub.experiment.HubReliabilityExperiment;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LegacyHubClientTest {

  static final String UETR = "eb6305c9-1f7f-49de-aed0-16487c27b42d";

  static LegacyHub hub(LegacyHub.Faults faults) {
    return new LegacyHub(faults, 1).add(new Payment(UETR, "PH0000000001", new BigDecimal("12500.00"), "EUR",
        LocalDate.of(2026, 7, 1), "ST"));
  }

  static LegacyHubClient client(LegacyHub hub) {
    return new LegacyHubClient(new InMemoryTransport(hub), "PFLWORKER", 4, Duration.ZERO);
  }

  @Test
  void looksUpAndRenewsExpiredSessionsWithoutBotheringTheCaller() throws Exception {
    LegacyHub hub = hub(new LegacyHub.Faults(0, 0, 2));
    LegacyHubClient c = client(hub);
    for (int i = 0; i < 5; i++) {
      Payment p = c.lookup(UETR);
      assertEquals(new BigDecimal("12500.00"), p.amount());
      assertEquals(LocalDate.of(2026, 7, 1), p.valueDate());
    }
    HubException e = assertThrows(HubException.class, () -> c.lookup("00000000-0000-4000-8000-000000000000"));
    assertEquals(HubException.Kind.NOT_FOUND, e.kind());
  }

  @Test
  void aSecondRecallForTheSameReferenceFindsTheFirst() throws Exception {
    LegacyHub hub = hub(LegacyHub.Faults.NONE);
    LegacyHubClient c = client(hub);
    LegacyHubClient.RecallReceipt first = c.recall(UETR, "PFL0000000000001", new BigDecimal("12500.00"), "EUR");
    LegacyHubClient.RecallReceipt again = c.recall(UETR, "PFL0000000000001", new BigDecimal("12500.00"), "EUR");
    assertFalse(first.alreadyOnFile());
    assertTrue(again.alreadyOnFile());
    assertEquals(first.hubReference(), again.hubReference());
    assertEquals(1, hub.recalls().size());
  }

  @Test
  void wrongAmountIsRejectedNotRetried() {
    LegacyHub hub = hub(LegacyHub.Faults.NONE);
    HubException e = assertThrows(HubException.class,
        () -> client(hub).recall(UETR, "PFL0000000000002", new BigDecimal("125.00"), "EUR"));
    assertEquals(HubException.Kind.REJECTED, e.kind());
    assertTrue(hub.recalls().isEmpty());
  }

  @Test
  void underLostAnswersAndJobRetriesNoRecallIsSentTwice() {
    LegacyHub.Faults hostile = new LegacyHub.Faults(0.15, 0.20, 5);
    for (long seed = 1; seed <= 15; seed++) {
      HubReliabilityExperiment.Outcome ours = HubReliabilityExperiment.run(true, hostile, seed, 150, 3);
      assertEquals(0, ours.recalledTwiceOrMore(), "seed " + seed);
      assertEquals(ours.recalls(), ours.recalledOnce() + ours.neverRecalled());
    }
  }

  @Test
  void blindRetryDoesSendRecallsTwice() {
    HubReliabilityExperiment.Outcome blind = HubReliabilityExperiment.run(false, new LegacyHub.Faults(0.15, 0.20, 5), 1, 300, 3);
    assertTrue(blind.recalledTwiceOrMore() > 20, "the danger this client exists for is real: " + blind);
  }

  @Test
  void realHttpTimeoutsAreTreatedAsAmbiguous() throws Exception {
    LegacyHub hub = hub(new LegacyHub.Faults(0, 0.5, 100));
    try (HubHttpServer server = new HubHttpServer(hub, 0, Duration.ofMillis(400))) {
      LegacyHubClient c = new LegacyHubClient(new HttpHubTransport(URI.create("http://127.0.0.1:" + server.port() + "/ph9/gateway"),
          Duration.ofMillis(150)), "PFLWORKER", 8, Duration.ZERO);
      LegacyHubClient.RecallReceipt receipt = c.recall(UETR, "PFL0000000000003", new BigDecimal("12500.00"), "EUR");
      assertTrue(receipt.hubReference().startsWith("RC"));
      assertEquals(1, hub.recalls().size(), "half the answers were lost, the money was recalled once");
    }
  }
}
