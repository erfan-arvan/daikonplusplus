package com.example;

public class Foo {

  // Generic method with type variable T
  public static <T extends CharSequence> T notEmpty(T value) {
    if (value == null || value.length() == 0) {
      throw new IllegalArgumentException("empty");
    }
    return value;
  }
}
