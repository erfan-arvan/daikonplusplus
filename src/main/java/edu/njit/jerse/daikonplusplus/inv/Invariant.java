package edu.njit.jerse.daikonplusplus.inv;

import java.util.Objects;

/**
 * Represents a candidate or inferred invariant as a Java-style expression string, along with its
 * validation status.
 */
public class Invariant {

  /** The actual invariant expression, e.g., "x > 0". */
  private final String expression;

  /** Whether this invariant has been falsified by dynamic evidence. */
  private boolean falsified;

  /**
   * Constructs a new invariant with the given expression. By default, it is assumed to be valid
   * (not falsified).
   *
   * @param expression The Java-style Boolean expression representing the invariant.
   */
  public Invariant(String expression) {
    this.expression = expression;
    this.falsified = false;
  }

  /** Returns the invariant expression string. */
  public String getExpression() {
    return expression;
  }

  /** Returns whether this invariant has been falsified. */
  public boolean isFalsified() {
    return falsified;
  }

  /** Marks this invariant as falsified. */
  public void markFalsified() {
    this.falsified = true;
  }

  /** Clears the falsified flag (i.e., marks it as valid again). */
  public void resetFalsified() {
    this.falsified = false;
  }

  /** Returns a string representation (with optional status). */
  @Override
  public String toString() {
    return expression + (falsified ? "  [FALSIFIED]" : "");
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Invariant other)) return false;
    return Objects.equals(this.expression, other.expression) && this.falsified == other.falsified;
  }

  @Override
  public int hashCode() {
    return Objects.hash(expression, falsified);
  }
}
