package io.github.mkp.pfl.verify;

import io.github.mkp.pfl.bpmn.EventDefinition;
import io.github.mkp.pfl.bpmn.Flow;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.NodeKind;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.feel.Feel;
import io.github.mkp.pfl.feel.FeelException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks that predict whether Zeebe accepts a deployment. Each rule mirrors a validation the engine
 * performs when a resource is deployed; CI measures the agreement on mutated models against a real
 * cluster (see {@code reports/verifier-vs-engine.json}).
 */
final class DeployRules {

  /** ISO-8601 duration as Zeebe accepts it for timers: P[nY][nM][nW][nD][T[nH][nM][n[.n]S]]. */
  static final Pattern ISO_DURATION =
      Pattern.compile("^P(?!$)(\\d+Y)?(\\d+M)?(\\d+W)?(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?$");

  private DeployRules() {}

  static List<Issue> check(ProcessModel model) {
    List<Issue> issues = new ArrayList<>();
    for (ProcessModel.ParseFinding f : model.findings()) {
      switch (f.code()) {
        case "DANGLING_FLOW", "DUPLICATE_ID" ->
            issues.add(Issue.of(f.code(), Issue.Category.DEPLOY, Issue.Severity.ERROR, f.elementId(), f.detail(),
                "Every sequence flow must connect two elements of the process, and every id must be unique."));
        case "UNSUPPORTED_ELEMENT", "UNSUPPORTED_EVENT", "UNSUPPORTED_TIMER" ->
            issues.add(Issue.of(f.code(), Issue.Category.DESIGN, Issue.Severity.WARNING, f.elementId(), f.detail(),
                "The engine may run this, but this toolkit cannot check or simulate it. Review it by hand."));
        default ->
            issues.add(Issue.of(f.code(), Issue.Category.DESIGN, Issue.Severity.INFO, f.elementId(), f.detail(), ""));
      }
    }

    List<Node> starts = model.nodesOfKind(NodeKind.START_EVENT);
    if (starts.isEmpty()) {
      issues.add(Issue.of("NO_START_EVENT", Issue.Category.DEPLOY, Issue.Severity.ERROR, model.id(),
          "The process has no start event, so nothing can ever start it.",
          "Add a start event and connect it to the first step."));
    }
    long noneStarts = starts.stream().filter(s -> s.event() == null || s.event().type() == EventDefinition.Type.NONE).count();
    if (noneStarts > 1) {
      issues.add(Issue.of("MULTIPLE_NONE_START_EVENTS", Issue.Category.DEPLOY, Issue.Severity.ERROR, model.id(),
          "The process has " + noneStarts + " plain start events; the engine allows one.",
          "Keep one plain start event, or give the others a message or timer trigger."));
    }

    for (Node node : model.nodes().values()) {
      checkNode(model, node, issues);
    }
    for (Flow flow : model.flows().values()) {
      if (flow.condition() != null) {
        checkCondition(flow, issues);
        model.findNode(flow.source()).ifPresent(source -> {
          if (source.kind() != NodeKind.EXCLUSIVE_GATEWAY && source.kind() != NodeKind.PARALLEL_GATEWAY) {
            issues.add(Issue.of("CONDITION_OUTSIDE_GATEWAY", Issue.Category.DEPLOY, Issue.Severity.ERROR, flow.id(),
                "The flow out of '" + source.label() + "' has a condition, but only flows leaving a gateway may.",
                "Put an exclusive gateway after '" + source.label() + "' and move the condition there."));
          }
        });
      }
    }
    return issues;
  }

  private static void checkNode(ProcessModel model, Node node, List<Issue> issues) {
    switch (node.kind()) {
      case START_EVENT -> {
        if (!node.incoming().isEmpty()) {
          issues.add(Issue.of("START_EVENT_HAS_INCOMING", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Start event '" + node.label() + "' has an incoming flow.",
              "Start events begin the process; route loops back to a gateway or task instead."));
        }
      }
      case END_EVENT -> {
        if (!node.outgoing().isEmpty()) {
          issues.add(Issue.of("END_EVENT_HAS_OUTGOING", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "End event '" + node.label() + "' has an outgoing flow.",
              "Remove the flow, or replace the end event with an intermediate step."));
        }
      }
      case BOUNDARY_EVENT -> {
        Node host = node.attachedTo() == null ? null : model.nodes().get(node.attachedTo());
        if (host == null || !host.kind().isActivity()) {
          issues.add(Issue.of("BOUNDARY_NOT_ATTACHED", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Boundary event '" + node.label() + "' is not attached to a task of this process.",
              "Attach it to the task whose failure or delay it should catch."));
        }
        if (!node.incoming().isEmpty()) {
          issues.add(Issue.of("BOUNDARY_HAS_INCOMING", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Boundary event '" + node.label() + "' has an incoming flow.",
              "Boundary events are triggered by their task, not by a flow."));
        }
        if (node.outgoing().isEmpty()) {
          issues.add(Issue.of("BOUNDARY_WITHOUT_OUTGOING", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Boundary event '" + node.label() + "' leads nowhere.",
              "Connect it to the step that should handle the event."));
        }
      }
      default -> {
        // activity and gateway rules below
      }
    }

    if (node.event() != null) {
      checkEvent(node, issues);
    }

    if (node.kind().needsJobType() && node.taskType() == null) {
      issues.add(Issue.of("MISSING_JOB_TYPE", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
          "'" + node.label() + "' is automated but names no job type, so no worker could pick it up.",
          "Add <zeebe:taskDefinition type=\"...\"/> naming the worker or connector."));
    }
    if (node.kind() == NodeKind.BUSINESS_RULE_TASK
        && node.taskType() == null
        && node.property("zeebe:calledDecision").isEmpty()) {
      issues.add(Issue.of("MISSING_DECISION", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
          "Rule task '" + node.label() + "' calls no decision table and names no job type.",
          "Add <zeebe:calledDecision decisionId=\"...\" resultVariable=\"...\"/>."));
    }

    if (node.kind() == NodeKind.EXCLUSIVE_GATEWAY) {
      List<Flow> out = model.outgoing(node);
      if (node.defaultFlow() != null) {
        Flow def = model.flows().get(node.defaultFlow());
        if (def == null || !node.id().equals(def.source())) {
          issues.add(Issue.of("DEFAULT_FLOW_NOT_OUTGOING", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Gateway '" + node.label() + "' names default flow '" + node.defaultFlow() + "', which does not leave it.",
              "Point the default at one of the gateway's outgoing flows."));
        } else if (def.condition() != null) {
          issues.add(Issue.of("DEFAULT_FLOW_HAS_CONDITION", Issue.Category.DEPLOY, Issue.Severity.ERROR, def.id(),
              "The default flow of '" + node.label() + "' also has a condition.",
              "A default flow is taken when no condition holds; remove its condition."));
        }
      }
      if (out.size() > 1) {
        for (Flow f : out) {
          if (f.condition() == null && !f.id().equals(node.defaultFlow())) {
            issues.add(Issue.of("GATEWAY_FLOW_WITHOUT_CONDITION", Issue.Category.DEPLOY, Issue.Severity.ERROR, f.id(),
                "A flow out of decision '" + node.label() + "' has no condition and is not the default.",
                "Give it a condition such as =amount > 10000, or make it the default flow."));
          }
        }
      }
    }
  }

  private static void checkEvent(Node node, List<Issue> issues) {
    EventDefinition event = node.event();
    switch (event.type()) {
      case TIMER -> {
        String duration = event.timerDuration();
        if (duration == null) {
          if (node.kind() != NodeKind.START_EVENT) {
            issues.add(Issue.of("TIMER_WITHOUT_DURATION", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
                "Timer '" + node.label() + "' has no duration.",
                "Set timeDuration, e.g. PT4H or P2D."));
          }
        } else if (duration.startsWith("=")) {
          try {
            Feel.parse(duration);
          } catch (FeelException e) {
            if (e.kind() == FeelException.Kind.SYNTAX) {
              issues.add(Issue.of("TIMER_INVALID", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
                  "Timer '" + node.label() + "' has an invalid expression: " + e.getMessage(), "Fix the FEEL expression."));
            }
          }
        } else if (!ISO_DURATION.matcher(duration).matches()) {
          issues.add(Issue.of("TIMER_INVALID", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Timer '" + node.label() + "' has duration '" + duration + "', which is not an ISO-8601 duration.",
              "Write durations like PT30M, PT4H or P2D."));
        }
      }
      case MESSAGE -> {
        if (event.definitionMissing() || event.messageName() == null) {
          issues.add(Issue.of("MESSAGE_WITHOUT_NAME", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Message event '" + node.label() + "' refers to no named message.",
              "Reference a bpmn:message with a name."));
        } else if (node.kind() != NodeKind.START_EVENT && event.correlationKey() == null) {
          issues.add(Issue.of("MESSAGE_WITHOUT_CORRELATION_KEY", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Message '" + event.messageName() + "' has no correlation key, so the engine cannot tell which case a reply belongs to.",
              "Add <zeebe:subscription correlationKey=\"=uetr\"/> to the message."));
        }
      }
      case ERROR -> {
        if (event.definitionMissing()) {
          issues.add(Issue.of("ERROR_REF_MISSING", Issue.Category.DEPLOY, Issue.Severity.ERROR, node.id(),
              "Error event '" + node.label() + "' refers to an error that is not defined in the file.",
              "Define the bpmn:error with its errorCode, or remove the reference to catch every error."));
        }
      }
      default -> {
        // none and unsupported are handled elsewhere
      }
    }
  }

  private static void checkCondition(Flow flow, List<Issue> issues) {
    try {
      Feel.parse(flow.condition());
    } catch (FeelException e) {
      if (e.kind() == FeelException.Kind.SYNTAX) {
        issues.add(Issue.of("FEEL_SYNTAX", Issue.Category.DEPLOY, Issue.Severity.ERROR, flow.id(),
            "Condition '" + flow.condition() + "' is not valid: " + e.getMessage(),
            "Conditions are FEEL expressions starting with '=', e.g. =amount > 10000."));
      } else {
        issues.add(Issue.of("FEEL_UNSUPPORTED", Issue.Category.DESIGN, Issue.Severity.WARNING, flow.id(),
            "Condition '" + flow.condition() + "' uses FEEL this toolkit cannot evaluate: " + e.getMessage(),
            "The engine may accept it, but the simulator and backtest cannot follow this branch."));
      }
    }
  }
}
