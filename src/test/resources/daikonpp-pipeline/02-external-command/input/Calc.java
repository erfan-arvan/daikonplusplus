package com.example;

public class Calc {

  public static void main(String[] args) {
    Calc c = new Calc();
    System.out.println(c.safeDivide(10, 2));
    System.out.println(c.safeDivide(10, 0));
  }

  public int safeDivide(int a, int b) {
    if (b == 0) {
      return Integer.MIN_VALUE;
    }
    return a / b;
  }
}
