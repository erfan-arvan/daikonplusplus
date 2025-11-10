package edu.njit.jerse.daikonplusplus.llm;

import static edu.njit.jerse.daikonplusplus.util.DpFlags.*;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.javaparser.StaticJavaParser;
import com.openai.models.ChatModel;
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

  /** Debug flag (from {@link edu.njit.jerse.daikonplusplus.util.DpFlags}). */
  final boolean DEBUG = debug();

  /** Quality filter disable flag (from {@link edu.njit.jerse.daikonplusplus.util.DpFlags}). */
  final boolean NO_QF = noQualityFilter();

  /**
   * Creates a new generator using environment variables for configuration.
   *
   * <p>Automatically selects model and LLM backend based on {@code DP_*} environment variables.
   *
   * @param maxInvariants maximum number of invariants to request
   */
  public LlmInvariantGenerator(int maxInvariants) {
    this.maxInvariants = Math.max(1, maxInvariants);
    this.llm = buildLlmFromEnv();
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
  public LlmInvariantGenerator(ChatModel model, int maxInvariants) {
    this.maxInvariants = Math.max(1, maxInvariants);
    this.llm = buildLlmFromEnv(model);
  }

  /**
   * Dependency-injection constructor. Used mainly for testing, where a mock or replay
   * implementation can be provided directly.
   *
   * @param llm LLM backend implementation
   * @param maxInvariants maximum number of invariants to request
   */
  public LlmInvariantGenerator(LlmClient llm, int maxInvariants) {
    this.llm = Objects.requireNonNull(llm);
    this.maxInvariants = Math.max(1, maxInvariants);
  }

  /**
   * Generates candidate invariants for a given program point using the selected LLM backend.
   *
   * @param point program point to analyze
   * @param inScopeNames map of variable name → type (in-scope at the point)
   * @param methodBody optional method body (for context only)
   * @return a list of syntactically valid and quality-filtered {@link InvariantSpec}s
   */
  public List<InvariantSpec> proposeInvariants(
      ProgramPoint point, Map<String, String> inScopeNames, @Nullable String methodBody) {

    final boolean isExit = point.kind() == ProgramPointKind.METHOD_EXIT;

    try {
      // ----- Build system and user messages -----
      final String system = systemMessage();
      final String user =
          isExit
              ? userMessageForExit(point, inScopeNames, maxInvariants, methodBody)
              : userMessageForEntry(point, inScopeNames, maxInvariants, methodBody);

      if (DEBUG) {
        System.out.println("[DP] LLM REQUEST → " + point.kind() + " :: " + point.elementId());
        if (!inScopeNames.isEmpty()) {
          System.out.println("[DP] Scope: " + inScopeNames);
        }
      }

      // ----- Structured request via pluggable LlmClient -----
      List<InvariantsOut.Item> items = llm.complete(system, user);

      // ----- Parse + filter + dedup + limit -----
      List<InvariantSpec> kept = new ArrayList<>(Math.min(items.size(), maxInvariants));
      Set<String> seenExprs = new LinkedHashSet<>();

      for (InvariantsOut.Item it : items) {
        String expr = (it.expression == null) ? "" : it.expression.trim();
        if (expr.isEmpty()) continue;

        // Skip unparseable expressions
        if (!isParsableExpression(expr)) {
          if (DEBUG) System.out.println("[DP-LLM] drop(parse): " + expr);
          continue;
        }

        // Skip low-quality ones unless filter disabled
        if (!NO_QF && !InvariantQualityFilter.keep(expr, inScopeNames, isExit)) {
          if (DEBUG) System.out.println("[DP-LLM] drop(filter): " + expr);
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

      if (DEBUG) {
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
      if (DEBUG) {
        System.err.println("[DP-LLM] error for " + point.elementId() + " → " + e.getMessage());
      }
      return List.of();
    }
  }

  // =====================================================================
  // Prompt construction
  // =====================================================================

  /** Returns the standard system message shared by all prompts. */
  private static String systemMessage() {
    return String.join(
        "\n",
        "You are a program analysis assistant.",
        "Given a Java program point, propose useful boolean invariants.",
        "Rules:",
        "- Return ONLY JSON matching the provided schema.",
        "- Expressions must be valid Java boolean expressions at the point.",
        "- Use only listed in-scope names (e.g., parameters, 'this', fields if stated).",
        "- Avoid side effects, new helpers, and tautologies.",
        "- Prefer nullness, range, size, and ordering relations.");
  }

  /** Builds the user message for a method entry program point. */
  private String userMessageForEntry(
      ProgramPoint pt, Map<String, String> scope, int k, @Nullable String methodBody) {
    StringBuilder sb = new StringBuilder();
    sb.append("PROGRAM POINT: ").append(pt.elementId()).append(" [METHOD_ENTRY]\n");
    sb.append("You may reference ONLY these names:\n");
    scope.forEach((n, t) -> sb.append("- ").append(n).append(" : ").append(t).append("\n"));
    sb.append(
        "\nSTRICT RULES:\n"
            + "- Single-line Java boolean expressions only.\n"
            + "- No streams/lambdas/method refs; no side effects.\n"
            + "- Use short-circuit null checks.\n");
    if (methodBody != null && !methodBody.isBlank()) {
      sb.append("\nMETHOD SOURCE (abridged, do NOT reference locals):\n")
          .append(methodBody)
          .append("\n");
    }
    sb.append("\nReturn up to ").append(k).append(" expressions.");
    return sb.toString();
  }

  /** Builds the user message for a method exit program point. */
  private String userMessageForExit(
      ProgramPoint pt, Map<String, String> scope, int k, @Nullable String methodBody) {
    StringBuilder sb = new StringBuilder();
    sb.append("PROGRAM POINT: ").append(pt.elementId()).append(" [METHOD_EXIT]\n");
    sb.append("You may reference ONLY these names (params + 'result' if non-void):\n");
    scope.forEach((n, t) -> sb.append("- ").append(n).append(" : ").append(t).append("\n"));
    sb.append(
        "\nSTRICT RULES:\n"
            + "- Prefer relations involving 'result' and parameters.\n"
            + "- No streams/lambdas/method refs; no side effects.\n"
            + "- Use short-circuit null checks.\n");
    if (methodBody != null && !methodBody.isBlank()) {
      sb.append("\nMETHOD SOURCE (abridged, do NOT reference locals):\n")
          .append(methodBody)
          .append("\n");
    }
    sb.append("\nReturn up to ").append(k).append(" expressions.");
    return sb.toString();
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

  /**
   * Builds an {@link LlmClient} using environment variables.
   *
   * <p>Priority:
   *
   * <ol>
   *   <li>If {@code DP_LLM_CASSETTES} is set:
   *       <ul>
   *         <li>If {@code DP_DISABLE_REAL_LLM=1}, returns {@link ReplayingLlmClient}.
   *         <li>Otherwise, returns {@link RecordingCompositeLlmClient} (replay + real + record).
   *       </ul>
   *   <li>Else, returns {@link RealOpenAILlmClient} directly.
   * </ol>
   */
  private static LlmClient buildLlmFromEnv() {
    final @Nullable String envModel = System.getenv("DP_OPENAI_MODEL");
    ChatModel model = resolveModel(envModel).orElse(ChatModel.GPT_4_1_MINI);
    return buildLlmFromEnv(model);
  }

  /** Variant of {@link #buildLlmFromEnv()} with an explicit model. */
  private static LlmClient buildLlmFromEnv(ChatModel model) {
    String cassetteDir = System.getenv("DP_LLM_CASSETTES");
    boolean disableReal = "1".equals(System.getenv("DP_DISABLE_REAL_LLM"));

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
