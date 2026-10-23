package InterviewQuestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Separate verification code: students can read the twelve demos without these test helpers. */
public class SimpleExamplesChecks {
    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Check failed");
        }
    }

    private static void rejected(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    private static void parallel(int count, IntConsumer action) throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                final int value = index;
                tasks.add(() -> {
                    action.accept(value);
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void parking() throws Exception {
        var lot = new SimpleParkingLot.ParkingLot(1);
        AtomicInteger parked = new AtomicInteger();
        parallel(100, index -> {
            if (lot.park("car" + index) >= 0) {
                parked.incrementAndGet();
            }
        });
        check(parked.get() == 1);
        rejected(() -> lot.leave("missing"));
        var single = new SimpleParkingLot.ParkingLot(1);
        check(single.park("A") == 0);
        rejected(() -> single.park("A"));
        single.leave("A");
        check(single.park("B") == 0);
    }

    private static void cache() throws Exception {
        var cache = new SimpleLRUCache.LRUCache(8);
        parallel(1000, index -> {
            cache.put(index % 16, index);
            cache.get((index + 1) % 16);
        });
        check(cache.oldestFirst().size() == 8);
        check(new HashSet<>(cache.oldestFirst()).size() == 8);
        var small = new SimpleLRUCache.LRUCache(2);
        small.put(1, -1);
        small.put(2, 2);
        check(small.get(1) == -1);
        small.put(3, 3);
        check(small.get(2) == null);
        small.oldestFirst().clear();
        check(small.oldestFirst().size() == 2);
        var zero = new SimpleLRUCache.LRUCache(0);
        zero.put(1, 1);
        check(zero.get(1) == null);
    }

    private static void routingAndLimits() throws Exception {
        var balancer = new SimpleLoadBalancer.LoadBalancer();
        rejected(balancer::route);
        balancer.register("A");
        balancer.register("B");
        AtomicInteger countA = new AtomicInteger();
        parallel(100, index -> {
            if (balancer.route().equals("A")) {
                countA.incrementAndGet();
            }
        });
        check(countA.get() == 50);
        balancer.setHealthy("A", false);
        check(balancer.route().equals("B"));
        balancer.setHealthy("B", false);
        rejected(balancer::route);
        var limiter = new SimpleRateLimiter.RateLimiter(17, 1000);
        AtomicInteger allowed = new AtomicInteger();
        parallel(100, index -> {
            if (limiter.allow("same-user", 0)) {
                allowed.incrementAndGet();
            }
        });
        check(allowed.get() == 17);
        check(!limiter.allow("same-user", 999));
        check(limiter.allow("same-user", 1000));
        check(limiter.allow("other-user", 0));
        rejected(() -> limiter.allow("same-user", 999));
    }

    private static void paymentsAndExpenses() throws Exception {
        var payments = new SimplePaymentService.PaymentService();
        parallel(100, index -> payments.pay("same-id", "order", 500));
        check(payments.chargeCalls() == 1);
        rejected(() -> payments.pay("same-id", "order", 600));
        check(payments.refund("same-id").status() == SimplePaymentService.Status.REFUNDED);
        check(payments.refund("same-id").status() == SimplePaymentService.Status.REFUNDED);
        check(payments.pay("same-id", "order", 500).status() ==
              SimplePaymentService.Status.REFUNDED);
        check(payments.pay("declined", "order", 100001).status() ==
              SimplePaymentService.Status.DECLINED);
        rejected(() -> payments.refund("declined"));
        var book = new SimpleSplitwise.ExpenseBook(List.of("A", "B", "C"));
        parallel(100, index -> book.addExpense("A", 300, List.of("A", "B", "C")));
        check(book.balance("A") == 20000 && book.balance("B") == -10000);
        book.settle("B", "A", 10000);
        rejected(() -> book.settle("B", "A", 1));
        long before = book.balance("A");
        rejected(() -> book.addExpense("A", 100, List.of("A", "missing")));
        check(book.balance("A") == before);
        var rounding = new SimpleSplitwise.ExpenseBook(List.of("A", "B", "C"));
        rounding.addExpense("A", 100, List.of("A", "B", "C"));
        check(rounding.balance("A") == 66 && rounding.balance("B") == -33);
    }

    private static void food() throws Exception {
        var service = new SimpleFoodDelivery.FoodDelivery();
        for (String id : List.of("o0", "o1")) {
            service.place(id, "pizza", 1);
            service.pay(id);
            service.prepare(id);
            service.ready(id);
        }
        AtomicInteger assigned = new AtomicInteger();
        parallel(2, index -> {
            if (service.dispatch("o" + index)) {
                assigned.incrementAndGet();
            }
        });
        check(assigned.get() == 1);
        String active =
            service.status("o0") == SimpleFoodDelivery.Status.OUT_FOR_DELIVERY ? "o0" : "o1";
        String pending = active.equals("o0") ? "o1" : "o0";
        rejected(() -> service.cancel(active));
        service.deliver(active);
        check(service.dispatch(pending));
        service.deliver(pending);
        rejected(() -> service.deliver(pending));
        service.place("cancel", "rice", 1);
        service.cancel("cancel");
        check(service.status("cancel") == SimpleFoodDelivery.Status.CANCELLED);
    }

    private static void workers() throws Exception {
        var elevator = new SimpleElevator.Elevator("test-car");
        try {
            parallel(100, index -> elevator.request(index % 11));
        } finally {
            elevator.close();
        }
        check(elevator.arrivals().size() == 100);
        rejected(() -> elevator.request(1));
        var notifications = new SimpleNotifications.NotificationService();
        try {
            parallel(
                100,
                index
                -> notifications.send("user" + index, SimpleNotifications.Channel.EMAIL, "Hello"));
        } finally {
            notifications.close();
        }
        check(notifications.deliveries().size() == 100);
        rejected(() -> notifications.send("late", SimpleNotifications.Channel.SMS, "Hello"));
        List<Integer> attempted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var bucket = new SimpleLeakyBucket.LeakyBucket(10, 1, request -> {
            attempted.add(request);
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Expected test failure");
            }
        });
        AtomicInteger accepted = new AtomicInteger();
        parallel(100, index -> {
            if (bucket.submit(index)) {
                accepted.incrementAndGet();
            }
        });
        check(accepted.get() == 10);
        bucket.close(); // Also tests draining without an explicit start.
        bucket.close();
        check(attempted.size() == 10 && new HashSet<>(attempted).size() == 10 &&
              bucket.failures() == 1);
        check(!bucket.submit(101));
        rejected(bucket::start);
        Thread game = new Thread(new SimpleGameLoop.Game());
        game.start();
        game.interrupt();
        game.join(2000);
        check(!game.isAlive());
    }

    private static void chess() {
        var game = new SimpleChess.ChessGame();
        rejected(() -> game.move("e7", "e5"));
        rejected(() -> game.move("a1", "a4"));
        rejected(() -> game.move("e2", "e5"));
        check(game.pieceAt("e2") == 'P');
        game.move("e2", "e4");
        game.move("d7", "d5");
        game.move("e4", "d5");
        check(game.pieceAt("d5") == 'P' && game.pieceAt("e4") == '.');
        var mate = new SimpleChess.ChessGame();
        mate.move("f2", "f3");
        mate.move("e7", "e5");
        mate.move("g2", "g4");
        mate.move("d8", "h4");
        check(mate.status().equals("CHECKMATE"));
        rejected(() -> mate.move("a2", "a3"));
    }

    public static void main(String[] args) throws Exception {
        parking();
        cache();
        routingAndLimits();
        paymentsAndExpenses();
        food();
        workers();
        chess();
        System.out.println("All simple Java edge-case and concurrency checks passed");
    }
}
