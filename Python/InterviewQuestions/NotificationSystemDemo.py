# INTERVIEW DESIGN NOTES - Notification System
#
# Implementation status: runnable in-memory teaching example. Requirements
# below describe this edition; production extensions are explicitly excluded.
#
# Functional requirements:
# - Submit notifications asynchronously using EMAIL, SMS and PUSH adapters.
# - Render a named template with the literal {name} placeholder.
# - Fan out to configured recipient channels; empty preferences mean opt-out.
# - Schedule pending work by HIGH(0), NORMAL(1), LOW(2), then FIFO sequence.
# - Track aggregate QUEUED/SENT/FAILED/SKIPPED and per-channel attempts.
# - Deduplicate identical request IDs; retry each failed delivery up to three times.
#
# Non-functional requirements:
# - Separate submission from provider I/O and protect shared service state.
# - Keep successful channels from being retried when another channel fails.
# - Copy configuration and expose snapshots instead of mutable repository state.
# - Provide explicit start, idle wait and shutdown that drains accepted work.
#
# Core entities and responsibilities:
# - Request: ID, user, template, name and priority.
# - Delivery: one channel job with rendered message, status and attempt count.
# - NotificationService: owns templates/preferences, request index and queue.
# - NotificationChannel/FakeChannel: replaceable provider boundary and simulation.
# - Worker: consumes pending jobs and updates per-channel results.
#
# Key operations and flow:
# - submit -> validate request/preferences -> check ID -> render -> enqueue fan-out.
# - worker -> choose priority/FIFO job -> attempt delivery -> record result.
# - Aggregate status stays QUEUED while jobs remain; any final failure means FAILED.
#
# Design choices and invariants:
# - Registry-based adapters allow new channels without changing orchestration.
# - Idempotency is submission deduplication, not exactly-once external delivery.
# - Priority never preempts a call already running; retries use the same delivery ID.
# - No selected channels produces SKIPPED without contacting a provider.
#
# Assumptions and out of scope:
# - Templates/preferences are fixed for the service lifetime; providers are fake.
# - One service worker, no durable queue, backpressure, restart recovery or backoff.
# - Adapters must finish calls; owner code manages lifecycle, not provider callbacks.
# - Continuous high-priority arrivals can starve lower-priority work.
#
# Interview discussion and checks:
# - Test FIFO ties, opt-out, unknown templates, retry exhaustion and payload conflicts.
# - Test concurrent duplicate submissions, literal replacement text and close-before-start.
# - Discuss outbox delivery, provider idempotency, bounded queues and retry scheduling.
#
# Concurrency in this edition:
# - One service Thread; Condition-protected queue/state.
#
# Run / validation:
# - From repository root:
#   python3 scripts/run.py Python InterviewQuestions/NotificationSystemDemo
# - Prerequisites and focused checks: see README.md and the topic guide.

"""Priority delivery on a background thread. All channels are local simulations."""

from dataclasses import dataclass
import heapq
from threading import Condition, Thread, current_thread, Lock


@dataclass(frozen=True)
class Request:
    id: str
    user: str
    template: str
    name: str
    priority: int


class FakeChannel:
    def __init__(self, failures=0):
        self.failures = failures
        self.delivered = []
        self._lock = Lock()

    def send(self, delivery_id, message):
        with self._lock:
            if self.failures > 0:
                self.failures -= 1
                return False
            self.delivered.append(f"{delivery_id}:{message}")
            return True


@dataclass
class Delivery:
    request: Request
    channel: str
    message: str
    status: str = "QUEUED"
    attempts: int = 0


class NotificationService:
    def __init__(self, channels, templates, preferences):
        self._channels = dict(channels)
        self._templates = dict(templates)
        self._preferences = {user: list(values) for user, values in preferences.items()}
        self._requests = {}
        self._deliveries = {}
        self._sent_order = []
        self._queue = []
        self._sequence = 0
        self._active = 0
        self._closed = False
        self._started = False
        self._condition = Condition()
        self._worker = Thread(target=self._run, name="notification-worker")

    def submit(self, request):
        with self._condition:
            if (
                self._closed
                or not isinstance(request.id, str)
                or not request.id.strip()
                or not isinstance(request.name, str)
                or type(request.priority) is not int
                or not 0 <= request.priority <= 2
                or request.template not in self._templates
                or request.user not in self._preferences
            ):
                raise ValueError("Invalid or closed request")
            if request.id in self._requests:
                if self._requests[request.id] != request:
                    raise ValueError("Idempotency conflict")
                return
            selected = self._preferences[request.user]
            if len(set(selected)) != len(selected) or any(
                channel not in self._channels for channel in selected
            ):
                raise ValueError("Invalid channel preference")
            message = self._templates[request.template].replace("{name}", request.name)
            fanout = [Delivery(request, channel, message) for channel in selected]
            self._requests[request.id] = request
            self._deliveries[request.id] = fanout
            for delivery in fanout:
                heapq.heappush(self._queue, (request.priority, self._sequence, delivery))
                self._sequence += 1
            self._condition.notify_all()

    def status(self, request_id):
        with self._condition:
            if request_id not in self._deliveries:
                raise ValueError("Unknown notification")
            fanout = self._deliveries[request_id]
            if not fanout:
                return "SKIPPED"
            if any(delivery.status in ("QUEUED", "PROCESSING") for delivery in fanout):
                return "QUEUED"
            return "FAILED" if any(delivery.status == "FAILED" for delivery in fanout) else "SENT"

    def attempts(self, request_id, channel):
        with self._condition:
            self.status(request_id)
            return sum(
                delivery.attempts
                for delivery in self._deliveries[request_id]
                if delivery.channel == channel
            )

    def sent_order(self):
        with self._condition:
            return list(self._sent_order)

    def _start_worker(self):
        if not self._started:
            self._started = True
            self._worker.start()

    def start(self):
        with self._condition:
            if self._closed:
                raise RuntimeError("Service closed")
            self._start_worker()

    def _run(self):
        while True:
            with self._condition:
                while not self._queue and not self._closed:
                    self._condition.wait()
                if not self._queue:
                    return
                _, _, delivery = heapq.heappop(self._queue)
                delivery.status = "PROCESSING"
                self._active += 1
            sent, attempts = False, 0
            while not sent and attempts < 3:
                attempts += 1
                try:
                    sent = self._channels[delivery.channel].send(
                        f"{delivery.request.id}/{delivery.channel}", delivery.message
                    )
                except Exception:
                    sent = False
            with self._condition:
                delivery.attempts = attempts
                delivery.status = "SENT" if sent else "FAILED"
                if sent:
                    self._sent_order.append(f"{delivery.request.id}/{delivery.channel}")
                self._active -= 1
                self._condition.notify_all()

    def await_idle(self):
        if current_thread() is self._worker:
            raise RuntimeError("Worker cannot wait for itself")
        with self._condition:
            self.start()
            while self._queue or self._active:
                self._condition.wait()

    def close(self):
        if current_thread() is self._worker:
            raise RuntimeError("Worker cannot join itself")
        with self._condition:
            self._closed = True
            self._start_worker()
            self._condition.notify_all()
        self._worker.join()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def main():
    with NotificationService(
        {"EMAIL": FakeChannel(), "SMS": FakeChannel(1), "PUSH": FakeChannel(9)},
        {"welcome": "Hello {name}"},
        {"alice": ["EMAIL", "SMS"], "bob": ["PUSH"], "quiet": []},
    ) as service:
        low = Request("low", "alice", "welcome", "Alice", 2)
        service.submit(low)
        service.submit(Request("high", "alice", "welcome", "Alice", 0))
        service.submit(low)
        service.submit(Request("fail", "bob", "welcome", "Bob", 1))
        service.submit(Request("off", "quiet", "welcome", "Quiet", 1))
        service.await_idle()
        print("Sent:", ",".join(service.sent_order()))
        print("High:", service.status("high"))
        print("SMS attempts:", service.attempts("high", "SMS"))
        print("Push:", service.status("fail"))
        print("Opt-out:", service.status("off"))


if __name__ == "__main__":
    main()
