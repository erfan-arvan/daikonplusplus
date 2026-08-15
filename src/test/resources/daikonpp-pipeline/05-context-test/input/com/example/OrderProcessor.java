package com.example;

import java.util.*;

/** Processes orders and computes total price. */
public class OrderProcessor {

  /**
   * Processes a list of orders and returns the total price. Applies validation and discounts where
   * applicable.
   *
   * @param orders list of orders
   * @return total price after processing
   */
  public double processOrders(List<Order> orders) {
    if (orders == null || orders.isEmpty()) {
      return 0.0;
    }

    validateOrders(orders);

    double total = 0.0;

    for (Order order : orders) {
      double price = computePrice(order);

      if (isEligibleForDiscount(order)) {
        price = applyDiscount(price);
      }

      total += price;
    }

    return total;
  }

  /** Ensures all orders are valid. Throws IllegalArgumentException if any order is invalid. */
  private void validateOrders(List<Order> orders) {
    for (Order o : orders) {
      if (o == null || o.getQuantity() <= 0 || o.getUnitPrice() < 0) {
        throw new IllegalArgumentException("Invalid order");
      }
    }
  }

  /** Computes the price of an order. */
  private double computePrice(Order order) {
    return order.getQuantity() * order.getUnitPrice();
  }

  /** Returns true if the order qualifies for a discount. */
  private boolean isEligibleForDiscount(Order order) {
    return order.getQuantity() >= 10;
  }

  /** Applies a 10% discount. */
  private double applyDiscount(double price) {
    return price * 0.9;
  }
}
