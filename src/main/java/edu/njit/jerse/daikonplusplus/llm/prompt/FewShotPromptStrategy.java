package edu.njit.jerse.daikonplusplus.llm.prompt;

public final class FewShotPromptStrategy extends AbstractPromptStrategy {

  @Override
  public String name() {
    return "fewshot";
  }

  @Override
  protected String extraSystemInstructions(PromptContext ctx) {
    return """

        ===== EXAMPLE 1 =====
        PROGRAM POINT: Example1 [METHOD_EXIT]

        In-scope names:
        - x : int
        - result : int

        ===== PROGRAM CONTEXT =====
        [Method Implementation]
        /**
         * Returns x + 1.
         */
        public int inc(int x) { return x + 1; }
        ===========================

        Expected Output:
        {
          "invariants": [
            { "expression": "result == x + 1" }
          ]
        }
        ===== END EXAMPLE 1 =====

        ===== EXAMPLE 2 =====
        PROGRAM POINT: Example2 [METHOD_EXIT]

        In-scope names:
        - s : java.lang.String
        - result : int

        ===== PROGRAM CONTEXT =====
        [Method Implementation]
        /**
         * Returns 0 if s is null; otherwise returns s.length().
         */
        public int lenOrZero(String s) {
          return (s == null) ? 0 : s.length();
        }
        ===========================

        Expected Output:
        {
          "invariants": [
            { "expression": "s == null ? result == 0 : result == s.length()" }
          ]
        }
        ===== END EXAMPLE 2 =====
        """;
  }
}
