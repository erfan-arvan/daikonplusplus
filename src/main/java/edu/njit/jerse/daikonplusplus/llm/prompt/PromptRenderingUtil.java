package edu.njit.jerse.daikonplusplus.llm.prompt;

import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.util.Map;
import java.util.StringJoiner;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utility methods for rendering prompt components into textual form.
 *
 * <p>This class is responsible for converting structured prompt inputs (such as scope, program
 * points, and optional context sections) into formatted strings suitable for inclusion in LLM
 * prompts.
 *
 * <p>All methods are stateless and operate purely on the provided inputs.
 */
final class PromptRenderingUtil {
  private PromptRenderingUtil() {}

  /**
   * Formats in-scope variables into a readable list.
   *
   * @param inScope mapping from variable names to their types
   * @return formatted string where each variable is rendered on its own line, or {@code "(none)"}
   *     if the scope is empty
   */
  static String formatScope(Map<String, String> inScope) {
    if (inScope == null || inScope.isEmpty()) {
      return "(none)";
    }

    StringJoiner sj = new StringJoiner("\n");
    for (Map.Entry<String, String> e : inScope.entrySet()) {
      sj.add("- " + e.getKey() + " : " + e.getValue());
    }
    return sj.toString();
  }

  /**
   * Renders the program point identifier and kind.
   *
   * @param ctx prompt context
   * @return formatted program point header
   */
  static String renderProgramPointSection(PromptContext ctx) {
    return "PROGRAM POINT: " + ctx.point().elementId() + " [" + ctx.point().kind().name() + "]";
  }

  /**
   * Produces a short explanation of the semantics of the program point.
   *
   * @param ctx prompt context
   * @return explanation describing what state is valid at the program point
   */
  static String renderProgramPointExplanation(PromptContext ctx) {
    if (ctx.point().kind() == ProgramPointKind.METHOD_ENTRY) {
      return """
          This program point represents the state immediately BEFORE the method executes.
          Only parameters and object state are defined.
          """;
    }

    return """
        This program point represents the state immediately BEFORE the method returns.
        Parameters, object state, and 'result' (if non-void) may be referenced.
        """;
  }

  /**
   * Renders all available contextual sections into a structured block.
   *
   * <p>Only non-empty context fields are included. Sections are separated by blank lines and
   * labeled with headers.
   *
   * @param ctx prompt context
   * @return formatted context block, or {@code "(none)"} if no context is available
   */
  static String renderContextBlock(PromptContext ctx) {
    StringBuilder sb = new StringBuilder();

    appendSection(sb, "Method Implementation", ctx.methodImplementation());
    appendSection(sb, "Method Javadoc", ctx.methodJavadoc());
    appendSection(sb, "Enclosing Class Documentation", ctx.enclosingClassDocumentation());
    appendSection(sb, "Type-Level Documentation", ctx.typeLevelDocumentation());
    //    appendSection(sb, "Called Methods", ctx.calleeDocumentation());
    appendSection(sb, "Call-Site Context", ctx.callSiteContext());
    appendSection(sb, "Input-Output Examples", ctx.inputOutputExamples());

    if (sb.length() == 0) {
      sb.append("(none)");
    }

    return sb.toString().strip();
  }

  /**
   * Appends a labeled section to the context block if the value is non-empty.
   *
   * @param sb string builder accumulating the context
   * @param title section title
   * @param value section content (may be null or blank)
   */
  private static void appendSection(StringBuilder sb, String title, @Nullable String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append("\n\n");
    }
    sb.append("[").append(title).append("]\n");
    sb.append(value.strip());
  }
}
