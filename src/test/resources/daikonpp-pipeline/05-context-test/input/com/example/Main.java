package com.example;

import java.util.*;

public class Main {

  public static void main(String[] args) {
    OrderProcessor processor = new OrderProcessor();

    List<Order> orders = new ArrayList<>();
    orders.add(new Order(5, 10.0)); // no discount
    orders.add(new Order(12, 8.0)); // discount applies
    orders.add(new Order(1, 100.0)); // no discount

    double total = processor.processOrders(orders);

    System.out.println("Total: " + total);
  }
}
