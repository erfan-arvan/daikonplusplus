package edu.njit.jerse.daikonplusplus.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Central configuration for Daikon++. Immutable; props override env, env overrides defaults. */
public final class DpConfig {
  private final int threads;
  private final Path registryPath;
  private final Path outcomesPath;

  private final boolean includeBody;
  private final boolean registryReset;
  private final boolean debug;
  private final boolean keepWork;
  private final long runTimeoutSec;
  private final int maxRunRetries;

  private DpConfig(
      int threads,
      Path registryPath,
      Path outcomesPath,
      boolean includeBody,
      boolean registryReset,
      boolean debug,
      boolean keepWork,
      long runTimeoutSec,
      int maxRunRetries) {
    this.threads = threads;
    this.registryPath = registryPath;
    this.outcomesPath = outcomesPath;
    this.includeBody = includeBody;
    this.registryReset = registryReset;
    this.debug = debug;
    this.keepWork = keepWork;
    this.runTimeoutSec = runTimeoutSec;
    this.maxRunRetries = maxRunRetries;
  }

  /** number of worker threads for parallel processing. */
  public int threads() {
    return threads;
  }

  /** path to the JSONL registry */
  public Path registryPath() {
    return registryPath;
  }

  /** path to the JSONL outcomes (compiled/executed/verdict) */
  public Path outcomesPath() {
    return outcomesPath;
  }

  /** whether to include full method bodies in LLM prompts */
  public boolean includeBody() {
    return includeBody;
  }

  /** whether to clear the registry file at startup */
  public boolean registryReset() {
    return registryReset;
  }

  /** verbose logging. */
  public boolean debug() {
    return debug;
  }

  /** keep the working copy directory after the run */
  public boolean keepWork() {
    return keepWork;
  }

  /** seconds before the child JVM run is killed and timeout-recovery kicks in (default 120) */
  public long runTimeoutSec() {
    return runTimeoutSec;
  }

  /** max number of timeout-recovery retries before giving up (default 3) */
  public int maxRunRetries() {
    return maxRunRetries;
  }

  /** Convenience factory reading sane defaults from properties/env. */
  public static DpConfig fromEnv() {
    Map<String, String> env = System.getenv();

    int threads =
        Math.max(
            2,
            getInt2(
                "dp.threads",
                "DP_THREADS",
                /* def= */ Runtime.getRuntime().availableProcessors(),
                env));

    // -Ddp.registry=/path/file.jsonl OR DP_REGISTRY=/path/file.jsonl
    String regChosen =
        firstNonBlank(
            System.getProperty("dp.registry"),
            env.get("DP_REGISTRY"),
            "build/daikonpp_registry.jsonl");
    Path reg = Path.of(regChosen).toAbsolutePath().normalize();

    // -Ddp.outcomes=/path/file.jsonl OR DP_OUTCOMES=/path/file.jsonl
    String outChosen =
        firstNonBlank(
            System.getProperty("dp.outcomes"),
            env.get("DP_OUTCOMES"),
            "build/daikonpp_outcomes.jsonl");
    Path out = Path.of(outChosen).toAbsolutePath().normalize();

    // feature flags (props override env). Support both dp.flag and DP_FLAG.
    boolean includeBody = getBool2("dp.includeBody", "DP_INCLUDE_BODY", /*def*/ true, env);
    boolean registryReset = getBool2("dp.registryReset", "DP_REGISTRY_RESET", /*def*/ true, env);
    boolean debug = getBool2("dp.debug", "DP_DEBUG", /*def*/ true, env);
    boolean keepWork = getBool2("dp.keepWork", "DP_KEEP_WORK", /*def*/ true, env);
    long runTimeoutSec =
        Math.max(
            1, getLong2("dp.runTimeoutSec", "DP_RUN_TIMEOUT_SEC", /* def= */ 120L, env));
    int maxRunRetries =
        Math.max(
            0, getInt2("dp.maxRunRetries", "DP_MAX_RUN_RETRIES", /* def= */ 3, env));

    return new DpConfig(
        threads, reg, out, includeBody, registryReset, debug, keepWork, runTimeoutSec,
        maxRunRetries);
  }

  // ---- helpers ----

  private static boolean getBool2(
      String sysKey, String envKey, boolean def, Map<String, String> env) {
    String v = System.getProperty(sysKey);
    if (v == null) v = env.get(envKey);
    if (v == null) return def;
    switch (v.trim().toLowerCase(Locale.ROOT)) {
      case "1":
      case "true":
      case "yes":
      case "on":
        return true;
      case "0":
      case "false":
      case "no":
      case "off":
        return false;
      default:
        return def;
    }
  }

  private static int getInt2(String sysKey, String envKey, int def, Map<String, String> env) {
    String v = System.getProperty(sysKey);
    if (v == null) v = env.get(envKey);
    if (v == null || v.isBlank()) return def;
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException nfe) {
      return def;
    }
  }

  private static long getLong2(String sysKey, String envKey, long def, Map<String, String> env) {
    String v = System.getProperty(sysKey);
    if (v == null) v = env.get(envKey);
    if (v == null || v.isBlank()) return def;
    try {
      return Long.parseLong(v.trim());
    } catch (NumberFormatException nfe) {
      return def;
    }
  }

  private static @NonNull String firstNonBlank(
      @Nullable String a, @Nullable String b, @NonNull String c) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return c;
  }
}
