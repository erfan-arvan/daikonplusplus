package edu.njit.jerse.daikonplusplus.parse.context;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import edu.njit.jerse.daikonplusplus.model.ProgramElementId;
import edu.njit.jerse.daikonplusplus.model.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class ContextUtilsFullTest {

  private static final Path SRC_ROOT = Path.of("src/test/resources/testproj");

  private static void setupSolver() {
    CombinedTypeSolver solver = new CombinedTypeSolver();
    solver.add(new ReflectionTypeSolver());
    solver.add(new JavaParserTypeSolver(SRC_ROOT.toFile()));

    StaticJavaParser.getConfiguration().setSymbolResolver(new JavaSymbolSolver(solver));
  }

  @Test
  void testEverything() throws Exception {

    setupSolver();

    Path file = SRC_ROOT.resolve("foo/Bar.java");
    CompilationUnit cu = StaticJavaParser.parse(file);

    Optional<MethodDeclaration> maybe =
        cu.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("totalNameLength"));

    if (maybe.isEmpty()) {
      System.out.println("Method not found");
      return;
    }

    MethodDeclaration m = maybe.get();

    System.out.println("\n====================================");
    System.out.println("METHOD:");
    System.out.println(m.getDeclarationAsString());
    System.out.println("====================================");

    // -------------------------------
    System.out.println("\n=== PARAM TYPES ===");
    m.getParameters().forEach(p -> System.out.println(p.getName() + " : " + p.getType()));
    System.out.println("Return: " + m.getType());

    // -------------------------------
    System.out.println("\n=== METHOD BODY ===");
    System.out.println(m.getBody().map(Object::toString).orElse("NONE"));

    // -------------------------------
    System.out.println("\n=== JAVADOC ===");
    System.out.println(m.getJavadoc().map(j -> j.toText()).orElse("NONE"));

    // -------------------------------
    System.out.println("\n=== TYPE RESOLUTION + DOC (REAL PIPELINE) ===");

    var id =
        ProgramElementId.forMethod(
            "foo", // package
            "Bar", // class
            "", // nested
            "foo/Bar.java", // file path
            MethodSignatureUtil.jvmDescriptorBestEffort(m));

    var pp = new ProgramPointImpl(id, ProgramPointKind.METHOD_ENTRY);

    var typeContext = ContextUtils.extractTypeDocumentation(pp, SRC_ROOT);
    System.out.println(typeContext.orElse("NO TYPE INFO"));

    System.out.println("\n====================================\n");
  }
}
