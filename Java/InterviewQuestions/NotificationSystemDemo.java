/*
 * INTERVIEW DESIGN NOTES - Notification System
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Submit notifications asynchronously using EMAIL, SMS and PUSH adapters.
 * - Render a named template with the literal {name} placeholder.
 * - Fan out to configured recipient channels; empty preferences mean opt-out.
 * - Schedule pending work by HIGH(0), NORMAL(1), LOW(2), then FIFO sequence.
 * - Track aggregate QUEUED/SENT/FAILED/SKIPPED and per-channel attempts.
 * - Deduplicate identical request IDs; retry each failed delivery up to three times.
 *
 * Non-functional requirements:
 * - Separate submission from provider I/O and protect shared service state.
 * - Keep successful channels from being retried when another channel fails.
 * - Copy configuration and expose snapshots instead of mutable repository state.
 * - Provide explicit start, idle wait and shutdown that drains accepted work.
 *
 * Core entities and responsibilities:
 * - Request: ID, user, template, name and priority.
 * - Delivery: one channel job with rendered message, status and attempt count.
 * - NotificationService: owns templates/preferences, request index and queue.
 * - NotificationChannel/FakeChannel: replaceable provider boundary and simulation.
 * - Worker: consumes pending jobs and updates per-channel results.
 *
 * Key operations and flow:
 * - submit -> validate request/preferences -> check ID -> render -> enqueue fan-out.
 * - worker -> choose priority/FIFO job -> attempt delivery -> record result.
 * - Aggregate status stays QUEUED while jobs remain; any final failure means FAILED.
 *
 * Design choices and invariants:
 * - Registry-based adapters allow new channels without changing orchestration.
 * - Idempotency is submission deduplication, not exactly-once external delivery.
 * - Priority never preempts a call already running; retries use the same delivery ID.
 * - No selected channels produces SKIPPED without contacting a provider.
 *
 * Assumptions and out of scope:
 * - Templates/preferences are fixed for the service lifetime; providers are fake.
 * - One service worker, no durable queue, backpressure, restart recovery or backoff.
 * - Adapters must finish calls; owner code manages lifecycle, not provider callbacks.
 * - Continuous high-priority arrivals can starve lower-priority work.
 *
 * Interview discussion and checks:
 * - Test FIFO ties, opt-out, unknown templates, retry exhaustion and payload conflicts.
 * - Test concurrent duplicate submissions, literal replacement text and close-before-start.
 * - Discuss outbox delivery, provider idempotency, bounded queues and retry scheduling.
 *
 * Concurrency in this edition:
 * - One service Thread; synchronized queue/state; providers run outside the lock.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py Java InterviewQuestions/NotificationSystemDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Asynchronous, in-memory delivery; adapters simulate providers without network calls. */
public class NotificationSystemDemo {
    record Request(String id, String user, String template, String name, int priority) {
        // Priority: 0 = high, 1 = normal, 2 = low.
    }

    interface NotificationChannel {
        boolean send(String deliveryId, String message);
    }

    static class FakeChannel implements NotificationChannel {
        private int failures;
        final List<String> delivered = new ArrayList<>();

        FakeChannel(int failures) {
            this.failures = failures;
        }

        public synchronized boolean send(String deliveryId, String message) {
            if (failures > 0) {
                failures--;
                return false;
            }
            delivered.add(deliveryId + ":" + message);
            return true;
        }
    }

    static class Delivery {
        final Request request;
        final String channel;
        final String message;
        final long sequence;
        String status = "QUEUED";
        int attempts;

        Delivery(Request request, String channel, String message, long sequence) {
            this.request = request;
            this.channel = channel;
            this.message = message;
            this.sequence = sequence;
        }
    }

    static class NotificationService implements AutoCloseable {
        private final Map<String, NotificationChannel> channels;
        private final Map<String, String> templates;
        private final Map<String, List<String>> preferences;
        private final Map<String, Request> requests = new HashMap<>();
        private final Map<String, List<Delivery>> deliveries = new HashMap<>();
        private final List<String> sentOrder = new ArrayList<>();
        private final PriorityQueue<Delivery> queue = new PriorityQueue<>(
            Comparator.comparingInt((Delivery delivery) -> delivery.request.priority())
                .thenComparingLong(delivery -> delivery.sequence));
        private final Thread worker;
        private boolean started;
        private boolean closed;
        private long sequence;
        private int active;

        NotificationService(Map<String, NotificationChannel> channels,
                            Map<String, String> templates, Map<String, List<String>> preferences) {
            this.channels = Map.copyOf(channels);
            this.templates = Map.copyOf(templates);
            this.preferences = new HashMap<>();
            preferences.forEach((user, values) -> this.preferences.put(user, List.copyOf(values)));
            worker = new Thread(this::run, "notification-worker");
        }

        synchronized void submit(Request request) {
            if (closed || request == null || request.id() == null || request.id().isBlank() ||
                request.name() == null || request.priority() < 0 || request.priority() > 2 ||
                !templates.containsKey(request.template()) ||
                !preferences.containsKey(request.user())) {
                throw new IllegalArgumentException("Invalid or closed request");
            }
            Request existing = requests.get(request.id());
            if (existing != null) {
                if (!existing.equals(request)) {
                    throw new IllegalArgumentException("Idempotency conflict");
                }
                return;
            }
            List<String> selected = preferences.get(request.user());
            if (selected.stream().distinct().count() != selected.size() ||
                !channels.keySet().containsAll(selected)) {
                throw new IllegalArgumentException("Invalid channel preference");
            }
            String message = templates.get(request.template()).replace("{name}", request.name());
            List<Delivery> fanout = new ArrayList<>();
            for (String channel : selected) {
                fanout.add(new Delivery(request, channel, message, sequence++));
            }
            requests.put(request.id(), request);
            deliveries.put(request.id(), fanout);
            queue.addAll(fanout);
            notifyAll();
        }

        synchronized String status(String id) {
            List<Delivery> fanout = deliveries.get(id);
            if (fanout == null) {
                throw new IllegalArgumentException("Unknown notification");
            }
            if (fanout.isEmpty()) {
                return "SKIPPED";
            }
            if (fanout.stream().anyMatch(delivery
                                         -> delivery.status.equals("QUEUED") ||
                                                delivery.status.equals("PROCESSING"))) {
                return "QUEUED";
            }
            return fanout.stream().anyMatch(delivery -> delivery.status.equals("FAILED")) ? "FAILED"
                                                                                          : "SENT";
        }

        synchronized int attempts(String id, String channel) {
            status(id);
            return deliveries.get(id)
                .stream()
                .filter(delivery -> delivery.channel.equals(channel))
                .mapToInt(delivery -> delivery.attempts)
                .sum();
        }

        synchronized List<String> sentOrder() {
            return List.copyOf(sentOrder);
        }

        synchronized void start() {
            if (closed) {
                throw new IllegalStateException("Service closed");
            }
            startWorker();
        }

        private void startWorker() {
            if (!started) {
                started = true;
                worker.start();
            }
        }

        private void run() {
            while (true) {
                Delivery delivery;
                synchronized (this) {
                    while (queue.isEmpty() && !closed) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                            // Shutdown uses close(), which drains accepted work.
                        }
                    }
                    if (queue.isEmpty()) {
                        return;
                    }
                    delivery = queue.remove();
                    delivery.status = "PROCESSING";
                    active++;
                }
                boolean sent = false;
                int attempts = 0;
                while (!sent && attempts < 3) {
                    attempts++;
                    try {
                        sent = channels.get(delivery.channel)
                                   .send(delivery.request.id() + "/" + delivery.channel,
                                         delivery.message);
                    } catch (RuntimeException error) {
                        sent = false;
                    }
                }
                synchronized (this) {
                    delivery.attempts = attempts;
                    delivery.status = sent ? "SENT" : "FAILED";
                    if (sent) {
                        sentOrder.add(delivery.request.id() + "/" + delivery.channel);
                    }
                    active--;
                    notifyAll();
                }
            }
        }

        void awaitIdle() throws InterruptedException {
            if (Thread.currentThread() == worker) {
                throw new IllegalStateException("Worker cannot wait for itself");
            }
            synchronized (this) {
                start();
                while (!queue.isEmpty() || active != 0) {
                    wait();
                }
            }
        }

        public void close() {
            if (Thread.currentThread() == worker) {
                throw new IllegalStateException("Worker cannot join itself");
            }
            synchronized (this) {
                closed = true;
                startWorker();
                notifyAll();
            }
            boolean interrupted = false;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        try (NotificationService service =
                 new NotificationService(Map.of("EMAIL", new FakeChannel(0), "SMS",
                                                new FakeChannel(1), "PUSH", new FakeChannel(9)),
                                         Map.of("welcome", "Hello {name}"),
                                         Map.of("alice", List.of("EMAIL", "SMS"), "bob",
                                                List.of("PUSH"), "quiet", List.of()))) {
            Request low = new Request("low", "alice", "welcome", "Alice", 2);
            service.submit(low);
            service.submit(new Request("high", "alice", "welcome", "Alice", 0));
            service.submit(low);
            service.submit(new Request("fail", "bob", "welcome", "Bob", 1));
            service.submit(new Request("off", "quiet", "welcome", "Quiet", 1));
            service.awaitIdle();
            System.out.println("Sent: " + String.join(",", service.sentOrder()));
            System.out.println("High: " + service.status("high"));
            System.out.println("SMS attempts: " + service.attempts("high", "SMS"));
            System.out.println("Push: " + service.status("fail"));
            System.out.println("Opt-out: " + service.status("off"));
        }
    }
}
