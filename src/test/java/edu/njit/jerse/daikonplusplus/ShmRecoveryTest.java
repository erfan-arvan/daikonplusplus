package edu.njit.jerse.daikonplusplus;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for /dev/shm-backed invariant persistence (read-back) and related recovery behaviour. */
final class ShmRecoveryTest {

  // ---- readExecutedIdsFromShm ----

  @Test
  void readExecutedIdsFromShm_returnsUuidsFromExDir(@TempDir Path shmDir) throws Exception {
    Path exDir = shmDir.resolve("ex");
    Files.createDirectories(exDir);

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    Files.createFile(exDir.resolve(id1.toString()));
    Files.createFile(exDir.resolve(id2.toString()));

    Set<UUID> result = LogParser.readExecutedIdsFromShm(shmDir);

    assertEquals(2, result.size());
    assertTrue(result.contains(id1));
    assertTrue(result.contains(id2));
  }

  @Test
  void readExecutedIdsFromShm_ignoresNonUuidFilenames(@TempDir Path shmDir) throws Exception {
    Path exDir = shmDir.resolve("ex");
    Files.createDirectories(exDir);

    UUID valid = UUID.randomUUID();
    Files.createFile(exDir.resolve(valid.toString()));
    Files.createFile(exDir.resolve("not-a-uuid.txt"));
    Files.createFile(exDir.resolve("12345"));

    Set<UUID> result = LogParser.readExecutedIdsFromShm(shmDir);

    assertEquals(1, result.size());
    assertTrue(result.contains(valid));
  }

  // ---- readFalsifiedIdsFromShm ----

  @Test
  void readFalsifiedIdsFromShm_returnsUuidsFromFailDir(@TempDir Path shmDir) throws Exception {
    Path failDir = shmDir.resolve("fail");
    Files.createDirectories(failDir);

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    String json1 = "{\"type\":\"INV_FAIL\",\"id\":\"" + id1 + "\"}";
    String json2 = "{\"type\":\"INV_FAIL\",\"id\":\"" + id2 + "\"}";
    Files.writeString(failDir.resolve(id1 + ".json"), json1);
    Files.writeString(failDir.resolve(id2 + ".json"), json2);

    Set<UUID> result = LogParser.readFalsifiedIdsFromShm(shmDir);

    assertEquals(2, result.size());
    assertTrue(result.contains(id1));
    assertTrue(result.contains(id2));
  }

  @Test
  void readFalsifiedIdsFromShm_ignoresFilesWithoutJsonSuffix(@TempDir Path shmDir)
      throws Exception {
    Path failDir = shmDir.resolve("fail");
    Files.createDirectories(failDir);

    UUID valid = UUID.randomUUID();
    Files.writeString(failDir.resolve(valid + ".json"), "{\"type\":\"INV_FAIL\"}");
    // These should be ignored:
    Files.writeString(failDir.resolve(valid + ".txt"), "irrelevant");
    Files.writeString(failDir.resolve("not-a-uuid.json"), "{}");

    Set<UUID> result = LogParser.readFalsifiedIdsFromShm(shmDir);

    // "not-a-uuid.json" is not a valid UUID so it is silently skipped
    assertEquals(1, result.size());
    assertTrue(result.contains(valid));
  }
}
