package io.github.mkp.pfl.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.service.scenario.Scenario;
import io.github.mkp.pfl.sim.SimCase;
import io.github.mkp.pfl.sim.SimResult;
import io.github.mkp.pfl.sim.Simulator;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.WorkingCalendar;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenarioSimulationTest {

  @Test
  void scenarioReachesEveryElementOfTheProcessAndEveryCaseFinishes() throws Exception {
    ProcessModel toBe = new BpmnParser().parse(Path.of("../processes/payment-investigation-to-be.bpmn"));
    Instant start = Instant.parse("2026-07-06T07:00:00Z");
    SimResult result = new Simulator(toBe, new Staffing(Map.of("payments-ops", 3, "team-leads", 1), WorkingCalendar.ALWAYS))
        .run(Scenario.seeds(start), Scenario.behaviour(), start.plus(Duration.ofDays(5)));
    Set<String> visited = new HashSet<>();
    for (SimCase c : result.cases()) {
      assertNull(c.incident(), c.caseId());
      assertTrue(c.outcome().finished(), c.caseId());
      visited.addAll(c.path());
    }
    assertEquals(toBe.nodes().keySet(), visited, "the engine comparison covers the whole model");
    assertEquals(32, result.cases().size());
  }
}
