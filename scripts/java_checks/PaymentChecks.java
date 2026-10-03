import InterviewQuestions.PaymentServiceLLD.*;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PaymentChecks {
    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Payment invariant violated");
        }
    }

    static void rejects(Runnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException error) {
            rejected = true;
        }
        check(rejected);
    }

    static class UnknownGateway implements Gateway {
        int attempts;
        boolean allowRefund;

        public Outcome charge(String id, Request request) {
            attempts++;
            return Outcome.UNKNOWN;
        }

        public boolean refund(String id) {
            return allowRefund;
        }
    }

    public static void main(String[] args) throws Exception {
        Request request = new Request("order", 100, Method.CARD, "stripe");
        FakeGateway gateway = new FakeGateway(true, false);
        PaymentService service = new PaymentService(Map.of("stripe", gateway));
        var pool = Executors.newFixedThreadPool(8);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int index = 0; index < 100; index++) {
            futures.add(pool.submit(
                () -> { check(service.pay("same-key", request).status() == Status.SUCCESS); }));
        }
        pool.shutdown();
        check(pool.awaitTermination(10, TimeUnit.SECONDS));
        for (var future : futures) {
            future.get();
        }
        check(gateway.charges() == 1);
        rejects(() -> service.pay("same-key", new Request("order", 101, Method.CARD, "stripe")));
        rejects(() -> service.pay(" ", request));
        rejects(() -> new Request("order", 0, Method.CARD, "stripe"));
        rejects(() -> service.pay("unknown", new Request("order", 100, Method.UPI, "missing")));
        Payment snapshot = service.pay("same-key", request);
        check(service.refund("same-key").status() == Status.REFUNDED);
        check(service.refund("same-key").status() == Status.REFUNDED);
        check(snapshot.status() == Status.SUCCESS);
        check(service.webhook("late", "stripe", "same-key", Status.FAILED).status() ==
              Status.REFUNDED);
        rejects(() -> service.webhook("wrong", "paypal", "same-key", Status.SUCCESS));
        rejects(() -> service.refund("missing"));
        UnknownGateway unknown = new UnknownGateway();
        PaymentService pending = new PaymentService(Map.of("stripe", unknown));
        check(pending.pay("pending", request).status() == Status.PROCESSING);
        check(unknown.attempts == 3);
        check(pending.pay("pending", request).status() == Status.PROCESSING);
        check(unknown.attempts == 3);
        rejects(() -> pending.refund("pending"));
        check(pending.webhook("event", "stripe", "pending", Status.SUCCESS).status() ==
              Status.SUCCESS);
        check(pending.webhook("event", "stripe", "pending", Status.FAILED).status() ==
              Status.SUCCESS);
        boolean retryable = false;
        try {
            pending.refund("pending");
        } catch (IllegalStateException error) {
            retryable = true;
        }
        check(retryable);
        check(pending.pay("pending", request).status() == Status.SUCCESS);
        unknown.allowRefund = true;
        check(pending.refund("pending").status() == Status.REFUNDED);
        PaymentService declined =
            new PaymentService(Map.of("stripe", new FakeGateway(false, true)));
        check(declined.pay("declined", request).status() == Status.FAILED);
        rejects(() -> declined.refund("declined"));
        for (Method method : Method.values()) {
            check(
                service.pay(method.name(), new Request("order", 100, method, "stripe")).status() ==
                Status.SUCCESS);
        }
        System.out.println("PASS Java payment edge cases and concurrent idempotency");
    }
}
