package io.github.mkp.pfl.sim;

import io.github.mkp.pfl.bpmn.EventDefinition;
import io.github.mkp.pfl.bpmn.Flow;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.NodeKind;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.feel.Expr;
import io.github.mkp.pfl.feel.Feel;
import io.github.mkp.pfl.feel.FeelException;
import io.github.mkp.pfl.fitness.CaseOutcome;
import io.github.mkp.pfl.fitness.Work;
import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.Event;
import io.github.mkp.pfl.log.Lifecycle;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Discrete-event simulation of many cases flowing through one process model, with teams of people
 * shared between the cases.
 *
 * <p>Control flow follows Zeebe: an exclusive gateway takes the first outgoing flow whose condition
 * is true, in document order, then the default, and raises an incident if neither exists; a
 * parallel gateway waits for a token on every incoming flow; an activity with several outgoing
 * flows starts all of them; an unhandled BPMN error raises an incident; a case completes when its
 * last token is consumed. CI checks these paths against a real Camunda cluster.
 *
 * <p>Queueing is what makes this more than a sum of durations: when a change takes work away from
 * a team, every other case that waits for that team moves faster. A backtest that adds up average
 * step durations misses that, and with it most of the gain of automating a busy team's routine
 * work.
 */
public final class Simulator {

  private final ProcessModel model;
  private final Staffing staffing;
  private final Map<String, Expr> conditions = new HashMap<>();
  private final Map<String, String> conditionErrors = new HashMap<>();

  public Simulator(ProcessModel model, Staffing staffing) {
    this.model = model;
    this.staffing = staffing;
    for (Flow f : model.flows().values()) {
      if (f.condition() != null) {
        try {
          conditions.put(f.id(), Feel.parse(f.condition()));
        } catch (FeelException e) {
          conditionErrors.put(f.id(), e.getMessage());
        }
      }
    }
  }

  public ProcessModel model() {
    return model;
  }

  /**
   * Simulates the cases until every case finished or the horizon is reached.
   *
   * @param horizon cases still open at this instant stay open in the result
   */
  public SimResult run(List<CaseSeed> seeds, Behaviour behaviour, Instant horizon) {
    return new Run(behaviour, horizon).execute(seeds);
  }

  // ------------------------------------------------------------------ one run

  private enum Kind { ARRIVE, COMPLETE, CATCH, TIMER, WAKE }

  private record Scheduled(Instant time, long seq, Kind kind, CaseState c, Step step, Node boundary, String team)
      implements Comparable<Scheduled> {
    @Override
    public int compareTo(Scheduled o) {
      int t = time.compareTo(o.time);
      return t != 0 ? t : Long.compare(seq, o.seq);
    }
  }

  private static final class CaseState {
    final CaseSeed seed;
    final Map<String, Object> vars;
    final Map<String, Integer> occurrences = new HashMap<>();
    final Map<String, Map<String, Integer>> joins = new HashMap<>();
    final List<Event> events = new ArrayList<>();
    final List<String> path = new ArrayList<>();
    final List<Work> work = new ArrayList<>();
    int live;
    String incident;
    Instant finishedAt;

    CaseState(CaseSeed seed) {
      this.seed = seed;
      this.vars = new HashMap<>(seed.attributes());
      this.vars.put("caseId", seed.caseId());
    }
  }

  private static final class Step {
    final CaseState c;
    final Node node;
    final StepContext ctx;
    Instant start;
    String member;
    boolean done;

    Step(CaseState c, Node node, StepContext ctx) {
      this.c = c;
      this.node = node;
      this.ctx = ctx;
    }
  }

  private static final class Team {
    final String name;
    final Deque<String> free = new ArrayDeque<>();
    final Deque<Step> queue = new ArrayDeque<>();
    final List<LocalTime> pickups;
    boolean wakeScheduled;
    Instant sessionUntil = Instant.MIN;

    Team(String name, int size, List<LocalTime> pickups) {
      this.name = name;
      for (int i = 1; i <= size; i++) {
        free.add(String.format("%s-%02d", name, i));
      }
      this.pickups = pickups == null ? List.of() : pickups.stream().sorted().toList();
    }
  }

  private final class Run {
    private final Behaviour behaviour;
    private final Instant horizon;
    private final PriorityQueue<Scheduled> agenda = new PriorityQueue<>();
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private long seq;
    private Instant now = Instant.MIN;

    Run(Behaviour behaviour, Instant horizon) {
      this.behaviour = behaviour;
      this.horizon = horizon;
      staffing.teams().forEach((name, size) -> teams.put(name, new Team(name, size, staffing.pickupTimes().get(name))));
    }

    SimResult execute(List<CaseSeed> seeds) {
      List<CaseState> cases = new ArrayList<>();
      for (CaseSeed seed : seeds) {
        CaseState c = new CaseState(seed);
        cases.add(c);
        schedule(seed.arrival(), Kind.ARRIVE, c, null, null, null);
      }
      while (!agenda.isEmpty()) {
        Scheduled s = agenda.poll();
        if (s.time().isAfter(horizon)) {
          break;
        }
        now = s.time();
        switch (s.kind()) {
          case ARRIVE -> start(s.c());
          case COMPLETE -> complete(s.step());
          case CATCH -> caught(s.step());
          case TIMER -> timer(s.step(), s.boundary());
          case WAKE -> {
            Team team = teams.get(s.team());
            team.wakeScheduled = false;
            dispatch(team);
          }
        }
      }
      List<SimCase> result = new ArrayList<>();
      for (CaseState c : cases) {
        boolean finished = c.finishedAt != null;
        CaseRecord record = new CaseRecord(c.seed.caseId(), c.seed.arrival(), c.seed.attributes(), c.events);
        Instant end = finished ? c.finishedAt : record.end();
        CaseOutcome outcome = new CaseOutcome(c.seed.caseId(), c.seed.attributes(), c.seed.arrival(), end,
            c.work.stream().sorted(Comparator.comparing(Work::end)).toList(), finished);
        result.add(new SimCase(c.seed.caseId(), record, outcome, c.path, c.incident, Map.copyOf(withoutNulls(c.vars))));
      }
      return new SimResult(model.id(), result);
    }

    private void schedule(Instant at, Kind kind, CaseState c, Step step, Node boundary, String team) {
      agenda.add(new Scheduled(at, seq++, kind, c, step, boundary, team));
    }

    // -------------------------------------------------------------- token movement

    private void start(CaseState c) {
      Node startEvent = model.nodesOfKind(NodeKind.START_EVENT).stream()
          .filter(n -> n.incoming().isEmpty() && (n.event() == null || n.event().type() == EventDefinition.Type.NONE))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(model.id() + " has no plain start event"));
      c.path.add(startEvent.id());
      record(c, startEvent.id(), Lifecycle.COMPLETE, null, Map.of());
      c.live = 0;
      emit(c, startEvent.outgoing());
    }

    /** Puts a token on each flow and lets it run until it reaches something that takes time. */
    private void emit(CaseState c, List<String> flows) {
      Deque<String> work = new ArrayDeque<>(flows);
      c.live += flows.size();
      while (!work.isEmpty()) {
        if (c.incident != null) {
          return;
        }
        String flowId = work.poll();
        Node node = model.node(model.flow(flowId).target());
        arrive(c, node, flowId, work);
      }
      maybeFinish(c);
    }

    private void arrive(CaseState c, Node node, String viaFlow, Deque<String> work) {
      switch (node.kind()) {
        case END_EVENT -> {
          c.path.add(node.id());
          record(c, node.id(), Lifecycle.COMPLETE, null, Map.of());
          c.live--;
        }
        case EXCLUSIVE_GATEWAY -> {
          c.path.add(node.id());
          String chosen = choose(c, node);
          if (chosen == null) {
            return;
          }
          work.add(chosen);
        }
        case PARALLEL_GATEWAY -> {
          Map<String, Integer> counts = c.joins.computeIfAbsent(node.id(), k -> new HashMap<>());
          counts.merge(viaFlow, 1, Integer::sum);
          boolean ready = node.incoming().stream().allMatch(f -> counts.getOrDefault(f, 0) > 0);
          if (ready) {
            node.incoming().forEach(f -> counts.merge(f, -1, Integer::sum));
            c.path.add(node.id());
            c.live -= node.incoming().size();
            c.live += node.outgoing().size();
            work.addAll(node.outgoing());
          }
        }
        case INTERMEDIATE_CATCH_EVENT -> {
          c.path.add(node.id());
          Step step = new Step(c, node, context(c, node));
          record(c, node.id(), Lifecycle.READY, null, Map.of());
          EventDefinition e = node.event();
          Duration delay = e != null && e.type() == EventDefinition.Type.TIMER
              ? isoDuration(e.timerDuration())
              : behaviour.messageDelay(step.ctx);
          schedule(now.plus(delay), Kind.CATCH, c, step, null, null);
        }
        case TASK, MANUAL_TASK -> {
          // undefined and manual tasks pass straight through, as in Zeebe
          c.path.add(node.id());
          record(c, node.id(), Lifecycle.COMPLETE, null, Map.of());
          c.live += node.outgoing().size() - 1;
          work.addAll(node.outgoing());
        }
        default -> {
          if (node.kind().isActivity()) {
            c.path.add(node.id());
            activate(c, node);
          } else {
            incident(c, "cannot simulate element " + node.id() + " of kind " + node.kind());
          }
        }
      }
    }

    private String choose(CaseState c, Node gateway) {
      List<String> out = gateway.outgoing();
      if (out.isEmpty()) {
        c.live--;
        return null;
      }
      for (String f : out) {
        if (f.equals(gateway.defaultFlow())) {
          continue;
        }
        if (conditionErrors.containsKey(f)) {
          incident(c, "condition of " + f + " cannot be evaluated: " + conditionErrors.get(f));
          return null;
        }
        Expr condition = conditions.get(f);
        if (condition == null) {
          if (out.size() == 1) {
            return f;
          }
          continue;
        }
        if (Boolean.TRUE.equals(Feel.test(condition, c.vars))) {
          return f;
        }
      }
      if (gateway.defaultFlow() != null) {
        return gateway.defaultFlow();
      }
      incident(c, "no outgoing flow of '" + gateway.label() + "' matches and there is no default flow");
      return null;
    }

    private StepContext context(CaseState c, Node node) {
      int occurrence = c.occurrences.merge(node.id(), 1, Integer::sum);
      return new StepContext(c.seed.caseId(), c.seed.attributes(), Map.copyOf(withoutNulls(c.vars)), node.id(), occurrence, now);
    }

    private void activate(CaseState c, Node node) {
      Step step = new Step(c, node, context(c, node));
      record(c, node.id(), Lifecycle.READY, null, Map.of());
      for (Node boundary : model.boundaryEventsOf(node.id())) {
        EventDefinition e = boundary.event();
        if (e != null && e.type() == EventDefinition.Type.TIMER) {
          schedule(now.plus(isoDuration(e.timerDuration())), Kind.TIMER, c, step, boundary, null);
        }
      }
      if (node.actor() == Node.Actor.PERSON) {
        String teamName = node.candidateGroups().isEmpty() ? "unassigned" : node.candidateGroups().get(0);
        Team team = teams.get(teamName);
        if (team == null) {
          incident(c, "no team '" + teamName + "' is staffed for '" + node.label() + "'");
          return;
        }
        team.queue.add(step);
        dispatch(team);
      } else {
        begin(step, null);
      }
    }

    private void begin(Step step, String member) {
      step.start = now;
      step.member = member;
      record(step.c, step.node.id(), Lifecycle.START, member, Map.of());
      Duration service = behaviour.serviceTime(step.ctx);
      if (service.isNegative()) {
        throw new IllegalStateException("negative service time for " + step.node.id());
      }
      schedule(now.plus(service), Kind.COMPLETE, step.c, step, null, null);
    }

    private void dispatch(Team team) {
      if (team.queue.isEmpty() || team.free.isEmpty()) {
        return;
      }
      if (!staffing.calendar().isOpen(now) || (!team.pickups.isEmpty() && now.isAfter(team.sessionUntil))) {
        if (!team.wakeScheduled) {
          team.wakeScheduled = true;
          schedule(nextPickup(team), Kind.WAKE, null, null, null, team.name);
        }
        return;
      }
      while (!team.queue.isEmpty() && !team.free.isEmpty()) {
        Step step = team.queue.poll();
        if (step.done) {
          continue;
        }
        begin(step, team.free.poll());
      }
    }

    private Instant nextPickup(Team team) {
      Instant open = staffing.calendar().nextOpen(now);
      if (team.pickups.isEmpty()) {
        return open;
      }
      ZonedDateTime z = open.atZone(staffing.calendar().zone());
      for (int day = 0; day < 14; day++) {
        ZonedDateTime d = z.plusDays(day);
        for (LocalTime p : team.pickups) {
          Instant candidate = d.with(p).toInstant();
          if (!candidate.isBefore(open) && !candidate.isBefore(now) && staffing.calendar().isOpen(candidate)) {
            team.sessionUntil = candidate.plus(Duration.ofMinutes(60));
            return candidate;
          }
        }
      }
      throw new IllegalStateException("team " + team.name + " never picks up work");
    }

    private void complete(Step step) {
      if (step.done) {
        return;
      }
      step.done = true;
      CaseState c = step.c;
      release(step);
      String error = step.node.actor() == Node.Actor.PERSON ? null : behaviour.error(step.ctx);
      if (error != null) {
        Node boundary = model.boundaryEventsOf(step.node.id()).stream()
            .filter(b -> b.event() != null && b.event().type() == EventDefinition.Type.ERROR)
            .filter(b -> b.event().errorCode() == null || b.event().errorCode().equals(error))
            .findFirst().orElse(null);
        record(c, step.node.id(), Lifecycle.TERMINATE, step.member, Map.of());
        if (boundary == null) {
          incident(c, "'" + step.node.label() + "' threw " + error + " and nothing catches it");
          return;
        }
        c.path.add(boundary.id());
        record(c, boundary.id(), Lifecycle.COMPLETE, null, Map.of());
        c.live--;
        emit(c, boundary.outgoing());
        return;
      }
      Map<String, Object> out = behaviour.outputs(step.ctx);
      c.vars.putAll(out);
      record(c, step.node.id(), Lifecycle.COMPLETE, step.member, out);
      String role = step.node.actor() == Node.Actor.PERSON
          ? (step.node.candidateGroups().isEmpty() ? "unassigned" : step.node.candidateGroups().get(0))
          : step.node.taskType();
      c.work.add(new Work(step.node.id(), step.node.actor(), role, step.ctx.readyAt(), step.start, now));
      c.live--;
      emit(c, step.node.outgoing());
    }

    private void release(Step step) {
      if (step.member != null && step.node.actor() == Node.Actor.PERSON) {
        Team team = teams.get(step.node.candidateGroups().isEmpty() ? "unassigned" : step.node.candidateGroups().get(0));
        team.free.add(step.member);
        dispatch(team);
      }
    }

    private void caught(Step step) {
      CaseState c = step.c;
      Map<String, Object> out = behaviour.outputs(step.ctx);
      c.vars.putAll(out);
      record(c, step.node.id(), Lifecycle.COMPLETE, null, out);
      c.live--;
      emit(c, step.node.outgoing());
    }

    private void timer(Step step, Node boundary) {
      if (step.done || step.c.incident != null) {
        return;
      }
      CaseState c = step.c;
      c.path.add(boundary.id());
      if (boundary.cancelActivity()) {
        step.done = true;
        record(c, step.node.id(), Lifecycle.TERMINATE, step.member, Map.of());
        if (step.member != null) {
          release(step);
        }
        record(c, boundary.id(), Lifecycle.COMPLETE, null, Map.of());
        c.live--;
        emit(c, boundary.outgoing());
      } else {
        record(c, boundary.id(), Lifecycle.COMPLETE, null, Map.of());
        emit(c, boundary.outgoing());
      }
    }

    private void maybeFinish(CaseState c) {
      if (c.live == 0 && c.incident == null && c.finishedAt == null) {
        c.finishedAt = now;
      }
    }

    private void incident(CaseState c, String why) {
      if (c.incident == null) {
        c.incident = why;
      }
    }

    private void record(CaseState c, String activity, Lifecycle lifecycle, String resource, Map<String, Object> out) {
      c.events.add(new Event(activity, lifecycle, now, resource, withoutNulls(out)));
    }
  }

  private static Map<String, Object> withoutNulls(Map<String, Object> in) {
    Map<String, Object> out = new HashMap<>();
    in.forEach((k, v) -> {
      if (v != null) {
        out.put(k, v);
      }
    });
    return out;
  }

  /** Durations the simulator understands: weeks, days, hours, minutes, seconds. */
  public static Duration isoDuration(String iso) {
    if (iso == null) {
      throw new IllegalArgumentException("timer without duration");
    }
    String s = iso.strip();
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$").matcher(s);
    if (!m.matches()) {
      throw new IllegalArgumentException("unsupported timer duration " + iso);
    }
    Duration d = Duration.ZERO;
    if (m.group(1) != null) {
      d = d.plusDays(7L * Long.parseLong(m.group(1)));
    }
    if (m.group(2) != null) {
      d = d.plusDays(Long.parseLong(m.group(2)));
    }
    if (m.group(3) != null) {
      d = d.plusHours(Long.parseLong(m.group(3)));
    }
    if (m.group(4) != null) {
      d = d.plusMinutes(Long.parseLong(m.group(4)));
    }
    if (m.group(5) != null) {
      d = d.plusMillis(Math.round(Double.parseDouble(m.group(5)) * 1000));
    }
    return d;
  }
}
