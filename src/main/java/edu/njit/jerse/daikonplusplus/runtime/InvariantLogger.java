package edu.njit.jerse.daikonplusplus.runtime;

/**
 * Centralized logger for invariant evaluation results.
 *
 * <p>Emits one-line JSON objects on stdout to simplify downstream parsing.
 */
public final class InvariantLogger {
  private InvariantLogger() {}

  /**
   * Records a failed invariant or evaluation error.
   *
   * @param id UUID of the invariant
   * @param element human-readable element label (e.g., a.b.C#m(int):void)
   * @param file source file path
   * @param expr original expression string
   * @param phase "ENTRY" or "EXIT"
   * @param error empty for a normal falsification; otherwise, throwable class name
   */
  public static void fail(
      String id, String element, String file, String expr, String phase, String error) {
    System.out.println(
        "{\"type\":\"INV_FAIL\",\"id\":\""
            + esc(id)
            + "\",\"element\":\""
            + esc(element)
            + "\",\"file\":\""
            + esc(file)
            + "\",\"expr\":\""
            + esc(expr)
            + "\",\"phase\":\""
            + esc(phase)
            + "\",\"error\":\""
            + esc(error)
            + "\"}");
  }

  private static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
