package edu.njit.jerse.daikonplusplus.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight runtime logger for invariant execution and failure events.
 *
 * <p>This class collects events in-memory during program execution and appends them
 * to a log file at JVM shutdown. It is designed to have minimal runtime overhead
 * and to be safe under concurrent access.
 *
 * <p>Events recorded:
 * <ul>
 *   <li>{@code INV_EXD:<id>} — invariant was executed at least once</li>
 *   <li>JSON lines — invariant failure records</li>
 * </ul>
 *
 * <p>The output file is determined by the {@code DP_RUN_LOG} system property.
 *
 * <p>This class is intended to be used by injected invariant guards.
 */
public final class DpRuntimeLog {

  private static final AtomicBoolean INIT = new AtomicBoolean(false);

  // executed IDs
  private static final Set<String> EXECUTED = ConcurrentHashMap.newKeySet();

  // id -> JSON fail line (first one wins)
  private static final Map<String, String> FAILED = new ConcurrentHashMap<>();

  /**
   * Records that an invariant has been executed.
   *
   * <p>Each invariant ID is recorded at most once.
   *
   * @param id unique invariant identifier
   */
  public static void exd(String id) {
    ensureInit();
    EXECUTED.add(id);
  }

  /**
   * Records a failed invariant.
   *
   * <p>Only the first failure per invariant ID is retained. Subsequent failures
   * of the same invariant are ignored to avoid duplicate log entries.
   *
   * @param id invariant identifier
   * @param jsonLine serialized failure record (JSON)
   */
  public static void fail(String id, String jsonLine) {
    ensureInit();
    FAILED.putIfAbsent(id, jsonLine);
  }

  /**
   * Ensures that the shutdown hook is registered exactly once.
   *
   * <p>This method is thread-safe and idempotent.
   */
  private static void ensureInit() {
    if (!INIT.compareAndSet(false, true)) return;

    Runtime.getRuntime()
        .addShutdownHook(new Thread(DpRuntimeLog::flushSafely, "daikonpp-log-flush"));
  }

  /**
   * Flushes collected events to disk during JVM shutdown.
   *
   * <p>All exceptions are caught to avoid interfering with shutdown.
   */
  private static void flushSafely() {
    try {
      flush();
    } catch (Throwable t) {
      // last-resort safety: never block JVM shutdown
      t.printStackTrace();
    }
  }

  /**
   * Appends recorded events to the runtime log file.
   *
   * <p>The file is specified by the {@code DP_RUN_LOG} system property.
   * If the property is not set, no output is written.
   *
   * @throws IOException if writing to the log file fails
   */
  private static void flush() throws IOException {
    String logPath = System.getProperty("DP_RUN_LOG");
    if (logPath == null || logPath.isBlank()) return;

    Path p = Path.of(logPath);

    try (BufferedWriter w =
        Files.newBufferedWriter(
            p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

      for (String id : EXECUTED) {
        w.write("INV_EXD:" + id);
        w.newLine();
      }
      for (String json : FAILED.values()) {
        w.write(json);
        w.newLine();
      }
    }
  }

  private DpRuntimeLog() {}
}
