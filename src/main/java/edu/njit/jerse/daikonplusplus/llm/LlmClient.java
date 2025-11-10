package edu.njit.jerse.daikonplusplus.llm;

import java.io.IOException;
import java.util.List;

/**
 * Common interface for low-level clients that communicate with a Large Language Model (LLM) to
 * obtain structured responses.
 *
 * <p>Implementations of this interface — such as {@link RealOpenAILlmClient}, {@link
 * ReplayingLlmClient}, and {@link RecordingCompositeLlmClient} — provide different backends for
 * accessing or replaying model completions.
 *
 * <p>This interface is used by higher-level components like {@link LlmInvariantGenerator}, which
 * build prompts and interpret the structured results.
 */
/**
 * Common interface for clients that interact with a Large Language Model (LLM) to generate program
 * invariants.
 *
 * <p>Implementations include {@link RealOpenAILlmClient}, {@link ReplayingLlmClient}, and {@link
 * RecordingCompositeLlmClient}. {@link LlmInvariantGenerator} depends on this interface to obtain
 * structured LLM outputs.
 */
public interface LlmClient {
  /**
   * Sends a prompt pair to the underlying LLM and returns structured invariant items parsed from
   * the response.
   *
   * @param system the system prompt providing contextual or behavioral instructions to the model
   * @param user the user prompt containing the actual task input (e.g., code snippet or query)
   * @return a list of invariant items conforming to {@link
   *     LlmInvariantGenerator.InvariantsOut.Item}
   * @throws IOException if communication with the model or response parsing fails
   */
  List<LlmInvariantGenerator.InvariantsOut.Item> complete(String system, String user)
      throws IOException;
}
