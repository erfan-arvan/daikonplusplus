package edu.njit.jerse.daikonplusplus.llm.prompt;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.config.DpConfig;
import edu.njit.jerse.daikonplusplus.model.ProgramElementId;
import edu.njit.jerse.daikonplusplus.model.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for the optional extended shared prompt instructions. */
public class ExtendedInstructionsPromptTest {

  private static final String MARKER = "- Consider receiver state (this and its accessible fields)";

  private static PromptContext ctx(boolean extended) {
    ProgramElementId peid = ProgramElementId.forMethod("p", "C", "", "p/C.java", "m(int):int");
    return new PromptContext(
        new ProgramPointImpl(peid, ProgramPointKind.METHOD_EXIT),
        Map.of("x", "int", "result", "int"),
        "int m(int x) { return x; }",
        null,
        null,
        null,
        null,
        null,
        null,
        5,
        extended);
  }

  @Test
  public void defaultConfigDisablesExtendedInstructions() {
    assertFalse(DpConfig.fromEnv().promptExtendedInstructions());
  }

  @Test
  public void legacyConstructorKeepsExtendedInstructionsOff() {
    PromptContext legacy =
        new PromptContext(
            ctx(true).point(), Map.of("x", "int"), null, null, null, null, null, null, null, 5);
    assertFalse(legacy.extendedInstructions());
  }

  @Test
  public void offLeavesPromptUnchangedForAllSharedStrategies() {
    for (String name :
        List.of("baseline", "fewshot", "cot", "stepwise", "self_refine", "multi_sample")) {
      PromptStrategy s = PromptStrategyFactory.createInternal(name);
      Prompt off = s.buildPrompt(ctx(false));
      Prompt legacy =
          s.buildPrompt(
              new PromptContext(
                  ctx(false).point(),
                  ctx(false).inScope(),
                  ctx(false).methodImplementation(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  5));
      assertEquals(legacy.userMessage(), off.userMessage(), name);
      assertFalse(off.userMessage().contains(MARKER), name);
    }
  }

  @Test
  public void onAppendsInstructionsToAllSharedStrategies() {
    for (String name :
        List.of("baseline", "fewshot", "cot", "stepwise", "self_refine", "multi_sample")) {
      PromptStrategy s = PromptStrategyFactory.createInternal(name);
      String user = s.buildPrompt(ctx(true)).userMessage();
      assertTrue(user.contains(MARKER), name);
      assertTrue(
          user.contains("- Guard dereferences and array accesses when needed for safe evaluation."),
          name);
      assertTrue(
          user.contains("- At METHOD_EXIT, invariants may describe object or parameter state"),
          name);
      // Appended to the shared constraints, before the program context section.
      assertTrue(user.indexOf(MARKER) < user.indexOf("===== PROGRAM CONTEXT ====="), name);
    }
  }

  @Test
  public void naiveStrategyIsUnaffected() {
    PromptStrategy s = PromptStrategyFactory.createInternal("naive");
    assertEquals(s.buildPrompt(ctx(false)).userMessage(), s.buildPrompt(ctx(true)).userMessage());
  }
}
