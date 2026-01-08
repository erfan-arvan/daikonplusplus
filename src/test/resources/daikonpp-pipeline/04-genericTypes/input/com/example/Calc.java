package com.example;

public class Calc<T extends Number> {

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
    Calc<Integer> ci = new Calc<>();
    System.out.println(ci.safeDivide(10, 2));
    System.out.println(ci.safeDivide(10, 0));

    Calc<Double> cd = new Calc<>();
    System.out.println(cd.safeDivide(10.0, 2.0));
    System.out.println(cd.safeDivide(10.0, 0.0));
  }
}
