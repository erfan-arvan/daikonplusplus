package edu.njit.jerse.daikonplusplus.llm.prompt;

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
