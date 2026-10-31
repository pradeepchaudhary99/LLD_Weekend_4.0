package InterviewQuestions;
import java.util.*;
import java.util.concurrent.*;

public class LockerKafkaChecks {
    static void check(boolean value) {
        if (!value) {
            throw new AssertionError("Concurrency contract failed");
        }
    }
    public static void main(String[] args) throws Exception {
        var service = new AmazonLockerDemo.LockerService();
        service.addLocation("A", 0);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var gate = new CountDownLatch(1);
            List<Future<AmazonLockerDemo.Assignment>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                final String id = "p" + index;
                results.add(pool.submit(() -> {
                    gate.await();
                    try {
                        return service.deposit(id, "A", 0, 0, 10);
                    } catch (IllegalArgumentException noCapacity) {
                        return null;
                    }
                }));
            }
            gate.countDown();
            List<AmazonLockerDemo.Assignment> winners = new ArrayList<>();
            for (var result : results) {
                var assignment = result.get(5, TimeUnit.SECONDS);
                if (assignment != null) {
                    winners.add(assignment);
                }
            }
            check(winners.size() == 1 && service.notifications().size() == 1);
            var winner = winners.get(0);
            List<Future<Boolean>> pickups = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                pickups.add(
                    pool.submit(() -> service.pickup(winner.packageId(), winner.code(), 1)));
            }
            int successful = 0;
            for (var pickup : pickups) {
                if (pickup.get(5, TimeUnit.SECONDS)) {
                    successful++;
                }
            }
            check(successful == 1);

            var broker = new KafkaLLDDemo.Broker();
            broker.createTopic("t", 1);
            List<Future<Integer>> appends = new ArrayList<>();
            for (int index = 0; index < 500; index++) {
                final String value = Integer.toString(index);
                appends.add(pool.submit(() -> broker.publish("t", "key", value)));
            }
            for (var append : appends) {
                append.get(5, TimeUnit.SECONDS);
            }
            broker.join("t", "g", "reader");
            var batch = broker.poll("t", "g", "reader", 1000).get(0);
            check(batch.messages().size() == 500);
            Set<String> values = new HashSet<>();
            for (int index = 0; index < 500; index++) {
                var message = batch.messages().get(index);
                check(message.offset() == index);
                values.add(message.value());
            }
            check(values.size() == 500);
            check(broker.commit(batch) && !broker.commit(batch));
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        System.out.println("Java locker and Kafka concurrency checks passed");
    }
}
