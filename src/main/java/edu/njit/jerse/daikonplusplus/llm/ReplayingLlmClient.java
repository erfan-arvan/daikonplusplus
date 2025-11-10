package edu.njit.jerse.daikonplusplus.llm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * LLM client implementation that replays cached responses (cassettes) instead of calling a real
 * model.
 *
 * <p>This client is used during deterministic regression testing, where all LLM responses are read
 * from pre-recorded JSON "cassette" files under a specified directory. Each request is identified
 * by a hash key derived from the system and user prompts.
 *
 * <p>If a matching cassette file is not found, an {@link IOException} is thrown, indicating a
 * missing replay record.
 *
 * @see Cassette
 * @see LlmInvariantGenerator.InvariantsOut
 */
public final class ReplayingLlmClient implements LlmClient {
  private final Path cassetteDir;

  /**
   * Constructs a replay-only LLM client that loads responses from the given cassette directory.
   *
   * @param cassetteDir path to the directory containing pre-recorded LLM response files
   */
  public ReplayingLlmClient(Path cassetteDir) {
    this.cassetteDir = cassetteDir;
  }

  /**
   * Returns the cached LLM response corresponding to the given prompt pair.
   *
   * <p>The key is computed from the system and user prompts using {@link Cassette#key(String,
   * String)}. If the cassette file is missing or unreadable, an {@link IOException} is thrown.
   *
   * @param system the system prompt text
   * @param user the user prompt text
   * @return a list of invariant items reconstructed from the cassette file
   * @throws IOException if the cassette cannot be found or read
   */
  @Override
  public List<LlmInvariantGenerator.InvariantsOut.Item> complete(String system, String user)
      throws IOException {
    String key = Cassette.key(system, user);
    try {
      LlmInvariantGenerator.InvariantsOut out =
          Cassette.read(cassetteDir, key, LlmInvariantGenerator.InvariantsOut.class);
      return out.invariants;
    } catch (Exception e) {
      throw new IOException("Cassette missing for key=" + key + " in " + cassetteDir, e);
    }
  }
}
