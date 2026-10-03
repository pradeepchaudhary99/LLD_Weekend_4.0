"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PaymentService = exports.FakeGateway = exports.Request = void 0;

class Request {
    order;
    minorUnits;
    method;
    gateway;

    constructor(order, minorUnits, method, gateway) {
        this.order = order;
        this.minorUnits = minorUnits;
        this.method = method;
        this.gateway = gateway;
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
exports.Request = Request;

class FakeGateway {
    loseFirstResponse;
    decline;
    charged = new Set();
    refunded = new Set();

    constructor(loseFirstResponse = false, decline = false) {
        this.loseFirstResponse = loseFirstResponse;
        this.decline = decline;
        // Configurable outcomes keep the lesson deterministic without a network.
    }

    charge(paymentId, request) {
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

    refund(paymentId) {
        if (!this.charged.has(paymentId)) {
            return false;
        }
        this.refunded.add(paymentId);
        return true;
    }
    get charges() {
        return this.charged.size;
    }
}
exports.FakeGateway = FakeGateway;

class PaymentService {
    gateways;
    payments = new Map();
    events = new Set();

    constructor(gateways) {
        this.gateways = new Map(gateways);
    }
    // Synchronous commands are atomic within a single JS event loop.

    pay(key, request) {
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
        let payment = Object.freeze({ id: key, request, status: "PROCESSING" });
        this.payments.set(key, payment);
        for (let attempt = 0; attempt < 3; attempt++) {
            const outcome = this.gateways.get(request.gateway).charge(key, request);
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

    requirePayment(key) {
        const payment = this.payments.get(key);
        if (!payment) {
            throw new Error("Unknown payment");
        }
        return payment;
    }

    refund(key) {
        const payment = this.requirePayment(key);
        if (payment.status === "REFUNDED") {
            return payment;
        }
        if (payment.status !== "SUCCESS") {
            throw new Error("Only successful payments can be refunded");
        }
        if (!this.gateways.get(payment.request.gateway).refund(key)) {
            throw new Error("Refund pending; retry with the same payment ID");
        }
        const updated = Object.freeze({ ...payment, status: "REFUNDED" });
        this.payments.set(key, updated);
        return updated;
    }
    // Invoke only AFTER authenticating the provider; networking is out of scope.

    webhook(event, gateway, key, status) {
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
exports.PaymentService = PaymentService;
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
