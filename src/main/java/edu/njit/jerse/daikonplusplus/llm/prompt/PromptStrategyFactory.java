package edu.njit.jerse.daikonplusplus.llm.prompt;

public final class PromptStrategyFactory {
  private PromptStrategyFactory() {}

  public static PromptStrategy create(String rawName) {
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

  public static PromptStrategy create() {
    String raw = System.getenv().getOrDefault("DP_PROMPT_STRATEGY", "baseline");
    PromptStrategy strategy = create(raw);

    System.out.println("[DP] Using prompt strategy: " + strategy.name());

    return strategy;
  }
}
