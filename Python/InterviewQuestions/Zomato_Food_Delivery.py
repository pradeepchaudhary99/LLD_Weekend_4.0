# INTERVIEW DESIGN NOTES - Food Delivery
#
# Implementation status: runnable, deliberately small in-memory teaching example.
# The original classroom sketches are preserved in classroom-drafts/2026-10-11/.
#
# Functional requirements:
# - Browse two fixed restaurant menus; submit a one-line cart (item and quantity).
# - Charge an order, accept/reject it, prepare it and mark it ready for collection.
# - Reserve one available rider, record pickup and delivery, then release the rider.
# - Cancel before pickup; refund before changing state or releasing the rider.
# - Record transition events for notification consumers and status tracking.
#
# Non-functional requirements:
# - Reuse an order ID only for identical input; never charge it twice in this process.
# - Use integer minor currency units; validate quantity 1..100 before charging.
# - Serialize service operations; never assign one rider to two active deliveries.
# - Return menu/event snapshots; retain copied order inputs and the charged amount.
#
# Core entities and responsibilities:
# - Request: customer ID, address, restaurant and a single cart item/quantity.
# - Order: immutable request/amount snapshot plus status and assigned rider.
# - Restaurant/MenuItem: represented by fixed ID-to-price maps; no editable catalog.
# - DeliveryPartner: ID-to-availability map; delivery state lives in Order.
# - PaymentGateway: deterministic charge/refund fake; counters expose duplicate calls.
# - FoodDelivery: owns order transitions, rider reservation and notification events.
#
# Key operations and flow:
# - menu -> place -> ACCEPTED -> PREPARING -> READY -> assign -> PICKED_UP -> DELIVERED.
# - place returns PLACED or PAYMENT_FAILED; repeated input returns current status.
# - cancel(reject=true) rejects only PLACED orders; normal cancellation is pre-pickup.
# - If no rider exists, assign returns false and the READY order can be retried.
#
# Design choices and invariants:
# - Explicit transition table instead of a class for every state keeps the MVP small.
# - One service owns coupled order/rider invariants; private helpers assume its lock.
# - Failed refund preserves state/reservation; successful repeated cancel is a no-op.
# - Failed charges are remembered; a new order ID is needed for a new attempt.
# - Every committed transition appends an event; callers cannot mutate stored orders.
#
# Assumptions and out of scope:
# - One currency; menu prices are minor units. No taxes, coupons or partial refunds.
# - One item type per cart, fixed available menus and one rider keep the demo focused.
# - Customer IDs/addresses are caller-provided; authentication/ownership is upstream.
# - The fake gateway returns immediately without exceptions; configure it only when idle.
# - No network payment ambiguity, durable idempotency, outbox, live GPS or real SMS.
# - Real providers must not run under this lock: add durable intents, idempotency keys,
#   reconciliation and an outbox before introducing remote calls or multiple processes.
#
# Interview discussion and checks:
# - Demo checks duplicates/conflicts, wrong-restaurant items, invalid transitions,
#   rider contention, refund retry, duplicate cancellation and cancellation after pickup.
# - Discuss multiple cart lines, restaurant closure, rider rejection, payment timeouts,
#   delivery timeouts, bounded event retention and restart-safe recovery.
#
# Concurrency in this edition:
# - RLock protects all service operations in one Python process.
#
# Run / validation:
# - From repository root: python3 scripts/run.py "Python" InterviewQuestions/Zomato_Food_Delivery
# - Prerequisites, lifecycle and edge checks: see FOOD_DELIVERY.md.

from dataclasses import dataclass
from threading import RLock


@dataclass(frozen=True)
class Request:
    customer: str
    restaurant: str
    address: str
    item: str
    quantity: int


@dataclass
class Order:
    request: Request
    amount: int
    status: str
    partner: str = ""


class PaymentGateway:
    """Deterministic fake. The service owns it; no network calls are made."""

    def __init__(self):
        self.accept_charge = True
        self.accept_refund = True
        self.charges = 0
        self.refunds = 0

    def charge(self, amount):
        self.charges += 1
        return self.accept_charge

    def refund(self, amount):
        self.refunds += 1
        return self.accept_refund


class FoodDelivery:
    def __init__(self, gateway):
        self._gateway = gateway
        self._lock = RLock()
        self._menu = {"r1": {"pizza": 12000}, "r2": {"rice": 8000}}
        self._orders = {}
        self._partners = {"rider1": True}
        self._events = []

    def menu(self, restaurant):
        with self._lock:
            if restaurant not in self._menu:
                raise ValueError("Unknown restaurant")
            return dict(self._menu[restaurant])

    def place(self, order_id, request):
        with self._lock:
            if order_id in self._orders:
                if self._orders[order_id].request != request:
                    raise ValueError("Conflicting order ID")
                return self._orders[order_id].status
            if not order_id or not request.customer or not request.address:
                raise ValueError("Required identity/address")
            menu = self.menu(request.restaurant)
            if (
                request.item not in menu
                or type(request.quantity) is not int
                or not 1 <= request.quantity <= 100
            ):
                raise ValueError("Invalid cart")
            amount = menu[request.item] * request.quantity
            status = "PLACED" if self._gateway.charge(amount) else "PAYMENT_FAILED"
            self._orders[order_id] = Order(request, amount, status)
            self._events.append(f"{order_id}:{status}")
            return status

    def _order(self, order_id):
        if order_id not in self._orders:
            raise ValueError("Unknown order")
        return self._orders[order_id]

    def _change(self, order_id, order, status):
        order.status = status
        self._events.append(f"{order_id}:{status}")

    def advance(self, order_id, target):
        with self._lock:
            order = self._order(order_id)
            next_state = {
                "PLACED": "ACCEPTED",
                "ACCEPTED": "PREPARING",
                "PREPARING": "READY",
                "ASSIGNED": "PICKED_UP",
                "PICKED_UP": "DELIVERED",
            }
            if next_state.get(order.status) != target:
                raise ValueError("Invalid transition")
            self._change(order_id, order, target)
            if target == "DELIVERED":
                self._partners[order.partner] = True

    def assign(self, order_id):
        with self._lock:
            order = self._order(order_id)
            if order.status != "READY":
                raise ValueError("Order not ready")
            for partner, available in self._partners.items():
                if available:
                    self._partners[partner] = False
                    order.partner = partner
                    self._change(order_id, order, "ASSIGNED")
                    return True
            return False

    def cancel(self, order_id, reject=False):
        with self._lock:
            order = self._order(order_id)
            target = "REJECTED" if reject else "CANCELLED"
            if order.status == target:
                return True
            allowed = (
                {"PLACED"} if reject else {"PLACED", "ACCEPTED", "PREPARING", "READY", "ASSIGNED"}
            )
            if order.status not in allowed:
                raise ValueError("Cannot cancel")
            if not self._gateway.refund(order.amount):
                return False
            if order.partner:
                self._partners[order.partner] = True
            self._change(order_id, order, target)
            return True

    def status(self, order_id):
        with self._lock:
            return self._order(order_id).status

    def events(self):
        with self._lock:
            return list(self._events)


def check(condition):
    if not condition:
        raise AssertionError("Contract failed")


def rejected(action):
    try:
        action()
    except ValueError:
        return
    raise AssertionError("Expected rejection")


def main():
    gateway = PaymentGateway()
    service = FoodDelivery(gateway)
    request = Request("alice", "r1", "Home", "pizza", 2)
    check(service.menu("r1")["pizza"] == 12000)
    check(service.place("o1", request) == "PLACED")
    check(service.place("o1", request) == "PLACED" and gateway.charges == 1)
    rejected(lambda: service.place("o1", Request("bob", "r1", "Home", "pizza", 2)))
    rejected(lambda: service.place("bad", Request("alice", "r1", "Home", "rice", 1)))
    rejected(lambda: service.advance("o1", "DELIVERED"))
    for order_id in ["o1", "o2"]:
        service.place(order_id, request)
        for state in ["ACCEPTED", "PREPARING", "READY"]:
            service.advance(order_id, state)
    check(service.assign("o1"))
    check(not service.assign("o2"))
    gateway.accept_refund = False
    check(not service.cancel("o1") and service.status("o1") == "ASSIGNED")
    gateway.accept_refund = True
    check(service.cancel("o1"))
    refunds = gateway.refunds
    check(service.cancel("o1") and gateway.refunds == refunds)
    check(service.assign("o2"))
    service.advance("o2", "PICKED_UP")
    rejected(lambda: service.cancel("o2"))
    service.advance("o2", "DELIVERED")
    rejected(lambda: service.advance("o2", "PREPARING"))
    service.place("o3", request)
    check(service.cancel("o3", True))
    gateway.accept_charge = False
    check(service.place("o4", request) == "PAYMENT_FAILED")
    charges = gateway.charges
    check(service.place("o4", request) == "PAYMENT_FAILED" and gateway.charges == charges)
    rejected(lambda: service.place("bad", Request("alice", "r1", "Home", "pizza", 0)))
    rejected(lambda: service.status("missing"))
    snapshot = service.events()
    snapshot.clear()
    check(len(service.events()) > 0)
    print("Delivered: " + service.status("o2"))
    print("Cancelled: " + service.status("o1"))
    print("Rejected: " + service.status("o3"))
    print("Failed payment: " + service.status("o4"))
    print("Edge cases: passed")


if __name__ == "__main__":
    main()
