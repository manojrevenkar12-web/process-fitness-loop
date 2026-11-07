package io.github.mkp.pfl.feel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FeelTest {

  private static final Map<String, Object> CASE = Map.of(
      "amount", 12_500,
      "currency", "EUR",
      "inquiryType", "RECALL",
      "infoComplete", true,
      "payment", Map.of("status", "SETTLED", "fee", 2.5));

  static List<Object[]> conditions() {
    return List.of(
        new Object[] {"=amount > 10000", true},
        new Object[] {"=amount > 12500", false},
        new Object[] {"=amount >= 12500", true},
        new Object[] {"=amount = 12500.00", true},
        new Object[] {"=inquiryType = \"RECALL\"", true},
        new Object[] {"=inquiryType != \"RECALL\"", false},
        new Object[] {"=infoComplete = true", true},
        new Object[] {"=infoComplete", true},
        new Object[] {"=not(infoComplete)", false},
        new Object[] {"=payment.status = \"SETTLED\" and amount > 10000", true},
        new Object[] {"=currency = \"USD\" or amount - 2500 = 10000", true},
        new Object[] {"=(amount + payment.fee) * 2 > 25000", true},
        new Object[] {"=amount / 0 > 1", null},
        new Object[] {"=-amount < 0", true});
  }

  @ParameterizedTest
  @MethodSource("conditions")
  void evaluatesConditionsLikeZeebe(String expression, Boolean expected) {
    assertEquals(expected, Feel.test(Feel.parse(expression), CASE));
  }

  @Test
  void missingVariablesFollowThreeValuedLogic() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("amount", 50);
    assertNull(Feel.test(Feel.parse("=missing > 5"), vars), "comparison with a missing value is null");
    assertEquals(Boolean.FALSE, Feel.test(Feel.parse("=missing > 5 and amount > 100"), vars), "false and null is false");
    assertNull(Feel.test(Feel.parse("=missing > 5 and amount > 10"), vars), "true and null is null");
    assertEquals(Boolean.TRUE, Feel.test(Feel.parse("=missing > 5 or amount > 10"), vars), "true or null is true");
    assertEquals(Boolean.TRUE, Feel.test(Feel.parse("=missing = null"), vars), "equality with null is defined");
    assertNull(Feel.test(Feel.parse("=amount = \"50\""), vars), "values of different types are not comparable");
  }

  @Test
  void numbersOfEveryJavaTypeCompareAsDecimals() {
    assertEquals(Boolean.TRUE, Feel.test(Feel.parse("=x = 3"), Map.of("x", 3L)));
    assertEquals(Boolean.TRUE, Feel.test(Feel.parse("=x = 3"), Map.of("x", new BigDecimal("3.000"))));
    assertEquals(Boolean.TRUE, Feel.test(Feel.parse("=x < 3.5"), Map.of("x", 3.25d)));
  }

  @Test
  void reportsSyntaxErrorsAndUnsupportedFeelSeparately() {
    assertEquals(FeelException.Kind.SYNTAX, assertThrows(FeelException.class, () -> Feel.parse("amount > 5")).kind());
    assertEquals(FeelException.Kind.SYNTAX, assertThrows(FeelException.class, () -> Feel.parse("=(amount > 5")).kind());
    assertEquals(FeelException.Kind.SYNTAX, assertThrows(FeelException.class, () -> Feel.parse("=amount >")).kind());
    assertEquals(FeelException.Kind.SYNTAX, assertThrows(FeelException.class, () -> Feel.parse("=a < b < c")).kind());
    assertEquals(FeelException.Kind.SYNTAX, assertThrows(FeelException.class, () -> Feel.parse("=\"open")).kind());
    assertEquals(FeelException.Kind.UNSUPPORTED, assertThrows(FeelException.class, () -> Feel.parse("=count(items) > 1")).kind());
    assertEquals(FeelException.Kind.UNSUPPORTED, assertThrows(FeelException.class, () -> Feel.parse("=x in [1,2]")).kind());
  }

  @Test
  void listsTheVariablesAConditionReads() {
    assertEquals(Set.of("payment", "amount"), Feel.variables(Feel.parse("=payment.status = \"X\" and amount > 1")));
  }

  @Test
  void writesConditionsInPlainEnglish() {
    assertEquals("amount is more than 10000", Feel.toEnglish(Feel.parse("=amount > 10000")));
    assertEquals("inquiry type is \"RECALL\"", Feel.toEnglish(Feel.parse("=inquiryType = \"RECALL\"")));
    assertEquals("info complete", Feel.toEnglish(Feel.parse("=infoComplete = true")));
    assertEquals("not info complete", Feel.toEnglish(Feel.parse("=infoComplete = false")));
    assertEquals("(amount is at least 5 and currency is \"EUR\") or vip",
        Feel.toEnglish(Feel.parse("=(amount >= 5 and currency = \"EUR\") or vip")));
  }
}
