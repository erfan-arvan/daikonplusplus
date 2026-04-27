package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Prompt strategy that encourages internal reasoning before producing invariants.
 *
 * <p>Adds guidance to reason about scope, safety, and program constraints,
 * while requiring that only the final JSON output is returned.
 */
public final class ChainOfThoughtPromptStrategy extends AbstractPromptStrategy {

  @Override
  public String name() {
    return "cot";
  }

  @Override
  protected String extraUserInstructionsAfterContext(PromptContext ctx) {
    return """
        Before producing the JSON, think internally about:
        1) Which names are in-scope and their types.
        2) Which expressions are evaluable safely (add null checks only when required).
        3) What constraints are directly implied by the provided PROGRAM CONTEXT.
        4) Prefer simple relations between parameters, fields, and result and safe pure method calls.

        Do NOT include your reasoning in the output.
        """;
  }
}
