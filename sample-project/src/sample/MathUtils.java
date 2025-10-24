package sample;

public class MathUtils {

  // Target 1
  public static int sum(int a, int b) {
    if (a < 0) throw new IllegalArgumentException("negative a not allowed");
    return a + b;
  }

  // Target 2
  public static int max(int a, int b) {
    return (a >= b) ? a : b;
  }
}

