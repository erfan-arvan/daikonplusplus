package edu.njit.jerse.daikonplusplus;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for shm-based invariant storage and SEEN pre-population (SIGKILL recovery).
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>{@code readExecutedIdsFromShm}: reads UUIDs from shm/ex/ filenames
 *   <li>{@code readFalsifiedIdsFromShm}: reads UUIDs from shm/fail/*.json filenames
 *   <li>{@code readCurrentInvariantFromShm}: returns the UUID from shm/current/ (the stuck one)
 *   <li>SEEN pre-population: DpRuntime static init skips already-checked invariants on rerun
 * </ul>
 */
public class ShmRecoveryTest {

  @TempDir Path tmp;

  // ---- readExecutedIdsFromShm ----

  @Test
  public void readExecutedIdsFromShm_returnsUuidsFromExDir(@TempDir Path shmDir)
      throws IOException {
    Path exDir = shmDir.resolve("ex");
    Files.createDirectories(exDir);

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    Files.createFile(exDir.resolve(id1.toString()));
    Files.createFile(exDir.resolve(id2.toString()));

    Set<UUID> result = LogParser.readExecutedIdsFromShm(shmDir);

    assertEquals(Set.of(id1, id2), result);
  }

  @Test
  public void readExecutedIdsFromShm_emptyWhenExDirMissing(@TempDir Path shmDir)
      throws IOException {
    Set<UUID> result = LogParser.readExecutedIdsFromShm(shmDir);
    assertTrue(result.isEmpty());
  }

  @Test
  public void readExecutedIdsFromShm_ignoresNonUuidFilenames(@TempDir Path shmDir)
      throws IOException {
    Path exDir = shmDir.resolve("ex");
    Files.createDirectories(exDir);

    UUID valid = UUID.randomUUID();
    Files.createFile(exDir.resolve(valid.toString()));
    Files.createFile(exDir.resolve("not-a-uuid.tmp"));
    Files.createFile(exDir.resolve("12345"));

    Set<UUID> result = LogParser.readExecutedIdsFromShm(shmDir);

    assertEquals(Set.of(valid), result);
  }

  // ---- readFalsifiedIdsFromShm ----

  @Test
  public void readFalsifiedIdsFromShm_returnsUuidsFromFailDir(@TempDir Path shmDir)
      throws IOException {
    Path failDir = shmDir.resolve("fail");
    Files.createDirectories(failDir);

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    Files.writeString(
        failDir.resolve(id1 + ".json"),
        "{\"type\":\"INV_FAIL\",\"id\":\"" + id1 + "\"}",
        StandardCharsets.UTF_8);
    Files.writeString(
        failDir.resolve(id2 + ".json"),
        "{\"type\":\"INV_FAIL\",\"id\":\"" + id2 + "\"}",
        StandardCharsets.UTF_8);

    Set<UUID> result = LogParser.readFalsifiedIdsFromShm(shmDir);

    assertEquals(Set.of(id1, id2), result);
  }

  @Test
  public void readFalsifiedIdsFromShm_emptyWhenFailDirMissing(@TempDir Path shmDir)
      throws IOException {
    Set<UUID> result = LogParser.readFalsifiedIdsFromShm(shmDir);
    assertTrue(result.isEmpty());
  }

  @Test
  public void readFalsifiedIdsFromShm_ignoresFilesWithoutJsonSuffix(@TempDir Path shmDir)
      throws IOException {
    Path failDir = shmDir.resolve("fail");
    Files.createDirectories(failDir);

    UUID valid = UUID.randomUUID();
    Files.writeString(failDir.resolve(valid + ".json"), "{}", StandardCharsets.UTF_8);
    // These should be ignored
    Files.writeString(failDir.resolve(UUID.randomUUID() + ".txt"), "{}", StandardCharsets.UTF_8);
    Files.writeString(failDir.resolve("not-uuid.json"), "{}", StandardCharsets.UTF_8);

    Set<UUID> result = LogParser.readFalsifiedIdsFromShm(shmDir);

    assertEquals(Set.of(valid), result);
  }

  // ---- readCurrentInvariantFromShm ----

  @Test
  public void readCurrentInvariantFromShm_returnsStuckUuid(@TempDir Path shmDir)
      throws IOException {
    Path currentDir = shmDir.resolve("current");
    Files.createDirectories(currentDir);

    UUID stuckId = UUID.randomUUID();
    Files.createFile(currentDir.resolve(stuckId.toString()));

    Optional<UUID> result = LogParser.readCurrentInvariantFromShm(shmDir);

    assertTrue(result.isPresent());
    assertEquals(stuckId, result.get());
  }

  @Test
  public void readCurrentInvariantFromShm_emptyWhenCurrentDirMissing(@TempDir Path shmDir)
      throws IOException {
    Optional<UUID> result = LogParser.readCurrentInvariantFromShm(shmDir);
    assertFalse(result.isPresent());
  }

  @Test
  public void readCurrentInvariantFromShm_emptyWhenCurrentDirIsEmpty(@TempDir Path shmDir)
      throws IOException {
    Files.createDirectories(shmDir.resolve("current"));
    Optional<UUID> result = LogParser.readCurrentInvariantFromShm(shmDir);
    assertFalse(result.isPresent());
  }

  @Test
  public void readCurrentInvariantFromShm_ignoresNonUuidFilenames(@TempDir Path shmDir)
      throws IOException {
    Path currentDir = shmDir.resolve("current");
    Files.createDirectories(currentDir);
    Files.createFile(currentDir.resolve("not-a-uuid"));

    Optional<UUID> result = LogParser.readCurrentInvariantFromShm(shmDir);
    assertFalse(result.isPresent());
  }

  // ---- SEEN pre-population: compile and run injected code with pre-existing shm/ex/ files ----

  @Test
  public void seenPrePopulation_skipsAlreadyCheckedInvariants(@TempDir Path shmDir)
      throws Exception {
    // This test verifies that when shm/ex/<uuid> files exist before the JVM starts,
    // DpRuntime.SEEN is pre-populated and the guard skips those invariants.

    UUID preSeenId = UUID.randomUUID();

    // Create the shm directories and pre-seed ex/<uuid>
    Path exDir = shmDir.resolve("ex");
    Path failDir = shmDir.resolve("fail");
    Path currentDir = shmDir.resolve("current");
    Files.createDirectories(exDir);
    Files.createDirectories(failDir);
    Files.createDirectories(currentDir);
    Files.createFile(exDir.resolve(preSeenId.toString()));

    // Write a minimal Java program that uses DpRuntime and checks if the pre-seeded UUID is in SEEN
    Path srcDir = tmp.resolve("src");
    Files.createDirectories(srcDir);

    // Write DpRuntime into the source tree
    edu.njit.jerse.daikonplusplus.inject.DpRuntimeWriter.write(srcDir);

    // Write the test program
    String testCode =
        "import daikonpp.DpRuntime;\n"
            + "public class SeenCheck {\n"
            + "    public static void main(String[] a) {\n"
            + "        String id = \""
            + preSeenId
            + "\";\n"
            + "        if (DpRuntime.SEEN.contains(id)) {\n"
            + "            System.out.println(\"SEEN_PRE_POPULATED\");\n"
            + "        } else {\n"
            + "            System.out.println(\"NOT_SEEN\");\n"
            + "        }\n"
            + "    }\n"
            + "}\n";
    Files.writeString(srcDir.resolve("SeenCheck.java"), testCode, StandardCharsets.UTF_8);

    // Compile
    Path classesDir = tmp.resolve("classes");
    Files.createDirectories(classesDir);

    List<String> javacCmd =
        List.of(
            "javac",
            "-d",
            classesDir.toString(),
            srcDir.resolve("daikonpp").resolve("DpRuntime.java").toString(),
            srcDir.resolve("SeenCheck.java").toString());
    int compileExit = new ProcessBuilder(javacCmd).start().waitFor();
    assertEquals(0, compileExit, "Compilation of SeenCheck failed");

    // Run with DP_SHM_DIR pointing to our shm directory
    List<String> javaCmd =
        List.of(
            "java",
            "-DDP_SHM_DIR=" + shmDir.toAbsolutePath(),
            "-cp",
            classesDir.toString(),
            "SeenCheck");
    ProcessBuilder pb = new ProcessBuilder(javaCmd);
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    proc.waitFor();

    assertTrue(
        output.contains("SEEN_PRE_POPULATED"),
        "Expected SEEN to be pre-populated with " + preSeenId + " but got: " + output);
  }

  // ---- Live re-evaluation: the injected guard must not skip after the first call ----

  @Test
  public void injectedGuard_reevaluatesInvariantOnEveryCallWithinSameRun(@TempDir Path shmDir)
      throws Exception {
    // Regression test for a bug where the injected guard treated DpRuntime.SEEN (populated on
    // the *first* call to a checkpoint) as a reason to skip every later call to that same
    // checkpoint in the same process -- so an invariant that held on call 1 but was violated on
    // a later call (e.g. call 3) was never actually evaluated, and the violation was silently
    // missed. The guard must instead only skip invariants already checked by a *prior, killed*
    // process (recovered from shm/ex at JVM startup), never ones seen earlier in this same run.

    Path srcDir = tmp.resolve("src");
    Path pkgDir = srcDir.resolve("sample");
    Files.createDirectories(pkgDir);

    edu.njit.jerse.daikonplusplus.inject.DpRuntimeWriter.write(srcDir);

    Path checkerFile = pkgDir.resolve("Checker.java");
    Files.writeString(
        checkerFile,
        "package sample;\n"
            + "public class Checker {\n"
            + "    public static void check(int n) {\n"
            + "    }\n"
            + "}\n",
        StandardCharsets.UTF_8);

    edu.njit.jerse.daikonplusplus.model.ProgramElementId peid =
        edu.njit.jerse.daikonplusplus.model.ProgramElementId.forMethod(
            "sample", "Checker", "", "sample/Checker.java", "check(int):void");
    edu.njit.jerse.daikonplusplus.model.ProgramPoint point =
        new edu.njit.jerse.daikonplusplus.model.ProgramPointImpl(
            peid, edu.njit.jerse.daikonplusplus.model.ProgramPointKind.METHOD_ENTRY);
    edu.njit.jerse.daikonplusplus.model.InvariantSpec spec =
        new edu.njit.jerse.daikonplusplus.model.InvariantSpec("n < 3", "", Map.of());
    UUID invId = UUID.randomUUID();
    edu.njit.jerse.daikonplusplus.model.InvariantRecord rec =
        new edu.njit.jerse.daikonplusplus.model.InvariantRecord(
            invId, spec, point, "sample/Checker.java", java.time.Instant.now());

    edu.njit.jerse.daikonplusplus.inject.JavaParserInjector injector =
        new edu.njit.jerse.daikonplusplus.inject.JavaParserInjector(
            new edu.njit.jerse.daikonplusplus.inject.FileWriteCoordinator());
    injector.injectGuards(checkerFile, List.of(rec));

    Path mainFile = srcDir.resolve("Main.java");
    Files.writeString(
        mainFile,
        "public class Main {\n"
            + "    public static void main(String[] a) throws Exception {\n"
            + "        sample.Checker.check(1);\n" // holds
            + "        sample.Checker.check(2);\n" // holds
            + "        sample.Checker.check(3);\n" // violates n < 3
            + "        Thread.sleep(200);\n" // let the shutdown hook path settle either way
            + "    }\n"
            + "}\n",
        StandardCharsets.UTF_8);

    Path classesDir = tmp.resolve("classes");
    Files.createDirectories(classesDir);
    List<String> javacCmd =
        List.of(
            "javac",
            "-d",
            classesDir.toString(),
            srcDir.resolve("daikonpp").resolve("DpRuntime.java").toString(),
            checkerFile.toString(),
            mainFile.toString());
    int compileExit = new ProcessBuilder(javacCmd).start().waitFor();
    assertEquals(0, compileExit, "Compilation of injected Checker/Main failed");

    List<String> javaCmd =
        List.of(
            "java",
            "-DDP_SHM_DIR=" + shmDir.toAbsolutePath(),
            "-cp",
            classesDir.toString(),
            "Main");
    ProcessBuilder pb = new ProcessBuilder(javaCmd);
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    proc.waitFor();

    Path failFile = shmDir.resolve("fail").resolve(invId + ".json");
    assertTrue(
        Files.exists(failFile),
        "Invariant should have been re-evaluated (and caught failing) on the 3rd call, not "
            + "skipped because it already held on calls 1 and 2 -- process output:\n"
            + output);
  }

  @Test
  public void injectedGuard_stopsReevaluatingOnceFalsifiedInSameRun(@TempDir Path shmDir)
      throws Exception {
    // Companion to the test above: once an invariant IS falsified, there is no need to keep
    // re-checking it -- it's already refuted by an input. The guard must skip it on every later
    // call from then on (distinct from a merely-held invariant, which must keep being checked).
    //
    // The invariant expression itself increments a counter as a side effect, so we can observe
    // exactly how many times it was actually evaluated (not just how many times the checkpoint
    // was reached).

    Path srcDir = tmp.resolve("src");
    Path pkgDir = srcDir.resolve("sample2");
    Files.createDirectories(pkgDir);

    edu.njit.jerse.daikonplusplus.inject.DpRuntimeWriter.write(srcDir);

    Path checkerFile = pkgDir.resolve("Checker2.java");
    Files.writeString(
        checkerFile,
        "package sample2;\n"
            + "import java.util.concurrent.atomic.AtomicInteger;\n"
            + "public class Checker2 {\n"
            + "    public static final AtomicInteger EVAL_COUNT = new AtomicInteger();\n"
            + "    public static boolean evalAndCheck(int n) {\n"
            + "        EVAL_COUNT.incrementAndGet();\n"
            + "        return n < 2;\n"
            + "    }\n"
            + "    public static void check(int n) {\n"
            + "    }\n"
            + "}\n",
        StandardCharsets.UTF_8);

    edu.njit.jerse.daikonplusplus.model.ProgramElementId peid =
        edu.njit.jerse.daikonplusplus.model.ProgramElementId.forMethod(
            "sample2", "Checker2", "", "sample2/Checker2.java", "check(int):void");
    edu.njit.jerse.daikonplusplus.model.ProgramPoint point =
        new edu.njit.jerse.daikonplusplus.model.ProgramPointImpl(
            peid, edu.njit.jerse.daikonplusplus.model.ProgramPointKind.METHOD_ENTRY);
    edu.njit.jerse.daikonplusplus.model.InvariantSpec spec =
        new edu.njit.jerse.daikonplusplus.model.InvariantSpec(
            "Checker2.evalAndCheck(n)", "", Map.of());
    UUID invId = UUID.randomUUID();
    edu.njit.jerse.daikonplusplus.model.InvariantRecord rec =
        new edu.njit.jerse.daikonplusplus.model.InvariantRecord(
            invId, spec, point, "sample2/Checker2.java", java.time.Instant.now());

    edu.njit.jerse.daikonplusplus.inject.JavaParserInjector injector =
        new edu.njit.jerse.daikonplusplus.inject.JavaParserInjector(
            new edu.njit.jerse.daikonplusplus.inject.FileWriteCoordinator());
    injector.injectGuards(checkerFile, List.of(rec));

    Path mainFile = srcDir.resolve("Main2.java");
    Files.writeString(
        mainFile,
        "public class Main2 {\n"
            + "    public static void main(String[] a) throws Exception {\n"
            + "        sample2.Checker2.check(1);\n" // n=1: holds, evaluated
            + "        sample2.Checker2.check(2);\n" // n=2: falsified, evaluated
            + "        sample2.Checker2.check(3);\n" // n=3: must NOT be evaluated anymore
            + "        sample2.Checker2.check(4);\n" // n=4: must NOT be evaluated anymore
            + "        System.out.println(\"EVAL_COUNT:\" + sample2.Checker2.EVAL_COUNT.get());\n"
            + "    }\n"
            + "}\n",
        StandardCharsets.UTF_8);

    Path classesDir = tmp.resolve("classes");
    Files.createDirectories(classesDir);
    List<String> javacCmd =
        List.of(
            "javac",
            "-d",
            classesDir.toString(),
            srcDir.resolve("daikonpp").resolve("DpRuntime.java").toString(),
            checkerFile.toString(),
            mainFile.toString());
    int compileExit = new ProcessBuilder(javacCmd).start().waitFor();
    assertEquals(0, compileExit, "Compilation of injected Checker2/Main2 failed");

    List<String> javaCmd =
        List.of(
            "java",
            "-DDP_SHM_DIR=" + shmDir.toAbsolutePath(),
            "-cp",
            classesDir.toString(),
            "Main2");
    ProcessBuilder pb = new ProcessBuilder(javaCmd);
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    proc.waitFor();

    assertTrue(
        output.contains("EVAL_COUNT:2"),
        "Invariant should be evaluated exactly twice (call 1: holds, call 2: falsified) and "
            + "never again after being falsified (calls 3 and 4 must be skipped) -- output:\n"
            + output);
  }
}
