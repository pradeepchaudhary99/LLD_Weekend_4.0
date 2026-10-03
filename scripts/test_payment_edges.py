#!/usr/bin/env python3
"""Payment state-machine, retry and thread-safety checks in Python."""

from concurrent.futures import ThreadPoolExecutor
from test_chess_edges import ROOT, load

p = load("payment_lesson", ROOT / "Python/InterviewQuestions/PaymentServiceLLD.py")


def rejects(action, exception=ValueError):
    try:
        action()
    except exception:
        return
    raise AssertionError("Expected rejection")


class UnknownGateway:
    def __init__(self):
        self.attempts = 0
        self.allow_refund = False

    def charge(self, payment_id, request):
        self.attempts += 1
        return "UNKNOWN"

    def refund(self, payment_id):
        return self.allow_refund


def main():
    request = p.Request("order", 100, "CARD", "stripe")
    gateway = p.FakeGateway(True)
    service = p.PaymentService({"stripe": gateway})
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: service.pay("same-key", request), range(100)))
    assert all(payment.status == "SUCCESS" for payment in results)
    assert gateway.charges == 1
    rejects(lambda: service.pay("same-key", p.Request("order", 101, "CARD", "stripe")))
    rejects(lambda: service.pay(" ", request))
    rejects(lambda: p.Request("order", 0, "CARD", "stripe"))
    rejects(lambda: service.pay("unknown", p.Request("order", 100, "UPI", "missing")))
    snapshot = service.pay("same-key", request)
    assert service.refund("same-key").status == "REFUNDED"
    assert service.refund("same-key").status == "REFUNDED"
    assert snapshot.status == "SUCCESS"
    assert service.webhook("late", "stripe", "same-key", "FAILED").status == "REFUNDED"
    rejects(lambda: service.webhook("wrong", "paypal", "same-key", "SUCCESS"))
    rejects(lambda: service.refund("missing"))
    unknown = UnknownGateway()
    pending = p.PaymentService({"stripe": unknown})
    assert pending.pay("pending", request).status == "PROCESSING"
    assert unknown.attempts == 3
    assert pending.pay("pending", request).status == "PROCESSING"
    assert unknown.attempts == 3
    rejects(lambda: pending.refund("pending"))
    assert pending.webhook("event", "stripe", "pending", "SUCCESS").status == "SUCCESS"
    assert pending.webhook("event", "stripe", "pending", "FAILED").status == "SUCCESS"
    rejects(lambda: pending.refund("pending"), RuntimeError)
    assert pending.pay("pending", request).status == "SUCCESS"
    unknown.allow_refund = True
    assert pending.refund("pending").status == "REFUNDED"
    declined = p.PaymentService({"stripe": p.FakeGateway(decline=True)})
    assert declined.pay("declined", request).status == "FAILED"
    rejects(lambda: declined.refund("declined"))
    for method in ("CARD", "UPI", "NET_BANKING", "WALLET"):
        assert service.pay(method, p.Request("order", 100, method, "stripe")).status == "SUCCESS"
    print("PASS Python payment edge cases and concurrent idempotency")


if __name__ == "__main__":
    main()
