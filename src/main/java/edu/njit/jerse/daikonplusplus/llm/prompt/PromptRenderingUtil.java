package edu.njit.jerse.daikonplusplus.llm.prompt;

import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.util.Map;
import java.util.StringJoiner;
import org.checkerframework.checker.nullness.qual.Nullable;

final class PromptRenderingUtil {
  private PromptRenderingUtil() {}

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

  static String renderProgramPointSection(PromptContext ctx) {
    return "PROGRAM POINT: " + ctx.point().elementId() + " [" + ctx.point().kind().name() + "]";
  }

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
