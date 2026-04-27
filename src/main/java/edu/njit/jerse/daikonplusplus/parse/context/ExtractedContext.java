package edu.njit.jerse.daikonplusplus.parse.context;

import java.util.Map;

/**
 * Container for contextual information extracted for a program point.
 *
 * <p>Holds all context components used during invariant generation.
 */
public class ExtractedContext {

  public final Map<String, String> inScope;
  public final String methodBody;
  public final String methodJavadoc;
  public final String classDoc;
  public final String typeDoc;
  public final String callSiteContext;
  public final String ioExamples;
  public final String calleeDoc;

  /**
   * Creates a new extracted context instance.
   *
   * @param inScope variables and their types available at the program point
   * @param methodBody source code of the method
   * @param methodJavadoc method-level documentation
   * @param classDoc documentation of the enclosing class
   * @param typeDoc documentation of referenced types
   * @param callSiteContext call-site information
   * @param ioExamples input-output examples
   * @param calleeDoc documentation of called methods
   */
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
