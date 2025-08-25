package edu.njit.jerse.daikonplusplus.llm;

import static edu.njit.jerse.daikonplusplus.util.DpFlags.*;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.javaparser.StaticJavaParser;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonSchemaLocalValidation;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import edu.njit.jerse.daikonplusplus.model.InvariantSpec;
import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.util.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * OpenAI-backed invariant generator that asks a ChatGPT model to produce structured invariants for
 * a given program point.
 *
 * <p>This client uses OpenAI Structured Outputs so that the model's response must conform exactly
 * to the {@link InvariantsOut} JSON schema derived from the nested DTO classes below.
 *
 * <p><b>Environment:</b> The client reads credentials from standard OpenAI environment variables
 * via {@code OpenAIOkHttpClient.fromEnv()}:
 *
 * <ul>
 *   <li>OPENAI_API_KEY (required)
 *   <li>OPENAI_ORG_ID (optional)
 *   <li>OPENAI_PROJECT_ID (optional)
 * </ul>
 *
 * <p><b>Model selection:</b> You can pass a {@link ChatModel} at construction time, or set {@code
 * DP_OPENAI_MODEL} to something like {@code gpt-4.1-mini}; if absent, we default to {@link
 * ChatModel#GPT_4_1_MINI}.
 */
public final class OpenAIInvariantGeneratorClient {

  private final OpenAIClient client;
  private final ChatModel model;
  private final int maxInvariants;

  final boolean DEBUG = debug();
  final boolean NO_QF = noQualityFilter();

  private final List<String> classpathEntries;

  /**
   * Creates a new generator using env vars for credentials and a default model.
   *
   * @param maxInvariants maximum number of invariants to request from the model
   */
  public OpenAIInvariantGeneratorClient(int maxInvariants) {
    this.client = OpenAIOkHttpClient.fromEnv();
    final @Nullable String envModel = System.getenv("DP_OPENAI_MODEL");
    this.model = resolveModel(envModel).orElse(ChatModel.GPT_4_1_MINI);
    this.maxInvariants = Math.max(1, maxInvariants);
    this.classpathEntries = Collections.emptyList();
  }

  /**
   * Creates a new generator using a specific {@link ChatModel}.
   *
   * @param model chat model (e.g., {@code ChatModel.GPT_4_1_MINI})
   * @param maxInvariants maximum number of invariants to request
   */
  public OpenAIInvariantGeneratorClient(ChatModel model, int maxInvariants) {
    this.client = OpenAIOkHttpClient.fromEnv();
    this.model = Objects.requireNonNull(model);
    this.maxInvariants = Math.max(1, maxInvariants);
    this.classpathEntries = Collections.emptyList();
  }

  public OpenAIInvariantGeneratorClient(int maxInvariants, List<String> classpathEntries) {
    this.client = OpenAIOkHttpClient.fromEnv();
    final @Nullable String envModel = System.getenv("DP_OPENAI_MODEL");
    this.model = resolveModel(envModel).orElse(ChatModel.GPT_4_1_MINI);
    this.maxInvariants = Math.max(1, maxInvariants);
    this.classpathEntries =
        (classpathEntries == null) ? Collections.emptyList() : List.copyOf(classpathEntries);
  }

  /**
   * Proposes invariants for the provided program point.
   *
   * <p><b>Contract:</b> The {@code inScopeNames} map should contain variable names and their Java
   * types that are in scope at the entry point (e.g., parameters).
   *
   * @param point program point to analyze
   * @param inScopeNames map of varName -> fully-qualified or best-effort type
   * @return a list of syntactically valid {@link InvariantSpec}s (may be empty)
   */
  public List<InvariantSpec> proposeInvariants(
      ProgramPoint point,
      Map<String, String> inScopeNames,
      @org.checkerframework.checker.nullness.qual.Nullable String methodBody) {

    final boolean isExit = point.kind() == ProgramPointKind.METHOD_EXIT;

    try {
      // ----- build messages -----
      final String system = systemMessage();
      final String user =
          isExit
              ? userMessageForExit(point, inScopeNames, maxInvariants, methodBody)
              : userMessageForEntry(point, inScopeNames, maxInvariants, methodBody);
      if (DEBUG) {
        System.out.println("[DP] LLM REQUEST → " + point.kind() + " :: " + point.elementId());
        if (!inScopeNames.isEmpty()) {
          System.out.println(
              "[DP] Scope for " + point.kind() + " :: " + point.elementId() + " → " + inScopeNames);
        }
      }

      // ----- structured request -----
      StructuredChatCompletionCreateParams<InvariantsOut> params =
          ChatCompletionCreateParams.builder()
              .model(model)
              .responseFormat(InvariantsOut.class, JsonSchemaLocalValidation.YES)
              .addSystemMessage(system)
              .addUserMessage(user)
              .build();

      StructuredChatCompletion<InvariantsOut> completion =
          client.chat().completions().create(params);

      // flatten all items (don't limit yet; we limit after filtering)
      List<InvariantsOut.Item> items =
          completion.choices().stream()
              .flatMap(c -> c.message().content().stream()) // List<InvariantsOut>
              .filter(Objects::nonNull)
              .flatMap(out -> out.invariants.stream()) // Stream<Item>
              .filter(Objects::nonNull)
              .collect(java.util.stream.Collectors.toList());

      // ----- parse + quality filter + dedup + post-limit -----
      List<InvariantSpec> kept = new ArrayList<>(Math.min(items.size(), maxInvariants));
      java.util.LinkedHashSet<String> seenExprs = new java.util.LinkedHashSet<>();

      for (InvariantsOut.Item it : items) {
        String expr = (it.expression == null) ? "" : it.expression.trim();
        if (expr.isEmpty()) continue;

        // basic syntax check first (fast fail)
        if (!isParsableExpression(expr)) {
          if (DEBUG) System.out.println("[DP-LLM] drop(parse): " + expr);
          continue;
        }

        // quality filter (unless bypassed by env)
        if (!NO_QF) {
          if (!InvariantQualityFilter.keep(expr, inScopeNames, isExit, this.classpathEntries)) {
            if (DEBUG) System.out.println("[DP-LLM] drop(filter " + point.kind() + "): " + expr);
            continue;
          }
        }

        // dedup by expression text
        if (!seenExprs.add(expr)) continue;

        // always produce a NON-NULL meta map
        final Map<String, String> metaMap =
            (it.meta == null || it.meta.isEmpty())
                ? java.util.Collections.emptyMap()
                : it.meta.stream()
                    .filter(kv -> kv != null && kv.key != null && kv.value != null)
                    .collect(
                        java.util.stream.Collectors.toMap(
                            kv -> kv.key,
                            kv -> kv.value,
                            (a, b) -> a,
                            java.util.LinkedHashMap::new));

        kept.add(new InvariantSpec(expr, (it.rationale == null ? "" : it.rationale), metaMap));

        if (kept.size() >= maxInvariants) break; // limit AFTER filtering
      }

      if (DEBUG) {
        System.out.println(
            "[DP] LLM RESPONSE  "
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
        System.err.println(
            "[DP-LLM] error for "
                + point.kind()
                + " :: "
                + point.elementId()
                + " → "
                + e.getMessage());
      }
      return java.util.List.of();
    }
  }

  // ---------- prompt helpers ----------

  private static String systemMessage() {
    return String.join(
        "\n",
        "You are a program analysis assistant.",
        "Given a Java program point, propose useful boolean invariants.",
        "Rules:",
        "- Return ONLY JSON that matches the provided schema.",
        "- Expressions must be Java boolean expressions valid at the point.",
        "- Use only names that are in scope (e.g., parameters, 'this', fields if stated).",
        "- No side effects.",
        "- Prefer short-circuit patterns to avoid NPE (e.g., x != null && x.length > 0).",
        "- Avoid heavy operations (streams/regex) unless essential.",
        // cut fluff:
        "- DO NOT emit tautologies (e.g., A || !A, true).",
        "- DO NOT emit invariants that are always true by type/range, e.g.:",
        "  - a <= Integer.MAX_VALUE, a >= Integer.MIN_VALUE",
        "  - args.length >= 0",
        "  - xs instanceof List when xs is declared List",
        "- DO NOT emit vacuous disjunctions like '!xs.isEmpty() || xs.isEmpty()'.",
        "- Prefer invariants that mention at least one in-scope variable and",
        "  constrain nullness, ranges, sizes, ordering, or cross-variable relations.");
  }

  private String userMessageForEntry(
      ProgramPoint pt,
      Map<String, String> scope,
      int k,
      @org.checkerframework.checker.nullness.qual.Nullable String methodBody) {
    StringBuilder sb = new StringBuilder();
    sb.append("PROGRAM POINT: ").append(pt.elementId()).append(" [METHOD_ENTRY]\n");
    sb.append("You may reference ONLY these names:\n");
    scope.forEach((n, t) -> sb.append("- ").append(n).append(" : ").append(t).append("\n"));
    sb.append("\nSTRICT RULES:\n")
        .append("- Return single-line Java boolean expressions only.\n")
        .append("- NO streams/lambdas/method refs; NO new helpers; NO side effects.\n")
        .append("- Use short-circuit null checks to avoid NPEs.\n")
        .append("- Do NOT reference locals or fields not listed above.\n");
    if (methodBody != null && !methodBody.isBlank()) {
      sb.append("\nMETHOD SOURCE (abridged; for context only — do NOT reference locals):\n")
          .append(methodBody)
          .append("\n");
    }
    sb.append("\nReturn up to ").append(k).append(" expressions.");
    return sb.toString();
  }

  private String userMessageForExit(
      ProgramPoint pt,
      Map<String, String> scope,
      int k,
      @org.checkerframework.checker.nullness.qual.Nullable String methodBody) {
    StringBuilder sb = new StringBuilder();
    sb.append("PROGRAM POINT: ").append(pt.elementId()).append(" [METHOD_EXIT]\n");
    sb.append("You may reference ONLY these names (params and 'result' if non-void):\n");
    scope.forEach((n, t) -> sb.append("- ").append(n).append(" : ").append(t).append("\n"));
    sb.append("\nSTRICT RULES:\n")
        .append("- Prefer relations involving 'result' and parameters.\n")
        .append("- NO streams/lambdas/method refs; NO new helpers; NO side effects.\n")
        .append("- Use short-circuit null checks; single-line booleans only.\n")
        .append("- Do NOT reference locals or fields not listed above.\n");
    if (methodBody != null && !methodBody.isBlank()) {
      sb.append("\nMETHOD SOURCE (abridged; for context only — do NOT reference locals):\n")
          .append(methodBody)
          .append("\n");
    }
    sb.append("\nReturn up to ").append(k).append(" expressions.");
    return sb.toString();
  }

  // ---------- Utility ----------

  private static boolean isParsableExpression(String expr) {
    try {
      StaticJavaParser.parseExpression(expr);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private static String opt(String s) {
    return s == null ? "" : s.trim();
  }

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

  // ---------- Structured Outputs DTOs ----------
  // NOTE: we should avoid Map fields here; OpenAI's schema subset treats Map
  // like a class without named properties, which fails validation.

  @JsonClassDescription("A list of invariant proposals for a program point.")
  public static final class InvariantsOut {
    @JsonPropertyDescription("The invariants proposed by the model.")
    public List<Item> invariants = Collections.emptyList();

    @JsonClassDescription("One invariant entry.")
    public static final class Item {
      @JsonPropertyDescription("A pure Java boolean expression valid at the point.")
      public String expression;

      @JsonPropertyDescription("Optional rationale for why this invariant might hold.")
      public String rationale;

      @JsonPropertyDescription("Optional metadata as key/value pairs.")
      public List<KV> meta;

      // initialize fields for CF
      public Item() {
        this.expression = "";
        this.rationale = "";
        this.meta = Collections.emptyList();
      }
    }

    @JsonClassDescription("Key/value metadata pair.")
    public static final class KV {
      public String key;
      public String value;

      // initialize fields for CF
      public KV() {
        this.key = "";
        this.value = "";
      }
    }

    // No-args ctor for top-level container
    public InvariantsOut() {
      this.invariants = Collections.emptyList();
    }
  }
}
