package edu.njit.jerse.daikonplusplus.runtime;

/**
 * Lightweight, stdout-based logger for invariant evaluation results.
 *
 * <p>This class emits structured, single-line JSON records to standard output to enable easy
 * downstream parsing (e.g., by log collectors or post-processing tools).
 *
 * <p>It is intentionally minimal and stateless:
 *
 * <ul>
 *   <li>No buffering or aggregation
 *   <li>No deduplication
 *   <li>No synchronization
 * </ul>
 *
 * <p>Each invocation produces exactly one JSON line representing an invariant failure or evaluation
 * error.
 *
 * <p>Typical usage: called from injected invariant guards during program execution.
 */
public final class InvariantLogger {
  private InvariantLogger() {}

  /**
   * Emits a JSON record describing a failed invariant or evaluation error.
   *
   * <p>The output is written as a single line to {@code stdout} in the following format:
   *
   * <pre>
   * {
   *   "type": "INV_FAIL",
   *   "id": "<uuid>",
   *   "element": "<program element>",
   *   "file": "<source file>",
   *   "expr": "<invariant expression>",
   *   "phase": "<ENTRY|EXIT>",
   *   "error": "<error description>"
   * }
   * </pre>
   *
   * <p>The {@code error} field is empty for normal falsifications and contains a description (e.g.,
   * exception class name) if evaluation failed due to a runtime error.
   *
   * <p>All fields are JSON-escaped to ensure valid output.
   *
   * @param id unique invariant identifier (UUID string)
   * @param element human-readable program element label
   * @param file source file path where the invariant is injected
   * @param expr original invariant expression
   * @param phase evaluation phase ("ENTRY" or "EXIT")
   * @param error empty string for falsification, or error description if evaluation failed
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

  /**
   * Escapes a string for safe inclusion in JSON output.
   *
   * <p>Currently escapes backslashes and double quotes. Null inputs are converted to empty strings.
   *
   * @param s input string
   * @return JSON-safe escaped string
   */
  private static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
