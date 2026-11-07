package io.github.mkp.pfl.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.backtest.BacktestResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EngagementTest {

  static Path root;
  static Engagement engagement;

  @BeforeAll
  static void load() throws Exception {
    root = TestRoot.copy();
    engagement = TestRoot.engagement(root);
  }

  @Test
  void overviewShowsBaselineConformanceAndProposals() throws Exception {
    Map<String, Object> o = engagement.overview();
    assertTrue((int) o.get("cases") > 500);
    assertEquals(5, ((List<?>) o.get("proposals")).size());
    assertEquals(Boolean.TRUE, o.get("decisionLogIntact"));
    assertTrue(engagement.conformance().fitness() > engagement.documentedConformance().fitness(),
        "the discovered model describes the history better than the procedure document");
  }

  @Test
  void theAuthorCannotApproveTheirOwnChange() throws Exception {
    engagement.backtest("hub-connector");
    Engagement.Refused e = assertThrows(Engagement.Refused.class, () -> engagement.decide("hub-connector", "fde.manoj", "APPROVE", "ok"));
    assertEquals(403, e.status());
  }

  @Test
  void onlyAnImprovementCanBeApprovedAndOnlyAShadowRunOnUnmeasuredAssumptions() throws Exception {
    BacktestResult limit = engagement.backtest("raise-four-eyes-limit");
    assertEquals(BacktestResult.Verdict.BLOCKED, limit.verdict());
    assertEquals(409, assertThrows(Engagement.Refused.class,
        () -> engagement.decide("raise-four-eyes-limit", "risk.officer", "APPROVE", "")).status());
    assertEquals(409, assertThrows(Engagement.Refused.class,
        () -> engagement.decide("raise-four-eyes-limit", "risk.officer", "APPROVE_SHADOW", "")).status());

    BacktestResult agent = engagement.backtest("agent-intake");
    assertEquals(BacktestResult.Verdict.NEEDS_SHADOW_RUN, agent.verdict());
    assertEquals(409, assertThrows(Engagement.Refused.class,
        () -> engagement.decide("agent-intake", "ops.lead", "APPROVE", "")).status());
    assertEquals("APPROVE_SHADOW", engagement.decide("agent-intake", "ops.lead", "APPROVE_SHADOW", "pilot for two weeks").action());
  }

  @Test
  void approvalBindsTheModelFileItWasGiven() throws Exception {
    Path own = TestRoot.copy();
    Engagement e = TestRoot.engagement(own);
    BacktestResult r = e.backtest("hub-connector");
    assertEquals(BacktestResult.Verdict.IMPROVES, r.verdict(), r.reasons().toString());
    DecisionLog.Entry approval = e.decide("hub-connector", "ops.lead", "APPROVE", "measured in staging");
    assertEquals(64, approval.modelSha().length());
    assertEquals(64, approval.evidenceSha().length());

    assertEquals(503, assertThrows(Engagement.Refused.class, () -> e.deploy("hub-connector", "ops.lead")).status(),
        "approved, but no cluster configured");
    Path model = own.resolve("proposals/hub-connector.bpmn");
    Files.writeString(model, Files.readString(model).replace("Find payment by hand", "Find payment manually"));
    Engagement.Refused changed = assertThrows(Engagement.Refused.class, () -> e.deploy("hub-connector", "ops.lead"));
    assertTrue(changed.getMessage().contains("changed after it was approved"));
    assertEquals(-1, e.decisions().verify());
  }

  @Test
  void deployNeedsAnApprovalFirst() {
    assertEquals(409, assertThrows(Engagement.Refused.class, () -> engagement.deploy("add-analyst", "ops.lead")).status());
  }
}
