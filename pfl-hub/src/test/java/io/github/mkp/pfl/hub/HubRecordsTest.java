package io.github.mkp.pfl.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class HubRecordsTest {

  @Test
  void requestRoundTripsAtExactWidth() {
    HubRecords.Request r = new HubRecords.Request("RCLL", "S00000000042", "eb6305c9-1f7f-49de-aed0-16487c27b42d",
        "PFL0123456789ABC", new BigDecimal("12500.50"), "EUR", "CUST");
    String line = r.encode();
    assertEquals(HubRecords.REQUEST_LENGTH, line.length());
    assertEquals(r, HubRecords.Request.decode(line));
    assertEquals("000000001250050", line.substring(72, 87), "amount in minor units, zero padded");
  }

  @Test
  void responseRoundTripsAndTruncatesText() {
    HubRecords.Response r = new HubRecords.Response("00", "eb6305c9-1f7f-49de-aed0-16487c27b42d", "PH0000000001",
        new BigDecimal("0.01"), "USD", "20260706", "ST", "A message that is longer than thirty characters");
    String line = r.encode();
    assertEquals(HubRecords.RESPONSE_LENGTH, line.length());
    assertEquals("A message that is longer than ", HubRecords.Response.decode(line).text() + " ");
  }

  @Test
  void refusesWhatTheMainframeCannotTake() {
    assertThrows(IllegalArgumentException.class, () -> FixedWidth.text("Zürich", 10), "not ASCII");
    assertThrows(IllegalArgumentException.class, () -> FixedWidth.text("x".repeat(17), 16), "too long");
    assertThrows(IllegalArgumentException.class, () -> FixedWidth.amount(new BigDecimal("1.005"), 15), "sub-cent amount");
    assertThrows(IllegalArgumentException.class, () -> FixedWidth.amount(new BigDecimal("-1.00"), 15), "negative");
    assertThrows(IllegalArgumentException.class, () -> HubRecords.Request.decode("short"));
  }
}
