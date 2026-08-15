package com.example;

public class Calc {
  public static void main(String[] args) {
    Calc c = new Calc();
    int result1 = c.safeDivide(10, 2);
    int result2 = c.safeDivide(10, 0);
    System.out.println("Results: " + result1 + ", " + result2);
  }

  /** Returns a/b if b != 0, else returns Integer.MIN_VALUE. */
  public int safeDivide(int a, int b) {
    if (b == 0) {
      return Integer.MIN_VALUE;
    }
    return a / b;
  }
}
