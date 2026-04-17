package edu.njit.jerse.daikonplusplus.llm.prompt;

public final class StepwiseInvariantDiscoveryPromptStrategy extends AbstractPromptStrategy {

  @Override
  public String name() {
    return "stepwise";
  }

  @Override
  protected String extraUserInstructionsAfterContext(PromptContext ctx) {
    return """
        Discover invariants in these internal steps (do not print the steps):
        Step 1: Propose simple unary invariants (single variable properties) ONLY if implied by context.
        Step 2: Propose relational invariants between two variables (including 'result' on METHOD_EXIT) ONLY if implied by context.
        Step 3: Propose invariants using method calls ONLY when the call is side-effect free, callable from the listed in-scope names, and safely evaluable (add null checks only when required).

        Then output only the final JSON.
        """;
  }
}
