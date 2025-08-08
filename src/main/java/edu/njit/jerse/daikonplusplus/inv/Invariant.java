package edu.njit.jerse.daikonplusplus.inv;

import java.util.Objects;

/** Represents a candidate or inferred invariant as a Java-style expression string. */
public class Invariant {

  /** The actual invariant expression, e.g., "x > 0". */
  private final String expression;

  /** Whether this invariant has been falsified by dynamic evidence (e.g., a test case). */
  private boolean falsified;

  /**
   * Constructs a new invariant with the given expression.
   *
   * @param expression Java-style Boolean expression representing the invariant.
   */
  public Invariant(String expression) {
    this.expression = expression;
  }

  /** Returns the raw expression string. */
  public String getExpression() {
    return expression;
  }

  /** Returns a user-friendly string representation of this invariant. */
  @Override
  public String toString() {
    return expression;
  }

  /** Equality based on expression content. */
  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Invariant other)) return false;
    return Objects.equals(this.expression, other.expression);
  }

  /** Hash code based on the expression. */
  @Override
  public int hashCode() {
    return Objects.hash(expression);
  }
}
