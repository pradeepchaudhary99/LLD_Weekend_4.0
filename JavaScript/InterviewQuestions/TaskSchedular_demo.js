"use strict";
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
Async logical workers share one event loop; actions may await I/O. CPU work blocks it.
Pending tasks use a sorted array for readability (O(n log n) enqueue).
Run from repository root: python3 scripts/run.py "TypeScript" InterviewQuestions/TaskSchedular_demo
Generated JavaScript run: python3 scripts/run.py JavaScript InterviewQuestions/TaskSchedular_demo
See TASK_SCHEDULER_GUIDE.md for prerequisites and contracts.
*/
Object.defineProperty(exports, "__esModule", { value: true });

function requireCondition(condition, message) {
    if (!condition) {
        throw new Error(message);
    }
}

class Scheduler {
    workerCount;
    epoch = performance.now();
    tasks = new Map();
    pending = [];
    workers = [];
    dispatched = [];
    waiters = new Set();
    started = false;
    closed = false;

    constructor(workerCount) {
        this.workerCount = workerCount;
        requireCondition(
            Number.isInteger(workerCount) && workerCount >= 1 && workerCount <= 32,
            "Invalid worker count",
        );
    }

    now() {
        return Math.floor(performance.now() - this.epoch);
    }

    enqueue(task) {
        this.pending.push(task);
        this.pending.sort(
            (left, right) =>
                left.due - right.due ||
                left.priority - right.priority ||
                left.sequence - right.sequence,
        );
    }

    signal() {
        for (const wake of [...this.waiters]) {
            wake();
        }
    }

    wait(milliseconds) {
        return new Promise((resolve) => {
            let timer;
            const finish = () => {
                if (timer !== undefined) {
                    clearTimeout(timer);
                }
                this.waiters.delete(finish);
                resolve();
            };
            this.waiters.add(finish);
            if (milliseconds !== undefined) {
                timer = setTimeout(finish, Math.min(milliseconds, 2_147_483_647));
            }
        });
    }

    schedule(id, due, priority, interval, action) {
        requireCondition(
            !this.closed && id.trim().length > 0 && !this.tasks.has(id),
            "Closed or duplicate/empty ID",
        );
        requireCondition(
            [due, priority, interval].every(Number.isSafeInteger) &&
                due >= 0 &&
                due <= 1e12 &&
                priority >= 0 &&
                priority <= 2 &&
                interval >= 0 &&
                interval <= 1e9 &&
                typeof action === "function" &&
                this.tasks.size < 10000,
            "Invalid task or registry full",
        );
        const task = {
            id,
            due,
            priority,
            interval,
            action,
            sequence: this.tasks.size,
            status: "WAITING",
            runs: 0,
        };
        this.tasks.set(id, task);
        this.enqueue(task);
        this.signal();
    }

    start() {
        requireCondition(!this.started && !this.closed, "Already started/closed");
        this.started = true;
        this.workers = Array.from({ length: this.workerCount }, () => this.work());
    }

    task(id) {
        const task = this.tasks.get(id);
        requireCondition(task !== undefined, "Unknown task");
        return task;
    }

    cancel(id) {
        const task = this.task(id);
        if (task.status !== "WAITING" && task.status !== "RUNNING") {
            return false;
        }
        task.status = "CANCELLED";
        this.signal();
        return true;
    }

    snapshot(id) {
        const task = this.task(id);
        return { status: task.status, runs: task.runs };
    }

    dispatches() {
        return [...this.dispatched];
    }

    async awaitRuns(id, count, timeoutMs) {
        requireCondition(
            Number.isInteger(count) &&
                count >= 1 &&
                Number.isInteger(timeoutMs) &&
                timeoutMs >= 0 &&
                timeoutMs <= 60000,
            "Invalid wait",
        );
        const task = this.task(id);
        const deadline = this.now() + timeoutMs;
        while (task.runs < count) {
            if (["CANCELLED", "FAILED", "COMPLETED"].includes(task.status)) {
                return false;
            }
            const remaining = deadline - this.now();
            if (remaining <= 0) {
                return false;
            }
            await this.wait(remaining);
        }
        return true;
    }

    async take() {
        while (!this.closed) {
            while (this.pending[0]?.status === "CANCELLED") {
                this.pending.shift();
            }
            const task = this.pending[0];
            if (!task) {
                await this.wait();
                continue;
            }
            const remaining = task.due - this.now();
            if (remaining > 0) {
                await this.wait(remaining);
                continue;
            }
            this.pending.shift();
            task.status = "RUNNING";
            this.dispatched.push(task.id);
            return task;
        }
        return undefined;
    }

    async work() {
        while (true) {
            const task = await this.take();
            if (!task) {
                return;
            }
            let failed = false;
            try {
                await task.action();
            } catch {
                failed = true;
            }
            task.runs++;
            if (task.status !== "CANCELLED") {
                if (failed) {
                    task.status = "FAILED";
                } else if (task.interval > 0 && !this.closed) {
                    task.due = this.now() + task.interval;
                    task.status = "WAITING";
                    this.enqueue(task);
                } else {
                    task.status = "COMPLETED";
                }
            }
            this.signal();
        }
    }

    async close() {
        this.closed = true;
        for (const task of this.tasks.values()) {
            if (task.status === "WAITING" || (task.status === "RUNNING" && task.interval > 0)) {
                task.status = "CANCELLED";
            }
        }
        this.pending = [];
        this.signal();
        await Promise.all(this.workers);
    }
}

function check(condition) {
    if (!condition) {
        throw new Error("Scheduler contract failed");
    }
}

async function main() {
    const scheduler = new Scheduler(1);
    try {
        scheduler.schedule("low", 0, 2, 0, () => {});
        scheduler.schedule("high", 0, 0, 0, () => {});
        scheduler.schedule("cancel", 0, 1, 0, () => {
            throw new Error("Cancelled task ran");
        });
        check(scheduler.cancel("cancel"));
        scheduler.start();
        check(await scheduler.awaitRuns("low", 1, 5000));
        check(scheduler.dispatches().join() === "high,low");
        scheduler.schedule("failure", scheduler.now(), 1, 0, () => {
            throw new Error("Expected");
        });
        check(await scheduler.awaitRuns("failure", 1, 5000));
        check(scheduler.snapshot("failure").status === "FAILED");
        scheduler.schedule("repeat", scheduler.now(), 1, 5, () => {});
        check(await scheduler.awaitRuns("repeat", 2, 5000));
        check(scheduler.cancel("repeat"));
    } finally {
        await scheduler.close();
    }
    check(scheduler.snapshot("cancel").runs === 0);
    check(scheduler.snapshot("repeat").status === "CANCELLED");
    console.log("Priority: high,low");
    console.log("Cancelled before dispatch: true");
    console.log("Failure isolated: true");
    console.log("Recurring stopped: true");
    console.log("Shutdown: closed");
}
main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
