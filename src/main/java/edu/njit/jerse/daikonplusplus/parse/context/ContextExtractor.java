package edu.njit.jerse.daikonplusplus.parse.context;

import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import java.nio.file.Path;

public interface ContextExtractor {

  ExtractedContext extract(ProgramPoint point, Path srcRoot) throws Exception;
}
