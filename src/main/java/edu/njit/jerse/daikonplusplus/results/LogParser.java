package edu.njit.jerse.daikonplusplus.results;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses stdout logs emitted by {@code InvariantLogger.fail(...)}. Collects the set of invariant
 * UUIDs that were falsified or errored.
 */
public final class LogParser {
  private LogParser() {}

  /**
   * Reads a log file and returns the set of IDs that appeared in INV_FAIL lines. Any evaluation
   * error counts as falsified for "held" reporting.
   */
  public static Set<UUID> readFalsifiedIds(Path logFile) {
    Set<UUID> out = new HashSet<>();
    if (!Files.exists(logFile)) return out;
    try {
      List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
      for (String ln : lines) {
        if (!ln.contains("\"type\":\"INV_FAIL\"")) continue;
        int i = ln.indexOf("\"id\":\"");
        if (i < 0) continue;
        int j = ln.indexOf("\"", i + 6);
        if (j < 0) continue;
        String idStr = ln.substring(i + 6, j).replace("\\\"", "\"").replace("\\\\", "\\");
        try {
          out.add(UUID.fromString(idStr));
        } catch (IllegalArgumentException ignore) {
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to read run log: " + e.getMessage(), e);
    }
    return out;
  }

  /**
   * Reads a log file and returns the set of IDs that appeared in INV_EXD markers, meaning the
   * invariant was executed at least once.
   */
  /**
   * Reads a log file and returns the set of IDs that appeared in INV_EXD markers, meaning the
   * invariant was executed at least once.
   *
   * <p>Accepts lines containing: INV_EXD:<uuid> Ignores surrounding text and multiple markers per
   * line.
   */
  /**
   * Reads a log file and returns the set of IDs that appeared in INV_EXD markers, meaning the
   * invariant was executed at least once.
   *
   * <p>Accepts lines containing: INV_EXD:<uuid> Ignores surrounding text and multiple markers per
   * line.
   */
  public static Set<UUID> readExecutedIds(Path logFile) {
    Set<UUID> out = new HashSet<>();
    if (!Files.exists(logFile)) return out;

    // Exact UUID shape: 8-4-4-4-12 (hex only)
    final Pattern p =
        Pattern.compile(
            "INV_EXD:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    try {
      for (String ln : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
        Matcher m = p.matcher(ln);
        while (m.find()) {
          String idStr = m.group(1);
          if (idStr != null) {
            try {
              out.add(UUID.fromString(idStr));
            } catch (IllegalArgumentException ignore) {
              // skip malformed ID
            }
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to read run log: " + e.getMessage(), e);
    }
    return out;
  }

  /**
   * Scans instrumented source files for lines commented out due to javac errors. Returns the set of
   * IDs marked with //Failed Invariant in Compilation: ...
   */
  public static Set<UUID> readNonCompiledIds(Path srcRoot) {
    Set<UUID> out = new HashSet<>();
    if (!Files.exists(srcRoot)) return out;

    final Pattern p = Pattern.compile("\\\"id\\\\\":\\\\\"([0-9a-fA-F\\-]{36})\\\\\\\"");

    try (var walk = Files.walk(srcRoot)) {
      walk.filter(pth -> pth.toString().endsWith(".java"))
          .forEach(
              pth -> {
                try {
                  for (String ln : Files.readAllLines(pth, StandardCharsets.UTF_8)) {
                    if (!ln.contains("//Failed Invariant in Compilation:")) continue;
                    Matcher m = p.matcher(ln);
                    while (m.find()) {
                      final String g = m.group(1); // may be null per annotations
                      if (g == null) continue;
                      try {
                        out.add(UUID.fromString(g));
                      } catch (IllegalArgumentException ignore) {
                      }
                    }
                  }
                } catch (IOException ioe) {
                  System.err.println("    ! Failed to scan " + pth + ": " + ioe.getMessage());
                }
              });
    } catch (IOException e) {
      throw new RuntimeException("Failed to walk source tree: " + e.getMessage(), e);
    }

    return out;
  }
}
