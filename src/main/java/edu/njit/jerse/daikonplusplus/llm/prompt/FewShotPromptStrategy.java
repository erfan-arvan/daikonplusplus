package edu.njit.jerse.daikonplusplus.llm.prompt;

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
