package edu.njit.jerse.daikonplusplus.results;

import edu.njit.jerse.daikonplusplus.model.InvariantRecord;
import edu.njit.jerse.daikonplusplus.model.InvariantSpec;
import edu.njit.jerse.daikonplusplus.model.ProgramElementId;
import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Append-only registry for invariants. Uses a simple JSONL format per line.
 *
 * <p>TODO: For higher concurrency or querying, consider migrating to SQLite.
 */
public final class InvariantRegistry {
  /** Life-cycle status of each invariant record. */
  public enum Verdict {
    PROPOSED,
    COMPILED,
    EXECUTED,
    HELD,
    FALSIFIED,
    NEVER_EXECUTED,
    FAILED_TO_COMPILE
  }

  /** Simple immutable structure to update outcomes after execution. */
  public static final class Outcome {
    public final boolean compiled;
    public final boolean executed;
    public final Verdict verdict;

    public Outcome(boolean compiled, boolean executed, Verdict verdict) {
      this.compiled = compiled;
      this.executed = executed;
      this.verdict = verdict;
    }
  }

  private final Path jsonl;

  // in-memory index of (kind|element|expr), shared across runs for dedup
  private final Set<String> seenKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public InvariantRegistry(Path jsonl) {
    this.jsonl = jsonl;
    // Ensure file exists so empty runs still have a tangible artifact
    try {
      Path parent = jsonl.getParent();
      if (parent != null) Files.createDirectories(parent);
      if (!Files.exists(jsonl)) {
        Files.createFile(jsonl);
      }
    } catch (IOException ioe) {
      throw new RuntimeException("Failed to init registry file: " + ioe.getMessage(), ioe);
    }
    buildExistingIndex();
  }

  // ----- Append & read -----

  /**
   * Append only if this (kind|element|expr) hasn't been seen in this registry file.
   *
   * @return true if the record was new and appended, false if it was a duplicate
   */
  public synchronized boolean appendIfNew(InvariantRecord rec) {
    String key = keyOf(rec);
    if (seenKeys.contains(key)) return false;
    append(rec);
    seenKeys.add(key);
    return true;
  }

  /** Append the record to the registry JSONL. */
  public synchronized void append(InvariantRecord rec) {
    try {
      Path parent = jsonl.getParent();
      if (parent != null) Files.createDirectories(parent);
      try (BufferedWriter w =
          Files.newBufferedWriter(
              jsonl,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND)) {
        w.write(toJson(rec));
        w.newLine();
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to append registry: " + e.getMessage(), e);
    }
  }

  /** Loads all records in memory keyed by UUID. */
  public Map<UUID, InvariantRecord> loadAll() {
    if (!Files.exists(jsonl)) return Map.of();
    try {
      return Files.readAllLines(jsonl, StandardCharsets.UTF_8).stream()
          .filter(s -> !s.isBlank())
          .map(this::fromJson)
          .collect(Collectors.toMap(InvariantRecord::id, r -> r));
    } catch (IOException e) {
      throw new RuntimeException("Failed to read registry: " + e.getMessage(), e);
    }
  }

  // ----- Outcomes writing -----

  /** Backward-compatible: write outcomes next to the registry as a sidecar file. */
  public static void writeOutcomes(Path outPath, Map<UUID, Outcome> outcomes) {
    Objects.requireNonNull(outPath, "outPath");
    try {
      Path parent = outPath.getParent();
      if (parent != null) Files.createDirectories(parent);

      try (BufferedWriter w =
          Files.newBufferedWriter(
              outPath,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING)) {

        if (outcomes != null && !outcomes.isEmpty()) {
          for (var e : outcomes.entrySet()) {
            UUID id = e.getKey();
            Outcome o = e.getValue();
            // write real booleans, not strings
            w.write(
                "{\"id\":\""
                    + id
                    + "\","
                    + "\"compiled\":"
                    + o.compiled
                    + ","
                    + "\"executed\":"
                    + o.executed
                    + ","
                    + "\"verdict\":\""
                    + o.verdict.name()
                    + "\"}");
            w.newLine();
          }
        }
      }
    } catch (IOException ioe) {
      throw new RuntimeException(
          "Failed to write outcomes to " + outPath + ": " + ioe.getMessage(), ioe);
    }
  }

  // ----- Internal helpers -----

  // Create a canonical key for dedup
  private static String keyOf(InvariantRecord r) {
    String norm = r.spec().expression().trim().replaceAll("\\s+", " ");
    return r.point().kind().name() + "|" + r.point().elementId().toString() + "|" + norm;
  }

  // Pre-load keys from existing registry file to dedup across runs
  private void buildExistingIndex() {
    if (!Files.exists(jsonl)) return;
    try {
      for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
        if (line == null || line.isBlank()) continue;
        Map<String, String> m = parseFlatJson(line);
        String kind = m.getOrDefault("kind", "METHOD_ENTRY");
        String element = m.getOrDefault("element", "");
        String expr = m.getOrDefault("expr", "").trim().replaceAll("\\s+", " ");
        if (!expr.isEmpty() && !element.isEmpty()) {
          seenKeys.add(kind + "|" + element + "|" + expr);
        }
      }
    } catch (IOException ignore) {
      // If we can't index, we just won't dedup historical lines.
    }
  }

  // --- Minimal JSON (string-only for registry lines) ---

  private String toJson(InvariantRecord r) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    kv(sb, "id", r.id().toString()).append(",");
    kv(sb, "expr", r.spec().expression()).append(",");
    kv(sb, "rationale", r.spec().rationale()).append(",");
    kv(sb, "kind", r.point().kind().name()).append(",");
    kv(sb, "element", r.point().elementId().toString()).append(",");
    kv(sb, "file", r.sourceFile()).append(",");
    kv(sb, "createdAt", r.createdAt().toString());
    sb.append("}");
    return sb.toString();
  }

  private StringBuilder kv(StringBuilder sb, String k, String v) {
    sb.append("\"").append(esc(k)).append("\":\"").append(esc(v)).append("\"");
    return sb;
  }

  private String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // For brevity we parse essential fields back; meta fields may be ignored here.
  private InvariantRecord fromJson(String json) {
    Map<String, String> m = parseFlatJson(json);

    // required
    String idStr = m.get("id");
    if (idStr == null) throw new IllegalArgumentException("registry line missing 'id'");

    String createdAtStr = m.get("createdAt");
    if (createdAtStr == null)
      throw new IllegalArgumentException("registry line missing 'createdAt'");

    // optional with defaults
    String expr = (m.get("expr") == null) ? "" : m.get("expr");
    String kindStr = (m.get("kind") == null) ? "METHOD_ENTRY" : m.get("kind");
    String element = (m.get("element") == null) ? "" : m.get("element");
    String file = (m.get("file") == null) ? "" : m.get("file");

    UUID id = UUID.fromString(idStr);
    java.time.Instant ts = java.time.Instant.parse(createdAtStr);

    // rebuild a NON-NULL ProgramElementId from the serialized label + file
    ProgramElementId peid = parseElementIdFromLabel(element, file);
    ProgramPointKind kind = ProgramPointKind.valueOf(kindStr);
    ProgramPoint point = new ProgramPointImpl(peid, kind);

    InvariantSpec spec = new InvariantSpec(expr, "", java.util.Collections.emptyMap());
    return new InvariantRecord(id, spec, point, file, ts);
  }

  /**
   * Reconstructs a ProgramElementId from a human-readable label like "a.b.C$Inner#m(int):void" and
   * a file path. Best-effort but NON-NULL.
   */
  private ProgramElementId parseElementIdFromLabel(String label, String filePath) {
    if (label == null) label = "";

    // split "qualifiedClass#descriptor"
    String classPart;
    String descriptor;
    int hash = label.lastIndexOf('#');
    if (hash >= 0) {
      classPart = label.substring(0, hash);
      descriptor = label.substring(hash + 1);
    } else {
      classPart = label;
      descriptor = "<?>"; // fallback if missing
    }

    // split package vs. class (last dot)
    String pkg;
    String classQualified;
    int lastDot = classPart.lastIndexOf('.');
    if (lastDot >= 0) {
      pkg = classPart.substring(0, lastDot);
      classQualified = classPart.substring(lastDot + 1);
    } else {
      pkg = "";
      classQualified = classPart;
    }

    // split top-level vs nested ($-separated)
    String topLevelClass;
    String nestedPath;
    int dollar = classQualified.indexOf('$');
    if (dollar >= 0) {
      topLevelClass = classQualified.substring(0, dollar);
      nestedPath = classQualified.substring(dollar + 1);
    } else {
      topLevelClass = classQualified.isEmpty() ? "<?>" : classQualified;
      nestedPath = "";
    }

    // ensure non-empty descriptor
    if (descriptor == null || descriptor.isBlank()) descriptor = "<?>";

    return ProgramElementId.forMethod(pkg, topLevelClass, nestedPath, filePath, descriptor);
  }

  private Map<String, String> parseFlatJson(String json) {
    // tiny parser for {"k":"v",...} without escaped commas (we escape quotes only)
    Map<String, String> out = new HashMap<>();
    String inner = json.trim();
    if (inner.startsWith("{")) inner = inner.substring(1);
    if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
    if (inner.isBlank()) return out;
    for (String part : inner.split("\",\"")) {
      String p = part.replaceAll("^\"", "").replaceAll("\"$", "");
      int idx = p.indexOf("\":\"");
      if (idx > 0) {
        String k = p.substring(0, idx);
        String v = p.substring(idx + 3);
        out.put(k, v);
      }
    }
    return out;
  }
}
