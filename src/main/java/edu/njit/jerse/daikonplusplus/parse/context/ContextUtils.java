package edu.njit.jerse.daikonplusplus.parse.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utility methods for extracting contextual information from source code for a given program point.
 */
public final class ContextUtils {

  private ContextUtils() {}

  /**
   * Executes a task with a timeout.
   *
   * @param task computation to execute
   * @param millis timeout in milliseconds
   * @return result wrapped in {@link Optional}, or empty if timeout or failure occurs
   */
  public static <T> Optional<T> runWithTimeout(Callable<T> task, long millis) {
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      Future<T> future = executor.submit(task);
      return Optional.ofNullable(future.get(millis, TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      return Optional.empty(); // timeout or failure
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Finds a class declaration in the source tree by its fully qualified name.
   *
   * @param qualifiedName fully qualified class name
   * @param srcRoot root directory of the source code
   * @return matching class declaration if found
   */
  public static Optional<ClassOrInterfaceDeclaration> findClassInProject(
      String qualifiedName, Path srcRoot) {

    try {
      // Convert package to path
      int lastDot = qualifiedName.lastIndexOf('.');
      if (lastDot < 0) return Optional.empty();

      String pkg = qualifiedName.substring(0, lastDot);
      String cls = qualifiedName.substring(lastDot + 1);

      Path file = srcRoot.resolve(pkg.replace('.', '/') + "/" + cls + ".java");

      if (!java.nio.file.Files.exists(file)) return Optional.empty();

      CompilationUnit cu = StaticJavaParser.parse(file);

      return cu.findFirst(ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals(cls));

    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Extracts variables and types available at a program point.
   *
   * <p>Includes method parameters and, for exit points, the return value as {@code result}.
   *
   * @param point program point
   * @param srcRoot root directory of the source code
   * @return map from variable name to type
   * @throws IOException if parsing fails
   */
  public static Map<String, String> extractScope(ProgramPoint point, Path srcRoot)
      throws IOException {

    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {

      for (MethodDeclaration m : cls.getMethods()) {

        if (!m.getBody().isPresent()) continue;

        String desc = MethodSignatureUtil.jvmDescriptorBestEffort(m);
        if (!desc.equals(targetDesc)) continue;

        LinkedHashMap<String, String> scope =
            m.getParameters().stream()
                .collect(
                    Collectors.toMap(
                        p -> p.getName().asString(),
                        p -> p.getType().toString(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        if (point.kind() == ProgramPointKind.METHOD_EXIT) {
          String ret = m.getType().toString();
          if (!"void".equals(ret)) {
            scope.put("result", ret);
          }
        }

        return scope;
      }
    }

    return Map.of();
  }

  /**
   * Extracts the raw source code of the method corresponding to a program point.
   *
   * @param point program point
   * @param srcRoot root directory of the source code
   * @return method body as a string if found
   * @throws IOException if parsing fails
   */
  public static Optional<String> extractMethodBodyRaw(ProgramPoint point, Path srcRoot)
      throws IOException {

    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      Optional<MethodDeclaration> maybe =
          cls.getMethods().stream()
              .filter(m -> m.getBody().isPresent())
              .filter(m -> MethodSignatureUtil.jvmDescriptorBestEffort(m).equals(targetDesc))
              .findFirst();

      if (maybe.isPresent()) {
        MethodDeclaration md = maybe.get();

        return md.getTokenRange()
            .map(tr -> Optional.of(tr.toString()))
            .orElseGet(() -> Optional.of(md.toString()));
      }
    }

    return Optional.empty();
  }

  /**
   * Extracts Javadoc for the method corresponding to a program point.
   *
   * @param point program point
   * @param srcRoot root directory of the source code
   * @return method Javadoc text if present
   * @throws IOException if parsing fails
   */
  public static Optional<String> extractMethodJavadoc(ProgramPoint point, Path srcRoot)
      throws IOException {

    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      for (MethodDeclaration m : cls.getMethods()) {

        if (!m.getBody().isPresent()) continue;

        String desc = MethodSignatureUtil.jvmDescriptorBestEffort(m);
        if (!desc.equals(targetDesc)) continue;

        return m.getJavadoc().map(j -> j.toText());
      }
    }

    return Optional.empty();
  }

  /**
   * Extracts documentation for the class associated with a program point.
   *
   * @param point program point
   * @param srcRoot root directory of the source code
   * @return class documentation if available
   */
  public static Optional<String> extractClassDocumentation(ProgramPoint point, Path srcRoot) {

    String className = extractClassNameFromPoint(point);

    return extractFullClassInfo(className, srcRoot);
  }

  /**
   * Extracts documentation for types referenced in a method's signature.
   *
   * @param point program point
   * @param srcRoot root directory of the source code
   * @return concatenated documentation for referenced types if available
   * @throws IOException if parsing fails
   */
  public static Optional<String> extractTypeDocumentation(ProgramPoint point, Path srcRoot)
      throws IOException {

    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      for (MethodDeclaration m : cls.getMethods()) {

        if (!m.getBody().isPresent()) continue;

        String desc = MethodSignatureUtil.jvmDescriptorBestEffort(m);
        if (!desc.equals(targetDesc)) continue;

        Set<String> seen = new HashSet<>();
        List<String> collected = new ArrayList<>();

        List<com.github.javaparser.ast.type.Type> types = new ArrayList<>();
        m.getParameters().forEach(p -> types.add(p.getType()));
        types.add(m.getType());

        List<com.github.javaparser.ast.type.Type> expanded = new ArrayList<>();
        for (var t : types) collectTypesRecursively(t, expanded);

        for (var t : expanded) {
          try {
            Optional<ResolvedType> resolvedOpt = runWithTimeout(() -> t.resolve(), 100);
            if (resolvedOpt.isEmpty()) continue;

            var resolved = resolvedOpt.get();
            if (!resolved.isReferenceType()) continue;

            String qName = resolved.asReferenceType().getQualifiedName();

            if (qName.startsWith("java.")
                || qName.startsWith("javax.")
                || qName.startsWith("sun.")) {
              continue;
            }

            if (!seen.add(qName)) continue;

            extractFullClassInfo(qName, srcRoot).ifPresent(collected::add);

          } catch (Exception ignored) {
          }
        }

        if (collected.isEmpty()) {
          return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();

        for (String info : collected) {
          sb.append(info).append("\n\n");
        }

        return Optional.of(sb.toString().trim());
      }
    }

    return Optional.empty();
  }

  /**
   * Cache of parsed call-site index files, keyed by file path, so each is read only once per run.
   */
  private static final Map<String, Map<String, List<Map<String, String>>>> CALL_SITE_INDEX_CACHE =
      new ConcurrentHashMap<>();

  /** Maximum number of call sites to include as context for a single program point. */
  private static final int MAX_CALL_SITES = 5;

  /**
   * Loads (and caches) a pre-built call-site index from disk.
   *
   * <p>The index is a JSON object mapping a callee's key (in the same {@code
   * pkg.Class#name(paramTypes):returnType} format produced by {@link
   * edu.njit.jerse.daikonplusplus.model.ProgramElementId#toString()}) to a list of caller records,
   * each with {@code callerKey}, {@code callerJavadoc}, and {@code callSite} fields.
   *
   * @param path path to the call-site index JSON file
   * @return parsed index, or an empty map if the file is missing or unparsable
   */
  private static Map<String, List<Map<String, String>>> loadCallSiteIndex(String path) {
    return CALL_SITE_INDEX_CACHE.computeIfAbsent(
        path,
        p -> {
          try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(
                new File(p), new TypeReference<Map<String, List<Map<String, String>>>>() {});
          } catch (Exception e) {
            return Map.of();
          }
        });
  }

  /**
   * Locates the source declaration of a caller method identified by a {@code
   * pkg.Class#name(paramTypes):returnType} key, matching by declaring class and parameter count.
   *
   * @param callerKey caller identifier in {@code pkg.Class#name(paramTypes):returnType} format
   * @param srcRoot root directory of the source code
   * @return the caller's method declaration if it can be located
   */
  private static Optional<MethodDeclaration> findCallerDeclaration(String callerKey, Path srcRoot) {
    int hash = callerKey.indexOf('#');
    if (hash < 0) return Optional.empty();

    String qualifiedClass = callerKey.substring(0, hash);
    String desc = callerKey.substring(hash + 1);

    int paren = desc.indexOf('(');
    int closeParen = desc.indexOf(')');
    if (paren < 0 || closeParen < paren) return Optional.empty();

    String methodName = desc.substring(0, paren);
    String paramsPart = desc.substring(paren + 1, closeParen);
    int paramCount = paramsPart.isBlank() ? 0 : paramsPart.split(",").length;

    Optional<ClassOrInterfaceDeclaration> maybeClass = findClassInProject(qualifiedClass, srcRoot);
    if (maybeClass.isEmpty()) return Optional.empty();

    return maybeClass.get().getMethodsByName(methodName).stream()
        .filter(m -> m.getParameters().size() == paramCount)
        .findFirst();
  }

  /**
   * Extracts call-site context for a program point, using a pre-built call-site index.
   *
   * <p>The index is expected to have been produced offline (e.g. via static call-graph analysis)
   * and configured via {@code DP_CALL_SITES_INDEX} / {@code dp.callSitesIndex}. For each of the
   * program point's recorded callers (up to {@link #MAX_CALL_SITES}), this includes the caller's
   * javadoc (if any) and its method implementation.
   *
   * @param point program point
   * @param srcRoot root directory of the source code
   * @param callSitesIndexPath path to the call-site index JSON file, or {@code null} if unset
   * @return call-site context if available
   */
  public static Optional<String> extractCallSiteContext(
      ProgramPoint point, Path srcRoot, @Nullable String callSitesIndexPath) {

    if (callSitesIndexPath == null || callSitesIndexPath.isBlank()) {
      return Optional.empty();
    }

    Map<String, List<Map<String, String>>> index = loadCallSiteIndex(callSitesIndexPath);
    if (index.isEmpty()) return Optional.empty();

    String key = point.elementId().toString();
    List<Map<String, String>> callers = index.get(key);
    if (callers == null || callers.isEmpty()) return Optional.empty();

    StringBuilder sb = new StringBuilder();
    int included = 0;

    for (Map<String, String> caller : callers) {
      if (included >= MAX_CALL_SITES) break;

      String callerKey = caller.get("callerKey");
      if (callerKey == null || callerKey.isBlank()) continue;

      Optional<MethodDeclaration> callerMethodOpt = findCallerDeclaration(callerKey, srcRoot);
      if (callerMethodOpt.isEmpty()) continue;

      MethodDeclaration callerMethod = callerMethodOpt.get();
      Optional<String> body = callerMethod.getBody().map(Object::toString);
      if (body.isEmpty()) continue;

      String javadoc = caller.getOrDefault("callerJavadoc", "");
      if (javadoc == null || javadoc.isBlank()) {
        javadoc = callerMethod.getJavadoc().map(j -> j.toText().strip()).orElse("");
      }

      if (included > 0) sb.append("\n\n");
      sb.append("Caller: ").append(callerKey).append("\n");
      if (!javadoc.isBlank()) {
        sb.append("Javadoc: ").append(javadoc).append("\n");
      }
      sb.append("Implementation:\n").append(body.get());

      included++;
    }

    if (included == 0) return Optional.empty();
    return Optional.of(sb.toString());
  }

  /**
   * Cache of parsed I/O-examples index files, keyed by file path, so each is read only once per
   * run.
   */
  private static final Map<String, Map<String, List<Map<String, Object>>>> IO_EXAMPLES_INDEX_CACHE =
      new ConcurrentHashMap<>();

  /** Maximum number of input-output examples to include as context for a single program point. */
  private static final int MAX_IO_EXAMPLES = 5;

  /**
   * Loads (and caches) a pre-built input-output examples index from disk.
   *
   * <p>The index is a JSON object mapping a method's key (in the same {@code
   * pkg.Class#name(paramTypes):returnType} format produced by {@link
   * edu.njit.jerse.daikonplusplus.model.ProgramElementId#toString()}) to a list of example records,
   * each with an {@code args} object (parameter name to observed value) and a {@code return} value,
   * as captured by Daikon's Chicory front end from a real test run.
   *
   * @param path path to the I/O-examples index JSON file
   * @return parsed index, or an empty map if the file is missing or unparsable
   */
  private static Map<String, List<Map<String, Object>>> loadIOExamplesIndex(String path) {
    return IO_EXAMPLES_INDEX_CACHE.computeIfAbsent(
        path,
        p -> {
          try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(
                new File(p), new TypeReference<Map<String, List<Map<String, Object>>>>() {});
          } catch (Exception e) {
            return Map.of();
          }
        });
  }

  /**
   * Extracts input-output examples for a program point, using a pre-built I/O-examples index.
   *
   * <p>The index is expected to have been produced offline (e.g. via Daikon's Chicory
   * instrumentation of a real test run) and configured via {@code DP_IO_EXAMPLES_INDEX} / {@code
   * dp.ioExamplesIndex}. Up to {@link #MAX_IO_EXAMPLES} recorded examples are rendered as
   * argument/return value pairs.
   *
   * @param point program point
   * @param srcRoot root directory of the source code (unused; kept for signature symmetry with
   *     other {@code extract*} methods)
   * @param ioExamplesIndexPath path to the I/O-examples index JSON file, or {@code null} if unset
   * @return input-output example context if available
   */
  public static Optional<String> extractIOExamples(
      ProgramPoint point, Path srcRoot, @Nullable String ioExamplesIndexPath) {

    if (ioExamplesIndexPath == null || ioExamplesIndexPath.isBlank()) {
      return Optional.empty();
    }

    Map<String, List<Map<String, Object>>> index = loadIOExamplesIndex(ioExamplesIndexPath);
    if (index.isEmpty()) return Optional.empty();

    String key = point.elementId().toString();
    List<Map<String, Object>> examples = index.get(key);
    if (examples == null || examples.isEmpty()) return Optional.empty();

    StringBuilder sb = new StringBuilder();
    int included = 0;

    for (Map<String, Object> example : examples) {
      if (included >= MAX_IO_EXAMPLES) break;

      Object argsObj = example.get("args");
      if (!(argsObj instanceof Map)) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> args = (Map<String, Object>) argsObj;

      if (included > 0) sb.append("\n");
      sb.append("Example ").append(included + 1).append(": ");
      sb.append(
          args.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining(", ")));
      sb.append(" -> return=").append(example.get("return"));

      included++;
    }

    if (included == 0) return Optional.empty();
    return Optional.of(sb.toString());
  }

  /**
   * Collects a type and its nested type arguments recursively.
   *
   * @param type type to process
   * @param out list to store collected types
   */
  private static void collectTypesRecursively(
      com.github.javaparser.ast.type.Type type, List<com.github.javaparser.ast.type.Type> out) {

    out.add(type);

    if (type.isClassOrInterfaceType()) {
      type.asClassOrInterfaceType()
          .getTypeArguments()
          .ifPresent(
              args -> {
                for (var t : args) {
                  collectTypesRecursively(t, out);
                }
              });
    }
  }

  /**
   * Extracts documentation for methods called within a method.
   *
   * @param point program point
   * @param srcRoot root directory of the source code
   * @return documentation for called methods if available
   * @throws IOException if parsing fails
   */
  public static Optional<String> extractCalleeDocumentation(ProgramPoint point, Path srcRoot)
      throws IOException {

    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      for (MethodDeclaration m : cls.getMethods()) {

        if (!m.getBody().isPresent()) continue;

        String desc = MethodSignatureUtil.jvmDescriptorBestEffort(m);
        if (!desc.equals(targetDesc)) continue;

        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();

        // find all method calls
        m.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
            .limit(5) // limit to avoid explosion
            .forEach(
                call -> {
                  try {

                    Optional<
                            com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration>
                        resolvedOpt = runWithTimeout(() -> call.resolve(), 100);

                    if (resolvedOpt.isEmpty()) return;

                    var resolved = resolvedOpt.get();

                    String qClass = resolved.getPackageName() + "." + resolved.getClassName();

                    // skip JDK
                    if (qClass.startsWith("java.")
                        || qClass.startsWith("javax.")
                        || qClass.startsWith("sun.")) {
                      return;
                    }

                    String signature = resolved.getQualifiedSignature();

                    if (!seen.add(signature)) return;

                    sb.append("Called Method: ").append(signature).append("\n");

                    // try to find source
                    Optional<Optional<ClassOrInterfaceDeclaration>> maybeClassWrapped =
                        runWithTimeout(() -> findClassInProject(qClass, srcRoot), 100);

                    Optional<ClassOrInterfaceDeclaration> maybeClass =
                        maybeClassWrapped.orElse(Optional.empty());

                    if (maybeClass.isPresent()) {
                      ClassOrInterfaceDeclaration ci = maybeClass.get();

                      Optional<MethodDeclaration> targetMethod =
                          ci.getMethodsByName(resolved.getName()).stream().findFirst();

                      if (targetMethod.isPresent()) {

                        MethodDeclaration callee = targetMethod.get();

                        if (callee.getJavadoc().isPresent()) {
                          sb.append("Javadoc: ")
                              .append(callee.getJavadoc().get().toText())
                              .append("\n");
                        } else {
                          // fallback: short implementation only
                          sb.append("Signature: ")
                              .append(callee.getDeclarationAsString(false, false, false))
                              .append("\n");

                          callee
                              .getBody()
                              .ifPresent(
                                  b -> {
                                    String body = b.toString();
                                    if (body.length() < 300) {
                                      sb.append("Body: ").append(body).append("\n");
                                    } else {
                                      sb.append("Body: <omitted: too large>\n");
                                    }
                                  });
                        }
                      }

                    } else {
                      sb.append("Source: not available\n");
                    }

                    sb.append("\n");

                  } catch (Exception ignored) {
                  }
                });

        return sb.length() == 0 ? Optional.empty() : Optional.of(sb.toString());
      }
    }

    return Optional.empty();
  }

  /**
   * Extracts structured information for a class, including documentation, fields, and methods.
   *
   * @param qualifiedName fully qualified class name
   * @param srcRoot root directory of the source code
   * @return formatted class information if available
   */
  public static Optional<String> extractFullClassInfo(String qualifiedName, Path srcRoot) {

    try {
      int lastDot = qualifiedName.lastIndexOf('.');
      if (lastDot < 0) return Optional.empty();

      String pkg = qualifiedName.substring(0, lastDot);
      String clsName = qualifiedName.substring(lastDot + 1);

      Path file = srcRoot.resolve(pkg.replace('.', '/') + "/" + clsName + ".java");
      if (!java.nio.file.Files.exists(file)) return Optional.empty();

      CompilationUnit cu = StaticJavaParser.parse(file);

      Optional<ClassOrInterfaceDeclaration> clsOpt =
          cu.findFirst(ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals(clsName));

      if (clsOpt.isEmpty()) return Optional.empty();

      ClassOrInterfaceDeclaration cls = clsOpt.get();

      StringBuilder sb = new StringBuilder();

      // ===== CLASS =====
      sb.append("[Class] ").append(cls.getNameAsString()).append("\n");
      cls.getJavadoc().ifPresent(j -> sb.append(j.toText().trim()).append("\n"));
      sb.append("\n");

      // ===== FIELDS =====
      for (var field : cls.getFields()) {
        field
            .getVariables()
            .forEach(
                v -> {
                  sb.append("[Field] ")
                      .append(v.getNameAsString())
                      .append(" : ")
                      .append(v.getType().toString())
                      .append("\n");
                });
      }
      if (!cls.getFields().isEmpty()) sb.append("\n");

      // ===== METHODS =====
      for (MethodDeclaration m : cls.getMethods()) {
        sb.append("[Method] ")
            .append(m.getNameAsString())
            .append("(")
            .append(
                m.getParameters().stream()
                    .map(p -> p.getType().toString())
                    .collect(Collectors.joining(", ")))
            .append(")")
            .append(" : ")
            .append(m.getType().asString())
            .append("\n");
        m.getJavadoc().ifPresent(j -> sb.append(j.toText().trim()).append("\n"));
        sb.append("\n");
      }

      return Optional.of(sb.toString().trim());

    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Extracts the class name from a program point identifier.
   *
   * @param point program point
   * @return fully qualified class name
   */
  public static String extractClassNameFromPoint(ProgramPoint point) {
    String full = point.elementId().toString();
    // e.g. com.example.OrderProcessor#processOrders(...)

    int hashIdx = full.indexOf('#');
    if (hashIdx == -1) return full;

    return full.substring(0, hashIdx);
  }
}
