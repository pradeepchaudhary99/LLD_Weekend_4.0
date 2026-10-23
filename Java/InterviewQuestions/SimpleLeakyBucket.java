/*
 * SIMPLE JAVA - Queued leaky bucket
 *
 * Functional requirements (MVP): Bound pending work, reject overflow, process FIFO with a delay,
 * drain on close. Non-functional requirements: readable, in-memory, safe access within one JVM.
 * Core entities: LeakyBucket owns an ArrayBlockingQueue, one scheduled executor and a job handler.
 * Operations and flow: submit offers work. Each tick polls one job; close ends admission and drains
 * the queue. Design choices and concurrency: One scheduled worker serializes jobs. synchronized
 * makes submit/close atomic. Handler runs outside the lock; failures are counted so the periodic
 * task continues. Assumptions / omitted features: Fixed delay AFTER each attempt, not the advanced
 * version's start-to-start cadence. Idle ticks poll once per delay (1..1000 ms). Capacity excludes
 * the executing job. Handler must finish and must not close its worker. No
 * persistence/retries/timeouts. Edge cases to discuss/test: Full queue, failure followed by later
 * work, close before start, repeated close, post-close rejection. Run from repository root (Java
 * 17+ and Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleLeakyBucket Read
 * SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

public class SimpleLeakyBucket {
    static class LeakyBucket implements Runnable, AutoCloseable {
        private final ArrayBlockingQueue<Integer> waiting;
        private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();
        private final IntConsumer handler;
        private final int delayMillis;
        private boolean started;
        private boolean closed;
        private int failures;
        private volatile Thread workerThread;

        LeakyBucket(int capacity, int delayMillis, IntConsumer handler) {
            if (capacity <= 0 || delayMillis < 1 || delayMillis > 1000 || handler == null) {
                throw new IllegalArgumentException("Invalid capacity, delay or handler");
            }
            waiting = new ArrayBlockingQueue<>(capacity);
            this.delayMillis = delayMillis;
            this.handler = handler;
        }

        synchronized boolean submit(int request) {
            return !closed && waiting.offer(request); // offer returns false when full.
        }

        synchronized void start() {
            if (started || closed) {
                throw new IllegalStateException("Already started/closed");
            }
            startWorker();
        }

        private void startWorker() {
            started = true;
            // One attempt per tick, followed by a delay; slow jobs cannot cause catch-up bursts.
            worker.scheduleWithFixedDelay(this, 0, delayMillis, TimeUnit.MILLISECONDS);
        }

        public void run() {
            workerThread = Thread.currentThread();
            Integer request;
            synchronized (this) {
                request = waiting.poll();
                if (request == null && closed) {
                    worker.shutdown();
                }
            }
            if (request != null) {
                try {
                    handler.accept(request); // Never call user code while holding the queue lock.
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

        public void close() throws InterruptedException {
            if (Thread.currentThread() == workerThread) {
                throw new IllegalStateException("Handler cannot close its own worker");
            }
            synchronized (this) {
                closed = true;
                if (!started) {
                    startWorker();
                }
            }
            while (!worker.awaitTermination(1, TimeUnit.DAYS)) {
                // The worker shuts itself down only after all accepted requests are drained.
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        LeakyBucket bucket =
            new LeakyBucket(2, 1, request -> System.out.println("Processed: " + request));
        bucket.submit(1);
        bucket.submit(2);
        System.out.println("Third request accepted: " + bucket.submit(3));
        bucket.start();
        bucket.close();
        System.out.println("After close accepted: " + bucket.submit(4));
    }
}
