package io.github.mkp.pfl.verify;

import io.github.mkp.pfl.bpmn.Flow;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.NodeKind;
import io.github.mkp.pfl.bpmn.ProcessModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explores every way tokens can move through the process, ignoring data (any gateway branch may be
 * taken, any boundary event may fire), and reports runs that hang, run a step twice in one case or
 * can never finish.
 *
 * <p>These are the failures the engine does not catch at deploy time: Zeebe accepts a parallel
 * join that waits for a branch an exclusive gateway never sends, and the case then stays active
 * forever. The check is the classic soundness question for workflow nets, adapted to BPMN's
 * uncontrolled merges: activities and exclusive gateways fire on any one incoming token, parallel
 * gateways wait for one token on every incoming flow, an activity with several outgoing flows
 * starts all of them.
 */
final class BehaviourCheck {

  /** A flow holding this many tokens means a step will run twice in one case. */
  private static final int DUPLICATE = 2;

  /** Exploration does not go past this many tokens on one flow. */
  private static final int CAP = 3;

  private final ProcessModel model;
  private final int stateLimit;
  private final List<String> flowIds;
  private final Map<String, Integer> flowIndex = new HashMap<>();

  BehaviourCheck(ProcessModel model, int stateLimit) {
    this.model = model;
    this.stateLimit = stateLimit;
    this.flowIds = new ArrayList<>(model.flows().keySet());
    for (int i = 0; i < flowIds.size(); i++) {
      flowIndex.put(flowIds.get(i), i);
    }
  }

  record Result(List<Issue> issues, int states, boolean complete) {}

  private record Marking(int[] tokens) {
    boolean empty() {
      for (int t : tokens) {
        if (t != 0) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Marking m && Arrays.equals(tokens, m.tokens);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(tokens);
    }

    @Override
    public String toString() {
      return Arrays.toString(tokens);
    }
  }

  private record Firing(String nodeId, Marking next) {}

  Result run() {
    List<Marking> states = new ArrayList<>();
    List<Integer> parent = new ArrayList<>();
    List<String> firedBy = new ArrayList<>();
    Map<Marking, Integer> index = new HashMap<>();
    List<int[]> successors = new ArrayList<>();
    Set<String> fired = new HashSet<>();
    Deque<Integer> queue = new ArrayDeque<>();
    boolean complete = true;

    for (Node start : model.nodesOfKind(NodeKind.START_EVENT)) {
      if (!start.incoming().isEmpty()) {
        continue;
      }
      fired.add(start.id());
      int[] tokens = new int[flowIds.size()];
      for (String out : start.outgoing()) {
        tokens[flowIndex.get(out)]++;
      }
      Marking initial = new Marking(tokens);
      if (!index.containsKey(initial)) {
        index.put(initial, states.size());
        states.add(initial);
        parent.add(-1);
        firedBy.add(start.id());
        successors.add(null);
        queue.add(states.size() - 1);
      }
    }

    Map<String, Issue> duplicates = new java.util.LinkedHashMap<>();
    List<Integer> deadlocks = new ArrayList<>();
    while (!queue.isEmpty()) {
      int current = queue.poll();
      Marking marking = states.get(current);
      List<Firing> firings = firings(marking);
      int[] next = new int[firings.size()];
      for (int k = 0; k < firings.size(); k++) {
        Firing firing = firings.get(k);
        fired.add(firing.nodeId());
        Integer known = index.get(firing.next());
        if (known == null) {
          if (states.size() >= stateLimit) {
            complete = false;
            next[k] = -1;
            continue;
          }
          known = states.size();
          index.put(firing.next(), known);
          states.add(firing.next());
          parent.add(current);
          firedBy.add(firing.nodeId());
          successors.add(null);
          int[] t = firing.next().tokens();
          boolean capped = false;
          for (int f = 0; f < t.length; f++) {
            if (t[f] >= DUPLICATE) {
              Flow flow = model.flow(flowIds.get(f));
              Node target = model.node(flow.target());
              duplicates.computeIfAbsent(target.id(), id -> new Issue(
                  "DUPLICATE_EXECUTION", Issue.Category.BEHAVIOUR, Issue.Severity.ERROR, id,
                  "'" + target.label() + "' can run twice in the same case: two tokens reach it without a join.",
                  "Join the parallel branches with a parallel gateway before '" + target.label()
                      + "', or split with an exclusive gateway if only one branch should run.",
                  trace(parent, firedBy, states.size() - 1)));
            }
            if (t[f] >= CAP) {
              capped = true;
            }
          }
          if (!capped) {
            queue.add(known);
          }
        }
        next[k] = known;
      }
      successors.set(current, next);
      if (firings.isEmpty() && !marking.empty()) {
        deadlocks.add(current);
      }
    }

    // Breadth-first order puts the earliest duplication first; the ones further down the same path
    // are consequences of it and would only send the reader to the wrong element.
    List<Issue> issues = new ArrayList<>();
    int shortest = duplicates.values().stream().mapToInt(i -> i.trace().size()).min().orElse(0);
    duplicates.values().stream().filter(i -> i.trace().size() == shortest).forEach(issues::add);
    for (int d : deadlocks.subList(0, Math.min(3, deadlocks.size()))) {
      issues.add(deadlock(states.get(d), trace(parent, firedBy, d)));
    }

    // option to complete: every reachable state must be able to reach the empty marking
    BitSet canFinish = new BitSet(states.size());
    for (int i = 0; i < states.size(); i++) {
      if (states.get(i).empty()) {
        canFinish.set(i);
      }
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int i = 0; i < states.size(); i++) {
        if (canFinish.get(i) || successors.get(i) == null) {
          continue;
        }
        for (int s : successors.get(i)) {
          if (s >= 0 && canFinish.get(s)) {
            canFinish.set(i);
            changed = true;
            break;
          }
        }
      }
    }
    if (complete && duplicates.isEmpty()) {
      Set<Integer> deadlockSet = new HashSet<>(deadlocks);
      for (int i = 0; i < states.size(); i++) {
        if (!canFinish.get(i) && !deadlockSet.contains(i) && successors.get(i) != null) {
          Set<String> stuckAt = new LinkedHashSet<>();
          int[] t = states.get(i).tokens();
          for (int f = 0; f < t.length; f++) {
            if (t[f] > 0) {
              stuckAt.add(model.node(model.flow(flowIds.get(f)).target()).label());
            }
          }
          if (deadlocks.isEmpty()) {
            issues.add(new Issue("NO_WAY_TO_FINISH", Issue.Category.BEHAVIOUR, Issue.Severity.ERROR,
                model.flow(flowIds.get(firstToken(t))).target(),
                "After this sequence of steps the case can never finish; it keeps circling through "
                    + String.join(", ", stuckAt) + ".",
                "Give the loop an exit: a gateway branch that leads to an end event.",
                trace(parent, firedBy, i)));
          }
          break;
        }
      }
    }

    for (Node node : model.nodes().values()) {
      boolean fires = node.kind().isActivity() || node.kind().isGateway() || node.kind() == NodeKind.END_EVENT
          || node.kind() == NodeKind.INTERMEDIATE_CATCH_EVENT;
      if (fires && complete && !fired.contains(node.id()) && !node.incoming().isEmpty()) {
        issues.add(Issue.of("DEAD_ELEMENT", Issue.Category.BEHAVIOUR, Issue.Severity.WARNING, node.id(),
            "'" + node.label() + "' can never run: no sequence of steps from a start event reaches it.",
            "Check the flows leading into it, or remove it."));
      }
    }
    return new Result(issues, states.size(), complete);
  }

  private int firstToken(int[] tokens) {
    for (int f = 0; f < tokens.length; f++) {
      if (tokens[f] > 0) {
        return f;
      }
    }
    return 0;
  }

  private Issue deadlock(Marking marking, List<String> trace) {
    List<String> waiting = new ArrayList<>();
    String element = null;
    int[] t = marking.tokens();
    for (int f = 0; f < t.length; f++) {
      if (t[f] > 0) {
        Node target = model.node(model.flow(flowIds.get(f)).target());
        waiting.add("'" + target.label() + "'");
        if (element == null) {
          element = target.id();
        }
      }
    }
    return new Issue("DEADLOCK", Issue.Category.BEHAVIOUR, Issue.Severity.ERROR, element,
        "After this sequence of steps the case hangs: " + String.join(" and ", waiting)
            + " wait for a branch that was never started. The engine will keep the case open forever.",
        "Make the join match the split: an exclusive split needs an exclusive merge, a parallel split a parallel join.",
        trace);
  }

  private List<String> trace(List<Integer> parent, List<String> firedBy, int state) {
    List<String> steps = new ArrayList<>();
    for (int s = state; s >= 0; s = parent.get(s)) {
      steps.add(firedBy.get(s));
    }
    java.util.Collections.reverse(steps);
    return steps;
  }

  private List<Firing> firings(Marking marking) {
    List<Firing> result = new ArrayList<>();
    int[] tokens = marking.tokens();
    for (Node node : model.nodes().values()) {
      switch (node.kind()) {
        case START_EVENT, BOUNDARY_EVENT -> {
          // start events fire once at the beginning; boundary events fire with their activity
        }
        case PARALLEL_GATEWAY -> {
          if (node.incoming().isEmpty()) {
            continue;
          }
          boolean ready = node.incoming().stream().allMatch(f -> tokens[flowIndex.get(f)] > 0);
          if (ready) {
            int[] next = tokens.clone();
            node.incoming().forEach(f -> next[flowIndex.get(f)]--);
            node.outgoing().forEach(f -> next[flowIndex.get(f)]++);
            result.add(new Firing(node.id(), new Marking(next)));
          }
        }
        default -> {
          for (String in : new LinkedHashSet<>(node.incoming())) {
            int i = flowIndex.get(in);
            if (tokens[i] == 0) {
              continue;
            }
            for (List<String> outcome : outcomes(node)) {
              int[] next = tokens.clone();
              next[i]--;
              outcome.forEach(f -> next[flowIndex.get(f)]++);
              result.add(new Firing(node.id(), new Marking(next)));
            }
          }
        }
      }
    }
    return result;
  }

  /** The sets of flows that can receive a token when the node completes. */
  private List<List<String>> outcomes(Node node) {
    List<List<String>> outcomes = new ArrayList<>();
    if (node.kind() == NodeKind.EXCLUSIVE_GATEWAY) {
      if (node.outgoing().isEmpty()) {
        outcomes.add(List.of());
      }
      node.outgoing().forEach(f -> outcomes.add(List.of(f)));
      return outcomes;
    }
    if (node.kind() == NodeKind.END_EVENT) {
      outcomes.add(List.of());
      return outcomes;
    }
    outcomes.add(node.outgoing());
    for (Node boundary : model.boundaryEventsOf(node.id())) {
      if (boundary.cancelActivity()) {
        outcomes.add(boundary.outgoing());
      } else {
        List<String> both = new ArrayList<>(node.outgoing());
        both.addAll(boundary.outgoing());
        outcomes.add(both);
      }
    }
    return outcomes;
  }
}
