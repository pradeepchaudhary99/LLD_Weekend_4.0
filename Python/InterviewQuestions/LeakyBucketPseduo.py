# INTERVIEW DESIGN NOTES - Queued Leaky Bucket
#
# Functional requirements:
# - Accept requests into a bounded FIFO queue; reject overflow and submissions after close.
# - One worker attempts jobs with a minimum interval between scheduled attempt starts.
# - Wake an idle worker on submission; count ordinary handler failures and continue.
# - Close rejects new work, drains accepted work, and waits for the worker to finish.
#
# Non-functional requirements:
# - Protect admission and queue state; execute callbacks outside queue locks.
# - Use monotonic time, bounded waiting capacity and no idle busy-spinning.
# - Do not accumulate catch-up credits after slow callbacks or scheduler delays.
#
# Core entities and responsibilities:
# - LeakyBucket: capacity, waiting queue, worker lifecycle and cumulative failure count.
# - Request: integer job ID; handler processes it synchronously (awaitable in JS/TS).
# - Worker: thread/goroutine in native editions; async task on the Node event loop.
# - Monotonic clock: determines remaining delay since the previous attempt started.
#
# Operations and flow:
# - submit -> atomic closed/capacity check -> enqueue -> signal -> return acceptance.
# - start -> wait for work -> wait remaining interval -> dequeue -> attempt handler.
# - close before start starts a draining worker; repeated close waits safely again.
# - Ordinary handler failure increments failures; the job is not retried or requeued.
#
# Design choices and invariants:
# - Capacity counts waiting jobs, excluding the one executing; total outstanding <= capacity+1.
# - First attempt can start immediately. Slow work lowers throughput rather than causing bursts.
# - Callbacks are serial; start twice is invalid; submissions may be queued before start.
# - Close is owner-only: handlers must not call/await close on their own bucket.
# - Handlers must finish; no forced cancellation or shutdown timeout is promised.
#
# Assumptions and scope:
# - Interval is an integer 1..60000 milliseconds. This is a queue/shaper, not the
#   token-bucket or sliding-window admission algorithms in RateLimitingDemo.
# - Native threads and Node timers are not real-time; OS scheduling can delay attempts.
# - No durable queue, retry policy, distributed ordering, priorities or delivery guarantee
#   across process crashes. Fatal runtime/process failures are outside the contract.
# - C# uses Dispose for close; C++ also drains in its destructor; Go returns handler errors
#   and counts recovered panics; JS/TS await rejected promises and count them as failures.
# - Native FIFO operations are O(1); JS/TS Array.shift is O(queue length) in this small demo.
#
# Edge cases and discussion:
# - Full queue before start, close before start, idle wakeup, failure followed by later work,
#   repeated close, invalid configuration, cadence and concurrent producers during shutdown.
# - Discuss deadlines, backpressure, cancellation, durable delivery and overload policies.
#
# Run commands (from repository root):
# - python3 scripts/run.py "Python" InterviewQuestions/LeakyBucketPseduo
# - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.

from collections import deque
from threading import Condition, Thread, current_thread
from time import monotonic


class LeakyBucket:
    def __init__(self, capacity, interval_ms, handler):
        if (
            type(capacity) is not int
            or capacity <= 0
            or type(interval_ms) is not int
            or not 1 <= interval_ms <= 60000
            or not callable(handler)
        ):
            raise ValueError("Invalid configuration")
        self._capacity = capacity
        self._interval = interval_ms / 1000
        self._handler = handler
        self._queue = deque()
        self._condition = Condition()
        self._worker = None
        self._closed = False
        self._failures = 0

    def submit(self, request):
        with self._condition:
            if self._closed or len(self._queue) >= self._capacity:
                return False
            self._queue.append(request)
            self._condition.notify_all()
            return True

    def _start_worker(self):
        self._worker = Thread(target=self._run, name="leaky-bucket")
        self._worker.start()

    def start(self):
        with self._condition:
            if self._worker is not None or self._closed:
                raise ValueError("Already started/closed")
            self._start_worker()

    def _run(self):
        last_start = None
        while True:
            with self._condition:
                while not self._queue and not self._closed:
                    self._condition.wait()
                if not self._queue:
                    return
                if last_start is not None:
                    remaining = self._interval - (monotonic() - last_start)
                    while remaining > 0:
                        self._condition.wait(remaining)
                        remaining = self._interval - (monotonic() - last_start)
                request = self._queue.popleft()
            last_start = monotonic()
            try:
                self._handler(request)
            except Exception:
                with self._condition:
                    self._failures += 1

    def failures(self):
        with self._condition:
            return self._failures

    def close(self):
        with self._condition:
            if current_thread() is self._worker:
                raise ValueError("Handler cannot close its worker")
            self._closed = True
            if self._worker is None:
                self._start_worker()
            worker = self._worker
            self._condition.notify_all()
        worker.join()


def check(condition):
    if not condition:
        raise AssertionError("Queue contract failed")


def main():
    attempted = []

    def handle(request):
        attempted.append(request)
        if request == 1:
            raise ValueError("Simulated handler failure")

    bucket = LeakyBucket(2, 1, handle)
    check(bucket.submit(1))
    check(bucket.submit(2))
    check(not bucket.submit(3))
    bucket.start()
    bucket.close()
    bucket.close()
    check(not bucket.submit(4))
    check(attempted == [1, 2] and bucket.failures() == 1)
    before_start = LeakyBucket(1, 1, lambda request: None)
    check(before_start.submit(7))
    before_start.close()
    try:
        LeakyBucket(0, 1, handle)
    except ValueError:
        pass
    else:
        raise AssertionError("Expected rejection")
    print("Overflow rejected: true")
    print("FIFO attempts: 1,2")
    print("Handler failures: 1")
    print("Shutdown drained: true")


if __name__ == "__main__":
    main()
