package edu.njit.jerse.daikonplusplus.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
/**
 * Utility methods for parsing compiler errors and disabling injected invariant regions.
 *
 * <p>This class supports the invariant auto-filtering pipeline by:
 * <ul>
 *   <li>Extracting file/line information from compiler output (javac, Gradle, Maven)</li>
 *   <li>Mapping errors back to injected invariant regions</li>
 *   <li>Disabling (commenting out) invariant blocks that cause compilation failures</li>
 * </ul>
 *
 * <p>All methods are best-effort and designed to be robust against noisy or partially
 * structured compiler output.
 */
public final class InvariantAutoFilterUtil {

  private InvariantAutoFilterUtil() {}

  /**
   * Simple representation of a compiler error location.
   *
   * <p>Only file path and line number are used by the autofilter.
   * The message field is currently unused but reserved for future extensions.
   */
  public static final class JError {
    public final String file;
    public final int line;
    public final String msg;

    public JError(String f, int l, String m) {
      this.file = f;
      this.line = l;
      this.msg = m;
    }
  }

  /**
   * Parses compiler stderr output and extracts error locations.
   *
   * <p>Supports common formats:
   * <ul>
   *   <li>javac / Gradle: {@code file.java:line[:column]:}</li>
   *   <li>Maven: {@code file.java:[line,column]}</li>
   * </ul>
   *
   * <p>This method does not attempt to extract error messages, only file and line.
   *
   * @param stderr raw compiler stderr output
   * @return list of extracted error locations (possibly empty)
   */
  public static List<JError> parseErrors(String stderr) {
    List<JError> out = new ArrayList<>();
    if (stderr == null) return out;

    // Pattern 1: javac / gradle style
    Pattern p1 =
        Pattern.compile(
            "^\\s*(?:\\[[^]]+\\]\\s*)?(.+\\.java):(\\d+)(?::\\d+)?:", Pattern.MULTILINE);

    Matcher m1 = p1.matcher(stderr);

    while (m1.find()) {
      String file = m1.group(1);
      String lineStr = m1.group(2);

      if (file == null || lineStr == null) continue;

      file = file.trim();

      int line;
      try {
        line = Integer.parseInt(lineStr);
      } catch (NumberFormatException e) {
        continue;
      }

      out.add(new JError(file, line, ""));
    }

    // Pattern 2: Maven style ([line,column])
    Pattern p2 =
        Pattern.compile(
            "^\\s*(?:\\[[^]]+\\]\\s*)?(.+\\.java):\\[(\\d+),(\\d+)\\]", Pattern.MULTILINE);

    Matcher m2 = p2.matcher(stderr);

    while (m2.find()) {
      String file = m2.group(1);
      String lineStr = m2.group(2);

      if (file == null || lineStr == null) continue;

      file = file.trim();

      int line;
      try {
        line = Integer.parseInt(lineStr);
      } catch (NumberFormatException e) {
        continue;
      }

      out.add(new JError(file, line, ""));
    }

    return out;
  }

  /**
   * Disables the invariant block surrounding a given line number.
   *
   * <p>The method searches for markers:
   * <ul>
   *   <li>{@code __DP_INVARIANT_BEGIN__}</li>
   *   <li>{@code __DP_INVARIANT_END__}</li>
   * </ul>
   *
   * <p>If a block is found, all lines within the region are commented out
   * with a {@code // [DP] disabled invariant :: } prefix.
   *
   * <p>This operation is idempotent and safe to repeat.
   *
   * @param file source file containing injected invariants
   * @param lineno 1-based line number where the error occurred
   * @return 1 if a block was successfully disabled, 0 otherwise
   */
  public static int removeInvariantRegion(Path file, int lineno) {
    try {
      if (!Files.isRegularFile(file)) return 0;

      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

      int begin = -1, end = -1;

      for (int i = lineno - 1; i >= 0; i--) {
        if (lines.get(i).contains("__DP_INVARIANT_BEGIN__")) {
          begin = i;
          break;
        }
      }
      for (int i = lineno - 1; i < lines.size(); i++) {
        if (lines.get(i).contains("__DP_INVARIANT_END__")) {
          end = i;
          break;
        }
      }

      if (begin < 0 || end < 0) return 0;

      for (int i = begin; i <= end; i++) {
        lines.set(i, "// [DP] disabled invariant :: " + lines.get(i));
      }

      Files.write(file, lines, StandardCharsets.UTF_8);
      return 1;
    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * Parses compiler output from external build systems (e.g., Gradle, Maven)
   * and extracts javac-style error locations.
   *
   * <p>This method:
   * <ul>
   *   <li>Filters out common non-error noise lines</li>
   *   <li>Supports absolute-path javac errors: {@code /path/File.java:line: error}</li>
   *   <li>Supports Maven-style errors: {@code /path/File.java:[line,column]}</li>
   * </ul>
   *
   * <p>Only file and line number are extracted; error messages are ignored.
   *
   * @param output combined stdout/stderr from an external build process
   * @return list of extracted error locations (possibly empty)
   */
  public static List<JError> parseExternalCompilerErrors(String output) {
    List<JError> out = new ArrayList<>();
    if (output == null || output.isEmpty()) return out;

    Pattern JAVAC = Pattern.compile("(/[^:\\s]+\\.java):(\\d+):\\s*error");

    Pattern MAVEN = Pattern.compile("(/[^:\\s]+\\.java):\\[(\\d+),(\\d+)\\]");

    for (String rawLine : output.split("\n")) {
      String line = rawLine.trim();

      // filter noise
      if (line.isEmpty()) continue;
      if (line.startsWith("To honour the JVM settings")) continue;
      if (line.startsWith("> Task")) continue;
      if (line.startsWith("FAILURE:")) continue;
      if (line.startsWith("* What went wrong")) continue;
      if (line.startsWith("* Try:")) continue;
      if (line.startsWith("BUILD FAILED")) continue;

      // ---- Pattern 1: javac ----
      Matcher m1 = JAVAC.matcher(line);
      if (m1.find()) {
        String file = m1.group(1);
        String lineStr = m1.group(2);

        if (file != null && lineStr != null) {
          try {
            int lineNo = Integer.parseInt(lineStr);
            out.add(new JError(file, lineNo, ""));
          } catch (NumberFormatException ignored) {
          }
        }
        continue;
      }

      // ---- Pattern 2: Maven ----
      Matcher m2 = MAVEN.matcher(line);
      if (m2.find()) {
        String file = m2.group(1);
        String lineStr = m2.group(2);

        if (file != null && lineStr != null) {
          try {
            int lineNo = Integer.parseInt(lineStr);
            out.add(new JError(file, lineNo, ""));
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }

    return out;
  }
}
