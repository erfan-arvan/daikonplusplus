package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Strategy for constructing prompts used to query the LLM.
 */
public interface PromptStrategy {

  /**
   * @return name of the strategy (e.g., "baseline", "fewshot")
   */
  String name();

  /**
   * Builds a prompt for a given program point context.
   *
   * @param ctx prompt context
   * @return constructed prompt (system + user messages)
   */
  Prompt buildPrompt(PromptContext ctx);
}
