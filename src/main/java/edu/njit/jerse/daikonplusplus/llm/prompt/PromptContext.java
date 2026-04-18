package edu.njit.jerse.daikonplusplus.llm.prompt;

import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

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
