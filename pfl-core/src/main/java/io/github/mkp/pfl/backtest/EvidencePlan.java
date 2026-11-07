package io.github.mkp.pfl.backtest;

import io.github.mkp.pfl.bpmn.EventDefinition;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.NodeKind;
import io.github.mkp.pfl.bpmn.ProcessModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * For every step of a candidate process: where its durations, outputs and errors come from. Built
 * before anything is simulated, so a proposal with an unexplained step is refused up front.
 */
public record EvidencePlan(Map<String, Source> sources) {

  /**
   * Evidence for one step.
   *
   * @param history today's step whose durations stand for it, or null
   * @param outputsFrom today's step whose recorded outputs stand for it, or null
   * @param assumption the assumption for durations (when history is null), errors and outputs
   */
  public record Source(String history, String outputsFrom, Proposal.Assumption assumption) {

    public EvidenceTier weakestTier() {
      return history == null ? EvidenceTier.ASSUMED : EvidenceTier.SIMILAR_CASES;
    }
  }

  /** Thrown when a step of the candidate has no evidence. */
  public static final class MissingEvidence extends IllegalArgumentException {
    private final List<String> problems;

    MissingEvidence(List<String> problems) {
      super(String.join("; ", problems));
      this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
      return problems;
    }
  }

  public static EvidencePlan build(Proposal proposal, ProcessModel candidate, ProcessModel today, HistoryIndex history) {
    Map<String, Source> sources = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    for (Node node : candidate.nodes().values()) {
      boolean timed = node.kind().isActivity() && node.kind() != NodeKind.TASK && node.kind() != NodeKind.MANUAL_TASK;
      boolean message = node.kind() == NodeKind.INTERMEDIATE_CATCH_EVENT
          && node.event() != null && node.event().type() == EventDefinition.Type.MESSAGE;
      if (!timed && !message) {
        continue;
      }
      Proposal.StepEvidence declared = proposal.steps().get(node.id());
      if (declared != null) {
        if (declared.history() != null && !history.knows(declared.history())) {
          problems.add("'" + node.id() + "' points at history of '" + declared.history() + "', which today's history does not contain");
          continue;
        }
        if (declared.outputsFrom() != null && !history.knows(declared.outputsFrom())) {
          problems.add("'" + node.id() + "' takes outputs from '" + declared.outputsFrom() + "', which today's history does not contain");
          continue;
        }
        if (declared.history() == null && declared.assume() == null) {
          problems.add("'" + node.id() + "' declares neither history nor an assumption");
          continue;
        }
        sources.put(node.id(), new Source(declared.history(), declared.outputsFrom(), declared.assume()));
        continue;
      }
      Node old = today.nodes().get(node.id());
      boolean sameWork = old != null && history.knows(node.id())
          && (message ? old.kind() == NodeKind.INTERMEDIATE_CATCH_EVENT : old.actor() == node.actor());
      if (sameWork) {
        sources.put(node.id(), new Source(node.id(), node.id(), null));
      } else {
        problems.add("'" + node.id() + "' (" + node.label() + ") is new work or done by a different kind of actor than today;"
            + " declare which history stands for it or an assumption with its source");
      }
    }
    if (!problems.isEmpty()) {
      throw new MissingEvidence(problems);
    }
    return new EvidencePlan(sources);
  }
}
