package edu.njit.jerse.daikonplusplus.model;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a candidate invariant expressed as a Java boolean expression.
 *
 * <p>Includes the expression along with optional explanation and metadata.
 */
public final class InvariantSpec {
  private final String expression;
  private final String rationale;
  private final Map<String, String> meta;

  /**
   * Creates a new invariant specification.
   *
   * @param expression Java boolean expression
   * @param rationale optional explanation
   * @param meta optional metadata
   */
  public InvariantSpec(String expression, String rationale, Map<String, String> meta) {
    this.expression = Objects.requireNonNull(expression).trim();
    this.rationale = rationale == null ? "" : rationale;
    this.meta = meta;
  }

  /**
   * Returns the invariant expression.
   *
   * @return Java boolean expression
   */
  public String expression() {
    return expression;
  }

  /**
   * Returns the explanation associated with the invariant.
   *
   * @return rationale text (may be empty)
   */
  public String rationale() {
    return rationale;
  }

  /**
   * Returns metadata associated with the invariant.
   *
   * @return metadata map (may be null)
   */
  public Map<String, String> meta() {
    return meta;
  }
}
