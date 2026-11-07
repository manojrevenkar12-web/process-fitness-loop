package io.github.mkp.pfl.world.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.mkp.pfl.backtest.BacktestResult;
import io.github.mkp.pfl.backtest.BacktestSettings;
import io.github.mkp.pfl.backtest.Backtester;
import io.github.mkp.pfl.backtest.Proposal;
import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.conformance.ConformanceReport;
import io.github.mkp.pfl.conformance.TokenReplay;
import io.github.mkp.pfl.fitness.FitnessConfig;
import io.github.mkp.pfl.fitness.FitnessFunction;
import io.github.mkp.pfl.fitness.Kpi;
import io.github.mkp.pfl.fitness.Kpis;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.log.EventLogIo;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.SimResult;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.StaffingConfig;
import io.github.mkp.pfl.world.World;
import io.github.mkp.pfl.world.WorldParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scores the backtester against the ground truth.
 *
 * <p>For every world: the bank runs today's process for twelve weeks and exports its history. For
 * every proposal, the backtester projects the change from that history alone; then the world runs
 * the proposal over the same inquiries and reports what really happens. The report compares the
 * two, and compares the naive spreadsheet estimate with the truth too.
 *
 * <p>Usage: {@code EvaluateBacktest dev|heldout}. Dev worlds are for development; the held-out
 * worlds are only run for the published numbers, and their seeds are pinned by hash in {@code
 * reports/heldout-pin.json}.
 */
public final class EvaluateBacktest {

  private static final LocalDate FIRST_DAY = LocalDate.of(2026, 4, 6);
  private static final int DAYS = 84;
  private static final List<Kpi> REPORTED = List.of(Kpi.CYCLE_P90_HOURS, Kpi.SLA_BREACH_RATE, Kpi.COST_PER_CASE_EUR, Kpi.TOUCHES_PER_CASE);

  public static void main(String[] args) throws Exception {
    String set = args.length > 0 ? args[0] : "dev";
    Path root = Path.of(args.length > 1 ? args[1] : ".");
    ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT);
    @SuppressWarnings("unchecked")
    Map<String, List<Integer>> worlds = json.readValue(root.resolve("config/worlds.json").toFile(), Map.class);
    List<Integer> seeds = worlds.get(set);
    if (seeds == null) {
      throw new IllegalArgumentException("no world set '" + set + "' in config/worlds.json");
    }
    if (set.equals("heldout")) {
      checkPin(root, json);
    }
    Map<String, Object> report = evaluate(root, seeds, set, json);
    Path out = root.resolve("reports/backtest-" + set + ".json");
    Files.createDirectories(out.getParent());
    json.writeValue(out.toFile(), report);
    System.out.println("wrote " + out);
  }

  static Map<String, Object> evaluate(Path root, List<Integer> seeds, String set, ObjectMapper json) throws IOException {
    FitnessConfig config = FitnessConfig.load(root.resolve("config/fitness.json"));
    Staffing planned = StaffingConfig.load(root.resolve("config/staffing.json"));
    BpmnParser parser = new BpmnParser();
    ProcessModel asIs = parser.parse(root.resolve("processes/payment-investigation-as-is.bpmn"));
    ProcessModel documented = parser.parse(root.resolve("processes/payment-investigation-as-documented.bpmn"));
    List<Proposal> proposals = Proposal.loadAll(root.resolve("proposals"));
    BacktestSettings settings = BacktestSettings.defaults();

    List<Map<String, Object>> perWorld = new ArrayList<>();
    Map<String, List<Map<String, Object>>> byProposal = new LinkedHashMap<>();
    for (int seed : seeds) {
      long started = System.nanoTime();
      World world = new World(WorldParams.forSeed(seed), planned);
      List<CaseSeed> arrivals = world.arrivals(FIRST_DAY, DAYS);
      SimResult truthToday = world.run(World.practisedModel(), arrivals, Map.of());
      EventLog history = World.export(truthToday);
      if (seed == seeds.get(0) && set.equals("dev")) {
        EventLogIo.write(history, root.resolve("data/history-sample.jsonl"));
      }
      Backtester backtester = new Backtester(asIs, history, config, planned, settings.withSeed(settings.seed() + seed));
      FitnessFunction fitness = backtester.fitness();
      Kpis kpis = fitness.kpis();
      Map<Kpi, Double> trueToday = kpis.compute(truthToday.outcomes());
      NaiveEstimate naive = new NaiveEstimate(history);

      ConformanceReport asIsFit = new TokenReplay(asIs).replay(history);
      ConformanceReport documentedFit = new TokenReplay(documented).replay(history);

      Map<String, Object> w = new LinkedHashMap<>();
      w.put("seed", seed);
      w.put("cases", history.size());
      w.put("observed", ids(backtester.observed()));
      w.put("conformance", Map.of(
          "asIs", conformance(asIsFit),
          "documented", conformance(documentedFit)));
      List<Map<String, Object>> results = new ArrayList<>();
      for (Proposal p : proposals) {
        ProcessModel candidate = parser.parse(root.resolve(p.model()));
        BacktestResult projected = backtester.run(p, candidate);
        Map<Kpi, Double> trueCandidate = kpis.compute(world.run(candidate, arrivals, p.teams()).outcomes());
        Map<Kpi, Double> trueDelta = new EnumMap<>(Kpi.class);
        Map<Kpi, Double> trueProjected = new EnumMap<>(Kpi.class);
        for (Kpi k : Kpi.values()) {
          trueDelta.put(k, trueCandidate.get(k) - trueToday.get(k));
          trueProjected.put(k, backtester.observed().get(k) + trueDelta.get(k));
        }
        for (FitnessConfig.Constraint c : config.constraints()) {
          trueProjected.put(Kpi.of(c.kpi()), trueCandidate.get(Kpi.of(c.kpi())));
        }
        var trueScore = fitness.score(trueProjected);
        Map<Kpi, Double> naiveDelta = naive.delta(asIs, candidate, p, kpis);
        Map<Kpi, Double> naiveProjected = new EnumMap<>(Kpi.class);
        for (Kpi k : Kpi.values()) {
          naiveProjected.put(k, backtester.observed().get(k) + naiveDelta.get(k));
        }
        double naiveScore = fitness.score(naiveProjected).score();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("proposal", p.id());
        r.put("verdict", projected.verdict().name());
        r.put("reasons", projected.reasons());
        r.put("score", Map.of("projected", projected.score(), "true", trueScore.score(), "naive", naiveScore,
            "covered", covers(projected.score(), trueScore.score())));
        Map<String, Object> perKpi = new LinkedHashMap<>();
        for (Kpi k : REPORTED) {
          BacktestResult.Interval d = projected.delta().get(k.id());
          perKpi.put(k.id(), Map.of(
              "projected", d == null ? Map.of() : d,
              "true", trueDelta.get(k),
              "naive", naiveDelta.get(k),
              "covered", d != null && covers(d, trueDelta.get(k))));
        }
        r.put("delta", perKpi);
        r.put("evidence", projected.evidence());
        r.put("trueBreaches", trueScore.breaches());
        r.put("verdictHolds", verdictHolds(projected, trueScore.score(), !trueScore.feasible()));
        results.add(r);
        byProposal.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(r);
      }
      w.put("proposals", results);
      w.put("seconds", (System.nanoTime() - started) / 1e9);
      perWorld.add(w);
      System.out.printf(Locale.ROOT, "world %d: %d cases, %.1fs%n", seed, history.size(), (System.nanoTime() - started) / 1e9);
      for (Map<String, Object> r : results) {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) r.get("score");
        BacktestResult.Interval i = (BacktestResult.Interval) s.get("projected");
        System.out.printf(Locale.ROOT, "  %-22s %-16s projected %+.3f [%+.3f, %+.3f]  true %+.3f  naive %+.3f  %s%n",
            r.get("proposal"), r.get("verdict"), i.estimate(), i.low(), i.high(), (double) s.get("true"), (double) s.get("naive"),
            Boolean.TRUE.equals(s.get("covered")) ? "covered" : "MISSED");
      }
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("set", set);
    report.put("worlds", seeds);
    report.put("daysOfHistory", DAYS);
    report.put("settings", settings);
    report.put("summary", summary(byProposal));
    report.put("perWorld", perWorld);
    return report;
  }

  private static Map<String, Object> summary(Map<String, List<Map<String, Object>>> byProposal) {
    Map<String, Object> out = new LinkedHashMap<>();
    double oursAll = 0;
    double naiveAll = 0;
    int coveredAll = 0;
    int holdsAll = 0;
    int n = 0;
    for (var e : byProposal.entrySet()) {
      double ours = 0;
      double naive = 0;
      int covered = 0;
      int holds = 0;
      int scored = 0;
      Map<String, Integer> verdicts = new LinkedHashMap<>();
      for (Map<String, Object> r : e.getValue()) {
        verdicts.merge((String) r.get("verdict"), 1, Integer::sum);
        if (Boolean.TRUE.equals(r.get("verdictHolds"))) {
          holds++;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) r.get("score");
        BacktestResult.Interval i = (BacktestResult.Interval) s.get("projected");
        if (Double.isNaN(i.estimate())) {
          continue;
        }
        scored++;
        ours += Math.abs(i.estimate() - (double) s.get("true"));
        naive += Math.abs((double) s.get("naive") - (double) s.get("true"));
        if (Boolean.TRUE.equals(s.get("covered"))) {
          covered++;
        }
      }
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("worlds", e.getValue().size());
      p.put("verdicts", verdicts);
      p.put("verdictHolds", holds);
      if (scored > 0) {
        p.put("scoreErrorOurs", ours / scored);
        p.put("scoreErrorNaive", naive / scored);
        p.put("intervalCoverage", (double) covered / scored);
        oursAll += ours;
        naiveAll += naive;
        coveredAll += covered;
        n += scored;
      }
      holdsAll += holds;
      out.put(e.getKey(), p);
    }
    int total = byProposal.values().stream().mapToInt(List::size).sum();
    out.put("overall", Map.of(
        "scoreErrorOurs", n == 0 ? Double.NaN : oursAll / n,
        "scoreErrorNaive", n == 0 ? Double.NaN : naiveAll / n,
        "intervalCoverage", n == 0 ? Double.NaN : (double) coveredAll / n,
        "verdictsThatHold", holdsAll,
        "verdicts", total));
    return out;
  }

  /**
   * Whether the verdict was the right call given the truth: an improvement really improved, a
   * block really crossed a limit or was refused for missing evidence, a shadow-run request was
   * made for a change whose interval was honest about the truth.
   */
  static boolean verdictHolds(BacktestResult r, double trueScore, boolean trulyBreaches) {
    return switch (r.verdict()) {
      case IMPROVES -> trueScore > 0 && !trulyBreaches;
      case WORSE -> trueScore < 0;
      case UNCLEAR -> covers(r.score(), trueScore);
      case NEEDS_SHADOW_RUN -> !trulyBreaches;
      case BLOCKED -> trulyBreaches || r.breaches().isEmpty();
    };
  }

  static boolean covers(BacktestResult.Interval i, double truth) {
    return !Double.isNaN(i.estimate()) && truth >= i.low() && truth <= i.high();
  }

  private static Map<String, Object> conformance(ConformanceReport r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("fitness", r.fitness());
    m.put("perfectlyFitting", r.perfectlyFitting());
    m.put("deviations", r.deviations().stream().limit(6).map(d -> Map.of("kind", d.kind().name(), "element", d.elementId(),
        "cases", d.cases(), "message", d.message())).toList());
    return m;
  }

  private static Map<String, Double> ids(Map<Kpi, Double> m) {
    Map<String, Double> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(k.id(), v));
    return out;
  }

  /** Refuses to run the held-out worlds if their seeds changed since they were pinned. */
  private static void checkPin(Path root, ObjectMapper json) throws Exception {
    Path pin = root.resolve("reports/heldout-pin.json");
    @SuppressWarnings("unchecked")
    Map<String, List<Integer>> worlds = json.readValue(root.resolve("config/worlds.json").toFile(), Map.class);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(worlds.get("heldout").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    if (!Files.exists(pin)) {
      throw new IllegalStateException("reports/heldout-pin.json is missing; pin the held-out seeds before running them");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> pinned = json.readValue(pin.toFile(), Map.class);
    if (!digest.equals(pinned.get("sha256"))) {
      throw new IllegalStateException("held-out seeds changed since they were pinned (" + digest + " vs " + pinned.get("sha256") + ")");
    }
  }
}
