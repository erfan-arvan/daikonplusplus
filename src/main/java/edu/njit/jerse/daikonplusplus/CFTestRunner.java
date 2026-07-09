package edu.njit.jerse.daikonplusplus;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public class CFTestRunner {
  public static void main(String[] args) {
    // Debug: where is SourceChecker actually loaded from?
    try {
      Class<?> sc = Class.forName("org.checkerframework.framework.source.SourceChecker");
      ProtectionDomain pd = sc.getProtectionDomain();
      CodeSource cs = (pd == null) ? null : pd.getCodeSource();
      URL loc = (cs == null) ? null : cs.getLocation();

      System.out.println(
          "[DP] SourceChecker loaded from: " + (loc == null ? "<unknown>" : loc.toString()));
    } catch (ClassNotFoundException e) {
      System.out.println("[DP] SourceChecker NOT found on runtime classpath: " + e.getMessage());
    }

    // Scan JUnit tests under framework
    try (ScanResult scan =
        new ClassGraph()
            .enableClassInfo()
            .enableMethodInfo()
            .enableAnnotationInfo()
            .acceptPackages("org.checkerframework.framework.test.junit")
            .scan()) {

      var testClasses = scan.getClassesWithMethodAnnotation("org.junit.Test").loadClasses();

      if (testClasses.isEmpty()) {
        System.err.println(
            "[DP] No JUnit 4 tests found in org.checkerframework.framework.test.junit");
        return;
      }

      Class<?>[] classes = testClasses.toArray(new Class<?>[0]);

      System.out.println(
          "[DP] Discovered " + classes.length + " JUnit test classes via CFTestRunner:");
      for (Class<?> c : classes) {
        System.out.println("  [DP]   " + c.getName());
      }

      System.out.println(
          "[DP] Running " + classes.length + " JUnit test classes via CFTestRunner...");

      // 🔥 NEW: show how many tests ran + failures
      Result r = JUnitCore.runClasses(classes);
      System.out.println("[DP] JUnit run finished:");
      System.out.println("  [DP]   runCount    = " + r.getRunCount());
      System.out.println("  [DP]   failureCount= " + r.getFailureCount());
      System.out.println("  [DP]   ignoreCount = " + r.getIgnoreCount());
      if (!r.wasSuccessful()) {
        System.out.println("  [DP]   failures:");
        for (Failure f : r.getFailures()) {
          System.out.println("    [DP] " + f.getTestHeader() + " :: " + f.getMessage());
        }
      }
    }
  }
}
