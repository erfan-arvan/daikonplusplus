package com.example;

/** Represents a customer order. */
public class Order {

  private int quantity;
  private double unitPrice;

  public Order(int quantity, double unitPrice) {
    this.quantity = quantity;
    this.unitPrice = unitPrice;
  }

  /** Returns the number of items in the order. */
  public int getQuantity() {
    return quantity;
  }

  /** Returns the price per item. */
  public double getUnitPrice() {
    return unitPrice;
  }

  /** test extra Javadoc Second line .... */
  private Integer testExtra(Order a) {
    return 1;
  }
}
