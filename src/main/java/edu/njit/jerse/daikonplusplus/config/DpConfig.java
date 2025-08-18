package edu.njit.jerse.daikonplusplus.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Central configuration for Daikon++.
 *
 * <p>Immutable; values are loaded from system properties and/or environment variables.
 * System properties take precedence over environment variables, which in turn fall back
 * to sensible defaults
 */
public final class DpConfig {
  private final int threads;
  private final Path registryPath;

  private final boolean includeBody;
  private final boolean registryReset;
  private final boolean debug;
  private final boolean keepWork;

  private DpConfig(
          int threads,
          Path registryPath,
          boolean includeBody,
          boolean registryReset,
          boolean debug,
          boolean keepWork) {
    this.threads = threads;
    this.registryPath = registryPath;
    this.includeBody = includeBody;
    this.registryReset = registryReset;
    this.debug = debug;
    this.keepWork = keepWork;
  }

  /** number of worker threads for parallel processing. */
  public int threads() { return threads; }

  /** path to the JSONL registry */
  public Path registryPath() { return registryPath; }

  /** whether to include full method bodies in LLM prompts */
  public boolean includeBody() { return includeBody; }

  /** whether to clear the registry file at startup */
  public boolean registryReset() { return registryReset; }

  /** verbose logging. */
  public boolean debug() { return debug; }

  /** keep the working copy directory after the run */
  public boolean keepWork() { return keepWork; }

  /** Convenience factory reading sane defaults from properties/env. */
  public static edu.njit.jerse.daikonplusplus.config.DpConfig fromEnv() {
    Map<String, String> env = System.getenv();

    int threads = Math.max(2, Runtime.getRuntime().availableProcessors());

    // system property wins, then env var, then default
    // -Ddp.registry=/path/file.jsonl  OR  export DP_REGISTRY=/path/file.jsonl
    @Nullable String regProp = System.getProperty("dp.registry");
    @Nullable String regEnv  = env.get("DP_REGISTRY");
    String regChosen = firstNonBlank(regProp, regEnv, "build/daikonpp_registry.jsonl");
    Path reg = Path.of(regChosen);

    // feature flags default to true unless explicitly disabled.
    boolean includeBody   = getBool("DP_INCLUDE_BODY",   env, /*default*/ true);
    boolean registryReset = getBool("DP_REGISTRY_RESET", env, /*default*/ true);
    boolean debug         = getBool("DP_DEBUG",          env, /*default*/ true);
    boolean keepWork      = getBool("DP_KEEP_WORK",      env, /*default*/ true);

    return new edu.njit.jerse.daikonplusplus.config.DpConfig(threads, reg, includeBody, registryReset, debug, keepWork);
  }

  private static boolean getBool(String key, Map<String, String> env, boolean def) {
    // System property wins over env; both fall back to default.
    @Nullable String v = System.getProperty(key);
    if (v == null) v = env.get(key);
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

  private static @NonNull String firstNonBlank(
          @Nullable String a, @Nullable String b, @NonNull String c) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return c;
  }
}
