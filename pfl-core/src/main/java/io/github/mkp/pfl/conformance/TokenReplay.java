package io.github.mkp.pfl.conformance;

import io.github.mkp.pfl.bpmn.Flow;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.NodeKind;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.feel.Expr;
import io.github.mkp.pfl.feel.Feel;
import io.github.mkp.pfl.feel.FeelException;
import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.Event;
import io.github.mkp.pfl.log.EventLog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data-aware token replay: plays each recorded case through the model and counts where the history
 * and the model disagree.
 *
 * <p>Gateways and start and end events are silent; the history records steps. To reach the next
 * recorded step the replay may fire silent elements, and at an exclusive gateway it only takes the
 * branch the case's data selects, the way the engine would. That is what catches controls that were
 * skipped: a recall above the four-eyes limit sent without the second approval fits a model whose
 * gateways ignore data, and does not fit this replay.
 *
 * <p>Fitness follows Rozinat and van der Aalst: {@code f = ½(1 − m/c) + ½(1 − r/p)}.
 */
public final class TokenReplay {

  private static final int SEARCH_LIMIT = 5_000;

  private final ProcessModel model;
  private final List<String> flowIds;
  private final Map<String, Integer> flowIndex = new HashMap<>();
  private final Map<String, Expr> conditions = new HashMap<>();

  public TokenReplay(ProcessModel model) {
    this.model = model;
    this.flowIds = new ArrayList<>(model.flows().keySet());
    for (int i = 0; i < flowIds.size(); i++) {
      flowIndex.put(flowIds.get(i), i);
    }
    for (Flow f : model.flows().values()) {
      if (f.condition() != null) {
        try {
          conditions.put(f.id(), Feel.parse(f.condition()));
        } catch (FeelException e) {
          // an unreadable condition is treated as unknown: either branch may be taken
        }
      }
    }
  }

  public ConformanceReport replay(EventLog log) {
    List<CaseFit> fits = log.cases().stream().map(this::replay).toList();
    long produced = fits.stream().mapToLong(CaseFit::produced).sum();
    long consumed = fits.stream().mapToLong(CaseFit::consumed).sum();
    long missing = fits.stream().mapToLong(CaseFit::missing).sum();
    long remaining = fits.stream().mapToLong(CaseFit::remaining).sum();
    double fitness = 0.5 * (consumed == 0 ? 1 : 1 - (double) missing / consumed)
        + 0.5 * (produced == 0 ? 1 : 1 - (double) remaining / produced);
    double perfect = fits.isEmpty() ? 1.0 : (double) fits.stream().filter(CaseFit::perfect).count() / fits.size();

    Map<String, List<String>> casesByProblem = new LinkedHashMap<>();
    for (CaseFit fit : fits) {
      for (String p : new LinkedHashSet<>(fit.problems())) {
        casesByProblem.computeIfAbsent(p, k -> new ArrayList<>()).add(fit.caseId());
      }
    }
    List<Deviation> deviations = new ArrayList<>();
    casesByProblem.forEach((key, caseIds) -> {
      String[] parts = key.split(":", 2);
      Deviation.Kind kind = Deviation.Kind.valueOf(parts[0]);
      deviations.add(new Deviation(kind, parts[1], caseIds.size(), describe(kind, parts[1], caseIds.size(), log.size()),
          caseIds.subList(0, Math.min(5, caseIds.size()))));
    });
    deviations.sort(Comparator.comparingInt(Deviation::cases).reversed().thenComparing(Deviation::elementId));
    return new ConformanceReport(model.id(), log.size(), fitness, perfect, deviations);
  }

  public CaseFit replay(CaseRecord record) {
    Counter n = new Counter();
    List<String> problems = new ArrayList<>();
    Map<String, Object> vars = new HashMap<>(record.attributes());
    int[] marking = new int[flowIds.size()];
    Node start = model.nodesOfKind(NodeKind.START_EVENT).stream()
        .filter(s -> s.incoming().isEmpty()).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("model " + model.id() + " has no start event"));
    for (String f : start.outgoing()) {
      marking[flowIndex.get(f)]++;
      n.produced++;
    }

    for (Event e : record.completions()) {
      Node node = model.nodes().get(e.activity());
      if (node == null) {
        n.missing++;
        n.consumed++;
        problems.add(Deviation.Kind.NOT_IN_MODEL + ":" + e.activity());
        continue;
      }
      if (node.kind() == NodeKind.START_EVENT || node.kind().isGateway()) {
        continue;
      }
      if (!enabled(node, marking)) {
        List<Firing> path = silentPathTo(node, marking, vars);
        if (path != null) {
          for (Firing f : path) {
            apply(f, marking, n);
          }
        }
      }
      if (!enabled(node, marking)) {
        String in = inputFlowsOf(node).get(0);
        marking[flowIndex.get(in)]++;
        n.missing++;
        problems.add(Deviation.Kind.RAN_WHEN_NOT_ALLOWED + ":" + node.id());
      }
      vars.putAll(e.output());
      apply(fire(node, marking), marking, n);
    }

    // let silent elements, including end events, finish what they can
    List<Firing> close = silentPathToEmpty(marking, vars);
    if (close != null) {
      for (Firing f : close) {
        apply(f, marking, n);
      }
    }
    int remaining = Arrays.stream(marking).sum();
    if (remaining > 0) {
      for (int i = 0; i < marking.length; i++) {
        if (marking[i] > 0) {
          problems.add(Deviation.Kind.LEFT_UNDONE + ":" + firstVisibleAfter(flowIds.get(i)));
        }
      }
    }
    return new CaseFit(record.caseId(), n.produced, n.consumed, n.missing, remaining, problems);
  }

  // ------------------------------------------------------------------ firing

  private static final class Counter {
    int produced;
    int consumed;
    int missing;
  }

  /** A firing: the flows it takes a token from and the flows it gives one to. */
  private record Firing(String nodeId, List<String> consume, List<String> produce) {}

  private void apply(Firing firing, int[] marking, Counter n) {
    for (String f : firing.consume()) {
      marking[flowIndex.get(f)]--;
      n.consumed++;
    }
    for (String f : firing.produce()) {
      marking[flowIndex.get(f)]++;
      n.produced++;
    }
  }

  /** The flows whose token lets this node run: a boundary event runs off its activity's input. */
  private List<String> inputFlowsOf(Node node) {
    if (node.kind() == NodeKind.BOUNDARY_EVENT) {
      return model.node(node.attachedTo()).incoming();
    }
    return node.incoming();
  }

  private boolean enabled(Node node, int[] marking) {
    List<String> inputs = inputFlowsOf(node);
    if (inputs.isEmpty()) {
      return false;
    }
    if (node.kind() == NodeKind.PARALLEL_GATEWAY) {
      return inputs.stream().allMatch(f -> marking[flowIndex.get(f)] > 0);
    }
    return inputs.stream().anyMatch(f -> marking[flowIndex.get(f)] > 0);
  }

  /** Firing of a visible node whose input is marked. */
  private Firing fire(Node node, int[] marking) {
    List<String> consume = new ArrayList<>();
    for (String f : inputFlowsOf(node)) {
      if (marking[flowIndex.get(f)] > 0) {
        consume.add(f);
        break;
      }
    }
    List<String> produce = node.kind() == NodeKind.END_EVENT ? List.of() : node.outgoing();
    return new Firing(node.id(), consume, produce);
  }

  /** The ways a silent node can fire in this marking, given the case data. */
  private List<Firing> silentFirings(Node node, int[] marking, Map<String, Object> vars) {
    List<Firing> result = new ArrayList<>();
    switch (node.kind()) {
      case PARALLEL_GATEWAY -> {
        if (enabled(node, marking)) {
          result.add(new Firing(node.id(), node.incoming(), node.outgoing()));
        }
      }
      case EXCLUSIVE_GATEWAY -> {
        String in = node.incoming().stream().filter(f -> marking[flowIndex.get(f)] > 0).findFirst().orElse(null);
        if (in == null) {
          return result;
        }
        for (String out : branches(node, vars)) {
          result.add(new Firing(node.id(), List.of(in), List.of(out)));
        }
        if (node.outgoing().isEmpty()) {
          result.add(new Firing(node.id(), List.of(in), List.of()));
        }
      }
      case END_EVENT -> {
        String in = node.incoming().stream().filter(f -> marking[flowIndex.get(f)] > 0).findFirst().orElse(null);
        if (in != null) {
          result.add(new Firing(node.id(), List.of(in), List.of()));
        }
      }
      default -> {
        // visible elements only fire when the history says so
      }
    }
    return result;
  }

  /**
   * Branches an exclusive gateway may take for this case: the first condition that holds, as the
   * engine does; if none holds, the default; if conditions cannot be decided (unknown data), every
   * branch that is not definitely false.
   */
  private List<String> branches(Node gateway, Map<String, Object> vars) {
    List<String> out = gateway.outgoing();
    if (out.size() == 1 && model.flow(out.get(0)).condition() == null) {
      return out;
    }
    List<String> undecided = new ArrayList<>();
    for (String f : out) {
      if (f.equals(gateway.defaultFlow())) {
        continue;
      }
      Expr condition = conditions.get(f);
      // A history without the variable cannot tell which way the case went; the engine would read a
      // missing variable as null, but replay must not blame the case for what the export left out.
      Boolean holds = condition == null || !vars.keySet().containsAll(Feel.variables(condition))
          ? null
          : Feel.test(condition, vars);
      if (Boolean.TRUE.equals(holds)) {
        if (undecided.isEmpty()) {
          return List.of(f);
        }
        undecided.add(f);
        return undecided;
      }
      if (holds == null) {
        undecided.add(f);
      }
    }
    if (gateway.defaultFlow() != null) {
      undecided.add(gateway.defaultFlow());
    }
    return undecided;
  }

  private List<Firing> silentPathTo(Node target, int[] start, Map<String, Object> vars) {
    return search(start, vars, m -> enabled(target, m));
  }

  private List<Firing> silentPathToEmpty(int[] start, Map<String, Object> vars) {
    return search(start, vars, m -> Arrays.stream(m).sum() == 0);
  }

  private List<Firing> search(int[] start, Map<String, Object> vars, java.util.function.Predicate<int[]> goal) {
    record State(int[] marking, State parent, Firing via) {}
    Deque<State> queue = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    queue.add(new State(start.clone(), null, null));
    seen.add(Arrays.toString(start));
    int visited = 0;
    while (!queue.isEmpty() && visited++ < SEARCH_LIMIT) {
      State s = queue.poll();
      if (goal.test(s.marking())) {
        List<Firing> path = new ArrayList<>();
        for (State x = s; x.via() != null; x = x.parent()) {
          path.add(0, x.via());
        }
        return path;
      }
      for (Node node : model.nodes().values()) {
        for (Firing f : silentFirings(node, s.marking(), vars)) {
          int[] next = s.marking().clone();
          f.consume().forEach(id -> next[flowIndex.get(id)]--);
          f.produce().forEach(id -> next[flowIndex.get(id)]++);
          if (seen.add(Arrays.toString(next))) {
            queue.add(new State(next, s, f));
          }
        }
      }
    }
    return null;
  }

  /** The first step a person would recognise after a flow, skipping gateways. */
  private String firstVisibleAfter(String flowId) {
    String target = model.flow(flowId).target();
    Set<String> seen = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(List.of(target));
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!seen.add(id)) {
        continue;
      }
      Node node = model.node(id);
      if (!node.kind().isGateway()) {
        return id;
      }
      node.outgoing().forEach(f -> queue.add(model.flow(f).target()));
    }
    return target;
  }

  /** Steps that must run before this one can, walking back through gateways. */
  private List<String> predecessors(String nodeId) {
    List<String> labels = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(model.node(nodeId).incoming().stream().map(f -> model.flow(f).source()).toList());
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!seen.add(id)) {
        continue;
      }
      Node node = model.node(id);
      if (node.kind().isGateway()) {
        node.incoming().forEach(f -> queue.add(model.flow(f).source()));
      } else {
        labels.add("'" + node.label() + "'");
      }
    }
    return labels;
  }

  private String describe(Deviation.Kind kind, String elementId, int cases, int total) {
    String share = String.format(java.util.Locale.ROOT, "%d of %d cases (%.1f%%)", cases, total, 100.0 * cases / total);
    return switch (kind) {
      case NOT_IN_MODEL -> "'" + elementId + "' appears in " + share + " but is not a step of the model.";
      case LEFT_UNDONE -> "In " + share + " the history ends while the model still expects '"
          + model.findNode(elementId).map(Node::label).orElse(elementId) + "'.";
      case RAN_WHEN_NOT_ALLOWED -> {
        Node node = model.node(elementId);
        List<String> before = predecessors(elementId);
        yield "'" + node.label() + "' ran in " + share + " at a point where the model does not allow it"
            + (before.isEmpty() ? "." : "; for these cases the model first requires " + String.join(" or ", before) + ".");
      }
    };
  }
}
