/*
 * INTERVIEW DESIGN NOTES - Queued Leaky Bucket
 *
 * Functional requirements:
 * - Accept requests into a bounded FIFO queue; reject overflow and submissions after close.
 * - One worker attempts jobs with a minimum interval between scheduled attempt starts.
 * - Wake an idle worker on submission; count ordinary handler failures and continue.
 * - Close rejects new work, drains accepted work, and waits for the worker to finish.
 *
 * Non-functional requirements:
 * - Protect admission and queue state; execute callbacks outside queue locks.
 * - Use monotonic time, bounded waiting capacity and no idle busy-spinning.
 * - Do not accumulate catch-up credits after slow callbacks or scheduler delays.
 *
 * Core entities and responsibilities:
 * - LeakyBucket: capacity, waiting queue, worker lifecycle and cumulative failure count.
 * - Request: integer job ID; handler processes it synchronously (awaitable in JS/TS).
 * - Worker: thread/goroutine in native editions; async task on the Node event loop.
 * - Monotonic clock: determines remaining delay since the previous attempt started.
 *
 * Operations and flow:
 * - submit -> atomic closed/capacity check -> enqueue -> signal -> return acceptance.
 * - start -> wait for work -> wait remaining interval -> dequeue -> attempt handler.
 * - close before start starts a draining worker; repeated close waits safely again.
 * - Ordinary handler failure increments failures; the job is not retried or requeued.
 *
 * Design choices and invariants:
 * - Capacity counts waiting jobs, excluding the one executing; total outstanding <= capacity+1.
 * - First attempt can start immediately. Slow work lowers throughput rather than causing bursts.
 * - Callbacks are serial; start twice is invalid; submissions may be queued before start.
 * - Close is owner-only: handlers must not call/await close on their own bucket.
 * - Handlers must finish; no forced cancellation or shutdown timeout is promised.
 *
 * Assumptions and scope:
 * - Interval is an integer 1..60000 milliseconds. This is a queue/shaper, not the
 *   token-bucket or sliding-window admission algorithms in RateLimitingDemo.
 * - Native threads and Node timers are not real-time; OS scheduling can delay attempts.
 * - No durable queue, retry policy, distributed ordering, priorities or delivery guarantee
 *   across process crashes. Fatal runtime/process failures are outside the contract.
 * - C# uses Dispose for close; C++ also drains in its destructor; Go returns handler errors
 *   and counts recovered panics; JS/TS await rejected promises and count them as failures.
 * - Native FIFO operations are O(1); JS/TS Array.shift is O(queue length) in this small demo.
 *
 * Edge cases and discussion:
 * - Full queue before start, close before start, idle wakeup, failure followed by later work,
 *   repeated close, invalid configuration, cadence and concurrent producers during shutdown.
 * - Discuss deadlines, backpressure, cancellation, durable delivery and overload policies.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "Java" InterviewQuestions/LeakyBucketPseduo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

package InterviewQuestions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class LeakyBucketPseduo {
    static class LeakyBucket implements AutoCloseable {
        private final int capacity;
        private final long intervalNanos;
        private final IntConsumer handler;
        private final ArrayDeque<Integer> queue = new ArrayDeque<>();
        private Thread worker;
        private boolean closed;
        private int failures;

        LeakyBucket(int capacity, int intervalMillis, IntConsumer handler) {
            if (capacity <= 0 || intervalMillis <= 0 || intervalMillis > 60000 || handler == null) {
                throw new IllegalArgumentException("Invalid configuration");
            }
            this.capacity = capacity;
            intervalNanos = intervalMillis * 1_000_000L;
            this.handler = handler;
        }

        synchronized boolean submit(int request) {
            if (closed || queue.size() >= capacity) {
                return false;
            }
            queue.addLast(request);
            notifyAll();
            return true;
        }

        synchronized void start() {
            if (worker != null || closed) {
                throw new IllegalStateException("Already started/closed");
            }
            startWorker();
        }

        private void startWorker() {
            worker = new Thread(this::run, "leaky-bucket");
            worker.start();
        }

        private void run() {
            long lastStart = 0;
            boolean attempted = false;
            while (true) {
                int request;
                synchronized (this) {
                    try {
                        while (queue.isEmpty() && !closed) {
                            wait();
                        }
                        if (queue.isEmpty()) {
                            return;
                        }
                        long remaining;
                        while (attempted &&
                               (remaining = intervalNanos - (System.nanoTime() - lastStart)) > 0) {
                            wait(remaining / 1_000_000, (int)(remaining % 1_000_000));
                        }
                    } catch (InterruptedException ignored) {
                        // Shutdown uses close(), not interruption; accepted work is retained.
                        continue;
                    }
                    request = queue.removeFirst();
                }
                lastStart = System.nanoTime();
                attempted = true;
                try {
                    handler.accept(request);
                } catch (RuntimeException failure) {
                    synchronized (this) {
                        failures++;
                    }
                }
            }
        }

        synchronized int failures() {
            return failures;
        }

        public void close() {
            Thread thread;
            synchronized (this) {
                if (Thread.currentThread() == worker) {
                    throw new IllegalStateException("Handler cannot close its worker");
                }
                closed = true;
                if (worker == null) {
                    startWorker();
                }
                thread = worker;
                notifyAll();
            }
            boolean interrupted = false;
            while (true) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Queue contract failed");
        }
    }

    public static void main(String[] args) {
        List<Integer> attempted = new ArrayList<>();
        LeakyBucket bucket = new LeakyBucket(2, 1, request -> {
            attempted.add(request);
            if (request == 1) {
                throw new IllegalStateException("Simulated handler failure");
            }
        });
        check(bucket.submit(1));
        check(bucket.submit(2));
        check(!bucket.submit(3));
        bucket.start();
        bucket.close();
        bucket.close();
        check(!bucket.submit(4));
        check(attempted.equals(List.of(1, 2)) && bucket.failures() == 1);
        LeakyBucket beforeStart = new LeakyBucket(1, 1, request -> {});
        check(beforeStart.submit(7));
        beforeStart.close();
        boolean rejected = false;
        try {
            new LeakyBucket(0, 1, request -> {});
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected);
        System.out.println("Overflow rejected: true");
        System.out.println("FIFO attempts: 1,2");
        System.out.println("Handler failures: 1");
        System.out.println("Shutdown drained: true");
    }
}
