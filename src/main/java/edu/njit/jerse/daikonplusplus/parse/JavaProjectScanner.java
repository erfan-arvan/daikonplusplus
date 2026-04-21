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
 * Walks a Java source tree and emits {@link ProgramPoint}s.
 *
 * <p>This version emits only {@link ProgramPointKind#METHOD_ENTRY} and {@link
 * ProgramPointKind#METHOD_EXIT} for each method with a body.
 */
public final class JavaProjectScanner {
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
