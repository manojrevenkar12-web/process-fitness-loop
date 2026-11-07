package io.github.mkp.pfl.world;

import io.github.mkp.pfl.util.Draws;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * The hidden parameters of one simulated bank. Every world seed gives a different bank: other
 * volumes, other step durations, other habits. The backtester never sees any of this; it only sees
 * the history the world exports.
 *
 * @param seed world seed
 * @param weekdayCases average inquiries per working day
 * @param weekendCases average inquiries per weekend day
 * @param typeMix share of RECALL, NON_RECEIPT and QUERY
 * @param emailShare share of inquiries that arrive by e-mail rather than SWIFT
 * @param completeFirstTime probability that a SWIFT / e-mail inquiry has all details on first contact
 * @param minutes true median minutes per step and actor, e.g. {@code register_inquiry/PERSON}
 * @param spread p90 over median, per step
 * @param complexitySigma spread of the hidden per-case complexity that slows every step of a case
 * @param replyMedianHours median hours until a sender supplies missing details
 * @param leadPickups times of day the team leads look at their queue
 * @param opsAvailable payments-ops people actually at work (the plan says 7)
 * @param urgentShare share of large recalls flagged urgent
 * @param skipWhenUrgent probability that an urgent large recall is sent before its approval
 * @param notFoundRate true share of hub lookups that do not find the payment
 * @param agentFalseIncomplete probability the intake agent calls a complete inquiry incomplete
 * @param agentFalseComplete probability the intake agent calls an incomplete inquiry complete
 * @param creditedRate true share of gpi traces that show the payment credited
 */
public record WorldParams(
    long seed, double weekdayCases, double weekendCases, Map<String, Double> typeMix, double emailShare,
    Map<String, Double> completeFirstTime, Map<String, Double> minutes, Map<String, Double> spread,
    double complexitySigma, double replyMedianHours, List<LocalTime> leadPickups, int opsAvailable,
    double urgentShare, double skipWhenUrgent, double notFoundRate, double agentFalseIncomplete,
    double agentFalseComplete, double creditedRate) {

  /** Steps that exist in some process variant, with their true median minutes before world variation. */
  private static final Map<String, Double> BASE_MINUTES = new TreeMap<>(Map.ofEntries(
      Map.entry("register_inquiry/PERSON", 9.0),
      Map.entry("request_info/PERSON", 6.0),
      Map.entry("lookup_payment/PERSON", 11.0),
      Map.entry("check_funds/PERSON", 8.0),
      Map.entry("second_approval/PERSON", 5.0),
      Map.entry("send_recall/PERSON", 7.0),
      Map.entry("trace_payment/PERSON", 15.0),
      Map.entry("notify_customer/PERSON", 5.0),
      Map.entry("answer_query/PERSON", 18.0),
      Map.entry("close_case/PERSON", 3.0),
      Map.entry("manual_lookup/PERSON", 15.0),
      Map.entry("approve_recall/PERSON", 5.0),
      Map.entry("investigate_trace/PERSON", 18.0),
      Map.entry("classify_inquiry/AGENT", 0.7),
      Map.entry("hub_lookup/SYSTEM", 0.25),
      Map.entry("send_recall/SYSTEM", 0.35),
      Map.entry("request_info/SYSTEM", 0.15),
      Map.entry("gpi_trace/SYSTEM", 0.3),
      Map.entry("notify_customer/SYSTEM", 0.05)));

  public WorldParams {
    typeMix = Map.copyOf(typeMix);
    completeFirstTime = Map.copyOf(completeFirstTime);
    minutes = Map.copyOf(minutes);
    spread = Map.copyOf(spread);
    leadPickups = List.copyOf(leadPickups);
  }

  public static WorldParams forSeed(long seed) {
    SplittableRandom r = Draws.rng(seed, "world");
    Map<String, Double> minutes = new TreeMap<>();
    Map<String, Double> spread = new TreeMap<>();
    for (Map.Entry<String, Double> e : BASE_MINUTES.entrySet()) {
      minutes.put(e.getKey(), e.getValue() * uniform(r, 0.8, 1.25));
      spread.put(e.getKey(), uniform(r, 1.8, 2.8));
    }
    double recall = uniform(r, 0.28, 0.40);
    double nonReceipt = uniform(r, 0.32, 0.45);
    List<LocalTime> pickups = r.nextBoolean()
        ? List.of(LocalTime.of(10, 0), LocalTime.of(15, 0))
        : List.of(LocalTime.of(9, 30), LocalTime.of(13, 0), LocalTime.of(16, 30));
    return new WorldParams(
        seed,
        uniform(r, 44, 56),
        uniform(r, 5, 10),
        Map.of("RECALL", recall, "NON_RECEIPT", nonReceipt, "QUERY", 1 - recall - nonReceipt),
        uniform(r, 0.2, 0.4),
        Map.of("SWIFT", uniform(r, 0.85, 0.95), "EMAIL", uniform(r, 0.45, 0.65)),
        minutes,
        spread,
        uniform(r, 0.25, 0.45),
        uniform(r, 10, 30),
        pickups,
        r.nextDouble() < 0.5 ? 6 : 7,
        uniform(r, 0.10, 0.20),
        uniform(r, 0.15, 0.35),
        uniform(r, 0.04, 0.10),
        uniform(r, 0.02, 0.06),
        uniform(r, 0.01, 0.04),
        uniform(r, 0.6, 0.8));
  }

  private static double uniform(SplittableRandom r, double lo, double hi) {
    return lo + (hi - lo) * r.nextDouble();
  }
}
