package InterviewQuestions;

import InterviewQuestions.SplitWiseDemo.SplitWise;
import InterviewQuestions.NotificationSystemDemo.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

public class NotificationSplitWiseChecks {
    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Lesson invariant failed");
        }
    }

    static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Invalid operation accepted");
    }

    static void ledger() throws Exception {
        SplitWise service = new SplitWise();
        List<String> members = List.of("A", "B", "C");
        for (String user : members) {
            service.addUser(user);
        }
        service.addUser("D");
        service.createGroup("g", members);
        service.createGroup("other", members);
        check(service.addExpense("g", "equal", "A", 2, members, "EQUAL", List.of())
                  .equals(List.of(1L, 1L, 0L)));
        check(service
                  .addExpense("g", "percent", "B", 7, members, "PERCENTAGE",
                              List.of(5000L, 2500L, 2500L))
                  .equals(List.of(3L, 2L, 2L)));
        check(
            service.addExpense("g", "zero", "C", 1, members, "PERCENTAGE", List.of(0L, 0L, 10000L))
                .equals(List.of(0L, 0L, 1L)));
        check(service.balance("g", "A", "B") == 2);
        check(service.balance("g", "B", "A") == -2);
        check(service.balance("other", "A", "B") == 0);
        rejects(
            () -> service.addExpense("g", "bad", "A", 10, members, "EXACT", List.of(2L, 3L, 4L)));
        rejects(()
                    -> service.addExpense("g", "bad", "A", 10, members, "PERCENTAGE",
                                          List.of(5000L, 2000L, 2000L)));
        rejects(
            () -> service.addExpense("g", "bad", "A", 10, List.of("A", "A"), "EQUAL", List.of()));
        rejects(() -> service.addExpense("g", "bad", "D", 10, members, "EQUAL", List.of()));
        rejects(() -> service.addExpense("g", "equal", "A", 10, members, "EQUAL", List.of()));
        rejects(() -> service.addExpense("g", "bad", "A", 0, members, "EQUAL", List.of()));
        rejects(
            () -> service.addExpense("g", "bad", "A", 1000000001L, members, "EQUAL", List.of()));
        rejects(() -> service.settle("g", "bad", "A", "B", 3));
        rejects(() -> service.removeMember("g", "A"));
        check(service.history("g").size() == 3);
        check(service.balance("g", "A", "B") == 2);
        service.settle("g", "settle", "A", "B", 2);
        check(service.balance("g", "A", "B") == 0);
        service.removeMember("g", "A");
        service.addMember("g", "A");
        service.addMember("g", "D");
        service.removeMember("g", "D");
        rejects(() -> service.settle("g", "settle", "C", "B", 1));
        check(service.history("g").size() == 4);
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int index = 0; index < 100; index++) {
            String id = "parallel-" + index;
            futures.add(pool.submit(() -> {
                service.addExpense("other", id, "A", 10, List.of("B"), "EXACT", List.of(10L));
            }));
        }
        pool.shutdown();
        check(pool.awaitTermination(10, TimeUnit.SECONDS));
        for (var future : futures) {
            future.get();
        }
        check(service.balance("other", "B", "A") == 1000);
        check(service.history("other").size() == 100);
        // Hitting the documented balance bound must reject the entire transaction.
        for (int index = 0; index < 1000; index++) {
            service.addExpense("g", "limit-" + index, "A", 1000000000L, List.of("B"), "EXACT",
                               List.of(1000000000L));
        }
        rejects(()
                    -> service.addExpense("g", "overflow", "A", 2, List.of("C", "B"), "EXACT",
                                          List.of(1L, 1L)));
        check(service.balance("g", "C", "A") == 0);
        check(service.history("g").size() == 1004);
    }

    static void notifications() throws Exception {
        FakeChannel email = new FakeChannel(0);
        FakeChannel sms = new FakeChannel(2);
        FakeChannel push = new FakeChannel(10);
        try (NotificationService service = new NotificationService(
                 Map.of("EMAIL", email, "SMS", sms, "PUSH", push), Map.of("t", "Hello {name}"),
                 Map.of("u", List.of("EMAIL", "SMS"), "bad", List.of("PUSH"), "off", List.of()))) {
            Request low = new Request("low", "u", "t", "Ada", 2);
            service.submit(low);
            service.submit(new Request("high", "u", "t", "Ada", 0));
            service.submit(low);
            service.submit(new Request("fail", "bad", "t", "Ada", 1));
            service.submit(new Request("skip", "off", "t", "Ada", 1));
            check(service.status("low").equals("QUEUED"));
            check(service.status("skip").equals("SKIPPED"));
            rejects(() -> service.submit(new Request("low", "u", "t", "changed", 2)));
            rejects(() -> service.submit(new Request("missing", "u", "missing", "Ada", 1)));
            rejects(() -> service.submit(new Request("invalid", "u", "t", "Ada", 3)));
            rejects(() -> service.status("missing"));
            service.awaitIdle();
            check(service.sentOrder().equals(
                List.of("high/EMAIL", "high/SMS", "low/EMAIL", "low/SMS")));
            check(service.attempts("high", "SMS") == 3);
            check(service.attempts("low", "EMAIL") == 1);
            check(service.attempts("fail", "PUSH") == 3);
            check(service.status("fail").equals("FAILED"));
            check(email.delivered.get(0).equals("high/EMAIL:Hello Ada"));
            var pool = Executors.newFixedThreadPool(8);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 100; index++) {
                futures.add(pool.submit(
                    () -> { service.submit(new Request("same", "u", "t", "Ada", 1)); }));
            }
            pool.shutdown();
            check(pool.awaitTermination(10, TimeUnit.SECONDS));
            for (var future : futures) {
                future.get();
            }
            service.awaitIdle();
            check(service.attempts("same", "EMAIL") == 1);
            check(service.sentOrder().size() == 6);
        }
        NotificationService drain = new NotificationService(
            Map.of("EMAIL", email), Map.of("t", "{name}"), Map.of("u", List.of("EMAIL")));
        drain.submit(new Request("drain", "u", "t", "Ada", 1));
        drain.close();
        drain.close();
        check(drain.status("drain").equals("SENT"));
        rejects(() -> drain.submit(new Request("late", "u", "t", "Ada", 1)));
        rejects(drain::start);
    }

    public static void main(String[] args) throws Exception {
        ledger();
        notifications();
        System.out.println("Java notification and Splitwise checks passed");
    }
}
