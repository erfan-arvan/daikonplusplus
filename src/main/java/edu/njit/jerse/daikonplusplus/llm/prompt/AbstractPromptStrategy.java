package edu.njit.jerse.daikonplusplus.llm.prompt;

public abstract class AbstractPromptStrategy implements PromptStrategy {

  private static final boolean DEBUG_PRINT_FIRST_PROMPT = true;
  private static boolean printedOnce = false;

  @Override
  public Prompt buildPrompt(PromptContext ctx) {
    String system = buildSystemMessage(ctx);
    String user = buildUserMessage(ctx);

    //    if (DEBUG_PRINT_FIRST_PROMPT && !printedOnce) {
    printedOnce = true;
    String full =
        "===== FIRST PROMPT =====\n"
            + "----- SYSTEM -----\n"
            + system
            + "\n"
            + "----- USER -----\n"
            + user
            + "\n"
            + "========================\n";

    System.out.println(full);
    //    }

    return new Prompt(system, user);
  }

  protected String buildSystemMessage(PromptContext ctx) {
    return """
        You are a program analysis assistant.

        Task:
        Generate candidate invariants for a Java program point.

        Global rules:
        - Return ONLY JSON matching the required schema.
        - The JSON schema is:
          {
            "invariants": [
              { "expression": "<Java boolean expression>" }
            ]
          }
        - Each invariant must be a valid Java boolean expression.
        - Expressions must be valid at the program point.
        - Use only the listed in-scope names.
        - Do not introduce new variables, helper functions, or APIs.
        - Expressions must be side-effect free.
        """
        + extraSystemInstructions(ctx);
  }

  protected String buildUserMessage(PromptContext ctx) {
    StringBuilder sb = new StringBuilder();

    sb.append(PromptRenderingUtil.renderProgramPointSection(ctx)).append("\n\n");
    sb.append(PromptRenderingUtil.renderProgramPointExplanation(ctx)).append("\n");
    sb.append("In-scope names:\n");
    sb.append(PromptRenderingUtil.formatScope(ctx.inScope())).append("\n\n");
    sb.append(
        """
            Note:
            - These are base variables.
            - Expressions derived from them (e.g., field accesses and method calls) are allowed if valid at this program point.

            """);

    if (ctx.inScope().containsKey("result")) {
      sb.append(
          """
      Note about 'result':
      - 'result' is a symbolic name for the value returned by the method.
      - If the method has multiple return statements, 'result' refers to the value returned along any execution path.
      - Any invariant involving 'result' must hold for all possible return values of the method.

      """);
    }
    sb.append(
        """
        Constraints specific to this program point:
        - Single-line Java boolean expressions only.
        - Method calls are allowed and encouraged if callable using listed names.
        - Method calls may belong to the JDK or the project codebase.
        - Field accesses (e.g., obj.field) are allowed if accessible using listed names.
        - Do not invent methods or fields that are not available at this program point.
        - Add null checks ONLY when required for safe evaluation.
        - Do not use non-Java logical operators such as ==> or ⇒.
        - Logical implication (A ⇒ B) must be expressed as (!A || B) using standard Java boolean operators.
        - Prefer pure predicate-style method calls.
        - Avoid redundant or trivially true expressions.
        - Do NOT generate tautologies or self-comparisons (e.g., x == x, obj.method() == obj.method()).
        - Avoid semantically duplicate invariants that differ only by operand order or equivalent comparison form (e.g., a == b vs b == a, a <= b vs b >= a).
        - Do not focus solely on nullness-related invariants.
        - Prioritize invariants that capture meaningful relationships among the in-scope names and reflect the behavior of the method at this program point.
        """);

    String extraBeforeContext = extraUserInstructionsBeforeContext(ctx);
    if (!extraBeforeContext.isBlank()) {
      sb.append("\n").append(extraBeforeContext.strip()).append("\n");
    }

    sb.append("\n===== PROGRAM CONTEXT =====\n");
    sb.append("Consider the following context when generating invariants.\n");
    sb.append(PromptRenderingUtil.renderContextBlock(ctx)).append("\n");
    sb.append("===========================\n");

    String extraAfterContext = extraUserInstructionsAfterContext(ctx);
    if (!extraAfterContext.isBlank()) {
      sb.append("\n").append(extraAfterContext.strip()).append("\n");
    }

    sb.append("\nGenerate up to ")
        .append(ctx.maxInvariants())
        .append(" candidate invariants that are valid at this program point.\n");
    sb.append("Return ONLY the JSON.\n");

    return sb.toString();
  }

  protected String extraSystemInstructions(PromptContext ctx) {
    return "";
  }

  protected String extraUserInstructionsBeforeContext(PromptContext ctx) {
    return "";
  }

  protected String extraUserInstructionsAfterContext(PromptContext ctx) {
    return "";
  }
}
