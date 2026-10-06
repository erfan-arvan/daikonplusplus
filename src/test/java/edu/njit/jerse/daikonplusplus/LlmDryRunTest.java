package edu.njit.jerse.daikonplusplus;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dry-run mode builds every LLM prompt and counts its tokens, but sends nothing and stops before
 * injection: no working copy, no registry entries, no outcomes, sources untouched.
 */
public class LlmDryRunTest {

  private static final Path CASE =
      Paths.get("").toAbsolutePath().resolve("src/test/resources/daikonpp-pipeline/00-baseline");

  @TempDir Path tmp;

  @Test
  public void dryRunCountsPromptTokensAndSkipsEverythingElse() throws Exception {
    Path input = tmp.resolve("input");
    copyTree(CASE.resolve("input"), input);
    Map<Path, String> before = snapshot(input);

    Path work = tmp.resolve("work");
    Path registry = tmp.resolve("out").resolve("registry.jsonl");
    Path outcomes = tmp.resolve("out").resolve("outcomes.jsonl");

    // Same settings as the 00-baseline E2E case, whose prompts are recorded in the cassettes.
    Map<String, String> props = new LinkedHashMap<>();
    props.put("dp.llmDryRun", "true");
    props.put("dp.registry", registry.toString());
    props.put("dp.outcomes", outcomes.toString());
    props.put("dp.workDir", work.toString());
    props.put("dp.contexts", "METHOD_BODY,METHOD_JAVADOC,SCOPE");
    props.put("dp.promptStrategy", "fewshot");
    Map<String, String> prev = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : props.entrySet()) {
      prev.put(e.getKey(), System.getProperty(e.getKey()));
      System.setProperty(e.getKey(), e.getValue());
    }

    PrintStream origOut = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
      App.main(new String[] {input.toString(), "", "com.example.Main", "5"});
    } finally {
      System.setOut(origOut);
      for (Map.Entry<String, String> e : prev.entrySet()) {
        if (e.getValue() == null) System.clearProperty(e.getKey());
        else System.setProperty(e.getKey(), e.getValue());
      }
    }
    String out = buf.toString(StandardCharsets.UTF_8);
    origOut.println(out);

    // One prompt per program point, all counted.
    int points = intAfter(out, ">>> Points — ENTRY: \\d+  EXIT: \\d+  TOTAL: (\\d+)");
    assertTrue(points > 0, out);
    assertEquals(points, intAfter(out, "prompts built +: (\\d+)"), out);
    assertTrue(intAfter(out, "TOTAL input tokens +: (\\d+)") > points, out);
    assertTrue(out.contains("LLM DRY RUN finished"), out);

    // Prompts are identical to a real run's: all of them are already in the test cassettes.
    if (System.getenv("DP_LLM_CASSETTES") != null) {
      assertTrue(out.contains("already recorded      : " + points + " prompts"), out);
    }

    // Per-prompt TSV: header plus one line per prompt.
    Path tsv = outcomes.resolveSibling("daikonpp_dry_run_tokens.tsv");
    assertEquals(points + 1, Files.readAllLines(tsv, StandardCharsets.UTF_8).size());

    // Nothing else happened.
    assertFalse(out.contains("Injection phase"), out);
    assertFalse(Files.exists(work), "dry run must not create a working copy");
    assertFalse(Files.exists(outcomes), "dry run must not produce outcomes");
    assertEquals(0, Files.size(registry), "dry run must not register candidates");
    assertEquals(before, snapshot(input), "dry run must not modify sources");
  }

  private static int intAfter(String text, String regex) {
    Matcher m = Pattern.compile(regex).matcher(text);
    assertTrue(m.find(), "no match for " + regex + " in:\n" + text);
    return Integer.parseInt(m.group(1));
  }

  private static Map<Path, String> snapshot(Path root) throws Exception {
    try (Stream<Path> s = Files.walk(root)) {
      List<Path> files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
      Map<Path, String> m = new LinkedHashMap<>();
      for (Path f : files) m.put(root.relativize(f), Files.readString(f));
      return m;
    }
  }

  private static void copyTree(Path from, Path to) throws Exception {
    try (Stream<Path> s = Files.walk(from)) {
      for (Path p : s.sorted().collect(Collectors.toList())) {
        Path d = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) Files.createDirectories(d);
        else Files.copy(p, d);
      }
    }
  }
}
