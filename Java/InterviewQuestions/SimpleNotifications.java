/*
 * SIMPLE JAVA - Notifications
 *
 * Functional requirements (MVP): Send simulated EMAIL/SMS asynchronously; wait for results and
 * drain on close. Non-functional requirements: readable, in-memory, safe access within one JVM.
 * Core entities: Channel identifies delivery type. NotificationService owns one executor and an
 * audit list. Operations and flow: send validates -> submits task -> returns Future. Future.get
 * exposes completion/failure. Design choices and concurrency: ExecutorService safely queues
 * concurrent callers; one worker serializes delivery. A separate synchronized block protects copied
 * delivery snapshots. Assumptions / omitted features: Fake provider only; no templates,
 * preferences, priorities, retries or deduplication. Executor queue is unbounded for teaching;
 * owner closes after producers finish. Edge cases to discuss/test: Invalid message, ordered
 * submissions, copied snapshots, accepted work drains on close. Run from repository root (Java 17+
 * and Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleNotifications Read
 * SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SimpleNotifications {
    enum Channel { EMAIL, SMS }

    static class NotificationService implements AutoCloseable {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final List<String> deliveries = new ArrayList<>();

        Future<?> send(String recipient, Channel channel, String message) {
            if (recipient == null || recipient.isBlank() || channel == null || message == null ||
                message.isBlank()) {
                throw new IllegalArgumentException("Recipient, channel and message required");
            }
            // The executor safely accepts concurrent submissions and runs one delivery at a time.
            return worker.submit(() -> {
                String delivery = channel + " to " + recipient + ": " + message;
                synchronized (deliveries) {
                    deliveries.add(delivery); // Fake provider: record the delivery locally.
                }
            });
        }

        List<String> deliveries() {
            synchronized (deliveries) {
                return new ArrayList<>(deliveries);
            }
        }

        public void close() throws InterruptedException {
            worker.shutdown(); // Reject new work, but finish already accepted tasks.
            while (!worker.awaitTermination(1, TimeUnit.DAYS)) {
                // Keep waiting for accepted work; this demo's fake provider always finishes.
            }
        }
    }

    public static void main(String[] args) throws Exception {
        NotificationService service = new NotificationService();
        try {
            Future<?> first = service.send("Alice", Channel.EMAIL, "Class starts soon");
            Future<?> second = service.send("Bob", Channel.SMS, "Class starts soon");
            first.get(); // Future.get waits for completion and surfaces task failures.
            second.get();
        } finally {
            service.close();
        }
        for (String delivery : service.deliveries()) {
            System.out.println(delivery);
        }
    }
}
