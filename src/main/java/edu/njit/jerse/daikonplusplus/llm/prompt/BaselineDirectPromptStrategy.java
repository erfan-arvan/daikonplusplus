package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Baseline prompt strategy with no additional modifications.
 *
 * <p>Uses the default prompt structure defined in {@link AbstractPromptStrategy}.
 */
public final class BaselineDirectPromptStrategy extends AbstractPromptStrategy {
  @Override
  public String name() {
    return "baseline";
  }
}
