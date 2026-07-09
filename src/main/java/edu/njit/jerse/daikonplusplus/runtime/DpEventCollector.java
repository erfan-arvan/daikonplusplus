package edu.njit.jerse.daikonplusplus.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects invariant execution and failure events at runtime and persists them on JVM shutdown.
 *
 * <p>Events are stored in-memory during execution and flushed to a per-process sidecar file when
 * the JVM terminates. This avoids synchronization overhead during normal execution while ensuring
 * durability at shutdown.
 *
 * <p>The output location is controlled by the {@code DP_INV_DIR} system property.
 */
public final class DpEventCollector {

  private static final AtomicBoolean INIT = new AtomicBoolean(false);

  // RAM only (per JVM)
  private static final Set<String> EXECUTED = ConcurrentHashMap.newKeySet();
  private static final Map<String, String> FAILED_JSON = new ConcurrentHashMap<>(); // id -> json

  /**
   * Records that an invariant has been executed.
   *
   * @param id invariant identifier
   */
  public static void exd(String id) {
    ensureInit();
    EXECUTED.add(id);
  }

  /**
   * Records a failed invariant with its serialized JSON representation.
   *
   * <p>Only the first failure per invariant ID is retained.
   *
   * @param id invariant identifier
   * @param json serialized failure event
   */
  public static void fail(String id, String json) {
    ensureInit();
    FAILED_JSON.putIfAbsent(id, json);
  }

  /**
   * Initializes the collector and registers the shutdown hook.
   *
   * <p>This method is idempotent and safe to call multiple times.
   */
  private static void ensureInit() {
    if (!INIT.compareAndSet(false, true)) return;
    Runtime.getRuntime().addShutdownHook(new Thread(DpEventCollector::flushSafely, "dp-flush"));
  }

  /** Flushes collected events during JVM shutdown while suppressing all exceptions. */
  private static void flushSafely() {
    try {
      flushToSidecar();
    } catch (Throwable t) {
      // never block shutdown
    }
  }

  /**
   * Writes collected events to a per-process sidecar file.
   *
   * <p>The file is created under the directory specified by {@code DP_INV_DIR} and uses the process
   * ID in its name to avoid collisions.
   *
   * @throws IOException if writing fails
   */
  private static void flushToSidecar() throws IOException {
    String dir = System.getProperty("DP_INV_DIR");
    if (dir == null || dir.isBlank()) return;

    Path outDir = Path.of(dir);
    Files.createDirectories(outDir);

    String pid = pid();
    Path f = outDir.resolve("dp-events-" + pid + ".jsonl");

    try (BufferedWriter w =
        Files.newBufferedWriter(
            f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

      for (String id : EXECUTED) {
        w.write("INV_EXD:" + id);
        w.newLine();
      }
      for (String json : FAILED_JSON.values()) {
        w.write(json);
        w.newLine();
      }
    }
  }

  /**
   * Extracts the current process ID from the JVM runtime name.
   *
   * @return process identifier string
   */
  private static String pid() {
    // format is typically "<pid>@<hostname>"
    String name = ManagementFactory.getRuntimeMXBean().getName();
    int at = name.indexOf('@');
    return (at > 0) ? name.substring(0, at) : name;
  }

  private DpEventCollector() {}
}
