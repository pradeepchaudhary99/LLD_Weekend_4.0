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
