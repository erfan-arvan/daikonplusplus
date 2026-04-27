package edu.njit.jerse.daikonplusplus.llm.prompt;

import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Aggregates all information required to construct a prompt for invariant generation.
 *
 * <p>This includes the target program point, in-scope variables, optional contextual
 * information extracted from the codebase, and configuration such as the maximum number
 * of invariants to generate.
 *
 * <p>All context fields are optional and may be {@code null} depending on which
 * context extractors are enabled.
 */
public record PromptContext(
    ProgramPoint point,
    Map<String, String> inScope,
    @Nullable String methodImplementation,
    @Nullable String methodJavadoc,
    @Nullable String enclosingClassDocumentation,
    @Nullable String typeLevelDocumentation,
    @Nullable String callSiteContext,
    @Nullable String inputOutputExamples,
    @Nullable String calleeDoc,
    int maxInvariants) {}
