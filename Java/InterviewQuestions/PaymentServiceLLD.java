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
 * - Instance-level synchronization protects service/game mutations.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py Java InterviewQuestions/PaymentServiceLLD
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

package InterviewQuestions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory payment orchestration with fake gateways, never real money. */
public class PaymentServiceLLD {
    public enum Method { CARD, UPI, NET_BANKING, WALLET }
    public enum Status { PROCESSING, SUCCESS, FAILED, REFUNDED }
    public enum Outcome { SUCCESS, DECLINED, UNKNOWN }

    public record Request(String order, long minorUnits, Method method, String gateway) {
        public Request {
            if (order == null || order.isBlank() || minorUnits <= 0 || method == null ||
                gateway == null || gateway.isBlank()) {
                throw new IllegalArgumentException("Invalid payment request");
            }
        }
    }

    public record Payment(String id, Request request, Status status) {
        // Immutable snapshots prevent callers from modifying repository state.
    }

    public interface Gateway {
        Outcome charge(String paymentId, Request request);
        boolean refund(String paymentId);
    }

    public static final class FakeGateway implements Gateway {
        private final Set<String> charged = new HashSet<>();
        private final Set<String> refunded = new HashSet<>();
        private boolean loseFirstResponse;
        private final boolean decline;

        public FakeGateway(boolean loseFirstResponse, boolean decline) {
            this.loseFirstResponse = loseFirstResponse;
            this.decline = decline;
        }

        public synchronized Outcome charge(String paymentId, Request request) {
            if (decline) {
                return Outcome.DECLINED;
            }
            // Gateway idempotency also protects retries after an ambiguous timeout.
            charged.add(paymentId);
            if (loseFirstResponse) {
                loseFirstResponse = false;
                return Outcome.UNKNOWN;
            }
            return Outcome.SUCCESS;
        }

        public synchronized boolean refund(String paymentId) {
            if (!charged.contains(paymentId)) {
                return false;
            }
            refunded.add(paymentId);
            return true;
        }

        public synchronized int charges() {
            return charged.size();
        }
    }

    public static final class PaymentService {
        private final Map<String, Gateway> gateways;
        private final Map<String, Payment> payments = new HashMap<>();
        private record EventKey(String gateway, String event) {
            // A structured key avoids collisions from delimiter concatenation.
        }

        private final Set<EventKey> events = new HashSet<>();

        public PaymentService(Map<String, Gateway> gateways) {
            this.gateways = Map.copyOf(gateways);
        }

        public synchronized Payment pay(String key, Request request) {
            if (key == null || key.isBlank() || request == null ||
                !gateways.containsKey(request.gateway())) {
                throw new IllegalArgumentException("Invalid key or gateway");
            }
            Payment existing = payments.get(key);
            if (existing != null) {
                if (!existing.request().equals(request)) {
                    throw new IllegalArgumentException("Idempotency conflict");
                }
                return existing;
            }
            Payment payment = new Payment(key, request, Status.PROCESSING);
            payments.put(key, payment);
            for (int attempt = 0; attempt < 3; attempt++) {
                Outcome outcome = gateways.get(request.gateway()).charge(key, request);
                if (outcome != Outcome.UNKNOWN) {
                    payment = new Payment(
                        key, request, outcome == Outcome.SUCCESS ? Status.SUCCESS : Status.FAILED);
                    payments.put(key, payment);
                    break;
                }
            }
            // UNKNOWN remains PROCESSING until a trusted reconciliation event arrives.
            return payment;
        }

        public synchronized Payment refund(String key) {
            Payment payment = requirePayment(key);
            if (payment.status() == Status.REFUNDED) {
                return payment;
            }
            if (payment.status() != Status.SUCCESS) {
                throw new IllegalArgumentException("Only successful payments can be refunded");
            }
            if (!gateways.get(payment.request().gateway()).refund(key)) {
                throw new IllegalStateException("Refund pending; retry with the same payment ID");
            }
            Payment updated = new Payment(key, payment.request(), Status.REFUNDED);
            payments.put(key, updated);
            return updated;
        }

        // Called AFTER provider authentication. No public network endpoint in this lesson.
        public synchronized Payment webhook(String event, String gateway, String key,
                                            Status status) {
            Payment payment = requirePayment(key);
            if (event == null || event.isBlank() || !payment.request().gateway().equals(gateway) ||
                (status != Status.SUCCESS && status != Status.FAILED)) {
                throw new IllegalArgumentException("Invalid webhook");
            }
            if (!events.add(new EventKey(gateway, event))) {
                return payment;
            }
            if (payment.status() == Status.PROCESSING) {
                payment = new Payment(key, payment.request(), status);
                payments.put(key, payment);
            }
            return payment;
        }

        private Payment requirePayment(String key) {
            Payment payment = payments.get(key);
            if (payment == null) {
                throw new IllegalArgumentException("Unknown payment");
            }
            return payment;
        }
    }

    public static void main(String[] args) {
        FakeGateway gateway = new FakeGateway(true, false);
        PaymentService service = new PaymentService(Map.of("stripe", gateway));
        Request request = new Request("order-1", 1699900, Method.CARD, "stripe");
        System.out.println("Payment: " + service.pay("key-1", request).status());
        service.pay("key-1", request);
        System.out.println("Gateway charges: " + gateway.charges());
        System.out.println("Refund: " + service.refund("key-1").status());
        System.out.println("Late webhook: " +
                           service.webhook("event-1", "stripe", "key-1", Status.SUCCESS).status());
    }
}
