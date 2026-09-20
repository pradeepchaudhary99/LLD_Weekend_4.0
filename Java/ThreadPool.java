import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPool {
    static class CustomThreadPool implements AutoCloseable {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        private final List<Thread> workers = new ArrayList<>();
        private final Runnable stop = () -> {
        };
        private boolean shutdown;
        final AtomicInteger failures = new AtomicInteger();

        CustomThreadPool(int size) {
            if (size <= 0) {
                throw new IllegalArgumentException("Pool size must be positive");
            }

            for (int index = 0; index < size; index++) {
                Thread worker = new Thread(this::work, "worker-" + index);
                workers.add(worker);
                worker.start();
            }
        }

        private void work() {
            try {
                while (true) {
                    Runnable task = tasks.take();
                    if (task == stop) {
                        return;
                    }

                    try {
                        task.run();
                    } catch (RuntimeException failure) {
                        // One failed task must not remove a worker from the pool.
                        failures.incrementAndGet();
                    }
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized void submit(Runnable task) {
            if (shutdown) {
                throw new IllegalStateException("Pool is closed");
            }

            tasks.add(Objects.requireNonNull(task));
        }

        synchronized void shutdown() {
            if (!shutdown) {
                shutdown = true;
                // FIFO sentinels drain accepted work before each worker exits.
                for (Thread worker : workers) {
                    tasks.add(stop);
                }
            }
        }

        // Called by the owner, never from one of this pool's worker tasks.
        public void close() throws InterruptedException {
            shutdown();
            for (Thread worker : workers) {
                worker.join();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        CustomThreadPool pool = new CustomThreadPool(3);
        AtomicInteger total = new AtomicInteger();
        pool.submit(() -> {
            // Deliberately fail one task to demonstrate worker survival.
            throw new IllegalStateException("Expected teaching failure");
        });
        for (int value = 1; value <= 10; value++) {
            final int number = value;
            pool.submit(() -> total.addAndGet(number));
        }

        pool.close();
        pool.close();
        if (total.get() != 55 || pool.failures.get() != 1) {
            throw new AssertionError("Tasks lost or failures hidden");
        }

        System.out.println("Completed sum: " + total.get());
        System.out.println("Task failures: " + pool.failures.get());
        try {
            pool.submit(() -> {
                // This task must never be accepted.
            });
            throw new AssertionError("Accepted work after shutdown");
        } catch (IllegalStateException expected) {
            System.out.println("Submission after shutdown rejected");
        }

        try {
            new CustomThreadPool(0);
            throw new AssertionError("Accepted invalid pool size");
        } catch (IllegalArgumentException expected) {
            System.out.println("Invalid pool size rejected");
        }
    }
}
