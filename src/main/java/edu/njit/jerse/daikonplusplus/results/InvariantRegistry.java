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
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Append-only registry for invariants. Uses a simple JSONL format per line.
 *
 * <p>TODO: For higher concurrency or querying, consider migrating to SQLite.
 */
public final class InvariantRegistry {
  private final Path jsonl;

  // in-memory index of (kind|element|expr)
  private final java.util.Set<String> seenKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public InvariantRegistry(Path jsonl) {
    this.jsonl = jsonl;
    buildExistingIndex(); // <- NEW
  }

  // create a canonical key
  private static String keyOf(InvariantRecord r) {
    String norm = r.spec().expression().trim().replaceAll("\\s+", " ");
    return r.point().kind().name() + "|" + r.point().elementId().toString() + "|" + norm;
  }

  // pre-load keys from existing registry file
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
      // if we can't index, we just won't dedup historical lines.
    }
  }

  /** Append only if this (kind|element|expr) hasn't been seen in this registry file. */
  public synchronized void appendIfNew(InvariantRecord rec) {
    String key = keyOf(rec);
    // skip duplicate across runs
    if (seenKeys.contains(key)) return;
    append(rec); // write line
    seenKeys.add(key);
  }

  public synchronized void append(InvariantRecord rec) {
    try {
      final @Nullable Path parent = jsonl.getParent();
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

  /** Loads all records in memory. */
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

  // --- Minimal JSON ---

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

  // for brevity we only parse essential fields back; meta is ignored in this
  // skeleton.
  private InvariantRecord fromJson(String json) {
    Map<String, String> m = parseFlatJson(json);

    // required fields
    String idStr = m.get("id");
    if (idStr == null) throw new IllegalArgumentException("registry line missing 'id'");
    String createdAtStr = m.get("createdAt");
    if (createdAtStr == null)
      throw new IllegalArgumentException("registry line missing 'createdAt'");

    // optional fields with defaults
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
      nestedPath = classQualified.substring(dollar + 1); // keep '$' out; we store just the path
    } else {
      topLevelClass = classQualified.isEmpty() ? "<?>" : classQualified;
      nestedPath = "";
    }

    // ensure non-empty descriptor
    if (descriptor == null || descriptor.isBlank()) descriptor = "<?>";

    return ProgramElementId.forMethod(pkg, topLevelClass, nestedPath, filePath, descriptor);
  }

  private Map<String, String> parseFlatJson(String json) {
    // small parser for {"k":"v",...} without escaped commas (we escaped quotes only).
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
