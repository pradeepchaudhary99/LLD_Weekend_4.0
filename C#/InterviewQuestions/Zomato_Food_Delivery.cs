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
 * - A private monitor protects all service operations; snapshots are copied.
 *
 * Run / validation:
 * - From repository root: python3 scripts/run.py "C#" InterviewQuestions/Zomato_Food_Delivery
 * - Prerequisites, lifecycle and edge checks: see FOOD_DELIVERY.md.
 */

using System;
using System.Collections.Generic;

namespace LLDWeekend4.InterviewQuestions;

public class Zomato_Food_Delivery
{
    record Request(string customer, string restaurant, string address, string item, int quantity);

    class Order
    {
        public readonly Request request;
        public readonly int amount;
        public string status;
        public string partner = "";

        public Order(Request request, int amount, string status)
        {
            this.request = request;
            this.amount = amount;
            this.status = status;
        }
    }

    class PaymentGateway
    {
        public bool acceptCharge = true;
        public bool acceptRefund = true;
        public int charges;
        public int refunds;

        public bool Charge(int amount)
        {
            charges++;
            return acceptCharge;
        }

        public bool Refund(int amount)
        {
            refunds++;
            return acceptRefund;
        }
    }

    class FoodDelivery
    {
        private readonly object gate = new();
        private readonly PaymentGateway gateway;
        private readonly Dictionary<string, Dictionary<string, int>> menus =
            new() { ["r1"] = new() { ["pizza"] = 12000 }, ["r2"] = new() { ["rice"] = 8000 } };
        private readonly Dictionary<string, Order> orders = new();
        private readonly Dictionary<string, bool> partners = new() { ["rider1"] = true };
        private readonly List<string> audit = new();

        public FoodDelivery(PaymentGateway gateway)
        {
            this.gateway = gateway;
        }

        public Dictionary<string, int> Menu(string restaurant)
        {
            lock (gate)
            {
                Require(menus.ContainsKey(restaurant), "Unknown restaurant");
                return new Dictionary<string, int>(menus[restaurant]);
            }
        }

        public string Place(string id, Request request)
        {
            lock (gate)
            {
                Require(id != null && request != null, "Required request");
                if (orders.ContainsKey(id))
                {
                    Require(orders[id].request.Equals(request), "Conflicting order ID");
                    return orders[id].status;
                }
                Require(!(id.Length == 0) && request.customer != null &&
                            !(request.customer.Length == 0) && request.address != null &&
                            !(request.address.Length == 0),
                        "Required identity/address");
                Require(request.restaurant != null && request.item != null, "Required cart");
                Dictionary<string, int> menu = Menu(request.restaurant);
                Require(menu.ContainsKey(request.item) && request.quantity >= 1 &&
                            request.quantity <= 100,
                        "Invalid cart");
                int amount = menu[request.item] * request.quantity;
                string status = gateway.Charge(amount) ? "PLACED" : "PAYMENT_FAILED";
                orders[id] = new Order(request, amount, status);
                audit.Add(id + ":" + status);
                return status;
            }
        }

        private Order Order(string id)
        {
            Require(orders.ContainsKey(id), "Unknown order");
            return orders[id];
        }

        private void Change(string id, Order order, string status)
        {
            order.status = status;
            audit.Add(id + ":" + status);
        }

        public void Advance(string id, string target)
        {
            lock (gate)
            {
                Order order = Order(id);
                Dictionary<string, string> next =
                    new() { ["PLACED"] = "ACCEPTED", ["ACCEPTED"] = "PREPARING",
                            ["PREPARING"] = "READY", ["ASSIGNED"] = "PICKED_UP",
                            ["PICKED_UP"] = "DELIVERED" };
                Require(target != null && target.Equals(next.GetValueOrDefault(order.status)),
                        "Invalid transition");
                Change(id, order, target);
                if (target.Equals("DELIVERED"))
                {
                    partners[order.partner] = true;
                }
            }
        }

        public bool Assign(string id)
        {
            lock (gate)
            {
                Order order = Order(id);
                Require(order.status.Equals("READY"), "Order not ready");
                foreach (var entry in partners)
                {
                    if (entry.Value)
                    {
                        partners[entry.Key] = false;
                        order.partner = entry.Key;
                        Change(id, order, "ASSIGNED");
                        return true;
                    }
                }
                return false;
            }
        }

        public bool Cancel(string id, bool reject)
        {
            lock (gate)
            {
                Order order = Order(id);
                string target = reject ? "REJECTED" : "CANCELLED";
                if (order.status.Equals(target))
                {
                    return true;
                }
                List<string> allowed = reject
                                           ? new List<string> { "PLACED" }
                                           : new List<string> { "PLACED", "ACCEPTED", "PREPARING",
                                                                "READY", "ASSIGNED" };
                Require(allowed.Contains(order.status), "Cannot cancel");
                if (!gateway.Refund(order.amount))
                {
                    return false;
                }
                if (!(order.partner.Length == 0))
                {
                    partners[order.partner] = true;
                }
                Change(id, order, target);
                return true;
            }
        }

        public string Status(string id)
        {
            lock (gate)
            {
                return Order(id).status;
            }
        }

        public List<string> Events()
        {
            lock (gate)
            {
                return new List<string>(audit);
            }
        }
    }

    static void Require([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition,
                        string message)
    {
        if (!condition)
        {
            throw new ArgumentException(message);
        }
    }

    static void Check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Contract failed");
        }
    }

    static void Rejected(Action action)
    {
        try
        {
            action();
        }
        catch (ArgumentException)
        {
            return;
        }
        throw new Exception("Expected rejection");
    }

    public static void Main(string[] args)
    {
        PaymentGateway gateway = new PaymentGateway();
        FoodDelivery service = new FoodDelivery(gateway);
        Request request = new Request("alice", "r1", "Home", "pizza", 2);
        Check(service.Menu("r1")["pizza"] == 12000);
        Check(service.Place("o1", request).Equals("PLACED"));
        Check(service.Place("o1", request).Equals("PLACED") && gateway.charges == 1);
        Rejected(() => service.Place("o1", new Request("bob", "r1", "Home", "pizza", 2)));
        Rejected(() => service.Place("bad", new Request("alice", "r1", "Home", "rice", 1)));
        Rejected(() => service.Advance("o1", "DELIVERED"));
        foreach (string id in new List<string> { "o1", "o2" })
        {
            service.Place(id, request);
            foreach (string state in new List<string> { "ACCEPTED", "PREPARING", "READY" })
            {
                service.Advance(id, state);
            }
        }
        Check(service.Assign("o1"));
        Check(!service.Assign("o2"));
        gateway.acceptRefund = false;
        Check(!service.Cancel("o1", false) && service.Status("o1").Equals("ASSIGNED"));
        gateway.acceptRefund = true;
        Check(service.Cancel("o1", false));
        int refunds = gateway.refunds;
        Check(service.Cancel("o1", false) && gateway.refunds == refunds);
        Check(service.Assign("o2"));
        service.Advance("o2", "PICKED_UP");
        Rejected(() => service.Cancel("o2", false));
        service.Advance("o2", "DELIVERED");
        Rejected(() => service.Advance("o2", "PREPARING"));
        service.Place("o3", request);
        Check(service.Cancel("o3", true));
        gateway.acceptCharge = false;
        Check(service.Place("o4", request).Equals("PAYMENT_FAILED"));
        int charges = gateway.charges;
        Check(service.Place("o4", request).Equals("PAYMENT_FAILED") && gateway.charges == charges);
        Rejected(() => service.Place("bad", new Request("alice", "r1", "Home", "pizza", 0)));
        Rejected(() => service.Status("missing"));
        service.Events().Clear();
        Check(!(service.Events().Count == 0));
        Console.WriteLine("Delivered: " + service.Status("o2"));
        Console.WriteLine("Cancelled: " + service.Status("o1"));
        Console.WriteLine("Rejected: " + service.Status("o3"));
        Console.WriteLine("Failed payment: " + service.Status("o4"));
        Console.WriteLine("Edge cases: passed");
    }
}
