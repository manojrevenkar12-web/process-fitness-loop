package io.github.mkp.pfl.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.conformance.ConformanceReport;
import io.github.mkp.pfl.conformance.Deviation;
import io.github.mkp.pfl.conformance.TokenReplay;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.SimResult;
import io.github.mkp.pfl.sim.StaffingConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WorldTest {

  static World world(long seed) throws IOException {
    return new World(WorldParams.forSeed(seed), StaffingConfig.load(Path.of("../config/staffing.json")));
  }

  @Test
  void nothingThatShipsCanSeeTheTruth() throws IOException {
    for (String module : List.of("pfl-core", "pfl-hub", "pfl-service")) {
      Path src = Path.of("..", module, "src", "main");
      if (!Files.exists(src)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(src)) {
        for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          String text = Files.readString(f);
          assertFalse(text.contains("io.github.mkp.pfl.world"), f + " refers to the ground-truth world");
        }
      }
      Path pomFile = Path.of("..", module, "pom.xml");
      String pom = Files.exists(pomFile) ? Files.readString(pomFile) : "";
      assertFalse(pom.contains("<artifactId>pfl-world</artifactId>"), module + " depends on the world");
    }
  }

  @Test
  void exportedHistoryCarriesNoHiddenAttribute() throws IOException {
    World w = world(3);
    List<CaseSeed> arrivals = w.arrivals(LocalDate.of(2026, 4, 6), 7);
    EventLog history = World.export(w.run(World.practisedModel(), arrivals, Map.of()));
    assertTrue(history.size() > 200);
    history.cases().forEach(c -> World.HIDDEN.forEach(h -> assertFalse(c.attributes().containsKey(h), h)));
    assertTrue(arrivals.get(0).attributes().containsKey("complexity"), "the world itself keeps them");
  }

  @Test
  void sameSeedSameBank() throws IOException {
    List<CaseSeed> a = world(5).arrivals(LocalDate.of(2026, 4, 6), 3);
    List<CaseSeed> b = world(5).arrivals(LocalDate.of(2026, 4, 6), 3);
    assertEquals(a, b);
    assertTrue(!a.equals(world(6).arrivals(LocalDate.of(2026, 4, 6), 3)));
  }

  @Test
  void conformanceFindsTheUndocumentedShortcutAndTheReworkLoop() throws IOException {
    World w = world(1);
    SimResult truth = w.run(World.practisedModel(), w.arrivals(LocalDate.of(2026, 4, 6), 28), Map.of());
    EventLog history = World.export(truth);
    BpmnParser parser = new BpmnParser();
    ProcessModel asIs = parser.parse(Path.of("../processes/payment-investigation-as-is.bpmn"));
    ProcessModel documented = parser.parse(Path.of("../processes/payment-investigation-as-documented.bpmn"));

    long shortcuts = truth.cases().stream().filter(c -> c.path().contains("gw_amount") && !c.path().contains("second_approval")
        && ((Number) c.variables().get("amount")).doubleValue() > 10_000).count();
    assertTrue(shortcuts > 0, "the world does take the shortcut");

    ConformanceReport asIsFit = new TokenReplay(asIs).replay(history);
    Deviation skipped = asIsFit.deviations().stream()
        .filter(d -> d.kind() == Deviation.Kind.RAN_WHEN_NOT_ALLOWED && d.elementId().equals("send_recall")).findFirst().orElseThrow();
    assertEquals(shortcuts, skipped.cases(), "every skipped approval is found, nothing else is blamed");
    assertTrue(asIsFit.fitness() > 0.99);

    ConformanceReport documentedFit = new TokenReplay(documented).replay(history);
    assertTrue(documentedFit.fitness() < asIsFit.fitness());
    assertTrue(documentedFit.deviations().stream().anyMatch(d -> d.elementId().equals("request_info")));
  }
}
