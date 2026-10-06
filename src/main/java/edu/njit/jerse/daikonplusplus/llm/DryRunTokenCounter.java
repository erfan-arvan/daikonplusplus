package edu.njit.jerse.daikonplusplus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Dry-run sink for LLM prompts: records the token count of every prompt that would have been sent,
 * without contacting any LLM, and summarizes them.
 *
 * <p>Counts use the OpenAI tokenizer of the configured model ({@code o200k_base} for GPT-4o,
 * GPT-4.1 and GPT-5 families, {@code cl100k_base} for GPT-4/GPT-3.5). For local models the same
 * tokenizer is used as an approximation. Each request also carries {@link #CHAT_OVERHEAD_TOKENS}
 * tokens of chat framing; the structured-output JSON schema sent with each request is not included.
 *
 * <p>Output tokens cannot be known without sending, so they are taken from the cassettes: for each
 * prompt whose response is recorded, the response is tokenized as the compact JSON the model emits.
 * Prompts without a recorded response are estimated at the mean of the recorded ones. When prices
 * are configured, the summary also gives the cost.
 *
 * <p>Thread-safe: {@link #record} is called concurrently from the LLM phase workers.
 */
public final class DryRunTokenCounter {

  /**
   * Chat framing per request: 3 tokens per message plus 1 for its role (system, user), plus 3
   * tokens priming the assistant reply.
   */
  public static final int CHAT_OVERHEAD_TOKENS = 2 * (3 + 1) + 3;

  /** One prompt that would have been sent. */
  public static final class Entry {
    public final ProgramPointKind kind;
    public final String element;
    public final String cassetteKey;
    public final int systemTokens;
    public final int userTokens;

    /** Tokens of the recorded response, or -1 when no response is recorded. */
    public final int outputTokens;

    Entry(
        ProgramPointKind kind,
        String element,
        String cassetteKey,
        int systemTokens,
        int userTokens,
        int outputTokens) {
      this.kind = kind;
      this.element = element;
      this.cassetteKey = cassetteKey;
      this.systemTokens = systemTokens;
      this.userTokens = userTokens;
      this.outputTokens = outputTokens;
    }

    /** Input tokens billed for this request, including chat framing. */
    public int inputTokens() {
      return systemTokens + userTokens + CHAT_OVERHEAD_TOKENS;
    }
  }

  private final Encoding encoding;
  private final String encodingName;
  private final String modelLabel;
  private final @Nullable Path cassetteDir;
  private final double inputPricePerM;
  private final double outputPricePerM;
  private final ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * @param modelLabel model the prompts are meant for (selects the tokenizer; shown in the summary)
   * @param cassetteDir cassette directory to check for already-recorded prompts, or null
   */
  public DryRunTokenCounter(String modelLabel, @Nullable Path cassetteDir) {
    this(modelLabel, cassetteDir, -1, -1);
  }

  /**
   * @param modelLabel model the prompts are meant for (selects the tokenizer; shown in the summary)
   * @param cassetteDir cassette directory with recorded responses, or null
   * @param inputPricePerM USD per 1M input tokens, or negative when unknown
   * @param outputPricePerM USD per 1M output tokens, or negative when unknown
   */
  public DryRunTokenCounter(
      String modelLabel,
      @Nullable Path cassetteDir,
      double inputPricePerM,
      double outputPricePerM) {
    EncodingType type = encodingFor(modelLabel);
    this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(type);
    this.encodingName = type.getName();
    this.modelLabel = modelLabel;
    this.cassetteDir = cassetteDir;
    this.inputPricePerM = inputPricePerM;
    this.outputPricePerM = outputPricePerM;
  }

  /** Tokenizer used by the given OpenAI model name. */
  static EncodingType encodingFor(String model) {
    String m = model.trim().toLowerCase(Locale.ROOT);
    boolean legacy =
        (m.startsWith("gpt-4") && !m.startsWith("gpt-4o") && !m.startsWith("gpt-4.")
            || m.startsWith("gpt-3.5"));
    return legacy ? EncodingType.CL100K_BASE : EncodingType.O200K_BASE;
  }

  /** Counts the tokens of a text with this counter's tokenizer. */
  public int countTokens(String text) {
    return encoding.countTokensOrdinary(text);
  }

  /** Records the prompt that would have been sent for a program point. */
  public void record(ProgramPoint point, String system, String user) {
    String key = Cassette.key(system, user);
    entries.add(
        new Entry(
            point.kind(),
            String.valueOf(point.elementId()),
            key,
            countTokens(system),
            countTokens(user),
            recordedOutputTokens(key)));
  }

  /**
   * Tokens of the recorded response for a cassette key, as the compact JSON the model emits, or -1
   * when there is no readable recorded response.
   */
  private int recordedOutputTokens(String key) {
    if (cassetteDir == null) return -1;
    Path f = cassetteDir.resolve(key + ".json");
    if (!Files.exists(f)) return -1;
    try {
      return countTokens(JSON.writeValueAsString(JSON.readTree(f.toFile())));
    } catch (IOException e) {
      return -1;
    }
  }

  /** Snapshot of the recorded prompts. */
  public List<Entry> entries() {
    return new ArrayList<>(entries);
  }

  /**
   * Prints the token summary.
   *
   * @param out destination
   * @param scannedPoints number of program points in scope (for prompts that failed to build)
   */
  public void printSummary(PrintStream out, int scannedPoints) {
    List<Entry> all = entries();
    all.sort(Comparator.comparingInt(Entry::inputTokens));
    int n = all.size();

    long sys = 0, user = 0, input = 0;
    Map<ProgramPointKind, long[]> byKind = new EnumMap<>(ProgramPointKind.class);
    Set<String> distinctKeys = new HashSet<>();
    long distinctInput = 0;
    long cachedPrompts = 0, cachedInput = 0;
    Set<String> distinctMissing = new HashSet<>();
    long distinctMissingInput = 0;
    for (Entry e : all) {
      sys += e.systemTokens;
      user += e.userTokens;
      input += e.inputTokens();
      long[] k = byKind.computeIfAbsent(e.kind, __ -> new long[2]);
      k[0]++;
      k[1] += e.inputTokens();
      if (distinctKeys.add(e.cassetteKey)) distinctInput += e.inputTokens();
      if (cassetteDir != null) {
        if (Files.exists(cassetteDir.resolve(e.cassetteKey + ".json"))) {
          cachedPrompts++;
          cachedInput += e.inputTokens();
        } else if (distinctMissing.add(e.cassetteKey)) {
          distinctMissingInput += e.inputTokens();
        }
      }
    }

    out.println("==== DRY RUN: LLM prompt token summary (nothing was sent) ====");
    out.println("model / tokenizer       : " + modelLabel + " / " + encodingName);
    out.println("program points in scope : " + scannedPoints);
    StringBuilder kinds = new StringBuilder();
    for (Map.Entry<ProgramPointKind, long[]> k : byKind.entrySet()) {
      kinds
          .append(kinds.length() == 0 ? "" : ", ")
          .append(k.getKey())
          .append(' ')
          .append(k.getValue()[0]);
    }
    out.println("prompts built           : " + n + (n > 0 ? "  (" + kinds + ")" : ""));
    if (n < scannedPoints) {
      out.println("  (" + (scannedPoints - n) + " points produced no prompt; see errors above)");
    }
    out.println("distinct prompts        : " + distinctKeys.size() + "  (identical system+user)");
    out.println("system-prompt tokens    : " + sys);
    out.println("user-prompt tokens      : " + user);
    out.println(
        "chat framing tokens     : "
            + (long) n * CHAT_OVERHEAD_TOKENS
            + "  ("
            + CHAT_OVERHEAD_TOKENS
            + " per prompt)");
    out.println("TOTAL input tokens      : " + input);
    out.println("  of distinct prompts   : " + distinctInput);
    if (n > 0) {
      out.println(
          "per prompt (input)      : min "
              + all.get(0).inputTokens()
              + " | mean "
              + Math.round((double) input / n)
              + " | median "
              + percentile(all, 50)
              + " | p90 "
              + percentile(all, 90)
              + " | p99 "
              + percentile(all, 99)
              + " | max "
              + all.get(n - 1).inputTokens());
      for (Map.Entry<ProgramPointKind, long[]> k : byKind.entrySet()) {
        long[] v = k.getValue();
        out.println(
            String.format(
                Locale.ROOT,
                "  %-21s : %d prompts, %d tokens, mean %d",
                k.getKey(),
                v[0],
                v[1],
                Math.round((double) v[1] / v[0])));
      }
    }
    if (cassetteDir != null) {
      out.println("cassettes               : " + cassetteDir);
      out.println(
          "  already recorded      : " + cachedPrompts + " prompts, " + cachedInput + " tokens");
      out.println(
          "  NOT recorded (to send): "
              + (n - cachedPrompts)
              + " prompts ("
              + distinctMissing.size()
              + " distinct), "
              + distinctMissingInput
              + " tokens (distinct)");
    }
    long outRecorded = 0, recordedResponses = 0;
    for (Entry e : all) {
      if (e.outputTokens >= 0) {
        outRecorded += e.outputTokens;
        recordedResponses++;
      }
    }
    long outTotal = -1;
    if (cassetteDir == null) {
      out.println("output tokens           : unknown (no cassettes; set DP_LLM_CASSETTES)");
    } else if (recordedResponses == 0) {
      out.println("output tokens           : unknown (no recorded responses in cassettes)");
    } else {
      long mean = Math.round((double) outRecorded / recordedResponses);
      long unrecorded = n - recordedResponses;
      outTotal = outRecorded + unrecorded * mean;
      out.println(
          "output tokens (recorded): "
              + outRecorded
              + "  ("
              + recordedResponses
              + " recorded responses, mean "
              + mean
              + ")");
      if (unrecorded > 0) {
        out.println(
            "  unrecorded prompts    : "
                + unrecorded
                + " x mean "
                + mean
                + " = "
                + unrecorded * mean
                + " (estimated)");
      }
      out.println(
          "TOTAL output tokens     : " + outTotal + (unrecorded > 0 ? "  (estimated)" : ""));
    }
    if (inputPricePerM >= 0 && outputPricePerM >= 0) {
      double inCost = input / 1e6 * inputPricePerM;
      String line =
          String.format(
              Locale.ROOT,
              "COST (USD)              : input $%.2f (at $%s/1M)",
              inCost,
              inputPricePerM);
      if (outTotal >= 0) {
        double outCost = outTotal / 1e6 * outputPricePerM;
        line +=
            String.format(
                Locale.ROOT,
                " + output $%.2f (at $%s/1M) = $%.2f",
                outCost,
                outputPricePerM,
                inCost + outCost);
      }
      out.println(line);
      if (n > 0) {
        out.println(
            String.format(
                Locale.ROOT,
                "  per program point     : $%.6f",
                (inCost + (outTotal >= 0 ? outTotal / 1e6 * outputPricePerM : 0)) / n));
      }
    } else {
      out.println(
          "COST (USD)              : set DP_LLM_PRICE_INPUT_PER_M and DP_LLM_PRICE_OUTPUT_PER_M");
    }
    if (n > 0) {
      out.println("largest prompts:");
      for (int i = n - 1; i >= Math.max(0, n - 10); i--) {
        Entry e = all.get(i);
        out.println(
            String.format(Locale.ROOT, "  %8d  %-12s %s", e.inputTokens(), e.kind, e.element));
      }
    }
    out.println("not included: structured-output JSON schema sent with each request (input side)");
    out.println("==============================================================");
  }

  /** Nearest-rank percentile of the input tokens of entries sorted ascending. */
  private static int percentile(List<Entry> sorted, int p) {
    int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx))).inputTokens();
  }

  /** Writes one tab-separated line per prompt. */
  public void writeTsv(Path tsv) throws IOException {
    Path parent = tsv.getParent();
    if (parent != null) Files.createDirectories(parent);
    StringBuilder sb =
        new StringBuilder(
            "kind\telement\tcassette_key\tsystem_tokens\tuser_tokens\tinput_tokens"
                + "\toutput_tokens\n");
    for (Entry e : entries()) {
      sb.append(e.kind)
          .append('\t')
          .append(e.element.replace('\t', ' ').replace('\n', ' '))
          .append('\t')
          .append(e.cassetteKey)
          .append('\t')
          .append(e.systemTokens)
          .append('\t')
          .append(e.userTokens)
          .append('\t')
          .append(e.inputTokens())
          .append('\t')
          .append(e.outputTokens >= 0 ? String.valueOf(e.outputTokens) : "")
          .append('\n');
    }
    Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
  }
}
