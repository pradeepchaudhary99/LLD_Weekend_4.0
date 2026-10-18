# LRU cache and queued leaky bucket

Both classroom sketches are now runnable lessons in Java, Python, C++, Go, C#,
JavaScript and TypeScript. The original Java sources are preserved verbatim in
`classroom-drafts/2026-10-18/`. The filename `LeakyBucketPseduo` is retained so existing
references keep working; its contents are executable code now.

Every edition starts with functional/non-functional requirements, entities,
operations, design choices, scope, edge cases and exact run commands.

## Prerequisites and run commands

Use Python 3.10+, Java 17+, a C++17 compiler, Go 1.26+ (matching `Go/go.mod`),
Node.js 18+, and .NET 8 SDK for the languages you want to run. Run `npm ci` for the
pinned TypeScript compiler. Set `DOTNET` to the executable path if .NET is not on PATH.

From the repository root:

```sh
python3 scripts/run.py Java InterviewQuestions/LRU_CacheDemo
python3 scripts/run.py Python InterviewQuestions/LRU_CacheDemo
python3 scripts/run.py 'C++' InterviewQuestions/LRU_CacheDemo
python3 scripts/run.py Go InterviewQuestions/LRU_CacheDemo
python3 scripts/run.py 'C#' InterviewQuestions/LRU_CacheDemo
python3 scripts/run.py JavaScript InterviewQuestions/LRU_CacheDemo
python3 scripts/run.py TypeScript InterviewQuestions/LRU_CacheDemo

python3 scripts/run.py Java InterviewQuestions/LeakyBucketPseduo
python3 scripts/run.py Python InterviewQuestions/LeakyBucketPseduo
python3 scripts/run.py 'C++' InterviewQuestions/LeakyBucketPseduo
python3 scripts/run.py Go InterviewQuestions/LeakyBucketPseduo
python3 scripts/run.py 'C#' InterviewQuestions/LeakyBucketPseduo
python3 scripts/run.py JavaScript InterviewQuestions/LeakyBucketPseduo
python3 scripts/run.py TypeScript InterviewQuestions/LeakyBucketPseduo
```

The runner isolates Java lessons during compilation. Successful output ends with
`Edge cases: passed` for the cache or `Shutdown drained: true` for the queue.

## LRU cache walkthrough

Java keeps the original interview approach: a map from keys to nodes and a doubly
linked list with dummy head/tail nodes. The original sketch's missing capacity/map
initialization and incorrect pointer updates are fixed. Removing a node updates both
neighbors. Adding at the front updates the previous first node's backward link;
it does not overwrite the tail's predecessor unless the list was empty.

1. `put(1, 10)` then `put(2, 20)` gives MRU order `[2, 1]`.
2. `get(1)` returns 10 and moves key 1 to the front: `[1, 2]`.
3. `put(3, 30)` evicts key 2 and gives `[3, 1]`.
4. `put(1, -1)` updates the existing node without eviction: `[1, 3]`.
5. A missing key returns an explicit absence value; a stored -1 remains a valid hit.

| Edition | Recency structure | Miss result |
| --- | --- | --- |
| Java | Explicit linked nodes plus HashMap | `null` |
| Python | OrderedDict | `None` |
| C++ | std::list plus unordered_map of iterators | empty optional |
| C# | LinkedList plus Dictionary of nodes | nullable int |
| Go | container/list plus map of elements | `found == false` |
| JavaScript/TypeScript | Insertion-ordered Map; touch by delete/reinsert | `undefined` |

Get/put/eviction are expected constant-time for these map/list implementations.
The copied MRU-first `keys` list is O(n). Native editions lock the complete operation,
including successful reads because reads update recency. JS/TS calls execute
synchronously on one event loop. A multi-call get-then-put sequence is not atomic.

Capacity zero is a disabled cache; negative capacity is rejected. Values and keys
are integers within the language's range; JS/TS require safe integers on insertion.
No TTL, disk storage, loader, byte-based capacity or stampede protection is included.
Useful extensions are expiration, admission policy, scan resistance and sharding.

## Leaky bucket walkthrough

This example is a **bounded FIFO queue with a paced worker**, distinct from the
admission-only token bucket and sliding window in `RateLimitingDemo`.

1. Submit before or after start. Admission checks the closed flag and waiting-queue
   capacity atomically, returning false on overflow rather than silently dropping work.
2. Start creates one worker. An empty worker waits for a signal without spinning.
3. The first queued job can run immediately. Later attempts wait for the remaining
   interval since the previous attempt's scheduled start, using a monotonic clock.
4. The worker removes one job and calls its handler outside the queue lock.
5. Ordinary handler failures increment a counter; later jobs continue in FIFO order.
6. Close rejects new submissions, drains accepted jobs, then joins/awaits the worker.
   Closing before start creates a worker to perform the drain. Repeated close is safe.

Capacity counts **waiting jobs**, excluding the one executing, so at most capacity+1
jobs are outstanding. Handler execution is serial. If a handler takes longer than
the interval, the next job may start immediately afterward; there are no accumulated
credits for catch-up bursts. Timers and threads are not real-time schedulers, and
there is no upper bound on scheduling delay.

Native editions use a thread or goroutine; JS/TS use an async task and await the
handler's returned promise. Java/C#/Python/C++ record ordinary handler exceptions;
Go records returned errors and recovered panics; JS/TS record thrown errors and
promise rejections. Failed jobs are not retried. Fatal process/runtime failures are
outside the contract, and state is not durable across restart.

The owner must close the worker (`Dispose` in C#, `close` also invoked by C++'s
destructor). **A handler must not call or await close on its own bucket.** Java,
Python, C# and C++ reject that call; Go/JS would wait for themselves. Handlers must
finish for shutdown to finish. Forced cancellation, timeouts, priority, retries,
network delivery guarantees and distributed queues are interview extensions.

## Checks

Every language's demo checks cache eviction, promotion, negative values, snapshots,
zero/one capacity, queue overflow, FIFO attempts, failure continuation, drain,
repeated close, post-close rejection and invalid configuration.

Focused tests add a randomized cache reference model, concurrent cache operations,
concurrent queue producers, idle wakeup and processing cadence. Cadence checks use
20 ms intervals and a small measurement tolerance, without asserting exact scheduling
times. Go runs with its race detector. The Node test loads the generated lesson
classes without running their demos, exercising the async lifecycle directly.

```sh
python3 scripts/validate.py
python3 scripts/test_cache_leaky.py
python3 scripts/check_cache_leaky_native.py
(cd Go && go test -race ./InterviewQuestions/LRU_CacheDemo ./InterviewQuestions/LeakyBucketPseduo)
mkdir -p /tmp/lld-cache-leaky-checks
javac -d /tmp/lld-cache-leaky-checks Java/InterviewQuestions/LRU_CacheDemo.java Java/InterviewQuestions/LeakyBucketPseduo.java scripts/java_checks/CacheLeakyChecks.java
java -cp /tmp/lld-cache-leaky-checks InterviewQuestions.CacheLeakyChecks
```
