package io.github.mkp.pfl.service.scenario;

import io.github.mkp.pfl.sim.Behaviour;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.StepContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scripted cases for the re-engineered process: each case carries in its variables how its steps
 * will turn out (complete on first contact or not, payment in the hub or not, gpi status). The
 * same rules drive the simulator and the job workers on a real cluster, so the two must take the
 * same path through the model for every case. CI checks that they do.
 */
public final class Scenario {

  private Scenario() {}

  /** What a step sets, given the case's variables when the step started. */
  public static Map<String, Object> outputs(String nodeId, Map<String, Object> vars) {
    Map<String, Object> out = new LinkedHashMap<>();
    switch (nodeId) {
      case "classify_inquiry" -> {
        int attempts = vars.get("intakeAttempts") instanceof Number n ? n.intValue() + 1 : 1;
        out.put("intakeAttempts", attempts);
        out.put("infoComplete", Boolean.TRUE.equals(vars.get("scenarioComplete")) || attempts > 1);
        out.put("inquiryType", vars.get("scenarioType"));
      }
      case "gpi_trace" -> out.put("gpiStatus", vars.get("scenarioGpi"));
      case "manual_lookup" -> out.put("uetr", vars.get("scenarioCorrectUetr"));
      default -> {
        // other steps set nothing the path depends on
      }
    }
    return out;
  }

  /** The business error a step throws, or null. */
  public static String error(String nodeId, Map<String, Object> vars) {
    return nodeId.equals("hub_lookup") && Boolean.TRUE.equals(vars.get("scenarioMissing")) ? "PAYMENT_NOT_FOUND" : null;
  }

  /** Every combination that matters for the paths of the re-engineered process. */
  public static List<Map<String, Object>> cases() {
    List<Map<String, Object>> cases = new ArrayList<>();
    int n = 0;
    for (String type : List.of("RECALL", "NON_RECEIPT", "QUERY")) {
      for (int amount : List.of(900, 25_000)) {
        for (boolean complete : List.of(true, false)) {
          for (boolean missing : List.of(false, true)) {
            for (String gpi : type.equals("NON_RECEIPT") ? List.of("CREDITED", "PENDING") : List.of("CREDITED")) {
              n++;
              Map<String, Object> v = new LinkedHashMap<>();
              v.put("caseId", String.format("SCN-%03d", n));
              String correct = new UUID(0x5ce0a10L, n).toString();
              // a missing payment is one whose inquiry quotes a wrong UETR; the analyst finds the right one
              v.put("uetr", missing ? new UUID(0xbadL, n).toString() : correct);
              v.put("scenarioCorrectUetr", correct);
              v.put("amount", amount);
              v.put("currency", "EUR");
              v.put("channel", complete ? "SWIFT" : "EMAIL");
              v.put("scenarioType", type);
              v.put("scenarioComplete", complete);
              v.put("scenarioMissing", missing);
              v.put("scenarioGpi", gpi);
              cases.add(v);
            }
          }
        }
      }
    }
    return cases;
  }

  public static List<CaseSeed> seeds(Instant start) {
    List<CaseSeed> seeds = new ArrayList<>();
    int i = 0;
    for (Map<String, Object> c : cases()) {
      seeds.add(new CaseSeed((String) c.get("caseId"), start.plusSeconds(60L * i++), c));
    }
    return seeds;
  }

  /** The same rules as a simulator behaviour. */
  public static Behaviour behaviour() {
    return new Behaviour() {
      @Override
      public Duration serviceTime(StepContext step) {
        return Duration.ofMinutes(1);
      }

      @Override
      public Map<String, Object> outputs(StepContext step) {
        return Scenario.outputs(step.nodeId(), step.variables());
      }

      @Override
      public Duration messageDelay(StepContext step) {
        return Duration.ofMinutes(5);
      }

      @Override
      public String error(StepContext step) {
        return Scenario.error(step.nodeId(), step.variables());
      }
    };
  }
}
