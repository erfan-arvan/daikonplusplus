package sample;

public class Main {
  public static void main(String[] args) {
    // Simple calls to exercise both ENTRY and EXIT points.
    int s = MathUtils.sum(1, 2);     // 3
    int m = MathUtils.max(3, 2);     // 3

    if (s != 3) throw new AssertionError("sum wrong: " + s);
    if (m != 3) throw new AssertionError("max wrong: " + m);

    // Keep program short & deterministic
    System.out.println("OK:" + s + "," + m);
  }
}

