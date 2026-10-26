/*
Task scheduler: one process, in-memory teaching example.
Functional requirements: schedule one-time or fixed-delay recurring actions;
order dispatch by deadline, priority (0 high, 2 low), then insertion order;
cancel tasks, inspect status/runs, isolate action failures, and close workers.
Non-functional requirements: safe shared state, no polling loop, bounded task
registry (10,000 IDs), and no overlapping executions of the same recurring task.
Core entities: Task holds identity/deadline/action/state; Scheduler owns the
pending queue, registry and workers; Snapshot exposes a detached status/run count.
Flow: schedule -> start -> wait for earliest deadline -> mark RUNNING -> execute
outside the state lock -> record result -> optionally requeue after fixed delay.
Concurrency: queue selection and state transitions are atomic. Cancellation can
win before dispatch; after dispatch the action may finish but never recur.
Priorities break equal-deadline ties; completion order with several workers is
not guaranteed. Failures terminate that task, not the worker. Runs count attempts.
Close cancels pending work and running recurrence, waits for active actions, and
is owner-only. Actions must finish; there is no forced interruption or timeout.
Scope: monotonic milliseconds since construction, interval 0 means one-time;
IDs are retained for this scheduler lifetime. No persistence, retries, cron,
distributed ownership, eviction, or production-grade admission control.
Edge cases: duplicate/unknown IDs, invalid times, cancellation before/during run,
action failure, earlier insertion waking sleepers, and close before start.
A shared lock and condition protect state; native workers execute actions outside it.
The pending min-heap gives O(log n) insertion/removal.
Run from repository root: python3 scripts/run.py "Java" InterviewQuestions/TaskSchedular_demo
See TASK_SCHEDULER_GUIDE.md for prerequisites and contracts.
*/

package InterviewQuestions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class TaskSchedular_demo {
    enum Status { WAITING, RUNNING, COMPLETED, CANCELLED, FAILED }

    static class Task {
        final String id;
        final int priority;
        final long sequence;
        final long interval;
        final Runnable action;
        long due;
        int runs;
        Status status = Status.WAITING;

        Task(String id, long due, int priority, long sequence, long interval, Runnable action) {
            this.id = id;
            this.due = due;
            this.priority = priority;
            this.sequence = sequence;
            this.interval = interval;
            this.action = action;
        }
    }

    record Snapshot(Status status, int runs) {}

    static class Scheduler implements AutoCloseable {
        private final long epoch = System.nanoTime();
        private final Map<String, Task> tasks = new HashMap<>();
        private final PriorityQueue<Task> pending =
            new PriorityQueue<>(Comparator.comparingLong((Task task) -> task.due)
                                    .thenComparingInt(task -> task.priority)
                                    .thenComparingLong(task -> task.sequence));
        private final List<Thread> workers = new ArrayList<>();
        private final List<String> dispatched = new ArrayList<>();
        private boolean started;
        private boolean closed;

        Scheduler(int workerCount) {
            require(workerCount >= 1 && workerCount <= 32, "Invalid worker count");
            for (int index = 0; index < workerCount; index++) {
                workers.add(new Thread(this::work, "scheduler-" + index));
            }
        }

        long now() {
            return (System.nanoTime() - epoch) / 1_000_000;
        }

        synchronized void schedule(String id, long due, int priority, long interval,
                                   Runnable action) {
            require(!closed && id != null && !id.isBlank() && !tasks.containsKey(id),
                    "Closed or duplicate/empty ID");
            require(due >= 0 && due <= 1_000_000_000_000L && priority >= 0 && priority <= 2 &&
                        interval >= 0 && interval <= 1_000_000_000 && action != null &&
                        tasks.size() < 10000,
                    "Invalid task or registry full");
            Task task = new Task(id, due, priority, tasks.size(), interval, action);
            tasks.put(id, task);
            pending.add(task);
            notifyAll(); // Also wakes workers waiting for a later deadline.
        }

        synchronized void start() {
            require(!started && !closed, "Already started/closed");
            started = true;
            for (Thread worker : workers) {
                worker.start();
            }
        }

        synchronized boolean cancel(String id) {
            Task task = task(id);
            if (task.status != Status.WAITING && task.status != Status.RUNNING) {
                return false;
            }
            task.status = Status.CANCELLED;
            notifyAll();
            return true;
        }

        private Task task(String id) {
            require(tasks.containsKey(id), "Unknown task");
            return tasks.get(id);
        }

        synchronized Snapshot snapshot(String id) {
            Task task = task(id);
            return new Snapshot(task.status, task.runs);
        }

        synchronized List<String> dispatches() {
            return new ArrayList<>(dispatched);
        }

        synchronized boolean awaitRuns(String id, int count, long timeoutMillis)
            throws InterruptedException {
            require(count >= 1 && timeoutMillis >= 0 && timeoutMillis <= 60000, "Invalid wait");
            Task task = task(id);
            long deadline = now() + timeoutMillis;
            while (task.runs < count) {
                if (task.status == Status.CANCELLED || task.status == Status.FAILED ||
                    task.status == Status.COMPLETED) {
                    return false;
                }
                long remaining = deadline - now();
                if (remaining <= 0) {
                    return false;
                }
                wait(remaining);
            }
            return true;
        }

        private synchronized Task take() throws InterruptedException {
            while (!closed) {
                while (!pending.isEmpty() && pending.peek().status == Status.CANCELLED) {
                    pending.remove();
                }
                if (pending.isEmpty()) {
                    wait();
                    continue;
                }
                Task task = pending.peek();
                long remaining = task.due - now();
                if (remaining > 0) {
                    wait(remaining);
                    continue;
                }
                pending.remove();
                task.status = Status.RUNNING;
                dispatched.add(task.id);
                return task;
            }
            return null;
        }

        private void work() {
            while (true) {
                Task task;
                try {
                    task = take();
                } catch (InterruptedException ignored) {
                    continue; // close(), not external interruption, owns worker shutdown.
                }
                if (task == null) {
                    return;
                }
                boolean failed = false;
                try {
                    task.action.run(); // Never run application work under the scheduler lock.
                } catch (RuntimeException failure) {
                    failed = true;
                }
                synchronized (this) {
                    task.runs++;
                    if (task.status != Status.CANCELLED) {
                        if (failed) {
                            task.status = Status.FAILED;
                        } else if (task.interval > 0 && !closed) {
                            task.due =
                                now() + task.interval; // Fixed delay after completion; no overlap.
                            task.status = Status.WAITING;
                            pending.add(task);
                        } else {
                            task.status = Status.COMPLETED;
                        }
                    }
                    notifyAll();
                }
            }
        }

        public void close() throws InterruptedException {
            synchronized (this) {
                require(!workers.contains(Thread.currentThread()),
                        "Worker cannot close its scheduler");
                closed = true;
                for (Task task : tasks.values()) {
                    if (task.status == Status.WAITING ||
                        (task.status == Status.RUNNING && task.interval > 0)) {
                        task.status = Status.CANCELLED;
                    }
                }
                pending.clear();
                notifyAll();
            }
            for (Thread worker : workers) {
                worker.join();
            }
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Scheduler contract failed");
        }
    }

    public static void main(String[] args) throws Exception {
        Scheduler scheduler = new Scheduler(1);
        try {
            scheduler.schedule("low", 0, 2, 0, () -> {});
            scheduler.schedule("high", 0, 0, 0, () -> {});
            scheduler.schedule("cancel", 0, 1, 0,
                               () -> { throw new AssertionError("Cancelled task ran"); });
            check(scheduler.cancel("cancel"));
            scheduler.start();
            check(scheduler.awaitRuns("low", 1, 5000));
            check(scheduler.dispatches().equals(List.of("high", "low")));
            scheduler.schedule("failure", scheduler.now(), 1, 0,
                               () -> { throw new IllegalStateException("Expected"); });
            check(scheduler.awaitRuns("failure", 1, 5000));
            check(scheduler.snapshot("failure").status() == Status.FAILED);
            scheduler.schedule("repeat", scheduler.now(), 1, 5, () -> {});
            check(scheduler.awaitRuns("repeat", 2, 5000));
            check(scheduler.cancel("repeat"));
        } finally {
            scheduler.close();
        }
        check(scheduler.snapshot("cancel").runs() == 0);
        check(scheduler.snapshot("repeat").status() == Status.CANCELLED);
        System.out.println("Priority: high,low");
        System.out.println("Cancelled before dispatch: true");
        System.out.println("Failure isolated: true");
        System.out.println("Recurring stopped: true");
        System.out.println("Shutdown: closed");
    }
}
