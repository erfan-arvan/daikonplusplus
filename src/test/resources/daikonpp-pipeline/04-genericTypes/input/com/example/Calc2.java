package com.example;

public class Calc2<T extends Number> {

  public T safeDivide(T a, T b) {
    if (b.doubleValue() == 0.0) {
      return null;
    }

    if (a instanceof Integer) {
      return (T) Integer.valueOf(a.intValue() / b.intValue());
    }

    if (a instanceof Double) {
      return (T) Double.valueOf(a.doubleValue() / b.doubleValue());
    }

    throw new IllegalArgumentException("Unsupported numeric type");
  }

  public static void main(String[] args) {
    Calc2<Integer> ci = new Calc2<>();
    System.out.println(ci.safeDivide(10, 2));
    System.out.println(ci.safeDivide(10, 0));

    Calc2<Double> cd = new Calc2<>();
    System.out.println(cd.safeDivide(10.0, 2.0));
    System.out.println(cd.safeDivide(10.0, 0.0));
  }
}
