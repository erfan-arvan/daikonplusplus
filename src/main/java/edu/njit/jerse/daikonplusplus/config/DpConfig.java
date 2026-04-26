package edu.njit.jerse.daikonplusplus.config;

import edu.njit.jerse.daikonplusplus.parse.context.ContextKind;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Central configuration for Daikon++.
 *
 * <p>This class is immutable and resolves configuration values using the following precedence:
 *
 * <pre>
 *   dpconfig.properties (file)
 *     → System properties (-Ddp.xxx)
 *     → Environment variables (DP_XXX)
 *     → Built-in defaults
 * </pre>
 *
 * <p>This preserves backward compatibility while allowing users to define a configuration file
 * without modifying application code.
 */
public final class DpConfig {

  // ---- core ----
  private final int threads;
  private final Path registryPath;
  private final Path outcomesPath;

  // ---- feature flags ----
  private final boolean includeBody;
  private final boolean registryReset;
  private final boolean debug;
  private final boolean keepWork;
  private final boolean noQualityFilter;

  // ---- LLM / limits ----
  private final int llmTotalTimeoutSec;
  private final int llmPerReqTimeoutSec;
  private final int bodyMaxChars;

  // ---- LLM ----
  private final String openaiModel;
  private final @Nullable String llmCassettesDir;
  private final boolean disableRealLlm;

  // ---- execution / scripts ----
  private final @Nullable String compileMainScript;
  private final @Nullable String compileTestScript;

  // ---- time control ----
  private final int llmPollStepMs;

  // ---- external mode ----
  private final String externalCompileClasspath;

  // ---- working dir ----
  private final String workDir;

  // ---- context selection ----
  private final Set<ContextKind> enabledContexts;

  private final String promptStrategy;

  // ---- scan filtering whitelist ----
  private final Set<String> scanIncludes;

  private final String llmProvider; // openai | local
  private final String llmLocalBackend; // ollama | hf | vllm
  private final String llmLocalUrl;
  private final String llmLocalModel;

  private DpConfig(
      int threads,
      Path registryPath,
      Path outcomesPath,
      boolean includeBody,
      boolean registryReset,
      boolean debug,
      boolean keepWork,
      boolean noQualityFilter,
      int llmTotalTimeoutSec,
      int llmPerReqTimeoutSec,
      int bodyMaxChars,
      Set<ContextKind> enabledContexts,
      String openaiModel,
      @Nullable String llmCassettesDir,
      boolean disableRealLlm,
      @Nullable String compileMainScript,
      @Nullable String compileTestScript,
      int llmPollStepMs,
      String externalCompileClasspath,
      String workDir,
      Set<String> scanIncludes,
      String promptStrategy,
      String llmProvider,
      String llmLocalBackend,
      String llmLocalUrl,
      String llmLocalModel) {

    this.threads = threads;
    this.registryPath = registryPath;
    this.outcomesPath = outcomesPath;
    this.includeBody = includeBody;
    this.registryReset = registryReset;
    this.debug = debug;
    this.keepWork = keepWork;
    this.noQualityFilter = noQualityFilter;
    this.llmTotalTimeoutSec = llmTotalTimeoutSec;
    this.llmPerReqTimeoutSec = llmPerReqTimeoutSec;
    this.bodyMaxChars = bodyMaxChars;
    this.enabledContexts = enabledContexts;
    this.openaiModel = openaiModel;
    this.llmCassettesDir = llmCassettesDir;
    this.disableRealLlm = disableRealLlm;
    this.compileMainScript = compileMainScript;
    this.compileTestScript = compileTestScript;
    this.llmPollStepMs = llmPollStepMs;
    this.externalCompileClasspath = externalCompileClasspath;
    this.workDir = workDir;
    this.scanIncludes = scanIncludes;
    this.promptStrategy = promptStrategy;
    this.llmProvider = llmProvider;
    this.llmLocalBackend = llmLocalBackend;
    this.llmLocalUrl = llmLocalUrl;
    this.llmLocalModel = llmLocalModel;
  }

  public Set<String> scanIncludes() {
    return scanIncludes;
  }

  // ---- getters ----

  public int threads() {
    return threads;
  }

  public Path registryPath() {
    return registryPath;
  }

  public Path outcomesPath() {
    return outcomesPath;
  }

  public boolean includeBody() {
    return includeBody;
  }

  public boolean registryReset() {
    return registryReset;
  }

  public boolean debug() {
    return debug;
  }

  public boolean keepWork() {
    return keepWork;
  }

  public boolean noQualityFilter() {
    return noQualityFilter;
  }

  public int llmTotalTimeoutSec() {
    return llmTotalTimeoutSec;
  }

  public int llmPerReqTimeoutSec() {
    return llmPerReqTimeoutSec;
  }

  public int bodyMaxChars() {
    return bodyMaxChars;
  }

  public Set<ContextKind> enabledContexts() {
    return enabledContexts;
  }

  public String openaiModel() {
    return openaiModel;
  }

  public @Nullable String llmCassettesDir() {
    return llmCassettesDir;
  }

  public boolean disableRealLlm() {
    return disableRealLlm;
  }

  public @Nullable String compileMainScript() {
    return compileMainScript;
  }

  public @Nullable String compileTestScript() {
    return compileTestScript;
  }

  public int llmPollStepMs() {
    return llmPollStepMs;
  }

  public String externalCompileClasspath() {
    return externalCompileClasspath;
  }

  public String workDir() {
    return workDir;
  }

  public String promptStrategy() {
    return promptStrategy;
  }

  public String llmProvider() {
    return llmProvider;
  }

  public String llmLocalBackend() {
    return llmLocalBackend;
  }

  public String llmLocalUrl() {
    return llmLocalUrl;
  }

  public String llmLocalModel() {
    return llmLocalModel;
  }

  // ---- factory ----

  /**
   * Constructs a configuration instance using file, system properties, environment variables, and
   * defaults (in that order of precedence).
   */
  public static DpConfig fromEnv() {
    Map<String, String> file = loadConfigFile();
    Map<String, String> env = System.getenv();

    int threads =
        Math.max(
            2,
            getInt(
                "dp.threads", "DP_THREADS", Runtime.getRuntime().availableProcessors(), env, file));

    String regPath =
        firstNonBlank(
            file.get("dp.registry"),
            firstNonBlank(
                System.getProperty("dp.registry"),
                env.get("DP_REGISTRY"),
                "build/daikonpp_registry.jsonl"),
            "build/daikonpp_registry.jsonl");

    String outPath =
        firstNonBlank(
            file.get("dp.outcomes"),
            firstNonBlank(
                System.getProperty("dp.outcomes"),
                env.get("DP_OUTCOMES"),
                "build/daikonpp_outcomes.jsonl"),
            "build/daikonpp_outcomes.jsonl");

    boolean includeBody = getBool("dp.includeBody", "DP_INCLUDE_BODY", true, env, file);
    boolean registryReset = getBool("dp.registryReset", "DP_REGISTRY_RESET", true, env, file);
    boolean debug = getBool("dp.debug", "DP_DEBUG", false, env, file);
    boolean keepWork = getBool("dp.keepWork", "DP_KEEP_WORK", true, env, file);
    boolean noQualityFilter =
        getBool("dp.noQualityFilter", "DP_NO_QUALITY_FILTER", false, env, file);

    int llmTotalTimeoutSec =
        getInt("dp.llmTotalTimeoutSec", "DP_LLM_TOTAL_TIMEOUT_SEC", 180, env, file);

    int llmPerReqTimeoutSec =
        getInt("dp.llmPerReqTimeoutSec", "DP_LLM_REQ_TIMEOUT_SEC", 45, env, file);

    int bodyMaxChars = getInt("dp.bodyMaxChars", "DP_BODY_MAX_CHARS", 2000, env, file);

    Set<ContextKind> contexts = parseContexts(env, file);

    String openaiModel =
        firstNonBlank(
            file.get("dp.openaiModel"),
            firstNonBlank(
                System.getProperty("dp.openaiModel"), env.get("DP_OPENAI_MODEL"), "gpt-4.1"),
            "gpt-4.1");

    String llmCassettesDir = file.get("dp.llmCassettes");

    if (llmCassettesDir == null || llmCassettesDir.isBlank()) {
      llmCassettesDir = System.getProperty("dp.llmCassettes");
    }

    if (llmCassettesDir == null || llmCassettesDir.isBlank()) {
      llmCassettesDir = env.get("DP_LLM_CASSETTES");
    }

    if (llmCassettesDir != null && llmCassettesDir.isBlank()) {
      llmCassettesDir = null;
    }

    boolean disableRealLlm = getBool("dp.disableRealLlm", "DP_DISABLE_REAL_LLM", false, env, file);

    String compileMainScript =
        firstNonBlankNullable(
            file.get("dp.compileMainScript"),
            System.getProperty("dp.compileMainScript"),
            env.get("DP_COMPILE_MAIN_SCRIPT"));

    String compileTestScript =
        firstNonBlankNullable(
            file.get("dp.compileTestScript"),
            System.getProperty("dp.compileTestScript"),
            env.get("DP_COMPILE_TEST_SCRIPT"));

    int llmPollStepMs = getInt("dp.llmPollStepMs", "DP_LLM_POLL_STEP_MS", 1500, env, file);

    String externalCompileClasspath =
        firstNonBlank(
            file.get("dp.externalCompileClasspath"),
            firstNonBlank(
                System.getProperty("dp.externalCompileClasspath"),
                env.get("DP_EXTERNAL_COMPILE_CP"),
                ""),
            "");

    String workDir =
        firstNonBlank(
            file.get("dp.workDir"),
            firstNonBlank(
                System.getProperty("dp.workDir"),
                env.get("DP_WORKDIR"),
                System.getProperty("java.io.tmpdir") + "/daikonpp_work"),
            System.getProperty("java.io.tmpdir") + "/daikonpp_work");

    String promptStrategy =
        firstNonBlank(
            file.get("dp.promptStrategy"),
            firstNonBlank(
                System.getProperty("dp.promptStrategy"), env.get("DP_PROMPT_STRATEGY"), "baseline"),
            "baseline");

    Set<String> scanIncludes =
        parseCsvSet(
            firstNonBlankNullable(
                file.get("dp.scanIncludes"),
                System.getProperty("dp.scanIncludes"),
                env.get("DP_SCAN_INCLUDES")));

    String llmProvider =
        firstNonBlank(
            file.get("dp.llmProvider"),
            firstNonBlank(
                System.getProperty("dp.llmProvider"), env.get("DP_LLM_PROVIDER"), "openai"),
            "openai");

    String llmLocalBackend =
        firstNonBlank(
            file.get("dp.llmLocalBackend"),
            firstNonBlank(
                System.getProperty("dp.llmLocalBackend"),
                env.get("DP_LLM_LOCAL_BACKEND"),
                "ollama"),
            "ollama");

    String llmLocalModel =
        firstNonBlank(
            file.get("dp.llmLocalModel"),
            firstNonBlank(
                System.getProperty("dp.llmLocalModel"),
                env.get("DP_LLM_LOCAL_MODEL"),
                "qwen2.5:7b"),
            "qwen2.5:7b");

    String llmLocalUrl =
        firstNonBlank(
            file.get("dp.llmLocalUrl"),
            firstNonBlank(
                System.getProperty("dp.llmLocalUrl"),
                env.get("DP_LLM_LOCAL_URL"),
                "http://localhost:11434"),
            "http://localhost:11434");

    llmProvider = llmProvider.toLowerCase(Locale.ROOT);
    llmLocalBackend = llmLocalBackend.toLowerCase(Locale.ROOT);

    if (!llmProvider.equalsIgnoreCase("openai") && !llmProvider.equals("local")) {
      throw new IllegalArgumentException("Invalid DP_LLM_PROVIDER: " + llmProvider);
    }

    if (llmProvider.equalsIgnoreCase("local")) {
      if (llmLocalModel.isBlank()) {
        throw new IllegalArgumentException(
            "DP_LLM_LOCAL_MODEL must be set when DP_LLM_PROVIDER=local");
      }
    }

    return new DpConfig(
        threads,
        Path.of(regPath).toAbsolutePath().normalize(),
        Path.of(outPath).toAbsolutePath().normalize(),
        includeBody,
        registryReset,
        debug,
        keepWork,
        noQualityFilter,
        llmTotalTimeoutSec,
        llmPerReqTimeoutSec,
        bodyMaxChars,
        contexts,
        openaiModel,
        llmCassettesDir,
        disableRealLlm,
        compileMainScript,
        compileTestScript,
        llmPollStepMs,
        externalCompileClasspath,
        workDir,
        scanIncludes,
        promptStrategy,
        llmProvider,
        llmLocalBackend,
        llmLocalUrl,
        llmLocalModel);
  }

  // ---- helpers ----

  private static boolean getBool(
      String sysKey,
      String envKey,
      boolean def,
      Map<String, String> env,
      Map<String, String> file) {

    String v = file.get(sysKey);
    if (v == null) v = System.getProperty(sysKey);
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

  private static int getInt(
      String sysKey, String envKey, int def, Map<String, String> env, Map<String, String> file) {

    String v = file.get(sysKey);
    if (v == null) v = System.getProperty(sysKey);
    if (v == null) v = env.get(envKey);
    if (v == null || v.isBlank()) return def;

    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static @NonNull String firstNonBlank(
      @Nullable String a, @Nullable String b, @NonNull String c) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return c;
  }

  /**
   * Parses context configuration.
   *
   * <p>Default: all contexts enabled.
   *
   * <p>If overridden: only specified contexts are enabled.
   */
  private static Set<ContextKind> parseContexts(Map<String, String> env, Map<String, String> file) {

    EnumSet<ContextKind> defaults = EnumSet.allOf(ContextKind.class);

    String v = file.get("dp.contexts");
    if (v == null) v = System.getProperty("dp.contexts");
    if (v == null) v = env.get("DP_CONTEXTS");

    if (v == null || v.isBlank()) {
      return defaults;
    }

    EnumSet<ContextKind> set = EnumSet.noneOf(ContextKind.class);

    for (String s : v.split(",")) {
      try {
        set.add(ContextKind.valueOf(s.trim().toUpperCase()));
      } catch (IllegalArgumentException ignored) {
      }
    }

    return set.isEmpty() ? defaults : set;
  }

  /**
   * Loads configuration from {@code dpconfig.properties} if present.
   *
   * <p>This file is optional. Missing or invalid files are silently ignored.
   */
  private static Map<String, String> loadConfigFile() {
    java.util.Map<String, String> map = new java.util.HashMap<>();

    java.nio.file.Path path = java.nio.file.Path.of("dpconfig.properties");

    if (!java.nio.file.Files.exists(path)) {
      return map;
    }

    java.util.Properties props = new java.util.Properties();
    try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
      props.load(in);
      for (String name : props.stringPropertyNames()) {
        String value = props.getProperty(name);
        if (value != null) {
          map.put(name, value);
        }
      }
    } catch (Exception ignored) {
    }

    return map;
  }

  public void printSummary() {
    System.out.println("==== Daikon++ Config ====");

    System.out.println("threads = " + threads);
    System.out.println("registryPath = " + registryPath);
    System.out.println("outcomesPath = " + outcomesPath);

    System.out.println("includeBody = " + includeBody);
    System.out.println("registryReset = " + registryReset);
    System.out.println("debug = " + debug);
    System.out.println("keepWork = " + keepWork);
    System.out.println("noQualityFilter = " + noQualityFilter);

    System.out.println("llmTotalTimeoutSec = " + llmTotalTimeoutSec);
    System.out.println("llmPerReqTimeoutSec = " + llmPerReqTimeoutSec);
    System.out.println("bodyMaxChars = " + bodyMaxChars);

    System.out.println("enabledContexts = " + enabledContexts);

    System.out.println("openaiModel = " + openaiModel);
    System.out.println("llmCassettesDir = " + llmCassettesDir);
    System.out.println("disableRealLlm = " + disableRealLlm);

    System.out.println("compileMainScript = " + compileMainScript);
    System.out.println("compileTestScript = " + compileTestScript);

    System.out.println("llmPollStepMs = " + llmPollStepMs);

    System.out.println("externalCompileClasspath = " + externalCompileClasspath);

    System.out.println("workDir = " + workDir);

    System.out.println("promptStrategy = " + promptStrategy);

    System.out.println("scanIncludes = " + scanIncludes);

    System.out.println("llmProvider = " + llmProvider);
    System.out.println("llmLocalBackend = " + llmLocalBackend);
    System.out.println("llmLocalUrl = " + llmLocalUrl);
    System.out.println("llmLocalModel = " + llmLocalModel);

    System.out.println("=========================");
  }

  private static @Nullable String firstNonBlankNullable(
      @Nullable String a, @Nullable String b, @Nullable String c) {

    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    if (c != null && !c.isBlank()) return c;
    return null;
  }

  private static Set<String> parseCsvSet(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return java.util.Collections.emptySet();
    }

    Set<String> result = new java.util.LinkedHashSet<>();

    for (String part : value.split(",")) {
      String s = part.trim();
      if (s.isEmpty()) continue;

      // normalize
      s = s.replace("\\", "/");

      // convert package-style to path-style
      if (s.contains(".")) {
        s = s.replace(".", "/");
      }

      // 🔴 VALIDATION
      if (!s.matches("[a-zA-Z0-9_/]+")) {
        throw new IllegalArgumentException("Invalid dp.scanIncludes entry: '" + part + "'");
      }

      if (s.contains("//")) {
        throw new IllegalArgumentException(
            "Invalid dp.scanIncludes (double slash): '" + part + "'");
      }

      result.add(s);
    }

    return java.util.Collections.unmodifiableSet(result);
  }
}
