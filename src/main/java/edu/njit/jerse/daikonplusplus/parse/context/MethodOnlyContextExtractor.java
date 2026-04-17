package edu.njit.jerse.daikonplusplus.parse.context;

import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import java.nio.file.Path;
import java.util.Map;

public class MethodOnlyContextExtractor implements ContextExtractor {

  @Override
  public ExtractedContext extract(ProgramPoint point, Path srcRoot) throws Exception {

    Map<String, String> inScope = ContextUtils.extractScope(point, srcRoot);

    String body = ContextUtils.extractMethodBodyRaw(point, srcRoot).orElse("");

    return new ExtractedContext(
        inScope,
        body,
        ContextUtils.extractMethodJavadoc(point, srcRoot).orElse(""),
        ContextUtils.extractClassDocumentation(point, srcRoot).orElse(""),
        ContextUtils.extractTypeDocumentation(point, srcRoot).orElse(""),
        ContextUtils.extractCallSiteContext(point, srcRoot).orElse(""),
        ContextUtils.extractIOExamples(point, srcRoot).orElse(""),
        ContextUtils.extractCalleeDocumentation(point, srcRoot).orElse(""));
  }
}
