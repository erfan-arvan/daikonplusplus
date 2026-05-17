package edu.njit.jerse.daikonplusplus.results;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

    try (BufferedReader br = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
      String ln;
      while ((ln = br.readLine()) != null) {
        if (!ln.contains("\"type\":\"INV_FAIL\"")) continue;
        int i = ln.indexOf("\"id\":\"");
        if (i < 0) continue;
        int j = ln.indexOf("\"", i + 6);
        if (j < 0) continue;

        String idStr = ln.substring(i + 6, j).replace("\\\"", "\"").replace("\\\\", "\\");
        try {
          out.add(UUID.fromString(idStr));
        } catch (IllegalArgumentException ignore) {
          // skip malformed IDs
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
   *
   * <p>Accepts lines containing: INV_EXD:<uuid> Ignores surrounding text and multiple markers per
   * line.
   */
  public static Set<UUID> readExecutedIds(Path logFile) {
    Set<UUID> out = new HashSet<>();
    if (!Files.exists(logFile)) return out;

    // UUID regex: 8-4-4-4-12
    final Pattern p =
        Pattern.compile(
            "INV_EXD:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    try (BufferedReader br = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
      String ln;
      while ((ln = br.readLine()) != null) {
        Matcher m = p.matcher(ln);
        while (m.find()) {
          String idStr = m.group(1);
          if (idStr != null && !idStr.isEmpty()) {
            try {
              out.add(UUID.fromString(idStr));
            } catch (IllegalArgumentException ignore) {
              // skip malformed
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
   * Reads the run log from the end and returns the UUID from the very last {@code INV_EXD:<uuid>}
   * line. This identifies the most recently started invariant check — the one most likely to be
   * stuck in an infinite loop when a timeout fires.
   *
   * @return the UUID of the last executed invariant, or empty if none was found
   */
  public static Optional<UUID> readLastExecutedId(Path logFile) {
    if (!Files.exists(logFile)) return Optional.empty();
    final Pattern p =
        Pattern.compile(
            "INV_EXD:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    try {
      List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
      for (int i = lines.size() - 1; i >= 0; i--) {
        Matcher m = p.matcher(lines.get(i));
        UUID last = null;
        while (m.find()) {
          String idStr = m.group(1);
          if (idStr != null) {
            try {
              last = UUID.fromString(idStr);
            } catch (IllegalArgumentException ignore) {
            }
          }
        }
        if (last != null) return Optional.of(last);
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to read run log: " + e.getMessage(), e);
    }
    return Optional.empty();
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
                try (BufferedReader br = Files.newBufferedReader(pth, StandardCharsets.UTF_8)) {
                  String ln;
                  while ((ln = br.readLine()) != null) {
                    if (!ln.contains("//Failed Invariant in Compilation:")) continue;
                    Matcher m = p.matcher(ln);
                    while (m.find()) {
                      final String g = m.group(1); // may be null per annotations
                      if (g == null) continue;
                      try {
                        out.add(UUID.fromString(g));
                      } catch (IllegalArgumentException ignore) {
                        // skip malformed
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
