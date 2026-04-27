package edu.njit.jerse.daikonplusplus.parse;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import edu.njit.jerse.daikonplusplus.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a Java source tree and extracts program points for methods.
 *
 * <p>For each method with a body, this scanner emits two program points:
 * {@link ProgramPointKind#METHOD_ENTRY} and {@link ProgramPointKind#METHOD_EXIT}.
 *
 * <p>Files that cannot be parsed are skipped.
 */
public final class JavaProjectScanner {

    /**
     * Walks a source directory and returns method entry and exit program points.
     *
     * <p>Each Java file is parsed, and for every method with a body, a corresponding
     * ENTRY and EXIT program point is created.
     *
     * @param srcRoot root directory containing Java source files
     * @return list of discovered program points
     * @throws IOException if file traversal fails
     */
  public List<ProgramPoint> scanMethodEntryExit(Path srcRoot) throws IOException {
    List<ProgramPoint> points = new ArrayList<>();
    try (var stream = Files.walk(srcRoot)) {
      stream
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              file -> {
                try {
                  CompilationUnit cu = StaticJavaParser.parse(file);
                  String pkg =
                      cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
                  cu.findAll(ClassOrInterfaceDeclaration.class)
                      .forEach(
                          cls -> {
                            String top = cls.getNameAsString();
                            String nested = "";
                            cls.findAll(MethodDeclaration.class, md -> md.getBody().isPresent())
                                .forEach(
                                    md -> {
                                      String desc = MethodSignatureUtil.jvmDescriptorBestEffort(md);
                                      var peid =
                                          ProgramElementId.forMethod(
                                              pkg,
                                              top,
                                              nested,
                                              srcRoot.relativize(file).toString(),
                                              desc);
                                      points.add(
                                          new ProgramPointImpl(
                                              peid, ProgramPointKind.METHOD_ENTRY));
                                      points.add(
                                          new ProgramPointImpl(peid, ProgramPointKind.METHOD_EXIT));
                                    });
                          });
                } catch (Exception e) {
                  System.err.println("[WARN] Skipping file (parse failed): " + file);
                }
              });
    }
    return points;
  }
}
