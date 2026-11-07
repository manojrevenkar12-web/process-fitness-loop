package io.github.mkp.pfl.verify;

import io.github.mkp.pfl.bpmn.Flow;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.NodeKind;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.feel.Feel;
import io.github.mkp.pfl.feel.FeelException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a process before anyone deploys it: will the engine accept it, can every run finish, and
 * is there anything a reviewer should look at.
 *
 * <p>Generated models are the reason this exists. A model written from a natural-language brief
 * looks plausible in the diagram and still joins an exclusive split with a parallel gateway. The
 * engine deploys that happily; the first case that takes the branch hangs.
 */
public final class Verifier {

  private final int stateLimit;

  public Verifier() {
    this(200_000);
  }

  public Verifier(int stateLimit) {
    this.stateLimit = stateLimit;
  }

  public VerificationReport verify(ProcessModel model) {
    return verify(model, Optional.empty());
  }

  /**
   * @param knownVariables variables a case is guaranteed to carry when it reaches a gateway: the
   *     start variables plus what tasks declare in their {@code pfl:outputs} property. When given,
   *     conditions that read anything else are reported.
   */
  public VerificationReport verify(ProcessModel model, Optional<Set<String>> knownVariables) {
    List<Issue> issues = new ArrayList<>(DeployRules.check(model));
    BehaviourCheck.Result behaviour = new BehaviourCheck.Result(List.of(), 0, true);
    boolean structurallyUsable = issues.stream()
        .noneMatch(i -> i.code().equals("DANGLING_FLOW") || i.code().equals("DUPLICATE_ID"));
    if (structurallyUsable) {
      behaviour = new BehaviourCheck(model, stateLimit).run();
      issues.addAll(behaviour.issues());
      if (!behaviour.complete()) {
        issues.add(Issue.of("STATE_LIMIT", Issue.Category.BEHAVIOUR, Issue.Severity.WARNING, model.id(),
            "The behaviour check stopped after " + behaviour.states() + " states; it is not a proof.",
            "Split the process into smaller parts, or raise the limit."));
      }
    }
    issues.addAll(design(model, knownVariables));
    issues.sort(Comparator.comparing(Issue::category).thenComparing(Issue::severity));
    return new VerificationReport(model.id(), issues, behaviour.states(), behaviour.complete());
  }

  private static List<Issue> design(ProcessModel model, Optional<Set<String>> knownVariables) {
    List<Issue> issues = new ArrayList<>();
    for (Node node : model.nodes().values()) {
      if (node.kind() == NodeKind.USER_TASK) {
        if (!node.nativeUserTask()) {
          issues.add(Issue.of("JOB_BASED_USER_TASK", Issue.Category.DESIGN, Issue.Severity.WARNING, node.id(),
              "'" + node.label() + "' is a job-based user task, which Camunda 8 deprecates.",
              "Add <zeebe:userTask/> to make it a Camunda user task."));
        }
        if (node.candidateGroups().isEmpty()) {
          issues.add(Issue.of("USER_TASK_WITHOUT_ROLE", Issue.Category.DESIGN, Issue.Severity.WARNING, node.id(),
              "Nobody is named for '" + node.label() + "'; the work lands in no team's queue.",
              "Set candidateGroups, e.g. payments-ops."));
        }
      }
      if (node.kind() == NodeKind.PARALLEL_GATEWAY) {
        for (Flow f : model.outgoing(node)) {
          if (f.condition() != null) {
            issues.add(Issue.of("CONDITION_ON_PARALLEL_FLOW", Issue.Category.DESIGN, Issue.Severity.WARNING, f.id(),
                "A flow out of parallel gateway '" + node.label() + "' has a condition; parallel gateways take every flow.",
                "Use an exclusive gateway if the branch should depend on the condition."));
          }
        }
      }
      if (node.kind() == NodeKind.EXCLUSIVE_GATEWAY && model.outgoing(node).size() > 1 && node.defaultFlow() == null) {
        issues.add(Issue.of("NO_DEFAULT_FLOW", Issue.Category.DESIGN, Issue.Severity.WARNING, node.id(),
            "Decision '" + node.label() + "' has no default flow: a case that matches no condition raises an incident.",
            "Mark one flow as the default, usually the 'otherwise' branch."));
      }
    }
    if (knownVariables.isPresent()) {
      Set<String> known = new HashSet<>(knownVariables.get());
      for (Node node : model.nodes().values()) {
        node.property("pfl:outputs").ifPresent(out -> {
          for (String v : out.split(",")) {
            known.add(v.strip());
          }
        });
      }
      for (Flow flow : model.flows().values()) {
        if (flow.condition() == null) {
          continue;
        }
        try {
          for (String v : Feel.variables(Feel.parse(flow.condition()))) {
            if (!known.contains(v)) {
              issues.add(Issue.of("UNKNOWN_VARIABLE", Issue.Category.DESIGN, Issue.Severity.WARNING, flow.id(),
                  "Condition '" + flow.condition() + "' reads '" + v + "', which no start variable or task output provides.",
                  "Declare it as a start variable or in a task's pfl:outputs, or fix the name."));
            }
          }
        } catch (FeelException e) {
          // reported by the deploy rules
        }
      }
    }
    return issues;
  }
}
