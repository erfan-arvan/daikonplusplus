package edu.njit.jerse.daikonplusplus.parse.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.njit.jerse.daikonplusplus.llm.prompt.BaselineDirectPromptStrategy;
import edu.njit.jerse.daikonplusplus.llm.prompt.Prompt;
import edu.njit.jerse.daikonplusplus.llm.prompt.PromptContext;
import edu.njit.jerse.daikonplusplus.model.ProgramElementId;
import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link ContextUtils#extractIOExamples} reads a pre-built I/O-examples index JSON
 * file (as produced offline from a Daikon Chicory trace) and renders the recorded argument/return
 * pairs into prompt text.
 */
public class ContextUtilsIOExamplesTest {

  private static ProgramPoint mathUtilsAddPoint() {
    var id =
        ProgramElementId.forMethod(
            "com.example", "MathUtils", "", "com/example/MathUtils.java", "add(int,int):int");
    return new ProgramPointImpl(id, ProgramPointKind.METHOD_ENTRY);
  }

  private static Path writeIOExamplesIndex(Path dir, String json) throws Exception {
    Path indexFile = dir.resolve("io_examples.json");
    Files.writeString(indexFile, json);
    return indexFile;
  }

  @Test
  void extractsArgsAndReturnFromIndex(@TempDir Path tempDir) throws Exception {
    String json =
        """
        {
          "com.example.MathUtils#add(int,int):int" : [
            { "args": { "a": "3", "b": "5" }, "return": "8" },
            { "args": { "a": "-1", "b": "2" }, "return": "1" }
          ]
        }
        """;
    Path indexFile = writeIOExamplesIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint();

    Optional<String> result = ContextUtils.extractIOExamples(point, tempDir, indexFile.toString());

    assertTrue(result.isPresent(), "expected IO examples to be found");
    String text = result.get();

    assertTrue(text.contains("a=3, b=5 -> return=8"), text);
    assertTrue(text.contains("a=-1, b=2 -> return=1"), text);
  }

  @Test
  void returnsEmptyWhenIndexPathIsNull(@TempDir Path tempDir) {
    ProgramPoint point = mathUtilsAddPoint();
    assertFalse(ContextUtils.extractIOExamples(point, tempDir, null).isPresent());
  }

  @Test
  void returnsEmptyWhenMethodHasNoEntryInIndex(@TempDir Path tempDir) throws Exception {
    String json =
        """
        {
          "com.example.SomeOtherClass#unrelated():void" : [
            { "args": {}, "return": "null" }
          ]
        }
        """;
    Path indexFile = writeIOExamplesIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint();

    assertFalse(ContextUtils.extractIOExamples(point, tempDir, indexFile.toString()).isPresent());
  }

  @Test
  void capsAtFiveIOExamples(@TempDir Path tempDir) throws Exception {
    StringBuilder examplesJson = new StringBuilder();
    for (int i = 0; i < 7; i++) {
      if (i > 0) examplesJson.append(",\n");
      examplesJson.append(
          String.format(
              "{ \"args\": { \"a\": \"%d\", \"b\": \"1\" }, \"return\": \"%d\" }", i, i + 1));
    }

    String json = "{ \"com.example.MathUtils#add(int,int):int\" : [ " + examplesJson + " ] }";
    Path indexFile = writeIOExamplesIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint();
    Optional<String> result = ContextUtils.extractIOExamples(point, tempDir, indexFile.toString());

    assertTrue(result.isPresent());
    long exampleCount = result.get().lines().filter(l -> l.startsWith("Example ")).count();
    assertEquals(5, exampleCount, "should cap at MAX_IO_EXAMPLES=5 even though 7 were available");
  }

  @Test
  void ioExamplesAreRenderedIntoPrompt(@TempDir Path tempDir) throws Exception {
    String json =
        """
        {
          "com.example.MathUtils#add(int,int):int" : [
            { "args": { "a": "3", "b": "5" }, "return": "8" }
          ]
        }
        """;
    Path indexFile = writeIOExamplesIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint();
    Optional<String> ioExamples =
        ContextUtils.extractIOExamples(point, tempDir, indexFile.toString());
    assertTrue(ioExamples.isPresent());

    PromptContext ctx =
        new PromptContext(
            point,
            Map.of("a", "int", "b", "int"),
            "public static int add(int a, int b) { return a + b; }",
            null,
            null,
            null,
            null,
            ioExamples.get(),
            null,
            5);

    Prompt prompt = new BaselineDirectPromptStrategy().buildPrompt(ctx);

    assertTrue(prompt.userMessage().contains("[Input-Output Examples]"), prompt.userMessage());
    assertTrue(prompt.userMessage().contains("a=3, b=5 -> return=8"), prompt.userMessage());
  }
}
