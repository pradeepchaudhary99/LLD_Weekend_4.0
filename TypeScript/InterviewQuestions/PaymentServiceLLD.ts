/*
 * INTERVIEW DESIGN NOTES - Payment Service
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Create payments for CARD, UPI, NET_BANKING and WALLET methods.
 * - Choose a registered gateway and track PROCESSING, SUCCESS, FAILED or REFUNDED.
 * - Deduplicate identical keys and reject changed payloads under the same key.
 * - Retry ambiguous gateway outcomes up to three total attempts.
 * - Support full refunds and trusted, deduplicated reconciliation webhooks.
 *
 * Non-functional requirements:
 * - Serialize state transitions within a service instance.
 * - Use integer minor units and immutable/copied payment snapshots.
 * - Retain stable gateway payment identity across retries.
 * - Keep provider integration replaceable and failure scenarios testable locally.
 *
 * Core entities and responsibilities:
 * - Request: order, amount, method and gateway identity.
 * - Payment: ID, request and status snapshot.
 * - PaymentService: owns payment records and idempotency/event indexes.
 * - Gateway: charge/refund contract; FakeGateway simulates provider outcomes.
 *
 * Key operations and flow:
 * - pay -> validate -> check key -> store PROCESSING -> retry the same gateway ID.
 * - Definitive success/decline becomes SUCCESS/FAILED; exhaustion stays PROCESSING.
 * - refund requires SUCCESS; trusted webhook reconciles a PROCESSING payment.
 *
 * Design choices and invariants:
 * - Gateway interface isolates provider behavior from orchestration.
 * - An uncertain response is not proof of failure; do not invent a new payment ID.
 * - Full refund retries reuse the ID; late events cannot revert terminal states.
 * - Webhook event identity is scoped by provider; validate payment/provider match.
 *
 * Assumptions and out of scope:
 * - One assumed currency (INR), fake providers and in-memory records only.
 * - Webhook authentication happens before this trusted method; no HTTP endpoint.
 * - No partial refunds, persistent transactions, multi-process coordination,
 * - real payment credentials, retry backoff or conflicting-final-event recovery.
 *
 * Interview discussion and checks:
 * - Test concurrent duplicate keys, payload conflicts, lost responses and exhaustion.
 * - Test decline, failed/refunded retries, stale events and snapshot independence.
 * - Discuss durable unique constraints, provider idempotency and reconciliation.
 *
 * Concurrency in this edition:
 * - Synchronous operations on one event loop; no cross-worker safety.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py TypeScript InterviewQuestions/PaymentServiceLLD
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

// In-memory orchestration with fake gateways, never real money.
export type Method = "CARD" | "UPI" | "NET_BANKING" | "WALLET";
export type Status = "PROCESSING" | "SUCCESS" | "FAILED" | "REFUNDED";
export type Outcome = "SUCCESS" | "DECLINED" | "UNKNOWN";

export class Request {
    constructor(
        readonly order: string,
        readonly minorUnits: number,
        readonly method: Method,
        readonly gateway: string,
    ) {
        if (
            !order.trim() ||
            !Number.isSafeInteger(minorUnits) ||
            minorUnits <= 0 ||
            !["CARD", "UPI", "NET_BANKING", "WALLET"].includes(method) ||
            !gateway.trim()
        ) {
            throw new Error("Invalid payment request");
        }
        Object.freeze(this);
    }
}

export interface Payment {
    readonly id: string;
    readonly request: Request;
    readonly status: Status;
}

export interface Gateway {
    charge(paymentId: string, request: Request): Outcome;
    refund(paymentId: string): boolean;
}

export class FakeGateway implements Gateway {
    private readonly charged = new Set<string>();
    private readonly refunded = new Set<string>();

    constructor(
        private loseFirstResponse = false,
        private readonly decline = false,
    ) {
        // Configurable outcomes keep the lesson deterministic without a network.
    }

    charge(paymentId: string, request: Request): Outcome {
        if (this.decline) {
            return "DECLINED";
        }
        this.charged.add(paymentId);
        if (this.loseFirstResponse) {
            this.loseFirstResponse = false;
            return "UNKNOWN";
        }
        return "SUCCESS";
    }

    refund(paymentId: string): boolean {
        if (!this.charged.has(paymentId)) {
            return false;
        }
        this.refunded.add(paymentId);
        return true;
    }

    get charges(): number {
        return this.charged.size;
    }
}

export class PaymentService {
    private readonly gateways: Map<string, Gateway>;
    private readonly payments = new Map<string, Payment>();
    private readonly events = new Set<string>();

    constructor(gateways: Map<string, Gateway>) {
        this.gateways = new Map(gateways);
    }

    // Synchronous commands are atomic within a single JS event loop.
    pay(key: string, request: Request): Payment {
        if (!key.trim() || !this.gateways.has(request.gateway)) {
            throw new Error("Invalid key or gateway");
        }
        const existing = this.payments.get(key);
        if (existing) {
            const old = existing.request;
            if (
                old.order !== request.order ||
                old.minorUnits !== request.minorUnits ||
                old.method !== request.method ||
                old.gateway !== request.gateway
            ) {
                throw new Error("Idempotency conflict");
            }
            return existing;
        }
        let payment: Payment = Object.freeze({ id: key, request, status: "PROCESSING" });
        this.payments.set(key, payment);
        for (let attempt = 0; attempt < 3; attempt++) {
            const outcome = this.gateways.get(request.gateway)!.charge(key, request);
            if (outcome !== "UNKNOWN") {
                payment = Object.freeze({
                    ...payment,
                    status: outcome === "SUCCESS" ? "SUCCESS" : "FAILED",
                });
                this.payments.set(key, payment);
                break;
            }
        }
        return payment;
    }

    private requirePayment(key: string): Payment {
        const payment = this.payments.get(key);
        if (!payment) {
            throw new Error("Unknown payment");
        }
        return payment;
    }

    refund(key: string): Payment {
        const payment = this.requirePayment(key);
        if (payment.status === "REFUNDED") {
            return payment;
        }
        if (payment.status !== "SUCCESS") {
            throw new Error("Only successful payments can be refunded");
        }
        if (!this.gateways.get(payment.request.gateway)!.refund(key)) {
            throw new Error("Refund pending; retry with the same payment ID");
        }
        const updated: Payment = Object.freeze({ ...payment, status: "REFUNDED" });
        this.payments.set(key, updated);
        return updated;
    }

    // Invoke only AFTER authenticating the provider; networking is out of scope.
    webhook(event: string, gateway: string, key: string, status: Status): Payment {
        let payment = this.requirePayment(key);
        if (
            !event.trim() ||
            payment.request.gateway !== gateway ||
            (status !== "SUCCESS" && status !== "FAILED")
        ) {
            throw new Error("Invalid webhook");
        }
        const eventKey = JSON.stringify([gateway, event]);
        if (this.events.has(eventKey)) {
            return payment;
        }
        this.events.add(eventKey);
        if (payment.status === "PROCESSING") {
            payment = Object.freeze({ ...payment, status });
            this.payments.set(key, payment);
        }
        return payment;
    }
}

if (require.main === module) {
    const gateway = new FakeGateway(true);
    const service = new PaymentService(new Map([["stripe", gateway]]));
    const request = new Request("order-1", 1699900, "CARD", "stripe");
    console.log("Payment: " + service.pay("key-1", request).status);
    service.pay("key-1", request);
    console.log("Gateway charges: " + gateway.charges);
    console.log("Refund: " + service.refund("key-1").status);
    console.log("Late webhook: " + service.webhook("event-1", "stripe", "key-1", "SUCCESS").status);
}
