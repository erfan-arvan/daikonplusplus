package edu.njit.jerse.daikonplusplus.results;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that InvariantRegistry pre-loads previously written entries on construction so that a
 * second pipeline invocation (pointing at the same file) skips already-seen invariants.
 */
class InvariantRegistryCrossRunTest {

  @TempDir Path tmp;

  private static ProgramPoint makePoint(
      String pkg, String cls, String file, String desc, ProgramPointKind kind) {
    ProgramElementId id = ProgramElementId.forMethod(pkg, cls, "", file, desc);
    return new ProgramPointImpl(id, kind);
  }

  private static InvariantRecord rec(UUID id, ProgramPoint pt, String expr, String fileRel) {
    return new InvariantRecord(
        id, new InvariantSpec(expr, "", Map.of()), pt, fileRel, Instant.now());
  }

  // -----------------------------------------------------------------------
  // Core: second registry instance sees first run's entries as duplicates
  // -----------------------------------------------------------------------

  @Test
  void secondRun_skipsAlreadyRegisteredInvariant(@TempDir Path tmp2) throws Exception {
    Path registryFile = tmp2.resolve("registry.jsonl");

    ProgramPoint pt =
        makePoint(
            "com.example",
            "Calc",
            "com/example/Calc.java",
            "safeDivide(II)I",
            ProgramPointKind.METHOD_ENTRY);
    UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    InvariantRecord existing = rec(id1, pt, "b != 0", "com/example/Calc.java");

    // --- First run: write one invariant ---
    InvariantRegistry run1 = new InvariantRegistry(registryFile);
    run1.appendIfNew(existing);

    long sizeAfterRun1 = Files.size(registryFile);
    assertTrue(sizeAfterRun1 > 0, "Registry should have content after first run");

    // --- Second run: new registry instance, same file ---
    InvariantRegistry run2 = new InvariantRegistry(registryFile);

    // Same (kind|element|expr) → must be skipped
    UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222"); // different UUID, same expr
    InvariantRecord duplicate = rec(id2, pt, "b != 0", "com/example/Calc.java");
    run2.appendIfNew(duplicate);

    long sizeAfterDup = Files.size(registryFile);
    assertEquals(
        sizeAfterRun1, sizeAfterDup, "Duplicate invariant must not be appended in second run");

    // A genuinely new invariant IS accepted
    UUID id3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    InvariantRecord novel = rec(id3, pt, "a >= 0", "com/example/Calc.java");
    run2.appendIfNew(novel);

    long sizeAfterNovel = Files.size(registryFile);
    assertTrue(sizeAfterNovel > sizeAfterDup, "Novel invariant must be appended");

    // Verify both original and novel appear in the final file, duplicate does not
    String content = Files.readString(registryFile, StandardCharsets.UTF_8);
    assertTrue(content.contains(id1.toString()), "First-run UUID must be present");
    assertTrue(content.contains(id3.toString()), "Novel UUID must be present");
    assertFalse(content.contains(id2.toString()), "Duplicate UUID must NOT appear");
  }

  // -----------------------------------------------------------------------
  // Whitespace normalization: "b  !=  0" deduplicates against "b != 0"
  // -----------------------------------------------------------------------

  @Test
  void crossRun_dedup_isInsensitiveToWhitespace(@TempDir Path tmp2) throws Exception {
    Path registryFile = tmp2.resolve("registry.jsonl");
    ProgramPoint pt =
        makePoint(
            "com.example",
            "Calc",
            "com/example/Calc.java",
            "safeDivide(II)I",
            ProgramPointKind.METHOD_ENTRY);

    UUID id1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    InvariantRegistry run1 = new InvariantRegistry(registryFile);
    run1.appendIfNew(rec(id1, pt, "b != 0", "com/example/Calc.java"));
    long after1 = Files.size(registryFile);

    // Second run with extra whitespace in expression
    UUID id2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    InvariantRegistry run2 = new InvariantRegistry(registryFile);
    run2.appendIfNew(rec(id2, pt, "b  !=  0", "com/example/Calc.java"));

    assertEquals(
        after1, Files.size(registryFile), "Whitespace-variant expression must be deduplicated");
  }

  // -----------------------------------------------------------------------
  // Entry/Exit are separate keys: same expr at ENTRY ≠ same expr at EXIT
  // -----------------------------------------------------------------------

  @Test
  void crossRun_entryAndExitAreDistinctKeys(@TempDir Path tmp2) throws Exception {
    Path registryFile = tmp2.resolve("registry.jsonl");
    ProgramPoint entryPt =
        makePoint(
            "com.example",
            "Calc",
            "com/example/Calc.java",
            "safeDivide(II)I",
            ProgramPointKind.METHOD_ENTRY);
    ProgramPoint exitPt =
        makePoint(
            "com.example",
            "Calc",
            "com/example/Calc.java",
            "safeDivide(II)I",
            ProgramPointKind.METHOD_EXIT);

    UUID entryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    InvariantRegistry run1 = new InvariantRegistry(registryFile);
    run1.appendIfNew(rec(entryId, entryPt, "b != 0", "com/example/Calc.java"));
    long after1 = Files.size(registryFile);

    // Second run: same expression but at EXIT — should be accepted
    UUID exitId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    InvariantRegistry run2 = new InvariantRegistry(registryFile);
    run2.appendIfNew(rec(exitId, exitPt, "b != 0", "com/example/Calc.java"));

    assertTrue(
        Files.size(registryFile) > after1,
        "EXIT-point invariant must be written even though ENTRY variant exists");

    String content = Files.readString(registryFile, StandardCharsets.UTF_8);
    assertTrue(content.contains(entryId.toString()));
    assertTrue(content.contains(exitId.toString()));
  }
}
