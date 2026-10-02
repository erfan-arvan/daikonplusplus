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
}
