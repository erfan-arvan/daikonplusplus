package edu.njit.jerse.daikonplusplus.llm.prompt;

import org.checkerframework.checker.nullness.qual.Nullable;

public final class FewShotExampleProvider {

  private static boolean isBlank(@Nullable String s) {
    return s == null || s.isBlank();
  }

  public static String getExamples(PromptContext ctx) {
    boolean isBaseline =
        isBlank(ctx.methodJavadoc())
            && isBlank(ctx.enclosingClassDocumentation())
            && isBlank(ctx.typeLevelDocumentation())
            && isBlank(ctx.callSiteContext())
            && isBlank(ctx.inputOutputExamples())
            && isBlank(ctx.calleeDoc());

    if (isBaseline) {
      return "\n\n" + baselineExamples(ctx) + "\n";
    }

    // return "";
    // For now return the baseline one for all context stuff TODO: fix
    return "\n\n" + baselineExamples(ctx) + "\n";
  }

  private static String baselineExamples(PromptContext ctx) {
    return switch (ctx.point().kind()) {
      case METHOD_ENTRY -> baselineEntryExamples();
      case METHOD_EXIT -> baselineExitExamples();
      default -> "";
    };
  }

  private static String baselineEntryExamples() {
    return """
===== EXAMPLE 1 =====
PROGRAM POINT: Example1 [METHOD_ENTRY]

In-scope names:
- order : Order
- limit : int

===== PROGRAM CONTEXT =====
[Method Implementation]
public void process(Order order, int limit) {
  if (order.getQuantity() > limit) {
    throw new IllegalArgumentException();
  }
}
===========================

Expected Output:
{
  "invariants": [
    { "expression": "order != null" },
    { "expression": "order.getQuantity() <= limit" },
    { "expression": "order.getQuantity() >= 0" },
    { "expression": "limit >= 0" }
  ]
}
===== END EXAMPLE 1 =====

===== EXAMPLE 2 =====
PROGRAM POINT: Example2 [METHOD_ENTRY]

In-scope names:
- s : String
- start : int
- end : int

===== PROGRAM CONTEXT =====
[Method Implementation]
public String sub(String s, int start, int end) {
  if (start < 0 || end > s.length()) return "";
  return s.substring(start, end);
}
===========================

Expected Output:
{
  "invariants": [
    { "expression": "s != null" },
    { "expression": "start >= 0" },
    { "expression": "end <= s.length()" },
    { "expression": "start <= end" }
  ]
}
===== END EXAMPLE 2 =====
""";
  }

  private static String baselineExitExamples() {
    return """
===== EXAMPLE 1 =====
PROGRAM POINT: Example1 [METHOD_EXIT]

In-scope names:
- order : Order
- result : double

===== PROGRAM CONTEXT =====
[Method Implementation]
public double total(Order order) {
  return order.getQuantity() * order.getUnitPrice();
}
===========================

Expected Output:
{
  "invariants": [
    { "expression": "order != null" },
    { "expression": "result == order.getQuantity() * order.getUnitPrice()" },
    { "expression": "order.getQuantity() == 0 || result / order.getQuantity() == order.getUnitPrice()" },
    { "expression": "!(result < 0 && order.getQuantity() >= 0 && order.getUnitPrice() >= 0)" }
  ]
}
===== END EXAMPLE 1 =====

===== EXAMPLE 2 =====
PROGRAM POINT: Example2 [METHOD_EXIT]

In-scope names:
- card : String
- expiry : String
- result : boolean

===== PROGRAM CONTEXT =====
[Method Implementation]
public boolean valid(String card, String expiry) {
  if (card == null || expiry == null) return false;
  return card.length() == 16 && expiry.length() == 5 && expiry.charAt(2) == '/';
}
===========================

Expected Output:
{
  "invariants": [
    { "expression": "!result || (card != null && expiry != null)" },
    { "expression": "!result || (card.length() == 16 && expiry.length() == 5 && expiry.charAt(2) == '/')" }
  ]
}
===== END EXAMPLE 2 =====
""";
  }
}
