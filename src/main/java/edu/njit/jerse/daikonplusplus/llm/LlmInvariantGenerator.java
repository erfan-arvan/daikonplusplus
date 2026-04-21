package edu.njit.jerse.daikonplusplus.llm;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.javaparser.StaticJavaParser;
import com.openai.models.ChatModel;
import edu.njit.jerse.daikonplusplus.config.DpConfig;
import edu.njit.jerse.daikonplusplus.llm.prompt.Prompt;
import edu.njit.jerse.daikonplusplus.llm.prompt.PromptContext;
import edu.njit.jerse.daikonplusplus.llm.prompt.PromptStrategy;
import edu.njit.jerse.daikonplusplus.llm.prompt.PromptStrategyFactory;
import edu.njit.jerse.daikonplusplus.model.InvariantSpec;
import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.nio.file.Path;
import java.util.*;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * LLM-backed invariant generator that uses a pluggable {@link LlmClient} to propose candidate
 * invariants for a given {@link ProgramPoint}. This allows the same class to run in:
 *
 * <ul>
 *   <li><b>Real mode</b> — uses the live OpenAI API via {@link RealOpenAILlmClient}.
 *   <li><b>Replay mode</b> — uses recorded (mocked) responses via {@link ReplayingLlmClient}.
 *   <li><b>Record mode</b> — records new responses into cassette files via {@link
 *       RecordingCompositeLlmClient}.
 * </ul>
 *
 * <p>The client automatically chooses the correct mode based on environment variables:
 *
 * <ul>
 *   <li>{@code DP_LLM_CASSETTES}: path to directory containing (or to store) cassette JSON files.
 *   <li>{@code DP_DISABLE_REAL_LLM=1}: disables real network calls (for CI or offline testing).
 *   <li>{@code DP_OPENAI_MODEL}: optional model override (e.g., "gpt-4.1-mini").
 * </ul>
 *
 * <p>The core prompt structure (system + user messages) remains unchanged. This class handles:
 * building messages, invoking the {@link LlmClient}, filtering invalid or redundant expressions,
 * and returning final {@link InvariantSpec}s.
 */
public final class LlmInvariantGenerator {

  /** Pluggable LLM backend (real, replay, or record). */
  private final LlmClient llm;

  /** Maximum number of invariants requested per program point. */
  private final int maxInvariants;

  private final DpConfig config;
  private final PromptStrategy promptStrategy;

  private static boolean printedModelOnce = false;
  private static boolean printedStrategyOnce = false;

  /**
   * Creates a new generator using environment variables for configuration.
   *
   * <p>Automatically selects model and LLM backend based on {@code DP_*} environment variables.
   *
   * @param maxInvariants maximum number of invariants to request
   */
  public LlmInvariantGenerator(DpConfig config, int maxInvariants) {
    this.config = Objects.requireNonNull(config);
    this.maxInvariants = Math.max(1, maxInvariants);
    this.llm = buildLlmFromEnv(config);
    this.promptStrategy = PromptStrategyFactory.create(config.promptStrategy());
  }

  /**
   * Creates a new generator with an explicit {@link ChatModel}.
   *
   * <p>Still respects cassette and disable flags ({@code DP_LLM_CASSETTES}, {@code
   * DP_DISABLE_REAL_LLM}).
   *
   * @param model chat model to use (e.g., {@link ChatModel#GPT_4_1_MINI})
   * @param maxInvariants maximum number of invariants to request
   */
  public LlmInvariantGenerator(DpConfig config, ChatModel model, int maxInvariants) {
    this.config = Objects.requireNonNull(config);
    this.maxInvariants = Math.max(1, maxInvariants);
    this.llm = buildLlmFromEnv(config, model);
    this.promptStrategy = PromptStrategyFactory.create(config.promptStrategy());
  }

  /**
   * Dependency-injection constructor. Used mainly for testing, where a mock or replay
   * implementation can be provided directly.
   *
   * @param llm LLM backend implementation
   * @param maxInvariants maximum number of invariants to request
   */
  public LlmInvariantGenerator(DpConfig config, LlmClient llm, int maxInvariants) {
    this.config = Objects.requireNonNull(config);
    this.llm = Objects.requireNonNull(llm);
    this.maxInvariants = Math.max(1, maxInvariants);
    this.promptStrategy = PromptStrategyFactory.create(config.promptStrategy());
  }

  /**
   * Generates candidate invariants for a given program point using the selected LLM backend.
   *
   * @param point program point to analyze
   * @param methodBody optional method body (for context only)
   * @return a list of syntactically valid and quality-filtered {@link InvariantSpec}s
   */
  public List<InvariantSpec> proposeInvariants(
      ProgramPoint point,
      Map<String, String> inScope,
      String methodBody,
      String methodJavadoc,
      String enclosingClassDoc,
      String typeDoc,
      String callSiteContext,
      String inputOutputExamples,
      String calleeDoc) {

    final boolean isExit = point.kind() == ProgramPointKind.METHOD_EXIT;

    try {

      // ----- Build prompt via strategy -----
      PromptContext ctx =
          new PromptContext(
              point,
              inScope,
              methodBody,
              methodJavadoc,
              enclosingClassDoc,
              typeDoc,
              callSiteContext,
              inputOutputExamples,
              calleeDoc,
              maxInvariants);

      Prompt prompt = promptStrategy.buildPrompt(ctx);

      final String system = prompt.systemMessage();
      final String user = prompt.userMessage();

      if (!printedStrategyOnce && config.debug()) {
        printedStrategyOnce = true;
        System.out.println("[DP-LLM] Strategy: " + promptStrategy.name());
      }

      if (config.debug()) {
        System.out.println("[DP] LLM REQUEST → " + point.kind() + " :: " + point.elementId());
        if (!inScope.isEmpty()) {
          System.out.println("[DP] Scope: " + inScope);
        }
      }

      // ----- Structured request via pluggable LlmClient -----
      List<InvariantsOut.Item> items;
      try {
        items = llm.complete(system, user);
      } catch (Exception ex) {
        return List.of();
      }

      // ----- Parse + filter + dedup + limit -----
      List<InvariantSpec> kept = new ArrayList<>(Math.min(items.size(), maxInvariants));
      Set<String> seenExprs = new LinkedHashSet<>();

      for (InvariantsOut.Item it : items) {
        String expr = (it.expression == null) ? "" : it.expression.trim();
        if (expr.isEmpty()) continue;

        // Skip unparseable expressions
        Optional<String> parsed = parseableExpression(expr);

        if (parsed.isEmpty()) {
          if (config.debug()) System.out.println("[DP-LLM] drop(parse): " + expr);
          continue;
        }

        if (!expr.equals(parsed.get()) && config.debug()) {
          System.out.println("[DP-LLM] salvage(parse): " + parsed.get());
        }

        expr = parsed.get();

        // Skip low-quality ones unless filter disabled
        if (!config.noQualityFilter() && !InvariantQualityFilter.keep(expr, inScope, isExit)) {
          if (config.debug()) System.out.println("[DP-LLM] drop(filter): " + expr);
          continue;
        }

        // Deduplicate
        if (!seenExprs.add(expr)) continue;

        // Normalize metadata
        final Map<String, String> meta =
            (it.meta == null || it.meta.isEmpty())
                ? Collections.emptyMap()
                : it.meta.stream()
                    .filter(kv -> kv != null && kv.key != null && kv.value != null)
                    .collect(
                        java.util.stream.Collectors.toMap(
                            kv -> kv.key, kv -> kv.value, (a, b) -> a, LinkedHashMap::new));

        kept.add(new InvariantSpec(expr, (it.rationale == null ? "" : it.rationale), meta));

        if (kept.size() >= maxInvariants) break;
      }

      if (config.debug()) {
        System.out.println(
            "[DP] LLM RESPONSE "
                + point.kind()
                + " :: "
                + point.elementId()
                + " → "
                + kept.size()
                + " specs");
        for (String ex : seenExprs) System.out.println("[DP]   • " + ex);
      }

      return kept;

    } catch (Exception e) {
      if (e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {

        Thread.currentThread().interrupt();
        if (config.debug()) {
          System.err.println("[DP-LLM] skipped (interrupted): " + point.elementId());
        }
        return List.of();
      }

      System.err.println("[DP-LLM] FAILURE for " + point.elementId() + " → " + e);
      e.printStackTrace(System.err);
      return List.of();
    }
  }

  // =====================================================================
  // Utilities and environment configuration
  // =====================================================================

  /** Checks if a string parses as a valid Java expression. */
  private static boolean isParsableExpression(String expr) {
    try {
      StaticJavaParser.parseExpression(expr);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private static Optional<String> parseableExpression(String expr) {
    if (expr == null || expr.isBlank()) return Optional.empty();

    String trimmed = expr.trim();

    try {
      StaticJavaParser.parseExpression(trimmed);
      return Optional.of(trimmed);
    } catch (Exception ex) {
      String cleaned = sanitizeExpression(trimmed);

      if (!cleaned.equals(trimmed)) {
        try {
          StaticJavaParser.parseExpression(cleaned);
          return Optional.of(cleaned);
        } catch (Exception ignored) {
        }
      }

      return Optional.empty();
    }
  }

  private static String sanitizeExpression(String expr) {
    String e = expr.trim();
    e = e.replaceAll("[,}\\]]+$", "").trim();
    e = e.replace("```java", "").replace("```", "").trim();
    return e;
  }

  /** Resolves model name string to a {@link ChatModel}. */
  private static Optional<ChatModel> resolveModel(@Nullable String maybe) {
    if (maybe == null || maybe.isBlank()) return Optional.empty();
    String m = maybe.trim().toLowerCase(Locale.ROOT);
    switch (m) {
      case "gpt-4.1":
        return Optional.of(ChatModel.GPT_4_1);
      case "gpt-4.1-mini":
        return Optional.of(ChatModel.GPT_4_1_MINI);
      case "gpt-4o":
        return Optional.of(ChatModel.GPT_4O);
      case "gpt-4o-mini":
        return Optional.of(ChatModel.GPT_4O_MINI);
      case "gpt-5":
        return Optional.of(ChatModel.GPT_5);
      default:
        return Optional.of(ChatModel.GPT_4_1_MINI);
    }
  }

  private static LlmClient buildLlmFromEnv(DpConfig config) {
    ChatModel model =
        resolveModel(config.openaiModel())
            .orElseGet(
                () -> {
                  if (config.debug()) {
                    System.out.println("[DP-LLM] Unknown model, falling back to GPT_4_1_MINI");
                  }
                  return ChatModel.GPT_4_1_MINI;
                });

    if (!printedModelOnce) {
      printedModelOnce = true;
      System.out.println("[DP-LLM] Using model: " + model);
    }

    return buildLlmFromEnv(config, model);
  }

  private static LlmClient buildLlmFromEnv(DpConfig config, ChatModel model) {
    String cassetteDir = config.llmCassettesDir();
    boolean disableReal = config.disableRealLlm();

    if (cassetteDir != null && !cassetteDir.isBlank()) {
      Path dir = Path.of(cassetteDir);
      LlmClient replay = new ReplayingLlmClient(dir);
      if (disableReal) return replay;
      return new RecordingCompositeLlmClient(replay, new RealOpenAILlmClient(model), dir);
    }

    return new RealOpenAILlmClient(model);
  }

  // =====================================================================
  // Structured output DTOs
  // =====================================================================

  /** DTO representing the structured output schema from the model. */
  @JsonClassDescription("A list of invariant proposals for a program point.")
  public static final class InvariantsOut {
    @JsonPropertyDescription("The invariants proposed by the model.")
    public List<Item> invariants = Collections.emptyList();

    /** One invariant entry (expression + optional rationale/metadata). */
    @JsonClassDescription("One invariant entry.")
    public static final class Item {
      @JsonPropertyDescription("A pure Java boolean expression valid at the point.")
      public String expression;

      @JsonPropertyDescription("Optional rationale for why this invariant might hold.")
      public String rationale;

      @JsonPropertyDescription("Optional metadata as key/value pairs.")
      public List<KV> meta;

      public Item() {
        this.expression = "";
        this.rationale = "";
        this.meta = Collections.emptyList();
      }
    }

    /** Key/value metadata pair attached to an invariant. */
    @JsonClassDescription("Key/value metadata pair.")
    public static final class KV {
      public String key;
      public String value;

      public KV() {
        this.key = "";
        this.value = "";
      }
    }

    public InvariantsOut() {
      this.invariants = Collections.emptyList();
    }
  }
}
