package edu.njit.jerse.daikonplusplus.parse.context;

/**
 * Kinds of context that can be extracted for a program point.
 *
 * <ul>
 *   <li>{@code METHOD_BODY}: source code of the method</li>
 *   <li>{@code SCOPE}: in-scope variables and types</li>
 *   <li>{@code METHOD_JAVADOC}: method-level documentation</li>
 *   <li>{@code CLASS_DOC}: enclosing class documentation</li>
 *   <li>{@code TYPE_DOC}: documentation of referenced types</li>
 *   <li>{@code CALL_SITE}: usage context of the method</li>
 *   <li>{@code IO_EXAMPLES}: input-output examples</li>
 *   <li>{@code CALLEE_DOC}: documentation of called methods</li>
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
