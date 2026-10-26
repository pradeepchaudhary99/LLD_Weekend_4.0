# Task scheduler: one process, in-memory teaching example.
# Functional requirements: schedule one-time or fixed-delay recurring actions;
# order dispatch by deadline, priority (0 high, 2 low), then insertion order;
# cancel tasks, inspect status/runs, isolate action failures, and close workers.
# Non-functional requirements: safe shared state, no polling loop, bounded task
# registry (10,000 IDs), and no overlapping executions of the same recurring task.
# Core entities: Task holds identity/deadline/action/state; Scheduler owns the
# pending queue, registry and workers; Snapshot exposes a detached status/run count.
# Flow: schedule -> start -> wait for earliest deadline -> mark RUNNING -> execute
# outside the state lock -> record result -> optionally requeue after fixed delay.
# Concurrency: queue selection and state transitions are atomic. Cancellation can
# win before dispatch; after dispatch the action may finish but never recur.
# Priorities break equal-deadline ties; completion order with several workers is
# not guaranteed. Failures terminate that task, not the worker. Runs count attempts.
# Close cancels pending work and running recurrence, waits for active actions, and
# is owner-only. Actions must finish; there is no forced interruption or timeout.
# Scope: monotonic milliseconds since construction, interval 0 means one-time;
# IDs are retained for this scheduler lifetime. No persistence, retries, cron,
# distributed ownership, eviction, or production-grade admission control.
# Edge cases: duplicate/unknown IDs, invalid times, cancellation before/during run,
# action failure, earlier insertion waking sleepers, and close before start.
# A shared lock and condition protect state; native workers execute actions outside it.
# The pending min-heap gives O(log n) insertion/removal.
# Run from repository root: python3 scripts/run.py "Python" InterviewQuestions/TaskSchedular_demo
# See TASK_SCHEDULER_GUIDE.md for prerequisites and contracts.

from dataclasses import dataclass
import heapq
from threading import Condition, Thread, current_thread
from time import monotonic


@dataclass
class Task:
    id: str
    due: int
    priority: int
    sequence: int
    interval: int
    action: object
    runs: int = 0
    status: str = "WAITING"


class Scheduler:
    def __init__(self, worker_count):
        if type(worker_count) is not int or not 1 <= worker_count <= 32:
            raise ValueError("Invalid worker count")
        self._epoch = monotonic()
        self._condition = Condition()
        self._tasks = {}
        self._pending = []
        self._dispatched = []
        self._workers = [
            Thread(target=self._work, name=f"scheduler-{index}") for index in range(worker_count)
        ]
        self._started = False
        self._closed = False

    def now(self):
        return int((monotonic() - self._epoch) * 1000)

    def _enqueue(self, task):
        heapq.heappush(self._pending, (task.due, task.priority, task.sequence, task))

    def schedule(self, task_id, due, priority, interval, action):
        with self._condition:
            if self._closed or not task_id or task_id in self._tasks:
                raise ValueError("Closed or duplicate/empty ID")
            if (
                not all(type(value) is int for value in [due, priority, interval])
                or not 0 <= due <= 10**12
                or not 0 <= priority <= 2
                or not 0 <= interval <= 10**9
                or not callable(action)
                or len(self._tasks) >= 10000
            ):
                raise ValueError("Invalid task or registry full")
            task = Task(task_id, due, priority, len(self._tasks), interval, action)
            self._tasks[task_id] = task
            self._enqueue(task)
            self._condition.notify_all()

    def start(self):
        with self._condition:
            if self._started or self._closed:
                raise ValueError("Already started/closed")
            self._started = True
            for worker in self._workers:
                worker.start()

    def _task(self, task_id):
        if task_id not in self._tasks:
            raise ValueError("Unknown task")
        return self._tasks[task_id]

    def cancel(self, task_id):
        with self._condition:
            task = self._task(task_id)
            if task.status not in ("WAITING", "RUNNING"):
                return False
            task.status = "CANCELLED"
            self._condition.notify_all()
            return True

    def snapshot(self, task_id):
        with self._condition:
            task = self._task(task_id)
            return task.status, task.runs

    def dispatches(self):
        with self._condition:
            return list(self._dispatched)

    def await_runs(self, task_id, count, timeout_ms):
        if count < 1 or not 0 <= timeout_ms <= 60000:
            raise ValueError("Invalid wait")
        with self._condition:
            task = self._task(task_id)
            deadline = self.now() + timeout_ms
            while task.runs < count:
                if task.status in ("CANCELLED", "FAILED", "COMPLETED"):
                    return False
                remaining = deadline - self.now()
                if remaining <= 0:
                    return False
                self._condition.wait(remaining / 1000)
            return True

    def _take(self):
        with self._condition:
            while not self._closed:
                while self._pending and self._pending[0][3].status == "CANCELLED":
                    heapq.heappop(self._pending)
                if not self._pending:
                    self._condition.wait()
                    continue
                task = self._pending[0][3]
                remaining = task.due - self.now()
                if remaining > 0:
                    self._condition.wait(remaining / 1000)
                    continue
                heapq.heappop(self._pending)
                task.status = "RUNNING"
                self._dispatched.append(task.id)
                return task
            return None

    def _work(self):
        while True:
            task = self._take()
            if task is None:
                return
            failed = False
            try:
                task.action()
            except Exception:
                failed = True
            with self._condition:
                task.runs += 1
                if task.status != "CANCELLED":
                    if failed:
                        task.status = "FAILED"
                    elif task.interval > 0 and not self._closed:
                        task.due = self.now() + task.interval
                        task.status = "WAITING"
                        self._enqueue(task)
                    else:
                        task.status = "COMPLETED"
                self._condition.notify_all()

    def close(self):
        with self._condition:
            if current_thread() in self._workers:
                raise ValueError("Worker cannot close its scheduler")
            self._closed = True
            for task in self._tasks.values():
                if task.status == "WAITING" or (task.status == "RUNNING" and task.interval > 0):
                    task.status = "CANCELLED"
            self._pending.clear()
            self._condition.notify_all()
        if self._started:
            for worker in self._workers:
                worker.join()


def check(condition):
    if not condition:
        raise AssertionError("Scheduler contract failed")


def fail():
    raise ValueError("Expected handler failure")


def main():
    scheduler = Scheduler(1)
    try:
        scheduler.schedule("low", 0, 2, 0, lambda: None)
        scheduler.schedule("high", 0, 0, 0, lambda: None)
        scheduler.schedule("cancel", 0, 1, 0, fail)
        check(scheduler.cancel("cancel"))
        scheduler.start()
        check(scheduler.await_runs("low", 1, 5000))
        check(scheduler.dispatches() == ["high", "low"])
        scheduler.schedule("failure", scheduler.now(), 1, 0, fail)
        check(scheduler.await_runs("failure", 1, 5000))
        check(scheduler.snapshot("failure")[0] == "FAILED")
        scheduler.schedule("repeat", scheduler.now(), 1, 5, lambda: None)
        check(scheduler.await_runs("repeat", 2, 5000))
        check(scheduler.cancel("repeat"))
    finally:
        scheduler.close()
    check(scheduler.snapshot("cancel")[1] == 0)
    check(scheduler.snapshot("repeat")[0] == "CANCELLED")
    print("Priority: high,low")
    print("Cancelled before dispatch: true")
    print("Failure isolated: true")
    print("Recurring stopped: true")
    print("Shutdown: closed")


if __name__ == "__main__":
    main()
