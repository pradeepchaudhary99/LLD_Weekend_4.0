const assert = require("node:assert/strict");
const path = require("node:path");
const { Request, FakeGateway, PaymentService } = require(
    process.argv[2] ||
        path.resolve(__dirname, "../JavaScript/InterviewQuestions/PaymentServiceLLD.js"),
);

class UnknownGateway {
    attempts = 0;
    allowRefund = false;

    charge() {
        this.attempts++;
        return "UNKNOWN";
    }

    refund() {
        return this.allowRefund;
    }
}

const request = new Request("order", 100, "CARD", "stripe");
const gateway = new FakeGateway(true);
const service = new PaymentService(new Map([["stripe", gateway]]));
for (let index = 0; index < 100; index++) {
    assert.equal(service.pay("same-key", request).status, "SUCCESS");
}
assert.equal(gateway.charges, 1);
assert.throws(() => service.pay("same-key", new Request("order", 101, "CARD", "stripe")));
assert.throws(() => service.pay(" ", request));
assert.throws(() => new Request("order", 0, "CARD", "stripe"));
assert.throws(() => service.pay("unknown", new Request("order", 100, "UPI", "missing")));
const snapshot = service.pay("same-key", request);
assert.equal(service.refund("same-key").status, "REFUNDED");
assert.equal(service.refund("same-key").status, "REFUNDED");
assert.equal(snapshot.status, "SUCCESS");
assert.equal(service.webhook("late", "stripe", "same-key", "FAILED").status, "REFUNDED");
assert.throws(() => service.webhook("wrong", "paypal", "same-key", "SUCCESS"));
assert.throws(() => service.refund("missing"));
const unknown = new UnknownGateway();
const pending = new PaymentService(new Map([["stripe", unknown]]));
assert.equal(pending.pay("pending", request).status, "PROCESSING");
assert.equal(unknown.attempts, 3);
assert.equal(pending.pay("pending", request).status, "PROCESSING");
assert.equal(unknown.attempts, 3);
assert.throws(() => pending.refund("pending"));
assert.equal(pending.webhook("event", "stripe", "pending", "SUCCESS").status, "SUCCESS");
assert.equal(pending.webhook("event", "stripe", "pending", "FAILED").status, "SUCCESS");
assert.throws(() => pending.refund("pending"));
assert.equal(pending.pay("pending", request).status, "SUCCESS");
unknown.allowRefund = true;
assert.equal(pending.refund("pending").status, "REFUNDED");
const declined = new PaymentService(new Map([["stripe", new FakeGateway(false, true)]]));
assert.equal(declined.pay("declined", request).status, "FAILED");
assert.throws(() => declined.refund("declined"));
for (const method of ["CARD", "UPI", "NET_BANKING", "WALLET"]) {
    assert.equal(
        service.pay(method, new Request("order", 100, method, "stripe")).status,
        "SUCCESS",
    );
}
console.log("PASS JS/TS payment edge cases");
