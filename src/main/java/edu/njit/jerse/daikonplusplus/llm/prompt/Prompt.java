package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Pair of system and user messages used to query an LLM.
 *
 * <p>The {@code systemMessage} defines global instructions and constraints, while the {@code
 * userMessage} contains the task-specific input such as program context and in-scope variables.
 */
public record Prompt(String systemMessage, String userMessage) {}
