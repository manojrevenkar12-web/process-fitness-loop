package io.github.mkp.pfl.service.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gathers the reports the experiments wrote into {@code reports/results.json} and rewrites the
 * results block of the README from it. Numbers in the README are never typed by hand.
 */
public final class ResultsReport {

  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final String START = "<!-- results:start -->";
  private static final String END = "<!-- results:end -->";

  private ResultsReport() {}

  public static void write(Path root) throws IOException {
    Path reports = root.resolve("reports");
    Map<String, Object> results = new LinkedHashMap<>();
    for (String name : List.of("backtest-dev", "backtest-heldout")) {
      Map<String, Object> r = read(reports.resolve(name + ".json"));
      if (r != null) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("worlds", r.get("worlds"));
        b.put("summary", r.get("summary"));
        b.put("approvable", approvable(r));
        results.put(name, b);
      }
    }
    Map<String, Object> dev = read(reports.resolve("backtest-dev.json"));
    if (dev != null) {
      List<Map<String, Object>> worlds = list(dev.get("perWorld"));
      results.put("exampleWorld", worlds.get(0));
    }
    for (String name : List.of("hub-reliability", "engine-equivalence", "verifier-vs-engine", "verifier-vs-engine-first-run")) {
      Map<String, Object> r = read(reports.resolve(name + ".json"));
      if (r != null) {
        results.put(name, r);
      }
    }
    JSON.writeValue(reports.resolve("results.json").toFile(), results);
    Path readme = root.resolve("README.md");
    if (Files.exists(readme)) {
      String text = Files.readString(readme, StandardCharsets.UTF_8);
      int a = text.indexOf(START);
      int b = text.indexOf(END);
      if (a >= 0 && b > a) {
        String block = START + "\n" + markdown(results) + END;
        Files.writeString(readme, text.substring(0, a) + block + text.substring(b + END.length()), StandardCharsets.UTF_8);
      }
    }
  }

  @SuppressWarnings("unchecked")
  static String markdown(Map<String, Object> results) {
    StringBuilder md = new StringBuilder();
    md.append("| Measure | Result | Source |\n|---|---|---|\n");
    for (String set : List.of("backtest-heldout", "backtest-dev")) {
      Map<String, Object> b = (Map<String, Object>) results.get(set);
      if (b == null) {
        continue;
      }
      Map<String, Object> overall = (Map<String, Object>) ((Map<String, Object>) b.get("summary")).get("overall");
      String label = set.endsWith("heldout") ? "held-out" : "dev";
      int worlds = ((List<Object>) b.get("worlds")).size();
      md.append(String.format(Locale.ROOT, "| Backtest error on the fitness-score change, %s worlds | **%.3f** (naive spreadsheet estimate: %.3f) | `reports/%s.json`, %d worlds |%n",
          label, num(overall.get("scoreErrorOurs")), num(overall.get("scoreErrorNaive")), set, worlds));
      md.append(String.format(Locale.ROOT, "| 90%% interval contains the true change, %s worlds | **%.0f%%** of proposals | `reports/%s.json` |%n",
          label, 100 * num(overall.get("intervalCoverage")), set));
      Map<String, Object> approvable = (Map<String, Object>) b.get("approvable");
      md.append(String.format(Locale.ROOT, "| ... for the changes the gate lets a reviewer approve | **%d of %d** | `reports/%s.json` |%n",
          ((Number) approvable.get("covered")).intValue(), ((Number) approvable.get("total")).intValue(), set));
      md.append(String.format(Locale.ROOT, "| Verdicts that hold against the truth, %s worlds | **%d of %d** | `reports/%s.json` |%n",
          label, ((Number) overall.get("verdictsThatHold")).intValue(), ((Number) overall.get("verdicts")).intValue(), set));
    }
    Map<String, Object> hub = (Map<String, Object>) results.get("hub-reliability");
    if (hub != null) {
      Map<String, Object> o = (Map<String, Object>) hub.get("outcomes");
      Map<String, Object> ours = (Map<String, Object>) o.get("checkBeforeAct");
      Map<String, Object> blind = (Map<String, Object>) o.get("blindRetry");
      md.append(String.format(Locale.ROOT, "| Recalls sent twice under hub timeouts | **%d of %d** (blind retry: %d) | `reports/hub-reliability.json` |%n",
          ((Number) ours.get("recalledTwiceOrMore")).intValue(), ((Number) ours.get("recalls")).intValue(),
          ((Number) blind.get("recalledTwiceOrMore")).intValue()));
    }
    Map<String, Object> eq = (Map<String, Object>) results.get("engine-equivalence");
    if (eq != null) {
      md.append(String.format(Locale.ROOT, "| Simulator path equals Camunda %s path | **%d of %d** cases | `reports/engine-equivalence.json` |%n",
          eq.get("camundaVersion"), ((Number) eq.get("samePath")).intValue(), ((Number) eq.get("cases")).intValue()));
    }
    Map<String, Object> ve = (Map<String, Object>) results.get("verifier-vs-engine");
    Map<String, Object> first = (Map<String, Object>) results.get("verifier-vs-engine-first-run");
    if (ve != null) {
      for (String set : List.of("heldout", "dev")) {
        Map<String, Object> s = (Map<String, Object>) ve.get(set);
        if (s != null) {
          md.append(String.format(Locale.ROOT, "| Verifier predicts the engine's deploy decision, %s mutants | **%d of %d** | `reports/verifier-vs-engine.json` |%n",
              set.equals("heldout") ? "held-out" : "dev", ((Number) s.get("agree")).intValue(), ((Number) s.get("mutants")).intValue()));
        }
      }
      if (first != null) {
        Map<String, Object> h = (Map<String, Object>) first.get("heldout");
        md.append(String.format(Locale.ROOT, "| ... on the first engine run, before six rules were reclassified | %d of %d held-out | `reports/verifier-vs-engine-first-run.json` |%n",
            ((Number) h.get("agree")).intValue(), ((Number) h.get("mutants")).intValue()));
      }
    }
    return md.toString();
  }

  /** Interval coverage over the projections the gate would let a reviewer approve. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> approvable(Map<String, Object> report) {
    int covered = 0;
    int total = 0;
    for (Map<String, Object> world : list(report.get("perWorld"))) {
      for (Map<String, Object> p : list(world.get("proposals"))) {
        if ("IMPROVES".equals(p.get("verdict"))) {
          total++;
          if (Boolean.TRUE.equals(((Map<String, Object>) p.get("score")).get("covered"))) {
            covered++;
          }
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("covered", covered);
    out.put("total", total);
    return out;
  }

  private static double num(Object o) {
    return o == null ? Double.NaN : ((Number) o).doubleValue();
  }

  private static Map<String, Object> read(Path file) throws IOException {
    if (!Files.exists(file)) {
      return null;
    }
    return JSON.readValue(file.toFile(), new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object o) {
    return (List<Map<String, Object>>) o;
  }
}
