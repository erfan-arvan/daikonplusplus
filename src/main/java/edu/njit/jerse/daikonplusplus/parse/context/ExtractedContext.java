package edu.njit.jerse.daikonplusplus.parse.context;

import java.util.Map;

public class ExtractedContext {

  public final Map<String, String> inScope;
  public final String methodBody;
  public final String methodJavadoc;
  public final String classDoc;
  public final String typeDoc;
  public final String callSiteContext;
  public final String ioExamples;

  public final String calleeDoc;

  public ExtractedContext(
      Map<String, String> inScope,
      String methodBody,
      String methodJavadoc,
      String classDoc,
      String typeDoc,
      String callSiteContext,
      String ioExamples,
      String calleeDoc) {

    this.inScope = inScope;
    this.methodBody = methodBody;
    this.methodJavadoc = methodJavadoc;
    this.classDoc = classDoc;
    this.typeDoc = typeDoc;
    this.callSiteContext = callSiteContext;
    this.ioExamples = ioExamples;
    this.calleeDoc = calleeDoc;
  }
}
