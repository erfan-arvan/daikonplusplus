package edu.njit.jerse.daikonplusplus.model;

import java.util.Map;
import java.util.Objects;

/** Raw invariant proposal (from LLM) as a Java boolean expression with optional metadata. */
public final class InvariantSpec {
  private final String expression;
  private final String rationale;
  private final Map<String, String> meta;

  public InvariantSpec(String expression, String rationale, Map<String, String> meta) {
    this.expression = Objects.requireNonNull(expression).trim();
    this.rationale = rationale == null ? "" : rationale;
    this.meta = meta;
  }

  /** Java boolean expression, expected to be side-effect free. */
  public String expression() {
    return expression;
  }

  /** Optional free-text explanation. */
  public String rationale() {
    return rationale;
  }

  /** Optional metadata key/value pairs. */
  public Map<String, String> meta() {
    return meta;
  }
}
