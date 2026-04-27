package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Prompt strategy that augments the base system message with few-shot examples.
 *
 * <p>Examples are inserted into the system instructions via
 * {@link FewShotExampleProvider}, allowing the model to observe
 * input-output patterns alongside the general rules.
 */
public final class FewShotPromptStrategy extends AbstractPromptStrategy {

  @Override
  public String name() {
    return "fewshot";
  }

  @Override
  protected String extraSystemInstructions(PromptContext ctx) {
    return FewShotExampleProvider.getExamples(ctx);
  }
}
