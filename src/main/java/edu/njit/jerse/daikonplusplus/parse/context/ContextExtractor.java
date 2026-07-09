package edu.njit.jerse.daikonplusplus.parse.context;

import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import java.nio.file.Path;

/**
 * Extracts contextual information for a given program point.
 *
 * <p>Implementations provide specific types of context (e.g., method body, documentation, call
 * sites) used during invariant generation.
 */
public interface ContextExtractor {

  /**
   * Extracts context for a program point from the source tree.
   *
   * @param point program point for which context is extracted
   * @param srcRoot root directory of the source code
   * @return extracted context
   * @throws Exception if extraction fails
   */
  ExtractedContext extract(ProgramPoint point, Path srcRoot) throws Exception;
}
