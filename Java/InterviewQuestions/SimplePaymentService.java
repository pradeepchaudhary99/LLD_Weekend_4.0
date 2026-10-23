/*
 * SIMPLE JAVA - Payment service
 *
 * Functional requirements (MVP): Simulate pay, decline and full refund; deduplicate payment IDs
 * with identical input. Non-functional requirements: readable, in-memory, safe access within one
 * JVM. Core entities: Payment is an immutable record. PaymentService owns records. FakeBank counts
 * charges. Operations and flow: pay: validate -> reuse/check ID -> fake charge -> store. refund
 * replaces paid status. Design choices and concurrency: synchronized makes duplicate detection and
 * the immediate fake charge atomic locally. Assumptions / omitted features: One currency in minor
 * units. FakeBank declines amounts above 100000. Refund is a local simulation only. No real
 * provider, timeout, webhook, durable idempotency or partial refund. Edge cases to discuss/test:
 * Concurrent duplicate IDs, conflicting amounts, decline, repeated refund, unknown refund. Run from
 * repository root (Java 17+ and Python 3): python3 scripts/run.py Java
 * InterviewQuestions/SimplePaymentService Read SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.HashMap;
import java.util.Map;

public class SimplePaymentService {
    enum Status { PAID, DECLINED, REFUNDED }

    record Payment(String orderId, long cents, Status status) {}

    static class FakeBank {
        int chargeCalls;

        boolean charge(long cents) {
            chargeCalls++;
            return cents <= 100_000; // Deterministic simulation, not a real bank rule.
        }
    }

    static class PaymentService {
        private final FakeBank bank = new FakeBank();
        private final Map<String, Payment> payments = new HashMap<>();

        synchronized Payment pay(String id, String order, long cents) {
            if (id == null || id.isBlank() || order == null || order.isBlank() || cents <= 0) {
                throw new IllegalArgumentException(
                    "Payment ID, order and positive amount required");
            }
            Payment existing = payments.get(id);
            if (existing != null) {
                if (!existing.orderId().equals(order) || existing.cents() != cents) {
                    throw new IllegalArgumentException("Payment ID reused for different input");
                }
                return existing;
            }
            // One lock covers lookup + the immediate fake charge + insertion.
            // A real network call needs durable idempotency and a different locking design.
            Status status = bank.charge(cents) ? Status.PAID : Status.DECLINED;
            Payment payment = new Payment(order, cents, status);
            payments.put(id, payment);
            return payment;
        }

        synchronized Payment refund(String id) {
            Payment payment = payments.get(id);
            if (payment == null || payment.status() == Status.DECLINED) {
                throw new IllegalArgumentException("No paid payment");
            }
            Payment refunded = new Payment(payment.orderId(), payment.cents(), Status.REFUNDED);
            payments.put(id, refunded); // Simulated full refund; repeated call has the same result.
            return refunded;
        }

        synchronized int chargeCalls() {
            return bank.chargeCalls;
        }
    }

    public static void main(String[] args) {
        PaymentService service = new PaymentService();
        System.out.println("Payment: " + service.pay("p1", "order1", 500).status());
        service.pay("p1", "order1", 500);
        System.out.println("Bank calls: " + service.chargeCalls());
        System.out.println("Refund: " + service.refund("p1").status());
    }
}
