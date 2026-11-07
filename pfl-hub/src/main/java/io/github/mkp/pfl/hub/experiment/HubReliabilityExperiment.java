package io.github.mkp.pfl.hub.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.mkp.pfl.hub.HubException;
import io.github.mkp.pfl.hub.HubRecords;
import io.github.mkp.pfl.hub.HubTransport;
import io.github.mkp.pfl.hub.InMemoryTransport;
import io.github.mkp.pfl.hub.LegacyHub;
import io.github.mkp.pfl.hub.LegacyHubClient;
import io.github.mkp.pfl.hub.Payment;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends recalls through a hub that sheds load and loses answers, once with the check-before-act
 * client and once with a client that simply retries, and counts how often each recalls money
 * twice. Job retries are modelled the way Zeebe does them: when the client gives up, the job is
 * offered again and the whole call repeats.
 */
public final class HubReliabilityExperiment {

  /** Outcome of one client over one fault schedule. */
  public record Outcome(int recalls, int recalledOnce, int recalledTwiceOrMore, int neverRecalled, int extraRecalls, int hubCalls) {}

  private HubReliabilityExperiment() {}

  public static Outcome run(boolean checkBeforeAct, LegacyHub.Faults faults, long seed, int recalls, int jobRetries) {
    LegacyHub hub = new LegacyHub(faults, seed);
    List<String> uetrs = new java.util.ArrayList<>();
    for (int i = 0; i < recalls; i++) {
      String uetr = new UUID(seed, i).toString();
      uetrs.add(uetr);
      hub.add(new Payment(uetr, String.format("PH%010d", i), new BigDecimal("2500.00"), "EUR", LocalDate.of(2026, 7, 1), "ST"));
    }
    HubTransport transport = new InMemoryTransport(hub);
    LegacyHubClient good = new LegacyHubClient(transport, "PFLWORKER", 4, Duration.ZERO);
    BlindRetry blind = new BlindRetry(transport, 4);
    for (int i = 0; i < recalls; i++) {
      String clientRef = String.format("PFL%013d", i);
      for (int attempt = 0; attempt <= jobRetries; attempt++) {
        try {
          if (checkBeforeAct) {
            good.recall(uetrs.get(i), clientRef, new BigDecimal("2500.00"), "EUR");
          } else {
            blind.recall(uetrs.get(i), clientRef, new BigDecimal("2500.00"), "EUR");
          }
          break;
        } catch (HubException e) {
          if (e.kind() != HubException.Kind.UNAVAILABLE) {
            break;
          }
        }
      }
    }
    Map<String, Integer> perRef = new HashMap<>();
    hub.recalls().forEach(r -> perRef.merge(r.clientRef(), 1, Integer::sum));
    int once = (int) perRef.values().stream().filter(n -> n == 1).count();
    int twice = (int) perRef.values().stream().filter(n -> n > 1).count();
    int extra = perRef.values().stream().mapToInt(n -> n - 1).sum();
    return new Outcome(recalls, once, twice, recalls - perRef.size(), extra, hub.calls());
  }

  /** What not to do: retry the recall on any failure. */
  private static final class BlindRetry {
    private final HubTransport transport;
    private final int attempts;
    private String session;

    BlindRetry(HubTransport transport, int attempts) {
      this.transport = transport;
      this.attempts = attempts;
    }

    void recall(String uetr, String clientRef, BigDecimal amount, String currency) throws HubException {
      for (int i = 0; i < attempts; i++) {
        try {
          if (session == null) {
            session = HubRecords.Response.decode(transport.exchange(
                new HubRecords.Request("LOGN", "BLIND", "", "", BigDecimal.ZERO, "", "").encode())).hubRef();
          }
          HubRecords.Response r = HubRecords.Response.decode(transport.exchange(
              new HubRecords.Request("RCLL", session, uetr, clientRef, amount, currency, "CUST").encode()));
          if (r.rc().equals("12")) {
            session = null;
            continue;
          }
          return;
        } catch (HubTransport.TransportFailure e) {
          // retry
        }
      }
      throw new HubException(HubException.Kind.UNAVAILABLE, "gave up");
    }
  }

  public static void main(String[] args) throws Exception {
    LegacyHub.Faults faults = new LegacyHub.Faults(0.10, 0.08, 25);
    int recalls = 2_000;
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("faults", faults);
    report.put("recallsPerRun", recalls);
    report.put("jobRetries", 3);
    List<Long> seeds = List.of(11L, 12L, 13L, 14L, 15L);
    report.put("seeds", seeds);
    Map<String, Object> outcomes = new LinkedHashMap<>();
    for (boolean check : List.of(true, false)) {
      int total = 0;
      int twice = 0;
      int extra = 0;
      int never = 0;
      int calls = 0;
      for (long seed : seeds) {
        Outcome o = run(check, faults, seed, recalls, 3);
        total += o.recalls();
        twice += o.recalledTwiceOrMore();
        extra += o.extraRecalls();
        never += o.neverRecalled();
        calls += o.hubCalls();
      }
      outcomes.put(check ? "checkBeforeAct" : "blindRetry", Map.of("recalls", total, "recalledTwiceOrMore", twice,
          "extraRecalls", extra, "neverRecalled", never, "hubCallsPerRecall", (double) calls / total));
    }
    report.put("outcomes", outcomes);
    Path out = Path.of(args.length > 0 ? args[0] : "reports/hub-reliability.json");
    Files.createDirectories(out.toAbsolutePath().getParent());
    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
    System.out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(outcomes));
  }
}
