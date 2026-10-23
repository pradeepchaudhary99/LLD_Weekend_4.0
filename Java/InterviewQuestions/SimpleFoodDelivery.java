/*
 * SIMPLE JAVA - Food delivery
 *
 * Functional requirements (MVP): Place/pay/prepare an order; reserve a rider; deliver; cancel
 * unpaid orders. Non-functional requirements: readable, in-memory, safe access within one JVM. Core
 * entities: Order is an immutable record. FoodDelivery owns a fixed menu, orders and one rider
 * flag. Operations and flow: PLACED -> PAID -> PREPARING -> READY -> OUT_FOR_DELIVERY -> DELIVERED.
 * Design choices and concurrency: synchronized reserves the rider and updates the order under one
 * lock. Assumptions / omitted features: One restaurant/customer context, one item per order, one
 * rider; payment always simulated successful. No addresses, real providers, paid
 * cancellation/refund or delivery tracking. Edge cases to discuss/test: Invalid item/quantity,
 * duplicate order IDs, illegal transitions, two orders competing for rider. Run from repository
 * root (Java 17+ and Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleFoodDelivery
 * Read SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.HashMap;
import java.util.Map;

public class SimpleFoodDelivery {
    enum Status { PLACED, PAID, PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }

    record Order(String item, int quantity, long cents, Status status) {}

    static class FoodDelivery {
        private final Map<String, Long> menu = Map.of("pizza", 12000L, "rice", 8000L);
        private final Map<String, Order> orders = new HashMap<>();
        private boolean riderAvailable = true;

        synchronized Order place(String id, String item, int quantity) {
            if (id == null || id.isBlank() || item == null || !menu.containsKey(item) ||
                quantity < 1 || quantity > 100) {
                throw new IllegalArgumentException("Invalid order");
            }
            Order existing = orders.get(id);
            if (existing != null) {
                if (!existing.item().equals(item) || existing.quantity() != quantity) {
                    throw new IllegalArgumentException("Order ID already used");
                }
                return existing;
            }
            Order order = new Order(item, quantity, menu.get(item) * quantity, Status.PLACED);
            orders.put(id, order);
            return order;
        }

        private void change(String id, Status expected, Status next) {
            Order order = orders.get(id);
            if (order == null || order.status() != expected) {
                throw new IllegalStateException("Invalid order transition");
            }
            orders.put(id, new Order(order.item(), order.quantity(), order.cents(), next));
        }

        synchronized void pay(String id) {
            // Simulate a successful payment. No real payment provider is called.
            change(id, Status.PLACED, Status.PAID);
        }

        synchronized void prepare(String id) {
            change(id, Status.PAID, Status.PREPARING);
        }

        synchronized void ready(String id) {
            change(id, Status.PREPARING, Status.READY);
        }

        synchronized boolean dispatch(String id) {
            if (!orders.containsKey(id) || orders.get(id).status() != Status.READY) {
                throw new IllegalStateException("Order is not ready");
            }
            if (!riderAvailable) {
                return false;
            }
            // Reserve the rider and change the order under the SAME lock.
            change(id, Status.READY, Status.OUT_FOR_DELIVERY);
            riderAvailable = false;
            return true;
        }

        synchronized void deliver(String id) {
            change(id, Status.OUT_FOR_DELIVERY, Status.DELIVERED);
            riderAvailable = true;
        }

        synchronized void cancel(String id) {
            change(id, Status.PLACED, Status.CANCELLED); // Only unpaid orders in this MVP.
        }

        synchronized Status status(String id) {
            if (!orders.containsKey(id)) {
                throw new IllegalArgumentException("Unknown order");
            }
            return orders.get(id).status();
        }
    }

    public static void main(String[] args) {
        FoodDelivery service = new FoodDelivery();
        System.out.println("Price: " + service.place("o1", "pizza", 2).cents());
        service.pay("o1");
        service.prepare("o1");
        service.ready("o1");
        System.out.println("Rider assigned: " + service.dispatch("o1"));
        service.deliver("o1");
        System.out.println("Order: " + service.status("o1"));
    }
}
