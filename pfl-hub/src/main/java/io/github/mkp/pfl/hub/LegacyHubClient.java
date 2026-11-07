package io.github.mkp.pfl.hub;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Client for the payments hub.
 *
 * <p>The hub does not de-duplicate recalls, and it sometimes accepts one and then loses the answer.
 * Retrying blindly therefore recalls the money twice. This client checks before it acts: before
 * every recall it asks the hub whether a recall with this client reference is already on file, and
 * only sends one if not. The client reference comes from the process instance and element, so the
 * check also holds across job retries and worker restarts.
 */
public final class LegacyHubClient {

  /** What a recall produced. */
  public record RecallReceipt(String hubReference, boolean alreadyOnFile, int attempts) {}

  private final HubTransport transport;
  private final String user;
  private final int maxAttempts;
  private final Duration backoff;
  private String session;
  private int sentRecalls;

  public LegacyHubClient(HubTransport transport, String user, int maxAttempts, Duration backoff) {
    this.transport = transport;
    this.user = user;
    this.maxAttempts = maxAttempts;
    this.backoff = backoff;
  }

  /** How many RCLL transactions this client put on the wire, for tests and metrics. */
  public synchronized int sentRecalls() {
    return sentRecalls;
  }

  public synchronized Payment lookup(String uetr) throws HubException {
    HubRecords.Response r = call(new HubRecords.Request("LKUP", null, uetr, "", BigDecimal.ZERO, "", ""), true);
    return switch (r.rc()) {
      case "00" -> new Payment(r.uetr(), r.hubRef(), r.amount(), r.currency(),
          LocalDate.parse(r.valueDate(), DateTimeFormatter.BASIC_ISO_DATE), r.status());
      case "04" -> throw new HubException(HubException.Kind.NOT_FOUND, "the hub has no payment with UETR " + uetr);
      default -> throw unexpected(r);
    };
  }

  public synchronized RecallReceipt recall(String uetr, String clientRef, BigDecimal amount, String currency) throws HubException {
    int attempts = 0;
    String lastProblem = "";
    while (attempts < maxAttempts) {
      attempts++;
      try {
        // check before act: a recall on file for this reference means an earlier attempt got through
        HubRecords.Response status = exchange(new HubRecords.Request("STAT", null, "", clientRef, BigDecimal.ZERO, "", ""));
        if (status.rc().equals("00")) {
          return new RecallReceipt(status.hubRef(), true, attempts);
        }
        if (!status.rc().equals("04")) {
          throw unexpected(status);
        }
        sentRecalls++;
        HubRecords.Response r = exchange(new HubRecords.Request("RCLL", null, uetr, clientRef, amount, currency, "CUST"));
        switch (r.rc()) {
          case "00":
            return new RecallReceipt(r.hubRef(), false, attempts);
          case "04":
            throw new HubException(HubException.Kind.NOT_FOUND, "the hub has no payment with UETR " + uetr);
          case "16":
            throw new HubException(HubException.Kind.REJECTED, "the hub rejected the recall: " + r.text());
          default:
            throw unexpected(r);
        }
      } catch (HubTransport.TransportFailure e) {
        lastProblem = e.getMessage();
        pause(attempts);
      }
    }
    throw new HubException(HubException.Kind.UNAVAILABLE, "no answer from the hub after " + attempts + " attempts: " + lastProblem);
  }

  /** One request with session handling: an expired session is renewed and the request repeated. */
  private HubRecords.Response exchange(HubRecords.Request request) throws HubTransport.TransportFailure, HubException {
    for (int i = 0; i < 2; i++) {
      if (session == null) {
        login();
      }
      HubRecords.Request withSession = new HubRecords.Request(request.txn(), session, request.uetr(), request.clientRef(),
          request.amount(), request.currency(), request.reason());
      HubRecords.Response r = HubRecords.Response.decode(transport.exchange(withSession.encode()));
      if (!r.rc().equals("12")) {
        return r;
      }
      session = null;
    }
    throw new HubException(HubException.Kind.UNAVAILABLE, "the hub keeps expiring new sessions");
  }

  /** Safe transactions (lookups) are simply retried on any transport failure. */
  private HubRecords.Response call(HubRecords.Request request, boolean idempotent) throws HubException {
    String lastProblem = "";
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return exchange(request);
      } catch (HubTransport.TransportFailure e) {
        if (!idempotent && e.ambiguous()) {
          throw new HubException(HubException.Kind.UNAVAILABLE, "ambiguous failure on a non-idempotent call: " + e.getMessage());
        }
        lastProblem = e.getMessage();
        pause(attempt);
      }
    }
    throw new HubException(HubException.Kind.UNAVAILABLE, "no answer from the hub after " + maxAttempts + " attempts: " + lastProblem);
  }

  private void login() throws HubTransport.TransportFailure, HubException {
    HubRecords.Response r = HubRecords.Response.decode(
        transport.exchange(new HubRecords.Request("LOGN", user, "", "", BigDecimal.ZERO, "", "").encode()));
    if (!r.rc().equals("00")) {
      throw new HubException(HubException.Kind.UNAVAILABLE, "login refused: " + r.text());
    }
    session = r.hubRef();
  }

  private void pause(int attempt) {
    long millis = backoff.toMillis() * (1L << Math.min(attempt - 1, 6));
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static HubException unexpected(HubRecords.Response r) {
    return new HubException(r.rc().equals("16") ? HubException.Kind.REJECTED : HubException.Kind.UNAVAILABLE,
        "hub answered " + r.rc() + ": " + r.text());
  }
}
