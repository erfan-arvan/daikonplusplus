package edu.njit.jerse.daikonplusplus.parse;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.nio.file.Path;

/**
 * Initializes JavaParser with symbol resolution for a given source root.
 *
 * <p>Configures a {@link JavaSymbolSolver} with reflection and source-based type solvers and
 * installs it into {@link StaticJavaParser}. Initialization is performed only once.
 */
public final class JavaParserSetup {

  private static boolean initialized = false;

  /**
   * Initializes JavaParser configuration if not already initialized.
   *
   * @param srcRoot root directory of the source code used for type resolution
   */
  public static synchronized void init(Path srcRoot) {
    if (initialized) return;

    CombinedTypeSolver solver = new CombinedTypeSolver();
    solver.add(new ReflectionTypeSolver());
    solver.add(new JavaParserTypeSolver(srcRoot));

    ParserConfiguration config =
        new ParserConfiguration()
            .setSymbolResolver(new JavaSymbolSolver(solver))
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

    StaticJavaParser.setConfiguration(config);

    initialized = true;
  }
}
