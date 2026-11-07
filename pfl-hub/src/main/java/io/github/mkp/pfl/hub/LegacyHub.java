package io.github.mkp.pfl.hub;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the bank's payments hub, faithful to the parts that hurt: sessions expire after a
 * number of calls, it sheds load with 503s, and it sometimes accepts a recall and then loses the
 * answer. It does not de-duplicate recalls; sending one twice recalls the money twice.
 */
public final class LegacyHub {

  /**
   * Fault rates.
   *
   * @param overload share of calls refused before processing (HTTP 503)
   * @param lostAnswer share of calls processed whose answer never arrives
   * @param sessionCalls calls a session survives before it expires
   */
  public record Faults(double overload, double lostAnswer, int sessionCalls) {
    public static final Faults NONE = new Faults(0, 0, Integer.MAX_VALUE);
  }

  /** The hub refused the call before doing anything. */
  public static final class Overloaded extends RuntimeException {
    Overloaded() {
      super("503 service unavailable");
    }
  }

  /** The hub did the work; the answer is lost. */
  public static final class AnswerLost extends RuntimeException {
    AnswerLost() {
      super("answer lost after processing");
    }
  }

  /** A recall the hub recorded. */
  public record Recall(String hubReference, String uetr, String clientRef, BigDecimal amount) {}

  private final Map<String, Payment> payments = new HashMap<>();
  private final List<Recall> recalls = new ArrayList<>();
  private final Map<String, Integer> sessions = new HashMap<>();
  private final Faults faults;
  private final SplittableRandom random;
  private final AtomicInteger sequence = new AtomicInteger();
  private int calls;

  public LegacyHub(Faults faults, long seed) {
    this.faults = faults;
    this.random = new SplittableRandom(seed);
  }

  public synchronized LegacyHub add(Payment payment) {
    payments.put(payment.uetr(), payment);
    return this;
  }

  public synchronized List<Recall> recalls() {
    return List.copyOf(recalls);
  }

  public synchronized int calls() {
    return calls;
  }

  /** Handles one request line, or throws {@link Overloaded} or {@link AnswerLost}. */
  public synchronized String handle(String line) {
    calls++;
    if (random.nextDouble() < faults.overload()) {
      throw new Overloaded();
    }
    String answer = process(line);
    if (random.nextDouble() < faults.lostAnswer()) {
      throw new AnswerLost();
    }
    return answer;
  }

  private String process(String line) {
    HubRecords.Request req;
    try {
      req = HubRecords.Request.decode(line);
    } catch (IllegalArgumentException e) {
      return HubRecords.Response.code("16", "MALFORMED RECORD").encode();
    }
    if (req.txn().equals("LOGN")) {
      String token = String.format("S%011d", sequence.incrementAndGet());
      sessions.put(token, 0);
      return new HubRecords.Response("00", "", token, BigDecimal.ZERO, "", "", "", "SESSION OPEN").encode();
    }
    Integer used = sessions.get(req.session());
    if (used == null || used >= faults.sessionCalls()) {
      sessions.remove(req.session());
      return HubRecords.Response.code("12", "SESSION EXPIRED").encode();
    }
    sessions.put(req.session(), used + 1);
    return switch (req.txn()) {
      case "LKUP" -> lookup(req);
      case "RCLL" -> recall(req);
      case "STAT" -> status(req);
      default -> HubRecords.Response.code("16", "UNKNOWN TXN " + req.txn()).encode();
    };
  }

  private String lookup(HubRecords.Request req) {
    Payment p = payments.get(req.uetr());
    if (p == null) {
      return HubRecords.Response.code("04", "NO PAYMENT FOR UETR").encode();
    }
    return new HubRecords.Response("00", p.uetr(), p.hubReference(), p.amount(), p.currency(),
        p.valueDate().format(DateTimeFormatter.BASIC_ISO_DATE), p.status(), "FOUND").encode();
  }

  private String recall(HubRecords.Request req) {
    Payment p = payments.get(req.uetr());
    if (p == null) {
      return HubRecords.Response.code("04", "NO PAYMENT FOR UETR").encode();
    }
    if (p.amount().compareTo(req.amount()) != 0 || !p.currency().equals(req.currency())) {
      return HubRecords.Response.code("16", "AMOUNT OR CURRENCY MISMATCH").encode();
    }
    if (!p.status().equals("ST")) {
      return HubRecords.Response.code("16", "NOT SETTLED, CANNOT RECALL").encode();
    }
    String ref = String.format("RC%010d", sequence.incrementAndGet());
    recalls.add(new Recall(ref, p.uetr(), req.clientRef(), req.amount()));
    return new HubRecords.Response("00", p.uetr(), ref, req.amount(), p.currency(),
        LocalDate.of(2026, 7, 6).format(DateTimeFormatter.BASIC_ISO_DATE), "RC", "RECALL SENT").encode();
  }

  private String status(HubRecords.Request req) {
    for (Recall r : recalls) {
      if (r.clientRef().equals(req.clientRef())) {
        return new HubRecords.Response("00", r.uetr(), r.hubReference(), r.amount(), "", "", "RC", "RECALL ON FILE").encode();
      }
    }
    return HubRecords.Response.code("04", "NO RECALL FOR CLIENT REF").encode();
  }
}
