package edu.njit.jerse.daikonplusplus.llm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * LLM client that combines replay and live modes, recording new responses when missing.
 *
 * <p>This client first attempts to serve completions from a primary source (typically a {@link
 * ReplayingLlmClient}). If no cassette is found, it falls back to a live LLM (e.g., {@link
 * RealOpenAILlmClient}), and then records the obtained response back into the cassette directory
 * for future deterministic replays.
 *
 * <p>This design allows the system to transparently build or extend its cassette corpus while
 * running in a mixed record-and-replay mode.
 *
 * @see ReplayingLlmClient
 * @see RealOpenAILlmClient
 * @see Cassette
 */
public final class RecordingCompositeLlmClient implements LlmClient {
  private final LlmClient primary; // usually ReplayingLlmClient
  private final LlmClient fallback; // RealOpenAILlmClient
  private final Path cassetteDir;

  /**
   * Constructs a composite client that first replays from a primary client and falls back to a live
   * LLM when a cassette is missing.
   *
   * @param primary the primary replay client
   * @param fallback the fallback live client used when no cached response is found
   * @param cassetteDir directory where new cassettes should be written
   */
  public RecordingCompositeLlmClient(LlmClient primary, LlmClient fallback, Path cassetteDir) {
    this.primary = primary;
    this.fallback = fallback;
    this.cassetteDir = cassetteDir;
  }

  /**
   * Attempts to retrieve a cached response for the given prompt; if missing, queries the fallback
   * LLM and records the result as a new cassette.
   *
   * @param system the system prompt providing model context
   * @param user the user prompt containing the code or query
   * @return list of invariant items produced by the LLM
   * @throws IOException if both replay and recording fail
   */
  @Override
  public List<LlmInvariantGenerator.InvariantsOut.Item> complete(String system, String user)
      throws IOException {
    String key = Cassette.key(system, user);
    try {
      return primary.complete(system, user);
    } catch (IOException miss) {
      List<LlmInvariantGenerator.InvariantsOut.Item> items = fallback.complete(system, user);
      // persist as full InvariantsOut
      LlmInvariantGenerator.InvariantsOut out = new LlmInvariantGenerator.InvariantsOut();
      out.invariants = items;
      try {
        Cassette.write(cassetteDir, key, out);
      } catch (Exception ignore) {
      }
      return items;
    }
  }
}
