# Task scheduler

The completed `InterviewQuestions/TaskSchedular_demo` lesson keeps the classroom
filename for continuity. The original unfinished sketch is preserved verbatim in
`classroom-drafts/2026-10-26/TaskSchedular_demo.java.txt`.

## Requirements and entities

Schedule one-time and recurring actions, choose the earliest deadline, use priority
and insertion order for ties, cancel work, inspect progress, isolate failures, and
stop workers cleanly. A Task contains its ID, action, deadline, priority, interval,
status and attempt count. Scheduler owns the registry, pending queue and workers.
Snapshot returns a detached status and count.

Use a monotonic clock and synchronize shared state. Actions execute outside the
scheduler lock, allowing several workers to progress. Java, Python, C++, C# and Go
use native threads/goroutines and a min-heap. Python threads are useful for blocking
I/O; ordinary CPython does not parallelize CPU-bound Python code. JavaScript and
TypeScript use async logical workers on one event loop and a sorted array for
clarity. Their callbacks should await I/O; CPU-intensive work blocks the event loop.

## Flow and contracts

1. Construct with 1–32 workers and schedule tasks before or after `start`.
2. Give each task a lifetime-unique nonempty ID. Deadlines are absolute milliseconds
   since scheduler construction (`now`), not Unix timestamps. Priority 0 is high,
   1 medium, 2 low. Interval 0 is one-time; positive intervals mean fixed delay.
3. Workers wait until the next deadline or a state-change signal. Selection and
   marking RUNNING happen atomically. Equal deadlines use priority, then original
   insertion order. Multiple workers need not finish in dispatch order.
4. Run the action outside the state lock. Count the attempt, then mark COMPLETED or
   FAILED. An ordinary callback failure terminates that task without killing the
   worker. Successful recurrence is queued for completion time plus interval, so
   one recurring task never overlaps itself. There is no fixed-rate catch-up.
5. Cancelling WAITING work prevents dispatch. Cancelling RUNNING work permits its
   current action to finish but prevents recurrence. Terminal cancellation returns
   false; unknown IDs are errors. Dispatch is the cancellation boundary, not the
   first instruction of the callback.
6. The owner closes the scheduler. Pending work is cancelled; running recurring
   tasks cannot requeue. Already-running one-time actions finish. Close waits for
   workers and is repeatable. It does not drain future work or forcibly interrupt
   actions. **Never call/await close from an action. Actions must eventually return.**

Status and run count are synchronized snapshots, not an immutable execution log.
The demo retains dispatch history without a bound; long-lived production use needs
a bounded log and task-retention policy.
`awaitRuns` waits for completed attempts, not merely dispatch; it can return false
on cancellation, failure before the requested count, or timeout.

The registry retains at most 10,000 task IDs, including terminal tasks. Deadlines
are limited to 10^12 ms; intervals to 10^9 ms. This is an in-memory teaching model,
not a durable distributed scheduler. Persistence, cron, retries, eviction,
cooperative action cancellation and distributed leases are extensions. A process
crash loses all tasks. Worker-resource exhaustion and fatal runtime errors are
outside the example's failure-isolation contract.

## Prerequisites and run commands

Install Python 3.10+ for the runner plus the chosen language's toolchain: JDK 17+,
C++17 compiler, Go 1.22+, .NET SDK 8, or Node.js 18+ (20+ recommended).
For JavaScript/TypeScript, run `npm ci` at repository root first.

```sh
python3 scripts/run.py Java InterviewQuestions/TaskSchedular_demo
python3 scripts/run.py Python InterviewQuestions/TaskSchedular_demo
python3 scripts/run.py 'C++' InterviewQuestions/TaskSchedular_demo
python3 scripts/run.py Go InterviewQuestions/TaskSchedular_demo
python3 scripts/run.py 'C#' InterviewQuestions/TaskSchedular_demo
python3 scripts/run.py JavaScript InterviewQuestions/TaskSchedular_demo
python3 scripts/run.py TypeScript InterviewQuestions/TaskSchedular_demo
```

Each demo checks priority ordering, cancellation before dispatch, failure isolation,
recurrence and shutdown and prints:

```text
Priority: high,low
Cancelled before dispatch: true
Failure isolated: true
Recurring stopped: true
Shutdown: closed
```

Additional concurrency checks:

```sh
mkdir -p /tmp/lld-scheduler-checks
javac -d /tmp/lld-scheduler-checks Java/InterviewQuestions/TaskSchedular_demo.java tests/task-scheduler/TaskSchedulerChecks.java
java -cp /tmp/lld-scheduler-checks InterviewQuestions.TaskSchedulerChecks
python3 scripts/test_task_scheduler.py
node scripts/test-task-scheduler.cjs
(cd Go && go test -race ./InterviewQuestions/TaskSchedular_demo)
python3 scripts/validate.py
```

The focused checks exercise independent workers with barriers, cancelling running
recurrences, earlier insertion, no early execution, FIFO ties, fixed delay, failed
recurrence, invalid IDs and lifecycle behavior. They complement the seven-language
demos; they do not prove every possible interleaving.

The Simple Java MVP editions remain Java-only pending review.
