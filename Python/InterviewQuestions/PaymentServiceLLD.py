"""In-memory orchestration using simulated gateways, never real money."""

from dataclasses import dataclass, replace
from threading import RLock
from typing import Protocol


@dataclass(frozen=True)
class Request:
    order: str
    minor_units: int
    method: str
    gateway: str

    def __post_init__(self):
        if (
            not self.order.strip()
            or type(self.minor_units) is not int
            or self.minor_units <= 0
            or self.method not in ("CARD", "UPI", "NET_BANKING", "WALLET")
            or not self.gateway.strip()
        ):
            raise ValueError("Invalid payment request")


@dataclass(frozen=True)
class Payment:
    id: str
    request: Request
    status: str = "PROCESSING"


class Gateway(Protocol):
    def charge(self, payment_id: str, request: Request) -> str: ...

    def refund(self, payment_id: str) -> bool: ...


class FakeGateway:
    def __init__(self, lose_first_response=False, decline=False):
        self._charged = set()
        self._refunded = set()
        self._lose_first_response = lose_first_response
        self._decline = decline
        self._lock = RLock()

    def charge(self, payment_id, request):
        with self._lock:
            if self._decline:
                return "DECLINED"
            self._charged.add(payment_id)
            if self._lose_first_response:
                self._lose_first_response = False
                return "UNKNOWN"
            return "SUCCESS"

    def refund(self, payment_id):
        with self._lock:
            if payment_id not in self._charged:
                return False
            self._refunded.add(payment_id)
            return True

    @property
    def charges(self):
        with self._lock:
            return len(self._charged)


class PaymentService:
    def __init__(self, gateways):
        self._gateways = dict(gateways)
        self._payments = {}
        self._events = set()
        self._lock = RLock()

    def pay(self, key, request):
        with self._lock:
            if not key.strip() or request.gateway not in self._gateways:
                raise ValueError("Invalid key or gateway")
            if key in self._payments:
                existing = self._payments[key]
                if existing.request != request:
                    raise ValueError("Idempotency conflict")
                return existing
            payment = Payment(key, request)
            self._payments[key] = payment
            for _ in range(3):
                outcome = self._gateways[request.gateway].charge(key, request)
                if outcome != "UNKNOWN":
                    payment = replace(
                        payment, status="SUCCESS" if outcome == "SUCCESS" else "FAILED"
                    )
                    self._payments[key] = payment
                    break
            return payment

    def _require(self, key):
        if key not in self._payments:
            raise ValueError("Unknown payment")
        return self._payments[key]

    def refund(self, key):
        with self._lock:
            payment = self._require(key)
            if payment.status == "REFUNDED":
                return payment
            if payment.status != "SUCCESS":
                raise ValueError("Only successful payments can be refunded")
            if not self._gateways[payment.request.gateway].refund(key):
                raise RuntimeError("Refund pending; retry with the same payment ID")
            payment = replace(payment, status="REFUNDED")
            self._payments[key] = payment
            return payment

    def webhook(self, event, gateway, key, status):
        """Invoke only after provider authentication, outside this lesson's scope."""
        with self._lock:
            payment = self._require(key)
            if (
                not event.strip()
                or payment.request.gateway != gateway
                or status not in ("SUCCESS", "FAILED")
            ):
                raise ValueError("Invalid webhook")
            event_key = (gateway, event)
            if event_key in self._events:
                return payment
            self._events.add(event_key)
            if payment.status == "PROCESSING":
                payment = replace(payment, status=status)
                self._payments[key] = payment
            return payment


if __name__ == "__main__":
    gateway = FakeGateway(lose_first_response=True)
    service = PaymentService({"stripe": gateway})
    request = Request("order-1", 1699900, "CARD", "stripe")
    print("Payment:", service.pay("key-1", request).status)
    service.pay("key-1", request)
    print("Gateway charges:", gateway.charges)
    print("Refund:", service.refund("key-1").status)
    print("Late webhook:", service.webhook("event-1", "stripe", "key-1", "SUCCESS").status)
