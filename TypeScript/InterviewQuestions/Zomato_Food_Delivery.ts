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
 * - Synchronous methods have no await points on one JavaScript event loop.
 *
 * Run / validation:
 * - From repository root: python3 scripts/run.py "TypeScript" InterviewQuestions/Zomato_Food_Delivery
 * - Prerequisites, lifecycle and edge checks: see FOOD_DELIVERY.md.
 */

export {};

type Request = Readonly<{
    customer: string;
    restaurant: string;
    address: string;
    item: string;
    quantity: number;
}>;

type Order = {
    request: Request;
    amount: number;
    status: string;
    partner: string;
};

class PaymentGateway {
    acceptCharge = true;
    acceptRefund = true;
    charges = 0;
    refunds = 0;

    charge(_amount: number): boolean {
        this.charges++;
        return this.acceptCharge;
    }

    refund(_amount: number): boolean {
        this.refunds++;
        return this.acceptRefund;
    }
}

function requireCondition(condition: boolean, message: string): asserts condition {
    if (!condition) {
        throw new Error(message);
    }
}

class FoodDelivery {
    private readonly menus = new Map([
        ["r1", new Map([["pizza", 12000]])],
        ["r2", new Map([["rice", 8000]])],
    ]);
    private readonly orders = new Map<string, Order>();
    private readonly partners = new Map([["rider1", true]]);
    private readonly audit: string[] = [];

    constructor(private readonly gateway: PaymentGateway) {}

    menu(restaurant: string): Map<string, number> {
        const menu = this.menus.get(restaurant);
        requireCondition(menu !== undefined, "Unknown restaurant");
        return new Map(menu);
    }

    place(id: string, request: Request): string {
        const existing = this.orders.get(id);
        if (existing) {
            const old = existing.request;
            requireCondition(
                old.customer === request.customer &&
                    old.restaurant === request.restaurant &&
                    old.address === request.address &&
                    old.item === request.item &&
                    old.quantity === request.quantity,
                "Conflicting order ID",
            );
            return existing.status;
        }
        requireCondition(
            id.length > 0 && request.customer.length > 0 && request.address.length > 0,
            "Required identity/address",
        );
        const menu = this.menu(request.restaurant);
        const price = menu.get(request.item);
        requireCondition(
            price !== undefined &&
                Number.isInteger(request.quantity) &&
                request.quantity >= 1 &&
                request.quantity <= 100,
            "Invalid cart",
        );
        const amount = price * request.quantity;
        const status = this.gateway.charge(amount) ? "PLACED" : "PAYMENT_FAILED";
        this.orders.set(id, { request: { ...request }, amount, status, partner: "" });
        this.audit.push(`${id}:${status}`);
        return status;
    }

    private order(id: string): Order {
        const order = this.orders.get(id);
        requireCondition(order !== undefined, "Unknown order");
        return order;
    }

    private change(id: string, order: Order, status: string): void {
        order.status = status;
        this.audit.push(`${id}:${status}`);
    }

    advance(id: string, target: string): void {
        const order = this.order(id);
        const next = new Map([
            ["PLACED", "ACCEPTED"],
            ["ACCEPTED", "PREPARING"],
            ["PREPARING", "READY"],
            ["ASSIGNED", "PICKED_UP"],
            ["PICKED_UP", "DELIVERED"],
        ]);
        requireCondition(next.get(order.status) === target, "Invalid transition");
        this.change(id, order, target);
        if (target === "DELIVERED") {
            this.partners.set(order.partner, true);
        }
    }

    assign(id: string): boolean {
        const order = this.order(id);
        requireCondition(order.status === "READY", "Order not ready");
        for (const [partner, available] of this.partners) {
            if (available) {
                this.partners.set(partner, false);
                order.partner = partner;
                this.change(id, order, "ASSIGNED");
                return true;
            }
        }
        return false;
    }

    cancel(id: string, reject = false): boolean {
        const order = this.order(id);
        const target = reject ? "REJECTED" : "CANCELLED";
        if (order.status === target) {
            return true;
        }
        const allowed = reject
            ? ["PLACED"]
            : ["PLACED", "ACCEPTED", "PREPARING", "READY", "ASSIGNED"];
        requireCondition(allowed.includes(order.status), "Cannot cancel");
        if (!this.gateway.refund(order.amount)) {
            return false;
        }
        if (order.partner) {
            this.partners.set(order.partner, true);
        }
        this.change(id, order, target);
        return true;
    }

    status(id: string): string {
        return this.order(id).status;
    }

    events(): string[] {
        return [...this.audit];
    }
}

function check(condition: boolean): void {
    if (!condition) {
        throw new Error("Contract failed");
    }
}

function rejected(action: () => unknown): void {
    try {
        action();
    } catch {
        return;
    }
    throw new Error("Expected rejection");
}

const gateway = new PaymentGateway();
const service = new FoodDelivery(gateway);
const request: Request = {
    customer: "alice",
    restaurant: "r1",
    address: "Home",
    item: "pizza",
    quantity: 2,
};
check(service.menu("r1").get("pizza") === 12000);
check(service.place("o1", request) === "PLACED");
check(service.place("o1", request) === "PLACED" && gateway.charges === 1);
rejected(() => service.place("o1", { ...request, customer: "bob" }));
rejected(() => service.place("bad", { ...request, item: "rice" }));
rejected(() => service.advance("o1", "DELIVERED"));
for (const id of ["o1", "o2"]) {
    service.place(id, request);
    for (const state of ["ACCEPTED", "PREPARING", "READY"]) {
        service.advance(id, state);
    }
}
check(service.assign("o1"));
check(!service.assign("o2"));
gateway.acceptRefund = false;
check(!service.cancel("o1") && service.status("o1") === "ASSIGNED");
gateway.acceptRefund = true;
check(service.cancel("o1"));
const refunds = gateway.refunds;
check(service.cancel("o1") && gateway.refunds === refunds);
check(service.assign("o2"));
service.advance("o2", "PICKED_UP");
rejected(() => service.cancel("o2"));
service.advance("o2", "DELIVERED");
rejected(() => service.advance("o2", "PREPARING"));
service.place("o3", request);
check(service.cancel("o3", true));
gateway.acceptCharge = false;
check(service.place("o4", request) === "PAYMENT_FAILED");
const charges = gateway.charges;
check(service.place("o4", request) === "PAYMENT_FAILED" && gateway.charges === charges);
rejected(() => service.place("bad", { ...request, quantity: 0 }));
rejected(() => service.status("missing"));
service.events().length = 0;
check(service.events().length > 0);
console.log("Delivered: " + service.status("o2"));
console.log("Cancelled: " + service.status("o1"));
console.log("Rejected: " + service.status("o3"));
console.log("Failed payment: " + service.status("o4"));
console.log("Edge cases: passed");
