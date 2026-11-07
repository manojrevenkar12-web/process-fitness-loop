package io.github.mkp.pfl.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.bpmn.TestBpmn;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerifierTest {

  private final Verifier verifier = new Verifier();

  private static ProcessModel shipped(String name) throws Exception {
    return new BpmnParser().parse(Path.of("../processes", name));
  }

  @Test
  void theShippedProcessesDeployAndCanAlwaysFinish() throws Exception {
    for (String file : List.of("payment-investigation-as-is.bpmn", "payment-investigation-as-documented.bpmn",
        "payment-investigation-to-be.bpmn")) {
      VerificationReport report = verifier.verify(shipped(file));
      assertTrue(report.sound(), file + ": " + report.issues());
      assertTrue(report.byCategory(Issue.Category.BEHAVIOUR).isEmpty(), file + ": " + report.issues());
    }
  }

  @Test
  void theToBeProcessOnlyReadsVariablesSomethingProvides() throws Exception {
    VerificationReport report = verifier.verify(shipped("payment-investigation-to-be.bpmn"),
        Optional.of(Set.of("caseId", "amount", "currency", "channel")));
    assertFalse(report.has("UNKNOWN_VARIABLE"), report.issues().toString());

    VerificationReport missing = verifier.verify(shipped("payment-investigation-to-be.bpmn"),
        Optional.of(Set.of("caseId", "currency")));
    assertTrue(missing.has("UNKNOWN_VARIABLE"), "amount is read by the four-eyes gateway");
  }

  @Test
  void exclusiveSplitJoinedByParallelGatewayHangs() {
    ProcessModel model = TestBpmn.process("p")
        .start("s").xor("split", "f_b").serviceTask("a", "Auto refund", "refund").userTask("b", "Manual refund", "ops")
        .and("join").end("e")
        .flow("f0", "s", "split").flow("f_a", "split", "a", "=amount < 100").flow("f_b", "split", "b")
        .flow("f1", "a", "join").flow("f2", "b", "join").flow("f3", "join", "e")
        .parse();
    VerificationReport report = verifier.verify(model);
    assertTrue(report.deployable(), "the engine accepts this file");
    assertFalse(report.sound());
    Issue deadlock = report.issues().stream().filter(i -> i.code().equals("DEADLOCK")).findFirst().orElseThrow();
    assertEquals("join", deadlock.elementId());
    assertEquals("s", deadlock.trace().get(0));
    assertTrue(deadlock.message().contains("hangs"));
  }

  @Test
  void parallelSplitMergedByExclusiveGatewayRunsTheNextStepTwice() {
    ProcessModel model = TestBpmn.process("p")
        .start("s").and("split").userTask("a", "Check sanctions", "ops").userTask("b", "Check funds", "ops")
        .xor("merge", null).serviceTask("send", "Send recall", "recall").end("e")
        .flow("f0", "s", "split").flow("f1", "split", "a").flow("f2", "split", "b")
        .flow("f3", "a", "merge").flow("f4", "b", "merge").flow("f5", "merge", "send").flow("f6", "send", "e")
        .parse();
    VerificationReport report = verifier.verify(model);
    assertTrue(report.deployable());
    Issue twice = report.issues().stream().filter(i -> i.code().equals("DUPLICATE_EXECUTION")).findFirst().orElseThrow();
    assertEquals("send", twice.elementId(), "points at the first step that runs twice");
    assertEquals(1, report.issues().stream().filter(i -> i.code().equals("DUPLICATE_EXECUTION")).count());
    assertFalse(report.sound());
  }

  @Test
  void loopWithoutExitCanNeverFinish() {
    ProcessModel model = TestBpmn.process("p")
        .start("s").xor("m", null).userTask("a", "Chase sender", "ops").xor("g", null).end("e")
        .flow("f0", "s", "m").flow("f1", "m", "a").flow("f2", "a", "g")
        .flow("f3", "g", "m", "=true")
        .parse();
    VerificationReport report = verifier.verify(model);
    assertTrue(report.has("NO_WAY_TO_FINISH"), report.issues().toString());
    assertTrue(report.has("DEAD_ELEMENT") || model.node("e").incoming().isEmpty());
  }

  @Test
  void flagsWhatTheEngineRejects() {
    ProcessModel model = TestBpmn.process("p")
        .definition("<bpmn:message id=\"m\" name=\"reply\" />")
        .start("s").raw("<bpmn:serviceTask id=\"t\" name=\"Lookup\" />").xor("g", "f_missing")
        .userTask("a", "A", "ops").userTask("b", "B", "ops")
        .raw("<bpmn:intermediateCatchEvent id=\"w\"><bpmn:messageEventDefinition messageRef=\"m\" /></bpmn:intermediateCatchEvent>")
        .raw("<bpmn:intermediateCatchEvent id=\"timer\"><bpmn:timerEventDefinition><bpmn:timeDuration>4 hours</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>")
        .end("e")
        .flow("f0", "s", "t").flow("f1", "t", "g").flow("f2", "g", "a", "amount > 5").flow("f3", "g", "b")
        .flow("f4", "a", "w").flow("f5", "w", "timer").flow("f6", "timer", "e").flow("f7", "b", "e", "=x > 1")
        .flow("f8", "b", "nowhere")
        .parse();
    VerificationReport report = verifier.verify(model);
    assertFalse(report.deployable());
    for (String code : List.of("MISSING_JOB_TYPE", "DEFAULT_FLOW_NOT_OUTGOING", "FEEL_SYNTAX",
        "GATEWAY_FLOW_WITHOUT_CONDITION", "MESSAGE_WITHOUT_CORRELATION_KEY", "TIMER_INVALID",
        "CONDITION_OUTSIDE_GATEWAY", "DANGLING_FLOW")) {
      assertTrue(report.has(code), code + " missing in " + report.issues());
    }
    assertEquals(Issue.Category.DEPLOY, report.issues().get(0).category(), "deploy problems come first");
    // Camunda 8.10 deploys these; they are risks, not deploy errors (reports/verifier-vs-engine-first-run.json)
    for (String accepted : List.of("GATEWAY_FLOW_WITHOUT_CONDITION", "CONDITION_OUTSIDE_GATEWAY")) {
      assertTrue(report.issues().stream().filter(i -> i.code().equals(accepted)).allMatch(i -> i.category() != Issue.Category.DEPLOY), accepted);
    }
  }

  @Test
  void deployRulesAcceptValidTimersAndRejectMalformedOnes() {
    for (String ok : List.of("PT4H", "P2D", "P1DT12H", "PT30M", "PT0.5S", "P1W")) {
      assertTrue(DeployRules.ISO_DURATION.matcher(ok).matches(), ok);
    }
    for (String bad : List.of("P", "PT", "4H", "P1H", "PT1D", "P1.5D")) {
      assertFalse(DeployRules.ISO_DURATION.matcher(bad).matches(), bad);
    }
  }

  @Test
  void stateLimitMakesTheResultInconclusiveInsteadOfWrong() {
    ProcessModel model = TestBpmn.process("p")
        .start("s").and("split").userTask("a", "A", "ops").userTask("b", "B", "ops").userTask("c", "C", "ops")
        .and("join").end("e")
        .flow("f0", "s", "split").flow("f1", "split", "a").flow("f2", "split", "b").flow("f3", "split", "c")
        .flow("f4", "a", "join").flow("f5", "b", "join").flow("f6", "c", "join").flow("f7", "join", "e")
        .parse();
    VerificationReport full = verifier.verify(model);
    assertTrue(full.sound());
    VerificationReport limited = new Verifier(3).verify(model);
    assertFalse(limited.explorationComplete());
    assertFalse(limited.sound(), "an unfinished check is never reported as sound");
    assertTrue(limited.has("STATE_LIMIT"));
  }
}
