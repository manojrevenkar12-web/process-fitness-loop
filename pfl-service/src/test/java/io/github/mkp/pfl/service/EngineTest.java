package io.github.mkp.pfl.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.mkp.pfl.bpmn.BpmnParseException;
import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.camunda.CamundaClient;
import io.github.mkp.pfl.camunda.CamundaException;
import io.github.mkp.pfl.hub.InMemoryTransport;
import io.github.mkp.pfl.hub.LegacyHub;
import io.github.mkp.pfl.hub.LegacyHubClient;
import io.github.mkp.pfl.hub.Payment;
import io.github.mkp.pfl.service.mutate.BpmnMutations;
import io.github.mkp.pfl.service.scenario.Scenario;
import io.github.mkp.pfl.service.scenario.ScenarioWorkers;
import io.github.mkp.pfl.sim.SimCase;
import io.github.mkp.pfl.sim.Simulator;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.WorkingCalendar;
import io.github.mkp.pfl.verify.Verifier;
import io.github.mkp.pfl.worker.JobWorker;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real Camunda 8 cluster (CI starts one). Two questions: does the simulator take
 * the engine's path for every scripted case, and does the verifier predict which generated models
 * the engine will refuse.
 */
@Tag("engine")
class EngineTest {

  static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  static CamundaClient camunda;
  static String version;

  @BeforeAll
  static void waitForCluster() throws Exception {
    String url = System.getProperty("camunda.url");
    if (url == null || url.isBlank() || url.equals("null")) {
      url = System.getenv().getOrDefault("CAMUNDA_URL", "http://localhost:8080");
    }
    camunda = new CamundaClient(URI.create(url), null, null);
    long deadline = System.currentTimeMillis() + 240_000;
    while (true) {
      try {
        Map<String, Object> topology = camunda.topology();
        List<?> brokers = (List<?>) topology.get("brokers");
        if (brokers != null && !brokers.isEmpty()) {
          version = String.valueOf(topology.get("gatewayVersion"));
          return;
        }
      } catch (CamundaException e) {
        if (System.currentTimeMillis() > deadline) {
          throw e;
        }
      }
      Thread.sleep(2_000);
    }
  }

  @Test
  void simulatorTakesTheEnginesPathForEveryCaseAndNoRecallIsSentTwice() throws Exception {
    Path model = Path.of("../processes/payment-investigation-to-be.bpmn");
    ProcessModel toBe = new BpmnParser().parse(model);
    camunda.deploy("payment-investigation-to-be.bpmn", Files.readAllBytes(model));

    LegacyHub.Faults faults = new LegacyHub.Faults(0.10, 0.25, 20);
    LegacyHub hub = new LegacyHub(faults, 42);
    List<Map<String, Object>> cases = Scenario.cases();
    for (Map<String, Object> c : cases) {
      hub.add(new Payment((String) c.get("scenarioCorrectUetr"), "PH" + ((String) c.get("caseId")).substring(4),
          new BigDecimal(c.get("amount").toString()).setScale(2), "EUR", LocalDate.of(2026, 7, 1), "ST"));
    }
    LegacyHubClient hubClient = new LegacyHubClient(new InMemoryTransport(hub), "PFLCI", 6, Duration.ofMillis(5));

    Map<String, String> instances = new LinkedHashMap<>();
    Map<String, Map<String, Object>> byCase = new HashMap<>();
    try (JobWorker worker = new JobWorker(camunda, "ci-worker", ScenarioWorkers.handlers(camunda, hubClient), Duration.ofSeconds(30)).start()) {
      for (Map<String, Object> c : cases) {
        byCase.put((String) c.get("caseId"), c);
        instances.put((String) c.get("caseId"), camunda.startInstance(toBe.id(), c));
      }
      Set<String> done = new HashSet<>();
      long deadline = System.currentTimeMillis() + 300_000;
      while (done.size() < instances.size() && System.currentTimeMillis() < deadline) {
        for (Map.Entry<String, String> e : instances.entrySet()) {
          if (done.contains(e.getKey())) {
            continue;
          }
          for (Map<String, Object> task : camunda.openUserTasks(e.getValue())) {
            try {
              camunda.completeUserTask(String.valueOf(task.get("userTaskKey")),
                  Scenario.outputs((String) task.get("elementId"), byCase.get(e.getKey())));
            } catch (CamundaException ignored) {
              // already completed in an earlier round; the search lags behind
            }
          }
          try {
            if ("COMPLETED".equals(camunda.processInstance(e.getValue()).get("state"))) {
              done.add(e.getKey());
            }
          } catch (CamundaException ignored) {
            // not exported yet
          }
        }
        Thread.sleep(500);
      }
      report("jobs", worker);
    }

    Instant start = Instant.parse("2026-07-06T07:00:00Z");
    Map<String, List<String>> predicted = new HashMap<>();
    for (SimCase c : new Simulator(toBe, new Staffing(Map.of("payments-ops", 3, "team-leads", 1), WorkingCalendar.ALWAYS))
        .run(Scenario.seeds(start), Scenario.behaviour(), start.plus(Duration.ofDays(5))).cases()) {
      predicted.put(c.caseId(), c.path());
    }

    int same = 0;
    List<Map<String, Object>> mismatches = new ArrayList<>();
    for (Map.Entry<String, String> e : instances.entrySet()) {
      List<String> engine = enginePath(e.getValue(), predicted.get(e.getKey()).size());
      if (engine.equals(predicted.get(e.getKey()))) {
        same++;
      } else {
        mismatches.add(Map.of("case", e.getKey(), "engine", engine, "simulator", predicted.get(e.getKey())));
      }
    }
    Map<String, Integer> perRef = new HashMap<>();
    hub.recalls().forEach(r -> perRef.merge(r.clientRef(), 1, Integer::sum));
    long recallCases = cases.stream().filter(c -> c.get("scenarioType").equals("RECALL")).count();

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("camundaVersion", version);
    report.put("cases", instances.size());
    report.put("samePath", same);
    report.put("mismatches", mismatches);
    report.put("hubFaults", faults);
    report.put("recallCases", recallCases);
    report.put("recallsOnHub", hub.recalls().size());
    report.put("recalledTwice", perRef.values().stream().filter(n -> n > 1).count());
    report.put("hubCalls", hub.calls());
    write("engine-equivalence.json", report);

    assertEquals(instances.size(), same, "paths differ: " + mismatches);
    assertEquals(recallCases, hub.recalls().size(), "every recall case recalled the money exactly once");
  }

  private static void report(String what, JobWorker worker) {
    for (String type : List.of("pfl.intake-agent", "pfl.legacy-hub.lookup", "pfl.legacy-hub.recall", "pfl.gpi.track")) {
      System.out.println(what + " " + type + ": " + worker.counts(type));
    }
  }

  /** Element ids in creation order, waiting until the export has caught up with the whole path. */
  private static List<String> enginePath(String processInstanceKey, int expected) throws InterruptedException {
    List<String> path = List.of();
    for (int i = 0; i < 40; i++) {
      List<Map<String, Object>> items = new ArrayList<>(camunda.elementInstances(processInstanceKey));
      items.removeIf(m -> "PROCESS".equals(m.get("type")));
      items.sort(Comparator.comparing(m -> Long.parseLong(String.valueOf(m.get("elementInstanceKey")))));
      path = items.stream().map(m -> (String) m.get("elementId")).toList();
      if (path.size() >= expected) {
        return path;
      }
      Thread.sleep(500);
    }
    return path;
  }

  @Test
  void verifierPredictsWhichGeneratedModelsTheEngineRefuses() throws Exception {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("camundaVersion", version);
    report.put("dev", corpus(List.of("../processes/payment-investigation-to-be.bpmn")));
    report.put("heldout", corpus(List.of("../proposals/hub-connector.bpmn", "../proposals/agent-intake.bpmn")));
    write("verifier-vs-engine.json", report);
    @SuppressWarnings("unchecked")
    Map<String, Object> dev = (Map<String, Object>) report.get("dev");
    assertTrue(((Number) dev.get("mutants")).intValue() > 40);
    for (String set : List.of("dev", "heldout")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> s = (Map<String, Object>) report.get(set);
      assertEquals(s.get("mutants"), s.get("agree"), set + ": the verifier and the engine disagree on a mutant");
    }
  }

  private static Map<String, Object> corpus(List<String> bases) throws Exception {
    Verifier verifier = new Verifier();
    List<Map<String, Object>> rows = new ArrayList<>();
    int agree = 0;
    int refused = 0;
    int predictedRefused = 0;
    for (String base : bases) {
      for (BpmnMutations.Mutant m : BpmnMutations.all(Files.readAllBytes(Path.of(base)))) {
        boolean predicted;
        try {
          predicted = verifier.verify(new BpmnParser().parse(m.bpmn())).deployable();
        } catch (BpmnParseException e) {
          predicted = false;
        }
        boolean accepted;
        String message = "";
        try {
          camunda.deploy("mutant.bpmn", m.bpmn());
          accepted = true;
        } catch (CamundaException e) {
          if (e.status() != 400) {
            throw e;
          }
          accepted = false;
          message = e.getMessage().length() > 300 ? e.getMessage().substring(0, 300) : e.getMessage();
        }
        if (predicted == accepted) {
          agree++;
        }
        if (!accepted) {
          refused++;
        }
        if (!predicted) {
          predictedRefused++;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("base", Path.of(base).getFileName().toString());
        row.put("mutant", m.name());
        row.put("verifierSaysDeploys", predicted);
        row.put("engineDeployed", accepted);
        row.put("engineMessage", message);
        rows.add(row);
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mutants", rows.size());
    out.put("agree", agree);
    out.put("engineRefused", refused);
    out.put("verifierPredictedRefused", predictedRefused);
    out.put("rows", rows);
    return out;
  }

  private static void write(String name, Map<String, Object> report) throws Exception {
    Path out = Path.of("../reports", name);
    Files.createDirectories(out.getParent());
    JSON.writeValue(out.toFile(), report);
  }
}
