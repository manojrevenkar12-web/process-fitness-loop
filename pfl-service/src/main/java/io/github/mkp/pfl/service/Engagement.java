package io.github.mkp.pfl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.mkp.pfl.backtest.BacktestResult;
import io.github.mkp.pfl.backtest.BacktestSettings;
import io.github.mkp.pfl.backtest.Backtester;
import io.github.mkp.pfl.backtest.Proposal;
import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.camunda.CamundaClient;
import io.github.mkp.pfl.conformance.ConformanceReport;
import io.github.mkp.pfl.conformance.TokenReplay;
import io.github.mkp.pfl.explain.Explainer;
import io.github.mkp.pfl.fitness.FitnessConfig;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.log.EventLogIo;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.StaffingConfig;
import io.github.mkp.pfl.verify.Verifier;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One customer engagement: today's process and its history, the fitness function, the proposals,
 * their backtests and the decisions people made about them.
 *
 * <p>The rules a change goes through, in order: the backtest must say IMPROVES (or NEEDS_SHADOW_RUN
 * for a shadow approval); the approver must not be the author; the approval binds the exact model
 * file and backtest result it was given; a deployment needs an approval whose model hash still
 * matches the file. The service enforces these rules; no model, agent or dashboard can skip them.
 */
public final class Engagement {

  /** A request the rules refuse. */
  public static final class Refused extends RuntimeException {
    private final int status;

    public Refused(int status, String message) {
      super(message);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final Path root;
  private final ServiceConfig config;
  private final ProcessModel today;
  private final ProcessModel documented;
  private final EventLog history;
  private final FitnessConfig fitness;
  private final Backtester backtester;
  private final DecisionLog decisions;
  private final Map<String, Proposal> proposals = new LinkedHashMap<>();
  private final Map<String, BacktestResult> results = new ConcurrentHashMap<>();
  private final Map<String, String> resultHashes = new ConcurrentHashMap<>();
  private ConformanceReport todayFit;
  private ConformanceReport documentedFit;

  public Engagement(Path root, ServiceConfig config, BacktestSettings settings) throws IOException {
    this.root = root;
    this.config = config;
    BpmnParser parser = new BpmnParser();
    this.today = parser.parse(root.resolve(config.today()));
    this.documented = parser.parse(root.resolve(config.documented()));
    this.history = EventLogIo.read(root.resolve(config.history()));
    this.fitness = FitnessConfig.load(root.resolve("config/fitness.json"));
    Staffing staffing = StaffingConfig.load(root.resolve("config/staffing.json"));
    this.backtester = new Backtester(today, history, fitness, staffing, settings);
    this.decisions = new DecisionLog(root.resolve(config.decisions()));
    for (Proposal p : Proposal.loadAll(root.resolve(config.proposals()))) {
      proposals.put(p.id(), p);
    }
  }

  public Map<String, Proposal> proposals() {
    return proposals;
  }

  public DecisionLog decisions() {
    return decisions;
  }

  public synchronized ConformanceReport conformance() {
    if (todayFit == null) {
      todayFit = new TokenReplay(today).replay(history);
      documentedFit = new TokenReplay(documented).replay(history);
    }
    return todayFit;
  }

  public synchronized ConformanceReport documentedConformance() {
    conformance();
    return documentedFit;
  }

  /** Overview for the dashboard. */
  public Map<String, Object> overview() throws IOException {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("process", today.label());
    o.put("cases", history.size());
    o.put("baseline", backtester.observed().entrySet().stream()
        .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey().id(), e.getValue()), Map::putAll));
    o.put("fitness", fitness);
    o.put("staffingNotes", backtester.staffingNotes());
    o.put("conformance", Map.of("today", conformance(), "documented", documentedConformance()));
    o.put("proposals", proposals.values().stream().map(p -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", p.id());
      m.put("title", p.title());
      m.put("author", p.author());
      m.put("backtest", results.get(p.id()));
      return m;
    }).toList());
    o.put("decisions", decisions.entries());
    o.put("decisionLogIntact", decisions.verify() == -1);
    return o;
  }

  public BacktestResult backtest(String id) throws IOException {
    Proposal p = proposal(id);
    BacktestResult r = backtester.run(p, new BpmnParser().parse(root.resolve(p.model())));
    results.put(id, r);
    resultHashes.put(id, DecisionLog.sha256(JSON.writeValueAsString(r)));
    return r;
  }

  public DecisionLog.Entry decide(String id, String reviewer, String action, String note) throws IOException {
    Proposal p = proposal(id);
    if (reviewer == null || reviewer.isBlank()) {
      throw new Refused(400, "a decision needs the reviewer's name");
    }
    BacktestResult r = results.get(id);
    if (!"REJECT".equals(action)) {
      if (reviewer.equals(p.author())) {
        throw new Refused(403, "the author of a change cannot approve it; ask another reviewer");
      }
      if (r == null) {
        throw new Refused(409, "run the backtest before deciding");
      }
      switch (action) {
        case "APPROVE" -> {
          if (r.verdict() != BacktestResult.Verdict.IMPROVES) {
            throw new Refused(409, "only a change the backtest shows to improve can be approved; this one is " + r.verdict());
          }
        }
        case "APPROVE_SHADOW" -> {
          if (r.verdict() != BacktestResult.Verdict.NEEDS_SHADOW_RUN && r.verdict() != BacktestResult.Verdict.IMPROVES) {
            throw new Refused(409, "a " + r.verdict() + " change cannot run in shadow either");
          }
        }
        default -> throw new Refused(400, "action must be APPROVE, APPROVE_SHADOW or REJECT");
      }
    }
    return decisions.append("DECISION", id, reviewer, action, note, r == null ? null : r.verdict().name(),
        resultHashes.get(id), modelSha(p));
  }

  /** Deploys an approved change; the approval must be about the model file as it is now. */
  public DecisionLog.Entry deploy(String id, String deployer) throws IOException {
    Proposal p = proposal(id);
    String sha = modelSha(p);
    List<DecisionLog.Entry> entries = decisions.entries();
    DecisionLog.Entry approval = null;
    for (DecisionLog.Entry e : entries) {
      if (id.equals(e.proposal()) && "DECISION".equals(e.kind())) {
        approval = e;
      }
    }
    if (approval == null || !"APPROVE".equals(approval.action())) {
      throw new Refused(409, "the latest decision on " + id + " is not an approval");
    }
    if (!sha.equals(approval.modelSha())) {
      throw new Refused(409, "the model changed after it was approved; it needs a new backtest and a new approval");
    }
    if (config.camundaUrl() == null) {
      throw new Refused(503, "no Camunda cluster is configured (camundaUrl in config/service.json)");
    }
    CamundaClient camunda = new CamundaClient(URI.create(config.camundaUrl()), null, null);
    Map<String, Object> deployment = camunda.deploy(Path.of(p.model()).getFileName().toString(), Files.readAllBytes(root.resolve(p.model())));
    return decisions.append("DEPLOYMENT", id, deployer, "DEPLOYED", "deployment " + deployment.get("deploymentKey"),
        approval.verdict(), approval.evidenceSha(), sha);
  }

  public Map<String, Object> verify(byte[] bpmn) {
    ProcessModel model = new BpmnParser().parse(bpmn);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("report", new Verifier().verify(model));
    out.put("explanation", new Explainer().explain(model));
    return out;
  }

  private Proposal proposal(String id) {
    Proposal p = proposals.get(id);
    if (p == null) {
      throw new Refused(404, "no proposal " + id);
    }
    return p;
  }

  private String modelSha(Proposal p) throws IOException {
    return DecisionLog.sha256(Files.readAllBytes(root.resolve(p.model())));
  }
}
