package edu.njit.jerse.daikonplusplus.parse.context;

/**
 * Kinds of context that can be extracted for a program point.
 *
 * <ul>
 *   <li>{@code METHOD_BODY}: source code of the method
 *   <li>{@code SCOPE}: in-scope variables and types
 *   <li>{@code METHOD_JAVADOC}: method-level documentation
 *   <li>{@code CLASS_DOC}: enclosing class documentation
 *   <li>{@code TYPE_DOC}: documentation of referenced types
 *   <li>{@code CALL_SITE}: usage context of the method
 *   <li>{@code IO_EXAMPLES}: input-output examples
 *   <li>{@code CALLEE_DOC}: documentation of called methods
 * </ul>
 */
public enum ContextKind {
  METHOD_BODY,
  SCOPE,
  METHOD_JAVADOC,
  CLASS_DOC,
  TYPE_DOC,
  CALL_SITE,
  IO_EXAMPLES,
  CALLEE_DOC
}
