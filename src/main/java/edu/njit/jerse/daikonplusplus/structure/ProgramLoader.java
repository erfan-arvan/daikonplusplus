package edu.njit.jerse.daikonplusplus.structure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithRange;
import com.github.javaparser.ast.type.Type;
import edu.njit.jerse.daikonplusplus.pp.ProgramPoint;
import edu.njit.jerse.daikonplusplus.pp.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.pp.ProgramPointKind;
import edu.njit.jerse.daikonplusplus.pp.VariableInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Utility class for parsing Java source files and building the program structure hierarchy.
 *
 * <p>Uses JavaParser to extract packages, classes, fields, methods, and constructors, and generates
 * {@link ProgramPoint} objects for key locations like method entry and exit.
 */
public class ProgramLoader {

  /** Shared JavaParser instance. */
  private final JavaParser parser;

  /** Cache for package elements to avoid duplicates across files. */
  private final Map<String, PackageElement> packageCache = new HashMap<>();

  /** Constructs a new ProgramLoader with default parser settings. */
  public ProgramLoader() {
    this.parser = new JavaParser(new ParserConfiguration());
  }

  /**
   * Loads a program model from a list of source file paths.
   *
   * @param programName Name to assign to the loaded program.
   * @param sourceFiles List of paths to Java source files.
   * @return A {@link Program} object representing the loaded structure.
   * @throws IOException if any file cannot be read or parsed.
   */
  public Program load(String programName, List<Path> sourceFiles) throws IOException {
    Program program = new Program(programName);

    for (Path p : sourceFiles) {
      if (!Files.isRegularFile(p)) continue;

      CompilationUnit cu =
          parser.parse(p).getResult().orElseThrow(() -> new IOException("Parse failed for " + p));

      String pkgName = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
      PackageElement pkgElt = packageCache.computeIfAbsent(pkgName, PackageElement::new);
      if (!program.getTopLevelElements().contains(pkgElt)) {
        program.addTopLevelElement(pkgElt);
      }

      // Handle classes/interfaces
      cu.findAll(ClassOrInterfaceDeclaration.class)
          .forEach(
              td -> {
                createClassLike(td, pkgElt, p);
              });

      // Handle enums
      cu.findAll(EnumDeclaration.class)
          .forEach(
              td -> {
                createClassLike(td, pkgElt, p);
              });
    }

    return program;
  }

  /** Creates a ClassElement (for class/interface/enum) and processes its members. */
  private void createClassLike(TypeDeclaration<?> td, PackageElement pkgElt, Path file) {
    String className = td.getNameAsString();
    int start = startLine(td);
    int end = endLine(td);
    ClassElement cls = new ClassElement(className, pkgElt, file.toString(), start, end);

    // Iterate members with explicit typing to avoid raw/erased inference issues.
    for (BodyDeclaration<?> member : td.getMembers()) {
      if (member.isFieldDeclaration()) {
        createFields(member.asFieldDeclaration(), cls, file);
      } else if (member.isConstructorDeclaration()) {
        createConstructor(member.asConstructorDeclaration(), cls, file);
      } else if (member.isMethodDeclaration()) {
        createMethod(member.asMethodDeclaration(), cls, file);
      }
    }
  }

  /** Creates field elements from a JavaParser {@link FieldDeclaration}. */
  private void createFields(FieldDeclaration fd, ClassElement parent, Path file) {
    int start = startLine(fd);
    int end = endLine(fd);
    String type = fd.getElementType().asString();
    fd.getVariables()
        .forEach(
            var -> {
              new FieldElement(var.getNameAsString(), type, parent, file.toString(), start, end);
            });
  }

  /** Creates a constructor element and its associated program points. */
  private void createConstructor(ConstructorDeclaration cd, ClassElement parent, Path file) {
    int start = startLine(cd);
    int end = endLine(cd);
    List<String> paramTypes = new ArrayList<>();
    cd.getParameters().forEach(p -> paramTypes.add(erasedType(p.getType())));

    ConstructorElement ctor =
        new ConstructorElement(
            cd.getNameAsString(), paramTypes, parent, file.toString(), start, end);

    List<VariableInfo> params = new ArrayList<>();
    cd.getParameters()
        .forEach(p -> params.add(new VariableInfo(p.getNameAsString(), erasedType(p.getType()))));

    ctor.addProgramPoint(
        new ProgramPointImpl(ctor, ProgramPointKind.CONSTRUCTOR_ENTRY, start, params, List.of()));
    ctor.addProgramPoint(
        new ProgramPointImpl(ctor, ProgramPointKind.METHOD_EXIT, end, params, List.of()));
  }

  /** Creates a method element and its associated program points. */
  private void createMethod(MethodDeclaration md, ClassElement parent, Path file) {
    int start = startLine(md);
    int end = endLine(md);
    String ret = erasedType(md.getType());
    List<String> paramTypes = new ArrayList<>();
    md.getParameters().forEach(p -> paramTypes.add(erasedType(p.getType())));

    MethodElement method =
        new MethodElement(
            md.getNameAsString(), ret, paramTypes, parent, file.toString(), start, end);

    List<VariableInfo> params = new ArrayList<>();
    md.getParameters()
        .forEach(p -> params.add(new VariableInfo(p.getNameAsString(), erasedType(p.getType()))));

    // METHOD_ENTRY
    method.addProgramPoint(
        new ProgramPointImpl(method, ProgramPointKind.METHOD_ENTRY, start, params, List.of()));

    // METHOD_EXIT (+ synthetic 'return' if non-void)
    List<VariableInfo> exitVars = new ArrayList<>(params);
    if (!"void".equals(ret)) {
      exitVars.add(new VariableInfo("return", ret));
    }
    method.addProgramPoint(
        new ProgramPointImpl(method, ProgramPointKind.METHOD_EXIT, end, exitVars, List.of()));
  }

  /** Returns the erased (simple) type name for a JavaParser {@link Type}. */
  private static String erasedType(Type t) {
    return t.asString();
  }

  /** Returns the starting line for a node, or -1 if unknown. */
  private static int startLine(NodeWithRange<?> n) {
    return n.getRange().map(r -> r.begin.line).orElse(-1);
  }

  /** Returns the ending line for a node, or -1 if unknown. */
  private static int endLine(NodeWithRange<?> n) {
    return n.getRange().map(r -> r.end.line).orElse(-1);
  }
}
