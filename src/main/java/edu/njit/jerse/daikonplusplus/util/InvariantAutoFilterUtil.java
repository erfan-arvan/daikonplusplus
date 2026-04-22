package edu.njit.jerse.daikonplusplus.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class InvariantAutoFilterUtil {

  private InvariantAutoFilterUtil() {}

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
