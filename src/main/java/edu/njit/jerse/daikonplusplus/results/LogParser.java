package edu.njit.jerse.daikonplusplus.results;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
   * Like {@link #readLastExecutedId(Path)} but only considers bytes written at or after {@code
   * startOffset}. Pass the file size captured just before a run started so the stale detector
   * ignores {@code INV_EXD} entries from previous runs.
   */
  public static Optional<UUID> readLastExecutedIdFrom(Path logFile, long startOffset) {
    if (!Files.exists(logFile)) return Optional.empty();
    final Pattern p =
        Pattern.compile(
            "INV_EXD:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    try {
      FileInputStream fis = new FileInputStream(logFile.toFile());
      long skipped = fis.skip(startOffset);
      if (skipped < startOffset) {
        fis.close();
        return Optional.empty();
      }
      UUID last = null;
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
        String ln;
        while ((ln = br.readLine()) != null) {
          Matcher m = p.matcher(ln);
          while (m.find()) {
            String g = m.group(1);
            if (g == null) continue;
            try {
              last = UUID.fromString(g);
            } catch (IllegalArgumentException ignore) {
            }
          }
        }
      }
      return Optional.ofNullable(last);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read run log: " + e.getMessage(), e);
    }
  }

  /**
   * Scans the log from {@code startOffset} and returns the UUID of the last {@code INV_EXD:<uuid>}
   * that has no matching {@code INV_DON:<uuid>} after it — i.e., an invariant that started
   * evaluation but never completed. Returns empty if every EXD has a corresponding DON (process is
   * between test batches, not stuck inside an invariant check).
   */
  public static Optional<UUID> readOpenInvariantIdFrom(Path logFile, long startOffset) {
    if (!Files.exists(logFile)) return Optional.empty();
    final Pattern p =
        Pattern.compile(
            "INV_(EXD|DON):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    try {
      FileInputStream fis = new FileInputStream(logFile.toFile());
      long skipped = fis.skip(startOffset);
      if (skipped < startOffset) {
        fis.close();
        return Optional.empty();
      }
      // Track: index of last EXD line per UUID, and whether a DON appeared after it
      java.util.LinkedHashMap<UUID, Boolean> openMap = new java.util.LinkedHashMap<>();
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
        String ln;
        while ((ln = br.readLine()) != null) {
          Matcher m = p.matcher(ln);
          while (m.find()) {
            String kind = m.group(1);
            String g = m.group(2);
            if (g == null || kind == null) continue;
            try {
              UUID id = UUID.fromString(g);
              if ("EXD".equals(kind)) {
                openMap.put(id, true); // open: EXD seen, no DON yet
              } else { // DON
                openMap.put(id, false); // closed
              }
            } catch (IllegalArgumentException ignore) {
            }
          }
        }
      }
      // Return the last UUID that is still open (EXD without DON)
      UUID lastOpen = null;
      for (java.util.Map.Entry<UUID, Boolean> e : openMap.entrySet()) {
        if (e.getValue()) lastOpen = e.getKey();
      }
      return Optional.ofNullable(lastOpen);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read run log: " + e.getMessage(), e);
    }
  }

  /**
   * Returns the set of invariant UUIDs that were executed in a prior run, by listing filenames in
   * {@code shmDir/ex/}. Each filename is expected to be a UUID string; non-UUID filenames are
   * silently skipped.
   */
  public static Set<UUID> readExecutedIdsFromShm(Path shmDir) {
    Set<UUID> out = new HashSet<>();
    Path exDir = shmDir.resolve("ex");
    if (!Files.exists(exDir)) return out;
    try (var s = Files.list(exDir)) {
      s.forEach(
          p -> {
            Path fn = p.getFileName();
            if (fn == null) return;
            try {
              out.add(UUID.fromString(fn.toString()));
            } catch (IllegalArgumentException ignore) {
            }
          });
    } catch (IOException e) {
      throw new RuntimeException("Failed to list shm/ex: " + e.getMessage(), e);
    }
    return out;
  }

  /**
   * Returns the set of invariant UUIDs that failed in a prior run, by listing {@code *.json}
   * filenames in {@code shmDir/fail/}. The {@code .json} suffix is stripped before UUID parsing;
   * non-conforming filenames are silently skipped.
   */
  public static Set<UUID> readFalsifiedIdsFromShm(Path shmDir) {
    Set<UUID> out = new HashSet<>();
    Path failDir = shmDir.resolve("fail");
    if (!Files.exists(failDir)) return out;
    try (var s = Files.list(failDir)) {
      s.forEach(
          p -> {
            Path fn = p.getFileName();
            if (fn == null) return;
            String name = fn.toString();
            if (!name.endsWith(".json")) return;
            try {
              out.add(UUID.fromString(name.substring(0, name.length() - 5)));
            } catch (IllegalArgumentException ignore) {
            }
          });
    } catch (IOException e) {
      throw new RuntimeException("Failed to list shm/fail: " + e.getMessage(), e);
    }
    return out;
  }

  /**
   * Returns the UUID of the invariant currently mid-evaluation (the "stuck" invariant) by reading
   * the first filename in {@code shmDir/current/}. Each invariant writes a marker to this directory
   * before evaluation and deletes it after; a file surviving a kill indicates the stuck invariant.
   * Returns empty if the directory is absent or contains no valid UUID filenames.
   */
  public static Optional<UUID> readCurrentInvariantFromShm(Path shmDir) {
    Path currentDir = shmDir.resolve("current");
    if (!Files.exists(currentDir)) return Optional.empty();
    try (var s = Files.list(currentDir)) {
      java.util.Iterator<Path> it = s.iterator();
      while (it.hasNext()) {
        Path entry = it.next();
        Path fn = entry.getFileName();
        if (fn == null) continue;
        try {
          return Optional.of(UUID.fromString(fn.toString()));
        } catch (IllegalArgumentException ignore) {
        }
      }
      return Optional.empty();
    } catch (IOException e) {
      throw new RuntimeException("Failed to list shm/current: " + e.getMessage(), e);
    }
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
