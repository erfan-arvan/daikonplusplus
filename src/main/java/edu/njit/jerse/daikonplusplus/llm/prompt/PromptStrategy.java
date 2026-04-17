package edu.njit.jerse.daikonplusplus.llm.prompt;

public interface PromptStrategy {

  String name();

  Prompt buildPrompt(PromptContext ctx);
}
