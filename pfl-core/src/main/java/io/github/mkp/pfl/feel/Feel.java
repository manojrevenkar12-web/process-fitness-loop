package io.github.mkp.pfl.feel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser and evaluator for the part of FEEL that gateway conditions use: literals, variable paths,
 * comparison, arithmetic, {@code and}, {@code or} and {@code not(...)}.
 *
 * <p>Evaluation follows FEEL's three-valued logic, because that is what decides which way a Zeebe
 * gateway goes: comparing with a missing variable gives {@code null}, {@code false and null} is
 * {@code false}, {@code true and null} is {@code null}. A gateway whose conditions are all {@code
 * null} or {@code false} and that has no default flow raises an incident in Zeebe; the simulator
 * does the same.
 */
public final class Feel {

  private Feel() {}

  /** Parses a Zeebe expression, which must start with {@code =}. */
  public static Expr parse(String expression) {
    if (expression == null) {
      throw new FeelException(FeelException.Kind.SYNTAX, "no expression", 0);
    }
    String trimmed = expression.strip();
    if (!trimmed.startsWith("=")) {
      throw new FeelException(
          FeelException.Kind.SYNTAX, "a condition must be an expression starting with '='", 0);
    }
    Parser parser = new Parser(tokenize(trimmed.substring(1)));
    Expr expr = parser.expression();
    parser.expectEnd();
    return expr;
  }

  /** Evaluates to a {@link BigDecimal}, {@link String}, {@link Boolean} or {@code null}. */
  public static Object evaluate(Expr expr, Map<String, ?> variables) {
    return switch (expr) {
      case Expr.Literal literal -> literal.value();
      case Expr.Path path -> resolve(path, variables);
      case Expr.Not not -> {
        Object value = evaluate(not.operand(), variables);
        yield value instanceof Boolean b ? !b : null;
      }
      case Expr.Negate negate -> {
        Object value = evaluate(negate.operand(), variables);
        yield value instanceof BigDecimal n ? n.negate() : null;
      }
      case Expr.Binary binary -> binary(binary, variables);
    };
  }

  /**
   * Evaluates a condition the way Zeebe uses it: {@link Boolean#TRUE}, {@link Boolean#FALSE}, or
   * {@code null} when the result is not a boolean.
   */
  public static Boolean test(Expr expr, Map<String, ?> variables) {
    Object value = evaluate(expr, variables);
    return value instanceof Boolean b ? b : null;
  }

  /** Root names of the variables an expression reads, in order of appearance. */
  public static Set<String> variables(Expr expr) {
    Set<String> names = new LinkedHashSet<>();
    collect(expr, names);
    return names;
  }

  private static void collect(Expr expr, Set<String> names) {
    switch (expr) {
      case Expr.Path path -> names.add(path.root());
      case Expr.Not not -> collect(not.operand(), names);
      case Expr.Negate negate -> collect(negate.operand(), names);
      case Expr.Binary binary -> {
        collect(binary.left(), names);
        collect(binary.right(), names);
      }
      case Expr.Literal literal -> {
        // no variables
      }
    }
  }

  private static Object resolve(Expr.Path path, Map<String, ?> variables) {
    Object current = variables.get(path.root());
    for (int i = 1; i < path.segments().size(); i++) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(path.segments().get(i));
    }
    return normalise(current);
  }

  /** Brings Java values into FEEL's value space: every number becomes a BigDecimal. */
  public static Object normalise(Object value) {
    return switch (value) {
      case null -> null;
      case BigDecimal n -> n;
      case Integer n -> BigDecimal.valueOf(n);
      case Long n -> BigDecimal.valueOf(n);
      case Double n -> BigDecimal.valueOf(n);
      case Float n -> BigDecimal.valueOf(n.doubleValue());
      case Short n -> BigDecimal.valueOf(n);
      case java.math.BigInteger n -> new BigDecimal(n);
      default -> value;
    };
  }

  private static Object binary(Expr.Binary binary, Map<String, ?> variables) {
    Expr.Op op = binary.op();
    if (op == Expr.Op.AND || op == Expr.Op.OR) {
      Object left = evaluate(binary.left(), variables);
      Object right = evaluate(binary.right(), variables);
      Boolean l = left instanceof Boolean b ? b : null;
      Boolean r = right instanceof Boolean b ? b : null;
      if (op == Expr.Op.AND) {
        if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) {
          return false;
        }
        return l != null && r != null ? Boolean.TRUE : null;
      }
      if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) {
        return true;
      }
      return l != null && r != null ? Boolean.FALSE : null;
    }
    Object left = evaluate(binary.left(), variables);
    Object right = evaluate(binary.right(), variables);
    if (op == Expr.Op.EQ || op == Expr.Op.NE) {
      Boolean equal = equal(left, right);
      if (equal == null) {
        return null;
      }
      return op == Expr.Op.EQ ? equal : !equal;
    }
    if (op.isComparison()) {
      Integer cmp = compare(left, right);
      if (cmp == null) {
        return null;
      }
      return switch (op) {
        case LT -> cmp < 0;
        case LE -> cmp <= 0;
        case GT -> cmp > 0;
        case GE -> cmp >= 0;
        default -> throw new IllegalStateException(op.name());
      };
    }
    if (!(left instanceof BigDecimal l) || !(right instanceof BigDecimal r)) {
      if (op == Expr.Op.ADD && left instanceof String ls && right instanceof String rs) {
        return ls + rs;
      }
      return null;
    }
    return switch (op) {
      case ADD -> l.add(r);
      case SUB -> l.subtract(r);
      case MUL -> l.multiply(r);
      case DIV -> r.signum() == 0 ? null : l.divide(r, MathContext.DECIMAL128);
      default -> throw new IllegalStateException(op.name());
    };
  }

  private static Boolean equal(Object left, Object right) {
    if (left == null || right == null) {
      return left == null && right == null;
    }
    if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
      return l.compareTo(r) == 0;
    }
    if (left.getClass() != right.getClass()) {
      return null;
    }
    return left.equals(right);
  }

  private static Integer compare(Object left, Object right) {
    if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
      return l.compareTo(r);
    }
    if (left instanceof String l && right instanceof String r) {
      return l.compareTo(r);
    }
    return null;
  }

  // ---------------------------------------------------------------- English rendering

  /** Renders a condition in words for a business reader, e.g. "amount is more than 10000". */
  public static String toEnglish(Expr expr) {
    return english(expr, false);
  }

  private static String english(Expr expr, boolean nested) {
    return switch (expr) {
      case Expr.Literal literal -> literalText(literal.value());
      case Expr.Path path -> humanise(path.dotted());
      case Expr.Not not -> "it is not the case that " + english(not.operand(), true);
      case Expr.Negate negate -> "minus " + english(negate.operand(), true);
      case Expr.Binary binary -> {
        String left = english(binary.left(), true);
        String right = english(binary.right(), true);
        String text =
            switch (binary.op()) {
              case AND -> left + " and " + right;
              case OR -> left + " or " + right;
              case EQ ->
                  isBooleanLiteral(binary.right())
                      ? (Boolean.TRUE.equals(((Expr.Literal) binary.right()).value())
                          ? left
                          : "not " + left)
                      : left + " is " + right;
              case NE -> left + " is not " + right;
              case LT -> left + " is less than " + right;
              case LE -> left + " is at most " + right;
              case GT -> left + " is more than " + right;
              case GE -> left + " is at least " + right;
              case ADD -> left + " plus " + right;
              case SUB -> left + " minus " + right;
              case MUL -> left + " times " + right;
              case DIV -> left + " divided by " + right;
            };
        boolean logical = binary.op() == Expr.Op.AND || binary.op() == Expr.Op.OR;
        yield nested && logical ? "(" + text + ")" : text;
      }
    };
  }

  private static boolean isBooleanLiteral(Expr expr) {
    return expr instanceof Expr.Literal l && l.value() instanceof Boolean;
  }

  private static String literalText(Object value) {
    return switch (value) {
      case null -> "empty";
      case String s -> "\"" + s + "\"";
      case BigDecimal n -> n.stripTrailingZeros().toPlainString();
      default -> value.toString();
    };
  }

  /** {@code infoComplete} becomes "info complete", {@code hub_status} becomes "hub status". */
  static String humanise(String name) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '_' || c == '.') {
        out.append(' ');
      } else if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(name.charAt(i - 1))) {
        out.append(' ').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  // ---------------------------------------------------------------- tokenizer and parser

  private enum T {
    NUMBER,
    STRING,
    NAME,
    OP,
    LPAREN,
    RPAREN,
    DOT,
    END
  }

  private record Token(T type, String text, int position) {}

  private static List<Token> tokenize(String source) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int offset = 1; // the stripped leading '='
    while (i < source.length()) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (Character.isDigit(c)) {
        int start = i;
        while (i < source.length() && Character.isDigit(source.charAt(i))) {
          i++;
        }
        if (i + 1 < source.length() && source.charAt(i) == '.' && Character.isDigit(source.charAt(i + 1))) {
          i++;
          while (i < source.length() && Character.isDigit(source.charAt(i))) {
            i++;
          }
        }
        tokens.add(new Token(T.NUMBER, source.substring(start, i), start + offset));
      } else if (c == '"') {
        int start = i++;
        StringBuilder text = new StringBuilder();
        boolean closed = false;
        while (i < source.length()) {
          char s = source.charAt(i++);
          if (s == '\\' && i < source.length()) {
            text.append(source.charAt(i++));
          } else if (s == '"') {
            closed = true;
            break;
          } else {
            text.append(s);
          }
        }
        if (!closed) {
          throw new FeelException(FeelException.Kind.SYNTAX, "unterminated string", start + offset);
        }
        tokens.add(new Token(T.STRING, text.toString(), start + offset));
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < source.length()
            && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
          i++;
        }
        tokens.add(new Token(T.NAME, source.substring(start, i), start + offset));
      } else if (c == '(') {
        tokens.add(new Token(T.LPAREN, "(", i++ + offset));
      } else if (c == ')') {
        tokens.add(new Token(T.RPAREN, ")", i++ + offset));
      } else if (c == '.') {
        tokens.add(new Token(T.DOT, ".", i++ + offset));
      } else if (c == '<' || c == '>' || c == '!') {
        int start = i++;
        if (i < source.length() && source.charAt(i) == '=') {
          i++;
        } else if (c == '!') {
          throw new FeelException(FeelException.Kind.SYNTAX, "'!' must be followed by '='", start + offset);
        }
        tokens.add(new Token(T.OP, source.substring(start, i), start + offset));
      } else if (c == '=' || c == '+' || c == '-' || c == '*' || c == '/') {
        tokens.add(new Token(T.OP, String.valueOf(c), i++ + offset));
      } else {
        throw new FeelException(
            FeelException.Kind.UNSUPPORTED, "character '" + c + "' is outside the supported FEEL subset", i + offset);
      }
    }
    tokens.add(new Token(T.END, "", source.length() + offset));
    return tokens;
  }

  private static final class Parser {
    private final List<Token> tokens;
    private int index;

    Parser(List<Token> tokens) {
      this.tokens = tokens;
    }

    Token peek() {
      return tokens.get(index);
    }

    Token next() {
      return tokens.get(index++);
    }

    boolean isKeyword(String word) {
      Token t = peek();
      return t.type() == T.NAME && t.text().equals(word);
    }

    void expectEnd() {
      Token t = peek();
      if (t.type() != T.END) {
        throw new FeelException(FeelException.Kind.SYNTAX, "unexpected '" + t.text() + "'", t.position());
      }
    }

    Expr expression() {
      Expr left = and();
      while (isKeyword("or")) {
        next();
        left = new Expr.Binary(Expr.Op.OR, left, and());
      }
      return left;
    }

    Expr and() {
      Expr left = comparison();
      while (isKeyword("and")) {
        next();
        left = new Expr.Binary(Expr.Op.AND, left, comparison());
      }
      return left;
    }

    Expr comparison() {
      Expr left = additive();
      Token t = peek();
      if (t.type() == T.OP) {
        Expr.Op op =
            switch (t.text()) {
              case "=" -> Expr.Op.EQ;
              case "!=" -> Expr.Op.NE;
              case "<" -> Expr.Op.LT;
              case "<=" -> Expr.Op.LE;
              case ">" -> Expr.Op.GT;
              case ">=" -> Expr.Op.GE;
              default -> null;
            };
        if (op != null) {
          next();
          Expr right = additive();
          Token after = peek();
          if (after.type() == T.OP && List.of("=", "!=", "<", "<=", ">", ">=").contains(after.text())) {
            throw new FeelException(
                FeelException.Kind.SYNTAX, "comparisons cannot be chained", after.position());
          }
          return new Expr.Binary(op, left, right);
        }
      }
      return left;
    }

    Expr additive() {
      Expr left = multiplicative();
      while (peek().type() == T.OP && (peek().text().equals("+") || peek().text().equals("-"))) {
        Expr.Op op = next().text().equals("+") ? Expr.Op.ADD : Expr.Op.SUB;
        left = new Expr.Binary(op, left, multiplicative());
      }
      return left;
    }

    Expr multiplicative() {
      Expr left = unary();
      while (peek().type() == T.OP && (peek().text().equals("*") || peek().text().equals("/"))) {
        Expr.Op op = next().text().equals("*") ? Expr.Op.MUL : Expr.Op.DIV;
        left = new Expr.Binary(op, left, unary());
      }
      return left;
    }

    Expr unary() {
      if (peek().type() == T.OP && peek().text().equals("-")) {
        next();
        return new Expr.Negate(unary());
      }
      return primary();
    }

    Expr primary() {
      Token t = next();
      switch (t.type()) {
        case NUMBER:
          return new Expr.Literal(new BigDecimal(t.text()));
        case STRING:
          return new Expr.Literal(t.text());
        case LPAREN: {
          Expr inner = expression();
          Token close = next();
          if (close.type() != T.RPAREN) {
            throw new FeelException(FeelException.Kind.SYNTAX, "missing ')'", close.position());
          }
          return inner;
        }
        case NAME:
          return name(t);
        case END:
          throw new FeelException(FeelException.Kind.SYNTAX, "the expression ends too early", t.position());
        default:
          throw new FeelException(FeelException.Kind.SYNTAX, "unexpected '" + t.text() + "'", t.position());
      }
    }

    Expr name(Token t) {
      switch (t.text()) {
        case "true":
          return new Expr.Literal(Boolean.TRUE);
        case "false":
          return new Expr.Literal(Boolean.FALSE);
        case "null":
          return new Expr.Literal(null);
        case "and":
        case "or":
          throw new FeelException(FeelException.Kind.SYNTAX, "'" + t.text() + "' needs a left operand", t.position());
        default:
          break;
      }
      if (peek().type() == T.LPAREN) {
        if (!t.text().equals("not")) {
          throw new FeelException(
              FeelException.Kind.UNSUPPORTED, "function '" + t.text() + "' is outside the supported FEEL subset", t.position());
        }
        next();
        Expr inner = expression();
        Token close = next();
        if (close.type() != T.RPAREN) {
          throw new FeelException(FeelException.Kind.SYNTAX, "missing ')'", close.position());
        }
        return new Expr.Not(inner);
      }
      List<String> segments = new ArrayList<>();
      segments.add(t.text());
      while (peek().type() == T.DOT) {
        next();
        Token part = next();
        if (part.type() != T.NAME) {
          throw new FeelException(FeelException.Kind.SYNTAX, "expected a name after '.'", part.position());
        }
        segments.add(part.text());
      }
      return new Expr.Path(segments);
    }
  }
}
