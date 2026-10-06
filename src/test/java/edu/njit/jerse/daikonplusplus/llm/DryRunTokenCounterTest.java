package edu.njit.jerse.daikonplusplus.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.knuddels.jtokkit.api.EncodingType;
import edu.njit.jerse.daikonplusplus.model.ProgramElementId;
import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests token counting, cassette lookup and the summary of {@link DryRunTokenCounter}. */
public class DryRunTokenCounterTest {

  @TempDir Path tmp;

  private static ProgramPoint point(String desc, ProgramPointKind kind) {
    return new ProgramPointImpl(
        ProgramElementId.forMethod("sample", "Calc", "", "sample/Calc.java", desc), kind);
  }

  @Test
  public void tokenizerFollowsModelFamily() {
    assertEquals(EncodingType.O200K_BASE, DryRunTokenCounter.encodingFor("gpt-4.1"));
    assertEquals(EncodingType.O200K_BASE, DryRunTokenCounter.encodingFor("gpt-4.1-mini"));
    assertEquals(EncodingType.O200K_BASE, DryRunTokenCounter.encodingFor("gpt-4o-mini"));
    assertEquals(EncodingType.O200K_BASE, DryRunTokenCounter.encodingFor("gpt-5"));
    assertEquals(EncodingType.CL100K_BASE, DryRunTokenCounter.encodingFor("gpt-4"));
    assertEquals(EncodingType.CL100K_BASE, DryRunTokenCounter.encodingFor("gpt-3.5-turbo"));
    assertEquals(EncodingType.O200K_BASE, DryRunTokenCounter.encodingFor("qwen2.5-coder:7b"));
  }

  @Test
  public void countsKnownTokenizations() {
    DryRunTokenCounter c = new DryRunTokenCounter("gpt-4.1", null);
    assertEquals(0, c.countTokens(""));
    assertEquals(2, c.countTokens("hello world"));
  }

  @Test
  public void recordsPromptsAndSummarizesWithCassetteLookup() throws Exception {
    Path cassettes = tmp.resolve("cassettes");
    Files.createDirectories(cassettes);
    // Only the first prompt is already recorded.
    Files.writeString(cassettes.resolve(Cassette.key("sys", "entry prompt") + ".json"), "{}");

    DryRunTokenCounter c = new DryRunTokenCounter("gpt-4.1", cassettes);
    c.record(point("abs(int):int", ProgramPointKind.METHOD_ENTRY), "sys", "entry prompt");
    c.record(point("abs(int):int", ProgramPointKind.METHOD_EXIT), "sys", "exit prompt text");
    c.record(point("abs(int):int", ProgramPointKind.METHOD_EXIT), "sys", "exit prompt text");

    List<DryRunTokenCounter.Entry> entries = c.entries();
    assertEquals(3, entries.size());
    long total = 0;
    for (DryRunTokenCounter.Entry e : entries) {
      assertEquals(c.countTokens("sys"), e.systemTokens);
      assertEquals(
          e.systemTokens + e.userTokens + DryRunTokenCounter.CHAT_OVERHEAD_TOKENS, e.inputTokens());
      total += e.inputTokens();
    }
    int exitTokens = c.countTokens("sys") + c.countTokens("exit prompt text") + 11;

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    c.printSummary(new PrintStream(buf, true, StandardCharsets.UTF_8), 4);
    String out = buf.toString(StandardCharsets.UTF_8);

    assertTrue(out.contains("DRY RUN"), out);
    assertTrue(out.contains("gpt-4.1 / o200k_base"), out);
    assertTrue(out.contains("program points in scope : 4"), out);
    assertTrue(out.contains("prompts built           : 3  (METHOD_ENTRY 1, METHOD_EXIT 2)"), out);
    assertTrue(out.contains("1 points produced no prompt"), out);
    assertTrue(out.contains("distinct prompts        : 2"), out);
    assertTrue(out.contains("TOTAL input tokens      : " + total), out);
    assertTrue(out.contains("  of distinct prompts   : " + (total - exitTokens)), out);
    assertTrue(out.contains("already recorded      : 1 prompts"), out);
    assertTrue(
        out.contains("NOT recorded (to send): 2 prompts (1 distinct), " + exitTokens + " tokens"),
        out);
    assertTrue(out.contains("Calc"), out);

    Path tsv = tmp.resolve("out").resolve("tokens.tsv");
    c.writeTsv(tsv);
    List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
    assertEquals(4, lines.size());
    assertTrue(lines.get(0).startsWith("kind\telement\t"));
  }

  @Test
  public void emptySummaryDoesNotFail() {
    DryRunTokenCounter c = new DryRunTokenCounter("gpt-4.1", null);
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    c.printSummary(new PrintStream(buf, true, StandardCharsets.UTF_8), 0);
    assertTrue(buf.toString(StandardCharsets.UTF_8).contains("TOTAL input tokens      : 0"));
  }

  @Test
  public void outputTokensComeFromRecordedResponsesAndCostIsComputed() throws Exception {
    Path cassettes = tmp.resolve("cassettes");
    Files.createDirectories(cassettes);
    // Pretty-printed like Cassette.write; counted as the compact JSON the model emits.
    String response =
        "{\n  \"invariants\" : [ {\n    \"expression\" : \"result >= 0\",\n"
            + "    \"meta\" : [ ],\n    \"rationale\" : \"abs is non-negative\"\n  } ]\n}";
    String compact =
        "{\"invariants\":[{\"expression\":\"result >= 0\",\"meta\":[],"
            + "\"rationale\":\"abs is non-negative\"}]}";
    Files.writeString(cassettes.resolve(Cassette.key("sys", "recorded") + ".json"), response);

    DryRunTokenCounter c = new DryRunTokenCounter("gpt-4.1", cassettes, 2.0, 8.0);
    c.record(point("abs(int):int", ProgramPointKind.METHOD_EXIT), "sys", "recorded");
    c.record(point("abs(int):int", ProgramPointKind.METHOD_ENTRY), "sys", "not recorded");

    int out = c.countTokens(compact);
    long input = 0;
    for (DryRunTokenCounter.Entry e : c.entries()) {
      input += e.inputTokens();
      assertEquals(e.kind == ProgramPointKind.METHOD_EXIT ? out : -1, e.outputTokens);
    }

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    c.printSummary(new PrintStream(buf, true, StandardCharsets.UTF_8), 2);
    String s = buf.toString(StandardCharsets.UTF_8);

    assertTrue(s.contains("output tokens (recorded): " + out + "  (1 recorded responses"), s);
    assertTrue(s.contains("unrecorded prompts    : 1 x mean " + out), s);
    assertTrue(s.contains("TOTAL output tokens     : " + 2 * out + "  (estimated)"), s);
    double cost = input / 1e6 * 2.0 + 2 * out / 1e6 * 8.0;
    assertTrue(s.contains(String.format(java.util.Locale.ROOT, "= $%.2f", cost)), s);

    Path tsv = tmp.resolve("tokens.tsv");
    c.writeTsv(tsv);
    String body = Files.readString(tsv, StandardCharsets.UTF_8);
    assertTrue(body.startsWith("kind\telement\tcassette_key\t"), body);
    assertTrue(body.contains("\t" + out + "\n"), body);
  }

  @Test
  public void withoutCassettesOutputTokensAreReportedUnknown() {
    DryRunTokenCounter c = new DryRunTokenCounter("gpt-4.1", null);
    c.record(point("abs(int):int", ProgramPointKind.METHOD_ENTRY), "sys", "u");
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    c.printSummary(new PrintStream(buf, true, StandardCharsets.UTF_8), 1);
    String s = buf.toString(StandardCharsets.UTF_8);
    assertTrue(s.contains("output tokens           : unknown"), s);
    assertTrue(s.contains("set DP_LLM_PRICE_INPUT_PER_M"), s);
  }
}
