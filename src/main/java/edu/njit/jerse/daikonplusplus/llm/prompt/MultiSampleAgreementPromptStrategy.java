package edu.njit.jerse.daikonplusplus.llm.prompt;

public final class MultiSampleAgreementPromptStrategy extends AbstractPromptStrategy {

  @Override
  public String name() {
    return "multi_sample";
  }

  @Override
  protected String extraUserInstructionsAfterContext(PromptContext ctx) {
    return """
        Within this single response, create three internal drafts with different focuses:
        - Draft A: focus on simple parameter/field constraints.
        - Draft B: focus on relations involving 'result' (if METHOD_EXIT) and parameters.
        - Draft C: focus on safe, pure method-call predicates (if any are implied by context).

        Then keep only invariants that appear in at least two drafts, or are clearly implied by context and non-redundant.
        Return ONLY the final agreed JSON. Do not output the drafts.
        """;
  }
}
