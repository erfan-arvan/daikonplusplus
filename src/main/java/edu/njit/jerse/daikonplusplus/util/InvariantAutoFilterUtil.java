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

    Matcher m =
        Pattern.compile("^\\s*(.*\\.java):(\\d+):\\s+error:\\s+(.*)$", Pattern.MULTILINE)
            .matcher(stderr);

    while (m.find()) {
      String file = m.group(1);
      String lineStr = m.group(2);
      String msg = m.group(3);

      if (file == null || lineStr == null || msg == null) {
        continue;
      }

      int line;
      try {
        line = Integer.parseInt(lineStr);
      } catch (NumberFormatException e) {
        continue;
      }

      out.add(new JError(file, line, msg));
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

    // Matches:
    // [javac] /path/File.java:123: error: message
    // /path/File.java:123: error: message
    Pattern p =
        Pattern.compile(
            "^\\s*(?:\\[[^]]+\\]\\s*)?(.+\\.java):(\\d+):\\s+error:\\s+(.*)$", Pattern.MULTILINE);

    Matcher m = p.matcher(output);

    while (m.find()) {
      String file = m.group(1);
      String lineStr = m.group(2);
      String msg = m.group(3);

      if (file == null || lineStr == null || msg == null) {
        continue;
      }

      file = file.trim();

      int line;
      try {
        line = Integer.parseInt(lineStr);
      } catch (NumberFormatException e) {
        continue;
      }

      out.add(new JError(file, line, msg.trim()));
    }

    return out;
  }
}
