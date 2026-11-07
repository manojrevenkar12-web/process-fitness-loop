package io.github.mkp.pfl.explain;

import io.github.mkp.pfl.bpmn.EventDefinition;
import io.github.mkp.pfl.bpmn.Flow;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.NodeKind;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.feel.Feel;
import io.github.mkp.pfl.feel.FeelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a generated process out as numbered steps a business lead can check against how the work
 * really happens, with no BPMN vocabulary.
 *
 * <p>The text comes from the model alone, never from a language model: the same file always gives
 * the same words, and every sentence points at an element the reader can find in the diagram. That
 * is what makes it safe to put in front of the person who signs the process off.
 */
public final class Explainer {

  private static final Pattern DURATION =
      Pattern.compile("^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$");

  public Explanation explain(ProcessModel model) {
    Map<String, Integer> number = numberSteps(model);
    List<Step> steps = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : number.entrySet()) {
      Node node = model.node(entry.getKey());
      steps.add(new Step(entry.getValue(), node.id(), node.label(), node.actor(), describe(model, node, number)));
    }
    Map<Node.Actor, Integer> byActor = new EnumMap<>(Node.Actor.class);
    for (Node node : model.nodes().values()) {
      if (node.kind().isActivity()) {
        byActor.merge(node.actor(), 1, Integer::sum);
      }
    }
    int decisions = (int) model.nodesOfKind(NodeKind.EXCLUSIVE_GATEWAY).stream()
        .filter(g -> g.outgoing().size() > 1).count();
    int waits = model.nodesOfKind(NodeKind.INTERMEDIATE_CATCH_EVENT).size();
    String summary = summary(model, byActor, decisions, waits);
    return new Explanation(model.id(), model.label(), summary, steps);
  }

  /** Numbers flow nodes breadth-first from the start events, in document order of flows. */
  private static Map<String, Integer> numberSteps(ProcessModel model) {
    Map<String, Integer> number = new LinkedHashMap<>();
    Deque<String> queue = new ArrayDeque<>();
    for (Node start : model.nodesOfKind(NodeKind.START_EVENT)) {
      queue.add(start.id());
    }
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (number.containsKey(id) || !model.nodes().containsKey(id)) {
        continue;
      }
      Node node = model.node(id);
      number.put(id, number.size() + 1);
      for (Flow f : model.outgoing(node)) {
        queue.add(f.target());
      }
      for (Node boundary : model.boundaryEventsOf(id)) {
        for (Flow f : model.outgoing(boundary)) {
          queue.add(f.target());
        }
      }
    }
    return number;
  }

  private static String describe(ProcessModel model, Node node, Map<String, Integer> number) {
    StringBuilder text = new StringBuilder();
    switch (node.kind()) {
      case START_EVENT -> {
        EventDefinition e = node.event();
        if (e != null && e.type() == EventDefinition.Type.MESSAGE) {
          text.append("A case starts when the message '").append(e.messageName()).append("' arrives.");
        } else if (e != null && e.type() == EventDefinition.Type.TIMER) {
          text.append("A case starts on a schedule.");
        } else {
          text.append("A case starts.");
        }
        text.append(next(model, node, number));
      }
      case END_EVENT -> text.append("The case is finished").append(node.name().isBlank() ? "." : ": " + node.name() + ".");
      case EXCLUSIVE_GATEWAY -> {
        List<Flow> out = model.outgoing(node);
        if (out.size() <= 1) {
          text.append("The paths come together here.").append(next(model, node, number));
        } else {
          text.append("A decision is made. Exactly one of these happens:");
          for (Flow f : out) {
            text.append("\n   - ");
            if (f.id().equals(node.defaultFlow())) {
              text.append("otherwise");
            } else {
              text.append("if ").append(condition(f));
            }
            text.append(", go to step ").append(stepOf(f.target(), number));
            if (!f.name().isBlank()) {
              text.append(" (").append(f.name()).append(")");
            }
          }
        }
      }
      case PARALLEL_GATEWAY -> {
        List<Flow> in = model.incoming(node);
        List<Flow> out = model.outgoing(node);
        if (in.size() > 1) {
          text.append("Waits until steps ").append(joinSteps(in.stream().map(Flow::source).toList(), number))
              .append(" are all done.");
        }
        if (out.size() > 1) {
          text.append(in.size() > 1 ? " Then steps " : "Steps ")
              .append(joinSteps(out.stream().map(Flow::target).toList(), number))
              .append(" happen at the same time.");
        } else {
          text.append(next(model, node, number));
        }
      }
      case INTERMEDIATE_CATCH_EVENT -> {
        EventDefinition e = node.event();
        if (e != null && e.type() == EventDefinition.Type.TIMER) {
          text.append("The case waits ").append(durationInWords(e.timerDuration())).append(".");
        } else if (e != null && e.type() == EventDefinition.Type.MESSAGE) {
          text.append("The case waits for the message '").append(e.messageName()).append("'");
          if (e.correlationKey() != null) {
            text.append(", matched to this case by ").append(keyInWords(e.correlationKey()));
          }
          text.append(".");
        } else {
          text.append("The case waits.");
        }
        text.append(next(model, node, number));
      }
      default -> {
        text.append(who(node)).append(".");
        if (!node.documentation().isBlank()) {
          text.append(" ").append(node.documentation());
        }
        for (Node boundary : model.boundaryEventsOf(node.id())) {
          text.append(" ").append(boundaryText(model, boundary, number));
        }
        text.append(next(model, node, number));
      }
    }
    return text.toString();
  }

  private static String who(Node node) {
    return switch (node.actor()) {
      case PERSON -> node.candidateGroups().isEmpty()
          ? "Done by a person (no team is named)"
          : "Done by a person from " + String.join(" or ", node.candidateGroups());
      case AGENT -> "Done by an AI agent" + (node.taskType() == null ? "" : " (" + node.taskType() + ")")
          + "; its output is checked by rules before the case moves on";
      case SYSTEM -> "Done automatically by " + (node.taskType() == null ? "a system" : "'" + node.taskType() + "'");
      case RULE -> "Decided by the rule table " + node.property("zeebe:calledDecision").map(d -> "'" + d + "'").orElse("");
      case NONE -> "A step with no automation or owner";
    };
  }

  private static String boundaryText(ProcessModel model, Node boundary, Map<String, Integer> number) {
    EventDefinition e = boundary.event();
    String trigger;
    if (e == null) {
      trigger = "If something happens";
    } else {
      trigger = switch (e.type()) {
        case TIMER -> "If it is not done within " + durationInWords(e.timerDuration());
        case ERROR -> e.errorCode() == null ? "If it fails with any business error" : "If it reports '" + e.errorCode() + "'";
        case MESSAGE -> "If the message '" + e.messageName() + "' arrives first";
        default -> "If something happens";
      };
    }
    String targets = joinSteps(model.outgoing(boundary).stream().map(Flow::target).toList(), number);
    return trigger + (boundary.cancelActivity() ? ", the step is stopped and the case goes to step " : ", step ")
        + targets + (boundary.cancelActivity() ? "." : " also starts while this step continues.");
  }

  private static String next(ProcessModel model, Node node, Map<String, Integer> number) {
    List<Flow> out = model.outgoing(node);
    if (out.isEmpty()) {
      return node.kind() == NodeKind.END_EVENT ? "" : " Nothing follows; this path of the case ends here.";
    }
    if (out.size() == 1) {
      return " Then step " + stepOf(out.get(0).target(), number) + ".";
    }
    return " Then steps " + joinSteps(out.stream().map(Flow::target).toList(), number) + " start at the same time.";
  }

  private static String condition(Flow flow) {
    try {
      return Feel.toEnglish(Feel.parse(flow.condition()));
    } catch (FeelException | NullPointerException e) {
      return "'" + flow.condition() + "' (cannot be read)";
    }
  }

  private static String keyInWords(String correlationKey) {
    try {
      return Feel.toEnglish(Feel.parse(correlationKey));
    } catch (FeelException e) {
      return correlationKey;
    }
  }

  private static String stepOf(String nodeId, Map<String, Integer> number) {
    Integer n = number.get(nodeId);
    return n == null ? "'" + nodeId + "'" : String.valueOf(n);
  }

  private static String joinSteps(List<String> ids, Map<String, Integer> number) {
    List<String> parts = ids.stream().map(id -> stepOf(id, number)).toList();
    if (parts.size() <= 1) {
      return String.join("", parts);
    }
    return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
  }

  /** "PT4H" becomes "4 hours", "P1DT12H" becomes "1 day and 12 hours". */
  public static String durationInWords(String iso) {
    if (iso == null) {
      return "for an unspecified time";
    }
    Matcher m = DURATION.matcher(iso);
    if (!m.matches()) {
      return iso;
    }
    String[] units = {"year", "month", "week", "day", "hour", "minute", "second"};
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < units.length; i++) {
      String g = m.group(i + 1);
      if (g != null && !g.equals("0")) {
        int n = Integer.parseInt(g);
        parts.add(n + " " + units[i] + (n == 1 ? "" : "s"));
      }
    }
    if (parts.isEmpty()) {
      return "no time";
    }
    if (parts.size() == 1) {
      return parts.get(0);
    }
    return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
  }

  private static String summary(ProcessModel model, Map<Node.Actor, Integer> byActor, int decisions, int waits) {
    int people = byActor.getOrDefault(Node.Actor.PERSON, 0);
    int agents = byActor.getOrDefault(Node.Actor.AGENT, 0);
    int systems = byActor.getOrDefault(Node.Actor.SYSTEM, 0) + byActor.getOrDefault(Node.Actor.RULE, 0);
    return "'" + model.label() + "' has " + (people + agents + systems + byActor.getOrDefault(Node.Actor.NONE, 0))
        + " steps: " + people + " done by people, " + agents + " by AI agents and " + systems
        + " by systems or rule tables. It makes " + decisions + " decision" + (decisions == 1 ? "" : "s")
        + " and waits on outside events " + waits + " time" + (waits == 1 ? "" : "s") + ".";
  }
}
