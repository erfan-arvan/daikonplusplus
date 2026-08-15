package edu.njit.jerse.daikonplusplus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Utility class for storing and retrieving serialized LLM request/response pairs ("cassettes").
 *
 * <p>Cassettes enable deterministic replay of LLM interactions by saving the structured model
 * output (e.g., {@code InvariantsOut}) under a stable hashed key derived from the prompt content.
 * This allows regression testing and offline execution without contacting the real API.
 *
 * <p>Files are stored as JSON under a directory (e.g., {@code src/test/cassettes}), with filenames
 * derived from a SHA-256 hash of the canonicalized request.
 *
 * <p>This class is package-private and used internally by {@link ReplayingLlmClient}, {@link
 * RecordingCompositeLlmClient}, and {@link RealOpenAILlmClient}.
 */
final class Cassette {
  private static final ObjectMapper MAPPER =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  /**
   * Computes a stable short key for a given (system, user) prompt pair.
   *
   * <p>The key is the first 20 hexadecimal characters of the SHA-256 hash of a canonical JSON
   * representation of the request. This ensures identical prompts always map to the same file.
   *
   * @param system the system prompt
   * @param user the user prompt
   * @return deterministic 20-character hex key
   */
  static String key(String system, String user) {
    // Stable hash of canonical JSON request (easy and robust)
    Map<String, Object> req = new TreeMap<>();
    req.put("system", system);
    req.put("user", user);
    String canonical;
    try {
      canonical = MAPPER.writeValueAsString(req);
    } catch (Exception e) {
      canonical = system + "\n" + user;
    }
    return sha256(canonical).substring(0, 20);
  }

  /**
   * Writes a cassette file containing the serialized object value.
   *
   * <p>Creates the directory if missing and writes JSON with stable ordering and pretty formatting.
   *
   * @param dir cassette directory
   * @param key file key (without extension)
   * @param value object to serialize
   * @throws Exception if writing fails
   */
  static void write(Path dir, String key, Object value) throws Exception {
    Files.createDirectories(dir);
    Path f = dir.resolve(key + ".json");
    String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    Files.writeString(f, json, StandardCharsets.UTF_8);
  }

  /**
   * Reads and deserializes a cassette file into a given type.
   *
   * @param dir cassette directory
   * @param key file key (without extension)
   * @param type class type to deserialize into
   * @param <T> generic result type
   * @return deserialized object
   * @throws Exception if reading or parsing fails
   */
  static <T> T read(Path dir, String key, Class<T> type) throws Exception {
    Path f = dir.resolve(key + ".json");
    String json = Files.readString(f, StandardCharsets.UTF_8);
    return MAPPER.readValue(json, type);
  }

  /**
   * Computes the SHA-256 hash of a string and returns its lowercase hexadecimal encoding.
   *
   * @param s input string
   * @return 64-character hex digest
   */
  private static String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : d) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
