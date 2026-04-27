package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Minimal prompt strategy that directly asks the model to generate invariants
 * without additional guidance, constraints, or examples.
 *
 * <p>This strategy provides only basic instructions, the program point, in-scope
 * variables, and optional context. It is intended as a simple baseline for
 * comparison against more structured prompting approaches.
 */
public final class NaiveDirectPromptStrategy implements PromptStrategy {

  @Override
  public String name() {
    return "naive";
  }

  @Override
  public Prompt buildPrompt(PromptContext ctx) {
    String system =
        """
        You generate candidate invariants for Java program points.

        Output requirement:
        Return ONLY valid JSON in the following format:

        {
          "invariants": [
            { "expression": "<Java boolean expression>" }
          ]
        }
        """;

    String user =
        PromptRenderingUtil.renderProgramPointSection(ctx)
            + "\n\nIn-scope names:\n"
            + PromptRenderingUtil.formatScope(ctx.inScope())
            + "\n\nNote:\n"
            + "- These are base variables.\n"
            + "- Expressions derived from them (e.g., field accesses and method calls) are allowed if valid at this program point.\n"
            + "\n\n===== PROGRAM CONTEXT =====\n"
            + PromptRenderingUtil.renderContextBlock(ctx)
            + "\n===========================\n\n"
            + "Generate up to "
            + ctx.maxInvariants()
            + " candidate invariants that are valid at this program point.\n"
            + "Return ONLY the JSON.\n";

    return new Prompt(system, user);
  }
}
