package edu.njit.jerse.daikonplusplus.llm.prompt;

/**
 * Factory for creating {@link PromptStrategy} instances from configuration strings.
 *
 * <p>This class maps user-provided strategy names (including aliases) to concrete {@link
 * PromptStrategy} implementations. If an unknown name is provided, it falls back to the baseline
 * strategy.
 *
 * <p>Name matching is case-insensitive and ignores leading/trailing whitespace.
 */
public final class PromptStrategyFactory {
  private PromptStrategyFactory() {}

  /**
   * Resolves a strategy name to a concrete {@link PromptStrategy} implementation.
   *
   * <p>This method performs normalization and alias resolution but does not log the selected
   * strategy.
   *
   * @param rawName user-provided strategy name (may be null)
   * @return corresponding {@link PromptStrategy}, or baseline if unknown
   */
  public static PromptStrategy createInternal(String rawName) {
    String name = rawName == null ? "" : rawName.trim().toLowerCase();

    return switch (name) {
      case "", "baseline", "direct" -> new BaselineDirectPromptStrategy();
      case "naive", "naive_direct" -> new NaiveDirectPromptStrategy();
      case "fewshot", "few_shot" -> new FewShotPromptStrategy();
      case "cot", "chain_of_thought" -> new ChainOfThoughtPromptStrategy();
      case "stepwise", "stepwise_invariant_discovery" ->
          new StepwiseInvariantDiscoveryPromptStrategy();
      case "self_refine", "self_refinement", "self_refinement_loop" ->
          new SelfRefinementPromptStrategy();
      case "multi_sample", "multi_sample_agreement", "ensemble" ->
          new MultiSampleAgreementPromptStrategy();
      default -> {
        String msg = "[DP] Unknown prompt strategy: '" + rawName + "' — falling back to 'baseline'";
        System.err.println(msg);

        yield new BaselineDirectPromptStrategy();
      }
    };
  }

  /**
   * Creates a {@link PromptStrategy} and logs the selected strategy name.
   *
   * <p>This method delegates to {@link #createInternal(String)} and emits a diagnostic message
   * indicating which strategy is being used.
   *
   * @param raw user-provided strategy name (may be null)
   * @return selected {@link PromptStrategy}
   */
  public static PromptStrategy create(String raw) {
    PromptStrategy strategy = createInternal(raw);

    System.out.println("[DP] Using prompt strategy: " + strategy.name());

    return strategy;
  }
}
