package edu.njit.jerse.daikonplusplus.pp;

/** Represents a variable visible at a program point. */
public class VariableInfo {
  private final String name;
  private final String type;

  public VariableInfo(String name, String type) {
    this.name = name;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  @Override
  public String toString() {
    return type + " " + name;
  }
}
