package io.github.mkp.pfl.hub;

import java.math.BigDecimal;

/**
 * The PH/9 gateway protocol: one fixed-width request line, one fixed-width response line.
 *
 * <pre>
 * request   txn(4) session(16) uetr(36) clientRef(16) amount(15) currency(3) reason(4)      = 94
 * response  rc(2) uetr(36) hubRef(12) amount(15) currency(3) valueDate(8) status(2) text(30) = 108
 * </pre>
 *
 * Transactions: LOGN (session field carries the user, the token comes back in hubRef), LKUP
 * (find a payment by UETR), RCLL (request a recall; the hub does NOT de-duplicate), STAT (find a
 * recall by the client reference). Return codes: 00 ok, 04 not found, 12 session expired, 16
 * rejected by validation, 99 system error.
 */
public final class HubRecords {

  public static final int REQUEST_LENGTH = 94;
  public static final int RESPONSE_LENGTH = 108;

  private HubRecords() {}

  /** A request line. */
  public record Request(String txn, String session, String uetr, String clientRef, BigDecimal amount, String currency, String reason) {

    public String encode() {
      return FixedWidth.text(txn, 4) + FixedWidth.text(session, 16) + FixedWidth.text(uetr, 36)
          + FixedWidth.text(clientRef, 16) + FixedWidth.amount(amount == null ? BigDecimal.ZERO : amount, 15)
          + FixedWidth.text(currency, 3) + FixedWidth.text(reason, 4);
    }

    public static Request decode(String line) {
      if (line.length() != REQUEST_LENGTH) {
        throw new IllegalArgumentException("request must be " + REQUEST_LENGTH + " characters, not " + line.length());
      }
      return new Request(FixedWidth.slice(line, 0, 4).strip(), FixedWidth.slice(line, 4, 16).strip(),
          FixedWidth.slice(line, 20, 36).strip(), FixedWidth.slice(line, 56, 16).strip(),
          FixedWidth.parseAmount(FixedWidth.slice(line, 72, 15)), FixedWidth.slice(line, 87, 3).strip(),
          FixedWidth.slice(line, 90, 4).strip());
    }
  }

  /** A response line. */
  public record Response(String rc, String uetr, String hubRef, BigDecimal amount, String currency, String valueDate,
      String status, String text) {

    public String encode() {
      return FixedWidth.text(rc, 2) + FixedWidth.text(uetr, 36) + FixedWidth.text(hubRef, 12)
          + FixedWidth.amount(amount == null ? BigDecimal.ZERO : amount, 15) + FixedWidth.text(currency, 3)
          + FixedWidth.text(valueDate, 8) + FixedWidth.text(status, 2)
          + FixedWidth.text(text == null ? "" : text.length() > 30 ? text.substring(0, 30) : text, 30);
    }

    public static Response decode(String line) {
      if (line.length() != RESPONSE_LENGTH) {
        throw new IllegalArgumentException("response must be " + RESPONSE_LENGTH + " characters, not " + line.length());
      }
      return new Response(FixedWidth.slice(line, 0, 2), FixedWidth.slice(line, 2, 36).strip(),
          FixedWidth.slice(line, 38, 12).strip(), FixedWidth.parseAmount(FixedWidth.slice(line, 50, 15)),
          FixedWidth.slice(line, 65, 3).strip(), FixedWidth.slice(line, 68, 8).strip(), FixedWidth.slice(line, 76, 2).strip(),
          FixedWidth.slice(line, 78, 30).strip());
    }

    public static Response code(String rc, String text) {
      return new Response(rc, "", "", BigDecimal.ZERO, "", "", "", text);
    }
  }
}
