package io.github.mkp.pfl.service;

import io.github.mkp.pfl.backtest.BacktestResult;
import io.github.mkp.pfl.backtest.BacktestSettings;
import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.conformance.ConformanceReport;
import io.github.mkp.pfl.conformance.TokenReplay;
import io.github.mkp.pfl.explain.Explainer;
import io.github.mkp.pfl.log.EventLogIo;
import io.github.mkp.pfl.service.report.ResultsReport;
import io.github.mkp.pfl.verify.Issue;
import io.github.mkp.pfl.verify.VerificationReport;
import io.github.mkp.pfl.verify.Verifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@code pfl} command line.
 *
 * <pre>
 * pfl verify FILE.bpmn [--vars a,b,c]      exit 1 if the engine would refuse it, 2 if runs can hang
 * pfl explain FILE.bpmn                    numbered steps in plain words
 * pfl conformance FILE.bpmn HISTORY.jsonl  how well the model describes the history
 * pfl backtest PROPOSAL_ID                 backtest one proposal of the engagement
 * pfl serve                                the API behind the dashboard
 * pfl report                               gather reports into reports/results.json and the README
 * pfl hub-sim PORT                         serve a simulated PH/9 hub with demo payments
 * pfl worker CAMUNDA_URL HUB_URL           work the hub job types against a cluster
 * </pre>
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      return;
    }
    Path root = Path.of(System.getProperty("pfl.root", "."));
    switch (args[0]) {
      case "verify" -> System.exit(verify(Path.of(args[1]), option(args, "--vars")));
      case "explain" -> System.out.print(new Explainer().explain(new BpmnParser().parse(Path.of(args[1]))).toMarkdown());
      case "conformance" -> {
        ConformanceReport r = new TokenReplay(new BpmnParser().parse(Path.of(args[1]))).replay(EventLogIo.read(Path.of(args[2])));
        System.out.printf("fitness %.4f, %.1f%% of %d cases fit perfectly%n", r.fitness(), 100 * r.perfectlyFitting(), r.cases());
        r.deviations().forEach(d -> System.out.println("- " + d.message()));
      }
      case "backtest" -> {
        Engagement e = new Engagement(root, ServiceConfig.load(root.resolve("config/service.json")), BacktestSettings.defaults());
        BacktestResult r = e.backtest(args[1]);
        System.out.println(Engagement.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r));
      }
      case "serve" -> {
        ServiceConfig config = ServiceConfig.load(root.resolve("config/service.json"));
        Engagement e = new Engagement(root, config, BacktestSettings.defaults());
        ApiServer server = new ApiServer(e, config.port());
        System.out.println("pfl API on http://127.0.0.1:" + server.port());
        Thread.currentThread().join();
      }
      case "report" -> ResultsReport.write(root);
      case "hub-sim" -> {
        io.github.mkp.pfl.hub.LegacyHub hub = new io.github.mkp.pfl.hub.LegacyHub(
            new io.github.mkp.pfl.hub.LegacyHub.Faults(0.05, 0.05, 50), 1);
        for (var c : io.github.mkp.pfl.service.scenario.Scenario.cases()) {
          hub.add(new io.github.mkp.pfl.hub.Payment((String) c.get("scenarioCorrectUetr"), "PH" + ((String) c.get("caseId")).substring(4),
              new java.math.BigDecimal(c.get("amount").toString()).setScale(2), "EUR", java.time.LocalDate.of(2026, 7, 1), "ST"));
        }
        var server = new io.github.mkp.pfl.hub.HubHttpServer(hub, Integer.parseInt(args[1]), java.time.Duration.ofSeconds(3));
        System.out.println("PH/9 hub simulator on http://127.0.0.1:" + server.port() + "/ph9/gateway");
        Thread.currentThread().join();
      }
      case "worker" -> {
        var camunda = new io.github.mkp.pfl.camunda.CamundaClient(java.net.URI.create(args[1]), null, null);
        var hub = new io.github.mkp.pfl.hub.LegacyHubClient(new io.github.mkp.pfl.hub.HttpHubTransport(java.net.URI.create(args[2]),
            java.time.Duration.ofSeconds(2)), "PFLWORKER", 5, java.time.Duration.ofMillis(200));
        var worker = new io.github.mkp.pfl.worker.JobWorker(camunda, "pfl-worker",
            io.github.mkp.pfl.service.scenario.ScenarioWorkers.handlers(camunda, hub), java.time.Duration.ofSeconds(60)).start();
        System.out.println("working " + String.join(", ", java.util.List.of("pfl.legacy-hub.lookup", "pfl.legacy-hub.recall",
            "pfl.intake-agent", "pfl.gpi.track", "pfl.notify", "pfl.request-info")) + " jobs; Ctrl-C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
        Thread.currentThread().join();
      }
      default -> usage();
    }
  }

  static int verify(Path file, Optional<String> vars) throws Exception {
    ProcessModel model = new BpmnParser().parse(file);
    VerificationReport report = new Verifier().verify(model,
        vars.map(v -> Arrays.stream(v.split(",")).map(String::strip).collect(Collectors.toSet())));
    for (Issue i : report.issues()) {
      System.out.printf("%-7s %-9s %-28s %s%n", i.severity(), i.category(), i.code(), i.message());
      if (!i.trace().isEmpty()) {
        System.out.println("        after: " + String.join(" -> ", i.trace()));
      }
    }
    System.out.printf("%s: %s, %d states explored%n", model.id(),
        report.sound() ? "deploys and every run can finish" : report.deployable() ? "deploys, but some runs cannot finish" : "the engine will refuse it",
        report.statesExplored());
    return report.deployable() ? (report.sound() ? 0 : 2) : 1;
  }

  private static Optional<String> option(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return Optional.of(args[i + 1]);
      }
    }
    return Optional.empty();
  }

  private static void usage() {
    System.out.println("usage: pfl verify|explain|conformance|backtest|serve|report|hub-sim|worker ...");
  }
}
