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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link ContextUtils#extractCallSiteContext} reads a pre-built call-site index JSON
 * file and resolves each caller's javadoc and implementation from source, and that the resulting
 * text is embedded correctly into a rendered prompt.
 */
public class ContextUtilsCallSiteTest {

  private static ProgramPoint mathUtilsAddPoint(String filePath) {
    var id =
        ProgramElementId.forMethod("com.example", "MathUtils", "", filePath, "add(int,int):int");
    return new ProgramPointImpl(id, ProgramPointKind.METHOD_ENTRY);
  }

  private static void writeSources(Path srcRoot) throws IOException {
    Path pkgDir = srcRoot.resolve("com/example");
    Files.createDirectories(pkgDir);

    Files.writeString(
        pkgDir.resolve("MathUtils.java"),
        """
        package com.example;

        public class MathUtils {
          public static int add(int a, int b) {
            return a + b;
          }
        }
        """);

    Files.writeString(
        pkgDir.resolve("Main.java"),
        """
        package com.example;

        public class Main {
          /** Entry point that exercises MathUtils. */
          public static void main(String[] args) {
            int r = MathUtils.add(2, 3);
            System.out.println(r);
          }
        }
        """);
  }

  private static Path writeCallSiteIndex(Path dir, String json) throws IOException {
    Path indexFile = dir.resolve("callsites.json");
    Files.writeString(indexFile, json);
    return indexFile;
  }

  @Test
  void extractsCallerJavadocAndImplementationFromIndex(@TempDir Path tempDir) throws Exception {
    writeSources(tempDir);

    String json =
        """
        {
          "com.example.MathUtils#add(int,int):int" : [
            {
              "callerKey" : "com.example.Main#main(String[]):void",
              "callerJavadoc" : "Entry point that exercises MathUtils.",
              "callSite" : "6 = invokestatic < Application, Lcom/example/MathUtils, add(II)I > 3,4 @2 exception:5"
            }
          ]
        }
        """;
    Path indexFile = writeCallSiteIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint("com/example/MathUtils.java");

    Optional<String> result =
        ContextUtils.extractCallSiteContext(point, tempDir, indexFile.toString());

    assertTrue(result.isPresent(), "expected call-site context to be found");
    String text = result.get();

    assertTrue(text.contains("Caller: com.example.Main#main(String[]):void"), text);
    assertTrue(text.contains("Javadoc: Entry point that exercises MathUtils."), text);
    assertTrue(text.contains("Implementation:"), text);
    assertTrue(text.contains("MathUtils.add(2, 3)"), text);
  }

  @Test
  void returnsEmptyWhenIndexPathIsNull(@TempDir Path tempDir) throws Exception {
    writeSources(tempDir);
    ProgramPoint point = mathUtilsAddPoint("com/example/MathUtils.java");

    assertFalse(ContextUtils.extractCallSiteContext(point, tempDir, null).isPresent());
  }

  @Test
  void returnsEmptyWhenMethodHasNoEntryInIndex(@TempDir Path tempDir) throws Exception {
    writeSources(tempDir);

    String json =
        """
        {
          "com.example.SomeOtherClass#unrelated():void" : [
            { "callerKey" : "com.example.Main#main(String[]):void", "callerJavadoc" : "", "callSite" : "x" }
          ]
        }
        """;
    Path indexFile = writeCallSiteIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint("com/example/MathUtils.java");

    assertFalse(
        ContextUtils.extractCallSiteContext(point, tempDir, indexFile.toString()).isPresent());
  }

  @Test
  void capsAtFiveCallSites(@TempDir Path tempDir) throws Exception {
    Path pkgDir = tempDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    Files.writeString(
        pkgDir.resolve("MathUtils.java"),
        """
        package com.example;

        public class MathUtils {
          public static int add(int a, int b) {
            return a + b;
          }
        }
        """);

    StringBuilder callersSource =
        new StringBuilder("package com.example;\n\npublic class Callers {\n");
    StringBuilder callersJson = new StringBuilder();
    for (int i = 0; i < 7; i++) {
      callersSource
          .append("  public void caller")
          .append(i)
          .append("() { MathUtils.add(1, 2); }\n");
      if (i > 0) callersJson.append(",\n");
      callersJson.append(
          String.format(
              "{ \"callerKey\": \"com.example.Callers#caller%d():void\", \"callerJavadoc\": \"\", \"callSite\": \"x\" }",
              i));
    }
    callersSource.append("}\n");
    Files.writeString(pkgDir.resolve("Callers.java"), callersSource.toString());

    String json = "{ \"com.example.MathUtils#add(int,int):int\" : [ " + callersJson + " ] }";
    Path indexFile = writeCallSiteIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint("com/example/MathUtils.java");
    Optional<String> result =
        ContextUtils.extractCallSiteContext(point, tempDir, indexFile.toString());

    assertTrue(result.isPresent());
    long callerCount = result.get().lines().filter(l -> l.startsWith("Caller: ")).count();
    assertEquals(5, callerCount, "should cap at MAX_CALL_SITES=5 even though 7 were available");
  }

  @Test
  void callSiteContextIsRenderedIntoPrompt(@TempDir Path tempDir) throws Exception {
    writeSources(tempDir);

    String json =
        """
        {
          "com.example.MathUtils#add(int,int):int" : [
            {
              "callerKey" : "com.example.Main#main(String[]):void",
              "callerJavadoc" : "Entry point that exercises MathUtils.",
              "callSite" : "6 = invokestatic < Application, Lcom/example/MathUtils, add(II)I > 3,4 @2 exception:5"
            }
          ]
        }
        """;
    Path indexFile = writeCallSiteIndex(tempDir, json);

    ProgramPoint point = mathUtilsAddPoint("com/example/MathUtils.java");
    Optional<String> callSiteContext =
        ContextUtils.extractCallSiteContext(point, tempDir, indexFile.toString());
    assertTrue(callSiteContext.isPresent());

    PromptContext ctx =
        new PromptContext(
            point,
            Map.of("a", "int", "b", "int"),
            "public static int add(int a, int b) { return a + b; }",
            null,
            null,
            null,
            callSiteContext.get(),
            null,
            null,
            5);

    Prompt prompt = new BaselineDirectPromptStrategy().buildPrompt(ctx);

    assertTrue(prompt.userMessage().contains("[Call-Site Context]"), prompt.userMessage());
    assertTrue(
        prompt.userMessage().contains("Caller: com.example.Main#main(String[]):void"),
        prompt.userMessage());
    assertTrue(
        prompt.userMessage().contains("Entry point that exercises MathUtils."),
        prompt.userMessage());
    assertTrue(prompt.userMessage().contains("MathUtils.add(2, 3)"), prompt.userMessage());
  }
}
