package com.example;

public class Foo {
  public static int bar(int x, String name) {
    if (name == null) return -1;
    return x + name.length();
  }
}
