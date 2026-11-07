package io.github.mkp.pfl.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.bpmn.TestBpmn;
import io.github.mkp.pfl.log.Event;
import io.github.mkp.pfl.log.Lifecycle;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulatorTest {

  /** Monday 6 July 2026, 09:00 in Berlin. */
  static final Instant MONDAY_9 = Instant.parse("2026-07-06T07:00:00Z");
  static final Instant FAR = MONDAY_9.plus(Duration.ofDays(60));
  static final Staffing OPS = new Staffing(Map.of("payments-ops", 1, "team-leads", 1),
      WorkingCalendar.weekdays("Europe/Berlin", 8, 18));

  static ProcessModel asIs() throws Exception {
    return new BpmnParser().parse(Path.of("../processes/payment-investigation-as-is.bpmn"));
  }

  static ProcessModel toBe() throws Exception {
    return new BpmnParser().parse(Path.of("../processes/payment-investigation-to-be.bpmn"));
  }

  static CaseSeed seed(String id, Instant at, String type, int amount) {
    return new CaseSeed(id, at, Map.of("inquiryType", type, "amount", amount, "currency", "EUR", "channel", "SWIFT"));
  }

  @Test
  void incompleteInquiryLoopsThroughTheSenderAndResolves() throws Exception {
    FixedBehaviour behaviour = new FixedBehaviour()
        .output("register_inquiry#1", Map.of("infoComplete", false))
        .output("register_inquiry#2", Map.of("infoComplete", true));
    SimCase c = new Simulator(asIs(), OPS).run(List.of(seed("c1", MONDAY_9, "RECALL", 20_000)), behaviour, FAR).cases().get(0);
    assertNull(c.incident());
    assertTrue(c.outcome().finished());
    assertEquals(List.of("inquiry_received", "gw_register_merge", "register_inquiry", "gw_complete", "request_info",
        "wait_reply", "gw_register_merge", "register_inquiry", "gw_complete", "lookup_payment", "gw_type", "check_funds",
        "gw_amount", "second_approval", "gw_approved_merge", "send_recall", "gw_done_merge", "close_case", "resolved"), c.path());
    assertEquals(8, c.outcome().work().size(), "two registrations, request, lookup, check, approval, send, close");
  }

  @Test
  void aSecondCaseWaitsForTheOnlyFreePerson() throws Exception {
    FixedBehaviour behaviour = new FixedBehaviour().defaultMinutes(30).output("register_inquiry", Map.of("infoComplete", true));
    List<CaseSeed> seeds = List.of(seed("a", MONDAY_9, "QUERY", 10), seed("b", MONDAY_9, "QUERY", 10));
    SimResult one = new Simulator(asIs(), OPS).run(seeds, behaviour, FAR);
    SimResult two = new Simulator(asIs(), OPS.withTeamSize("payments-ops", 2)).run(seeds, behaviour, FAR);
    double waitOne = one.cases().get(1).outcome().work().stream().mapToDouble(w -> w.waitMinutes()).sum();
    double waitTwo = two.cases().get(1).outcome().work().stream().mapToDouble(w -> w.waitMinutes()).sum();
    assertTrue(waitOne >= 30, "with one person the second case queues: " + waitOne);
    assertEquals(0.0, waitTwo, 1e-9);
  }

  @Test
  void peopleOnlyStartWorkInsideWorkingHours() throws Exception {
    Instant saturdayNoon = Instant.parse("2026-07-11T10:00:00Z");
    FixedBehaviour behaviour = new FixedBehaviour().output("register_inquiry", Map.of("infoComplete", true));
    SimCase c = new Simulator(asIs(), OPS).run(List.of(seed("w", saturdayNoon, "QUERY", 10)), behaviour, FAR).cases().get(0);
    Event firstStart = c.record().events().stream().filter(e -> e.lifecycle() == Lifecycle.START).findFirst().orElseThrow();
    assertEquals(Instant.parse("2026-07-13T06:00:00Z"), firstStart.time(), "Monday 08:00 Berlin");
  }

  @Test
  void noMatchingBranchAndNoDefaultIsAnIncident() throws Exception {
    ProcessModel model = TestBpmn.process("p").start("s").xor("g", null).userTask("a", "A", "payments-ops").end("e")
        .flow("f0", "s", "g").flow("f1", "g", "a", "=amount > 100").flow("f2", "a", "e").parse();
    SimCase c = new Simulator(model, OPS).run(List.of(seed("x", MONDAY_9, "QUERY", 5)), new FixedBehaviour(), FAR).cases().get(0);
    assertNotNull(c.incident());
    assertFalse(c.outcome().finished());
  }

  @Test
  void parallelJoinWaitsForTheSlowerBranch() throws Exception {
    ProcessModel model = TestBpmn.process("p").start("s").and("split").serviceTask("fast", "Fast", "t1")
        .serviceTask("slow", "Slow", "t2").and("join").end("e")
        .flow("f0", "s", "split").flow("f1", "split", "fast").flow("f2", "split", "slow")
        .flow("f3", "fast", "join").flow("f4", "slow", "join").flow("f5", "join", "e").parse();
    SimCase c = new Simulator(model, OPS).run(List.of(seed("p", MONDAY_9, "QUERY", 5)),
        new FixedBehaviour().minutes("fast", 1).minutes("slow", 50), FAR).cases().get(0);
    assertTrue(c.outcome().finished());
    assertEquals(50.0, c.outcome().cycleHours() * 60, 1e-6);
    assertEquals(1, c.path().stream().filter("join"::equals).count());
  }

  @Test
  void businessErrorTakesTheBoundaryPath() throws Exception {
    FixedBehaviour behaviour = new FixedBehaviour()
        .output("classify_inquiry", Map.of("infoComplete", true, "inquiryType", "QUERY"))
        .error("hub_lookup", "PAYMENT_NOT_FOUND");
    SimCase c = new Simulator(toBe(), OPS).run(List.of(seed("e", MONDAY_9, "QUERY", 5)), behaviour, FAR).cases().get(0);
    assertTrue(c.path().contains("not_found"));
    assertTrue(c.path().contains("manual_lookup"));
    assertTrue(c.record().events().stream().anyMatch(e -> e.activity().equals("hub_lookup") && e.lifecycle() == Lifecycle.TERMINATE));
    assertTrue(c.outcome().finished());
  }

  @Test
  void uncaughtBusinessErrorIsAnIncident() throws Exception {
    FixedBehaviour behaviour = new FixedBehaviour()
        .output("classify_inquiry", Map.of("infoComplete", true, "inquiryType", "RECALL"))
        .error("send_recall", "HUB_REJECTED");
    SimCase c = new Simulator(toBe(), OPS).run(List.of(seed("r", MONDAY_9, "RECALL", 5)), behaviour, FAR).cases().get(0);
    assertTrue(c.incident().contains("HUB_REJECTED"));
  }

  @Test
  void interruptingTimerStopsTheTaskAndEscalates() throws Exception {
    ProcessModel model = TestBpmn.process("p").start("s").userTask("work", "Work", "payments-ops")
        .raw("<bpmn:boundaryEvent id=\"late\" attachedToRef=\"work\"><bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:boundaryEvent>")
        .userTask("escalate", "Escalate", "team-leads").end("done").end("escalated")
        .flow("f0", "s", "work").flow("f1", "work", "done").flow("f2", "late", "escalate").flow("f3", "escalate", "escalated").parse();
    SimCase c = new Simulator(model, OPS).run(List.of(seed("t", MONDAY_9, "QUERY", 5)),
        new FixedBehaviour().minutes("work", 120).minutes("escalate", 5), FAR).cases().get(0);
    assertTrue(c.path().contains("late"));
    assertTrue(c.path().contains("escalated"));
    assertFalse(c.path().contains("done"));
    assertTrue(c.outcome().finished());
  }

  @Test
  void teamWithPickupTimesBatchesItsQueue() throws Exception {
    Staffing batching = new Staffing(Map.of("payments-ops", 3, "team-leads", 1), WorkingCalendar.weekdays("Europe/Berlin", 8, 18),
        Map.of("team-leads", List.of(LocalTime.of(10, 0), LocalTime.of(15, 0))));
    FixedBehaviour behaviour = new FixedBehaviour().defaultMinutes(5).output("register_inquiry", Map.of("infoComplete", true));
    SimCase c = new Simulator(asIs(), batching).run(List.of(seed("b", MONDAY_9, "RECALL", 50_000)), behaviour, FAR).cases().get(0);
    Event approvalStart = c.record().events().stream()
        .filter(e -> e.activity().equals("second_approval") && e.lifecycle() == Lifecycle.START).findFirst().orElseThrow();
    assertEquals(Instant.parse("2026-07-06T08:00:00Z"), approvalStart.time(), "picked up at the 10:00 round");
  }

  @Test
  void sameInputsGiveTheSameHistory() throws Exception {
    FixedBehaviour behaviour = new FixedBehaviour().output("register_inquiry", Map.of("infoComplete", true));
    List<CaseSeed> seeds = List.of(seed("1", MONDAY_9, "RECALL", 50_000), seed("2", MONDAY_9.plusSeconds(60), "NON_RECEIPT", 10));
    assertEquals(new Simulator(asIs(), OPS).run(seeds, behaviour, FAR).log(), new Simulator(asIs(), OPS).run(seeds, behaviour, FAR).log());
  }

  @Test
  void timerDurationsParse() {
    assertEquals(Duration.ofHours(4), Simulator.isoDuration("PT4H"));
    assertEquals(Duration.ofDays(9), Simulator.isoDuration("P1W2D"));
    assertEquals(Duration.ofMillis(1500), Simulator.isoDuration("PT1.5S"));
  }
}
