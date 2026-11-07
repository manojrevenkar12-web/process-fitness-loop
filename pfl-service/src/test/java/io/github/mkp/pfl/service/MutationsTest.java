package io.github.mkp.pfl.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParseException;
import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.service.mutate.BpmnMutations;
import io.github.mkp.pfl.verify.VerificationReport;
import io.github.mkp.pfl.verify.Verifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MutationsTest {

  /** Operators whose mutants the verifier must predict the engine refuses. */
  static final Set<String> REFUSED = Set.of("drop-job-type", "drop-condition", "broken-feel", "condition-without-equals",
      "unbalanced-paren", "condition-outside-gateway", "drop-default", "default-not-outgoing", "condition-on-default", "drop-correlation-key",
      "boundary-detached", "error-ref-missing", "start-has-incoming", "end-has-outgoing", "dangling-flow", "duplicate-id",
      "intermediate-timer-bad-duration");

  @Test
  void everyMutantIsJudgedAndTheJudgementFollowsTheOperator() throws Exception {
    byte[] original = Files.readAllBytes(Path.of("../processes/payment-investigation-to-be.bpmn"));
    List<BpmnMutations.Mutant> mutants = BpmnMutations.all(original);
    assertTrue(mutants.size() > 40, "got " + mutants.size());
    Verifier verifier = new Verifier();
    for (BpmnMutations.Mutant m : mutants) {
      boolean deployable;
      try {
        VerificationReport r = verifier.verify(new BpmnParser().parse(m.bpmn()));
        deployable = r.deployable();
        if (m.operator().equals("merge-becomes-parallel-join")) {
          assertTrue(r.deployable() && !r.sound(), m.name() + " deploys but hangs: " + r.issues());
        }
      } catch (BpmnParseException e) {
        deployable = false;
      }
      if (REFUSED.contains(m.operator())) {
        assertFalse(deployable, m.name());
      } else {
        assertTrue(deployable, m.name());
      }
    }
  }
}
