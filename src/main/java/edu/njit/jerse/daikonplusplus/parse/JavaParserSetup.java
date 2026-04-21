package edu.njit.jerse.daikonplusplus.parse;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.nio.file.Path;

public final class JavaParserSetup {

  private static boolean initialized = false;

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
