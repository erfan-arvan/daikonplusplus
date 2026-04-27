package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Prompt strategy that encourages the model to internally validate and refine
 * generated invariants before producing the final output.
 *
 * <p>The model is instructed to check each candidate invariant for syntactic
 * validity, scope correctness, evaluability, and redundancy, and to remove or
 * revise invalid candidates.
 *
 * <p>This aims to improve the quality and correctness of invariants without
 * requiring external post-processing.
 */
public final class SelfRefinementPromptStrategy extends AbstractPromptStrategy {

  @Override
  public String name() {
    return "self_refine";
  }

  @Override
  protected String extraUserInstructionsAfterContext(PromptContext ctx) {
    return """
        After drafting candidates, internally verify each invariant:
        - Valid Java boolean expression?
        - Uses only in-scope names?
        - Evaluable at this program point (no locals; add null checks only when required)?
        - Side-effect free?
        - Not redundant or trivially true?

        Remove or revise any invariant that fails these checks.
        Return ONLY the final JSON (no intermediate drafts).
        """;
  }
}
