package io.github.mkp.pfl.feel;

import java.math.BigDecimal;
import java.util.List;

/** Syntax tree of the FEEL subset used in gateway conditions. */
public sealed interface Expr {

  /** A literal number, string, boolean or null. */
  record Literal(Object value) implements Expr {}

  /** A variable reference such as {@code amount} or {@code payment.currency}. */
  record Path(List<String> segments) implements Expr {
    public Path {
      segments = List.copyOf(segments);
    }

    public String root() {
      return segments.get(0);
    }

    public String dotted() {
      return String.join(".", segments);
    }
  }

  /** {@code not(x)}. */
  record Not(Expr operand) implements Expr {}

  /** Unary minus. */
  record Negate(Expr operand) implements Expr {}

  /** A binary operation. */
  record Binary(Op op, Expr left, Expr right) implements Expr {}

  /** Binary operators, by precedence group. */
  enum Op {
    OR("or"),
    AND("and"),
    EQ("="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/");

    private final String symbol;

    Op(String symbol) {
      this.symbol = symbol;
    }

    public String symbol() {
      return symbol;
    }

    public boolean isComparison() {
      return this == EQ || this == NE || this == LT || this == LE || this == GT || this == GE;
    }
  }

  static Literal number(String text) {
    return new Literal(new BigDecimal(text));
  }
}
