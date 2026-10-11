/*
 * INTERVIEW DESIGN NOTES - Food Delivery
 *
 * Implementation status: runnable, deliberately small in-memory teaching example.
 * The original classroom sketches are preserved in classroom-drafts/2026-10-11/.
 *
 * Functional requirements:
 * - Browse two fixed restaurant menus; submit a one-line cart (item and quantity).
 * - Charge an order, accept/reject it, prepare it and mark it ready for collection.
 * - Reserve one available rider, record pickup and delivery, then release the rider.
 * - Cancel before pickup; refund before changing state or releasing the rider.
 * - Record transition events for notification consumers and status tracking.
 *
 * Non-functional requirements:
 * - Reuse an order ID only for identical input; never charge it twice in this process.
 * - Use integer minor currency units; validate quantity 1..100 before charging.
 * - Serialize service operations; never assign one rider to two active deliveries.
 * - Return menu/event snapshots; retain copied order inputs and the charged amount.
 *
 * Core entities and responsibilities:
 * - Request: customer ID, address, restaurant and a single cart item/quantity.
 * - Order: immutable request/amount snapshot plus status and assigned rider.
 * - Restaurant/MenuItem: represented by fixed ID-to-price maps; no editable catalog.
 * - DeliveryPartner: ID-to-availability map; delivery state lives in Order.
 * - PaymentGateway: deterministic charge/refund fake; counters expose duplicate calls.
 * - FoodDelivery: owns order transitions, rider reservation and notification events.
 *
 * Key operations and flow:
 * - menu -> place -> ACCEPTED -> PREPARING -> READY -> assign -> PICKED_UP -> DELIVERED.
 * - place returns PLACED or PAYMENT_FAILED; repeated input returns current status.
 * - cancel(reject=true) rejects only PLACED orders; normal cancellation is pre-pickup.
 * - If no rider exists, assign returns false and the READY order can be retried.
 *
 * Design choices and invariants:
 * - Explicit transition table instead of a class for every state keeps the MVP small.
 * - One service owns coupled order/rider invariants; private helpers assume its lock.
 * - Failed refund preserves state/reservation; successful repeated cancel is a no-op.
 * - Failed charges are remembered; a new order ID is needed for a new attempt.
 * - Every committed transition appends an event; callers cannot mutate stored orders.
 *
 * Assumptions and out of scope:
 * - One currency; menu prices are minor units. No taxes, coupons or partial refunds.
 * - One item type per cart, fixed available menus and one rider keep the demo focused.
 * - Customer IDs/addresses are caller-provided; authentication/ownership is upstream.
 * - The fake gateway returns immediately without exceptions; configure it only when idle.
 * - No network payment ambiguity, durable idempotency, outbox, live GPS or real SMS.
 * - Real providers must not run under this lock: add durable intents, idempotency keys,
 *   reconciliation and an outbox before introducing remote calls or multiple processes.
 *
 * Interview discussion and checks:
 * - Demo checks duplicates/conflicts, wrong-restaurant items, invalid transitions,
 *   rider contention, refund retry, duplicate cancellation and cancellation after pickup.
 * - Discuss multiple cart lines, restaurant closure, rider rejection, payment timeouts,
 *   delivery timeouts, bounded event retention and restart-safe recovery.
 *
 * Concurrency in this edition:
 * - Synchronized service methods serialize all operations in one JVM.
 *
 * Run / validation:
 * - From repository root: python3 scripts/run.py "Java" InterviewQuestions/Zomato_Food_Delivery
 * - Prerequisites, lifecycle and edge checks: see FOOD_DELIVERY.md.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Zomato_Food_Delivery {
    record Request(String customer, String restaurant, String address, String item, int quantity) {}

    static class Order {
        final Request request;
        final int amount;
        String status;
        String partner = "";

        Order(Request request, int amount, String status) {
            this.request = request;
            this.amount = amount;
            this.status = status;
        }
    }

    static class PaymentGateway {
        boolean acceptCharge = true;
        boolean acceptRefund = true;
        int charges;
        int refunds;

        boolean charge(int amount) {
            charges++;
            return acceptCharge;
        }

        boolean refund(int amount) {
            refunds++;
            return acceptRefund;
        }
    }

    static class FoodDelivery {
        private final PaymentGateway gateway;
        private final Map<String, Map<String, Integer>> menus =
            Map.of("r1", Map.of("pizza", 12000), "r2", Map.of("rice", 8000));
        private final Map<String, Order> orders = new HashMap<>();
        private final Map<String, Boolean> partners = new HashMap<>(Map.of("rider1", true));
        private final List<String> events = new ArrayList<>();

        FoodDelivery(PaymentGateway gateway) {
            this.gateway = gateway;
        }

        synchronized Map<String, Integer> menu(String restaurant) {
            require(menus.containsKey(restaurant), "Unknown restaurant");
            return Map.copyOf(menus.get(restaurant));
        }

        synchronized String place(String id, Request request) {
            require(id != null && request != null, "Required request");
            if (orders.containsKey(id)) {
                require(orders.get(id).request.equals(request), "Conflicting order ID");
                return orders.get(id).status;
            }
            require(!id.isEmpty() && request.customer() != null && !request.customer().isEmpty() &&
                        request.address() != null && !request.address().isEmpty(),
                    "Required identity/address");
            require(request.restaurant() != null && request.item() != null, "Required cart");
            Map<String, Integer> menu = menu(request.restaurant());
            require(menu.containsKey(request.item()) && request.quantity() >= 1 &&
                        request.quantity() <= 100,
                    "Invalid cart");
            int amount = menu.get(request.item()) * request.quantity();
            String status = gateway.charge(amount) ? "PLACED" : "PAYMENT_FAILED";
            orders.put(id, new Order(request, amount, status));
            events.add(id + ":" + status);
            return status;
        }

        private Order order(String id) {
            require(orders.containsKey(id), "Unknown order");
            return orders.get(id);
        }

        private void change(String id, Order order, String status) {
            order.status = status;
            events.add(id + ":" + status);
        }

        synchronized void advance(String id, String target) {
            Order order = order(id);
            Map<String, String> next =
                Map.of("PLACED", "ACCEPTED", "ACCEPTED", "PREPARING", "PREPARING", "READY",
                       "ASSIGNED", "PICKED_UP", "PICKED_UP", "DELIVERED");
            require(target != null && target.equals(next.get(order.status)), "Invalid transition");
            change(id, order, target);
            if (target.equals("DELIVERED")) {
                partners.put(order.partner, true);
            }
        }

        synchronized boolean assign(String id) {
            Order order = order(id);
            require(order.status.equals("READY"), "Order not ready");
            for (var entry : partners.entrySet()) {
                if (entry.getValue()) {
                    entry.setValue(false);
                    order.partner = entry.getKey();
                    change(id, order, "ASSIGNED");
                    return true;
                }
            }
            return false;
        }

        synchronized boolean cancel(String id, boolean reject) {
            Order order = order(id);
            String target = reject ? "REJECTED" : "CANCELLED";
            if (order.status.equals(target)) {
                return true;
            }
            List<String> allowed =
                reject ? List.of("PLACED")
                       : List.of("PLACED", "ACCEPTED", "PREPARING", "READY", "ASSIGNED");
            require(allowed.contains(order.status), "Cannot cancel");
            if (!gateway.refund(order.amount)) {
                return false;
            }
            if (!order.partner.isEmpty()) {
                partners.put(order.partner, true);
            }
            change(id, order, target);
            return true;
        }

        synchronized String status(String id) {
            return order(id).status;
        }

        synchronized List<String> events() {
            return new ArrayList<>(events);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Contract failed");
        }
    }

    static void rejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    public static void main(String[] args) {
        PaymentGateway gateway = new PaymentGateway();
        FoodDelivery service = new FoodDelivery(gateway);
        Request request = new Request("alice", "r1", "Home", "pizza", 2);
        check(service.menu("r1").get("pizza") == 12000);
        check(service.place("o1", request).equals("PLACED"));
        check(service.place("o1", request).equals("PLACED") && gateway.charges == 1);
        rejected(() -> service.place("o1", new Request("bob", "r1", "Home", "pizza", 2)));
        rejected(() -> service.place("bad", new Request("alice", "r1", "Home", "rice", 1)));
        rejected(() -> service.advance("o1", "DELIVERED"));
        for (String id : List.of("o1", "o2")) {
            service.place(id, request);
            for (String state : List.of("ACCEPTED", "PREPARING", "READY")) {
                service.advance(id, state);
            }
        }
        check(service.assign("o1"));
        check(!service.assign("o2"));
        gateway.acceptRefund = false;
        check(!service.cancel("o1", false) && service.status("o1").equals("ASSIGNED"));
        gateway.acceptRefund = true;
        check(service.cancel("o1", false));
        int refunds = gateway.refunds;
        check(service.cancel("o1", false) && gateway.refunds == refunds);
        check(service.assign("o2"));
        service.advance("o2", "PICKED_UP");
        rejected(() -> service.cancel("o2", false));
        service.advance("o2", "DELIVERED");
        rejected(() -> service.advance("o2", "PREPARING"));
        service.place("o3", request);
        check(service.cancel("o3", true));
        gateway.acceptCharge = false;
        check(service.place("o4", request).equals("PAYMENT_FAILED"));
        int charges = gateway.charges;
        check(service.place("o4", request).equals("PAYMENT_FAILED") && gateway.charges == charges);
        rejected(() -> service.place("bad", new Request("alice", "r1", "Home", "pizza", 0)));
        rejected(() -> service.status("missing"));
        service.events().clear();
        check(!service.events().isEmpty());
        System.out.println("Delivered: " + service.status("o2"));
        System.out.println("Cancelled: " + service.status("o1"));
        System.out.println("Rejected: " + service.status("o3"));
        System.out.println("Failed payment: " + service.status("o4"));
        System.out.println("Edge cases: passed");
    }
}
