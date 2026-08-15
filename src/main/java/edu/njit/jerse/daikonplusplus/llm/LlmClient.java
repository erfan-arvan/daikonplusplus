package edu.njit.jerse.daikonplusplus.llm;

import java.io.IOException;
import java.util.List;

/**
 * Interface for clients that interact with a Large Language Model (LLM) to generate invariants.
 *
 * <p>Implementations provide different backends for obtaining model outputs (e.g., real API calls,
 * replayed responses, or recorded executions).
 *
 * <p>Used by {@link LlmInvariantGenerator} to obtain structured invariant results.
 */
public interface LlmClient {
  /**
   * Sends prompts to the LLM and returns structured invariant items.
   *
   * @param system system prompt
   * @param user user prompt
   * @return list of generated invariant items
   * @throws IOException if communication or parsing fails
   */
  List<LlmInvariantGenerator.InvariantsOut.Item> complete(String system, String user)
      throws IOException;
}
