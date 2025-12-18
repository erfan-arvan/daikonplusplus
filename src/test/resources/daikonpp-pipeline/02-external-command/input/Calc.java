public class Calc {

  public static int safeDivide(int a, int b) {
    if (b == 0) {
      return Integer.MIN_VALUE;
    }
    return a / b;
  }

  public static void main(String[] args) {
    System.out.println(safeDivide(10, 2));
    System.out.println(safeDivide(10, 0));
  }
}
