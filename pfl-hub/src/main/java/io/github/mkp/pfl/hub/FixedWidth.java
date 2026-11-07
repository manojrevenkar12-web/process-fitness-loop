package io.github.mkp.pfl.hub;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Field helpers for the hub's fixed-width records: left-aligned text, zero-padded numbers. */
final class FixedWidth {

  private FixedWidth() {}

  static String text(String value, int width) {
    String v = value == null ? "" : value;
    if (v.length() > width) {
      throw new IllegalArgumentException("'" + v + "' does not fit in " + width + " characters");
    }
    for (char c : v.toCharArray()) {
      if (c < 0x20 || c > 0x7e) {
        throw new IllegalArgumentException("the hub only accepts printable ASCII, not '" + v + "'");
      }
    }
    return v + " ".repeat(width - v.length());
  }

  /** Amounts travel as integer minor units with an implied two decimals. */
  static String amount(BigDecimal value, int width) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException("negative amount " + value);
    }
    long cents;
    try {
      cents = value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("amount " + value + " has more than two decimals or is too large", e);
    }
    String s = Long.toString(cents);
    if (s.length() > width) {
      throw new IllegalArgumentException("amount " + value + " does not fit in " + width + " digits");
    }
    return "0".repeat(width - s.length()) + s;
  }

  static BigDecimal parseAmount(String field) {
    String digits = field.strip();
    if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
      throw new IllegalArgumentException("amount field '" + field + "' is not numeric");
    }
    return new BigDecimal(digits).movePointLeft(2);
  }

  static String slice(String record, int from, int width) {
    if (record.length() < from + width) {
      throw new IllegalArgumentException("record of " + record.length() + " characters ends before field at " + from);
    }
    return record.substring(from, from + width);
  }
}
