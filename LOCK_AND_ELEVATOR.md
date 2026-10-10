# Read/write lock and elevator lessons

These runnable lessons complete the September 26 classroom drafts. They are
available in Java, Python, C++, Go, C#, JavaScript, and TypeScript.

## Prerequisites and commands

Use Python 3.10+ for the runner and your chosen language toolchain: JDK 17+,
Python 3.10+, C++17, Go 1.26+, .NET 8 SDK, or Node.js 22+. TypeScript also needs
`npm ci` from the repository root. JavaScript runs with Node alone.

```sh
python3 scripts/run.py Java ReadWriteLockDemo
python3 scripts/run.py Java InterviewQuestions/ElevatorSystemDemo
```

To run both lessons in every language (requires all toolchains):

```sh
for language in Java Python 'C++' Go 'C#' JavaScript TypeScript; do
    python3 scripts/run.py "$language" ReadWriteLockDemo
    python3 scripts/run.py "$language" InterviewQuestions/ElevatorSystemDemo
done
```

Or replace `Java` in either individual command with your chosen language above.
For an SDK outside PATH, prefix C# commands with `DOTNET=/absolute/path/to/dotnet`.
The runner now detects Java package declarations and supports the
`InterviewQuestions` topic folder. Java lessons still compile separately.

## ReadWriteLockDemo

Multiple readers may enter together; a writer needs exclusive access. Java and
Python implement a non-reentrant, writer-preferring lock. New readers wait when
a writer is queued. Waiting happens inside a loop; exception-safe cleanup always
releases an acquired lock. Java also cleans up the waiting-writer count if a
waiting thread is interrupted. These teaching locks require balanced acquisition
and release by the caller; they do not track thread ownership or support upgrades.
Writer preference can delay readers under a continuous stream of writers.

C++ uses `shared_mutex` with RAII guards; Go uses `RWMutex` and `defer`; C# uses
`ReaderWriterLockSlim` and `finally`. Their fairness policies are runtime-specific;
the lesson promises exclusion, not identical scheduling in every language.

JavaScript and TypeScript demonstrate an **async read/write lock within one Node
event loop**. A FIFO queue admits a group of readers or one writer, and callbacks
may suspend while holding a permit. This is not an OS-thread or cross-worker
lock. CPU-bound callbacks still block Node's event loop. None of these locks
supports holding a read permit while requesting a write permit.

The cache fixes the draft's `cache.get(cache)` lookup, adds actual writes, and
uses `null`/`None`/`optional`/a found flag/`undefined` for missing keys. `-1` remains
a valid stored value. Four writers populate distinct keys; the demo waits for
completion and checks:

```text
Cache sum: 60
Missing: true
Stored negative: -1
Updated: 7
```

## InterviewQuestions/ElevatorSystemDemo

The completed lesson has elevators, nearest and round-robin selection strategies,
hall requests, internal requests, and display arrival notifications. Nearest
selection breaks ties by elevator ID. Stops are deduplicated. Travel continues
in the current direction while stops remain ahead, then reverses. An idle car
prefers stops above its floor, then below. A current-floor stop is served without
moving. Invalid floors, elevator IDs, building sizes, and hall directions fail
explicitly; the top floor cannot request UP and the ground floor cannot request
DOWN.

### Java: one Runnable per elevator

`Elevator implements Runnable`. The controller creates one named `Thread` per car,
and `run()` owns waiting, stop selection, one-floor movement, reversal and arrival
notifications. The controller only assigns requests and manages worker lifecycles.

- `start()` starts the workers once. For convenient classroom use, `runUntilIdle()`
  calls `start()` automatically. Requests can be queued before the first start.
- Each car protects its own floor, direction and pending `TreeSet` with its monitor.
  An idle car calls `wait()` in a loop; `addStop()` calls `notifyAll()`. It waits
  without spinning, and producers may submit while cars are running.
- Requests for the same **pending** floor coalesce. A request after that stop has
  already been served is new work. Stop direction and reversal rules are unchanged.
- `runUntilIdle()` waits for empty queues **and completed arrival callbacks**. It
  releases the controller lock while waiting, then rechecks all cars under the
  admission lock. New requests may be submitted after it returns; stop producers
  first if you need a final drain. The waiting caller can be interrupted.
- Use `try (ElevatorSystem system = ...)` so `close()` rejects new requests, drains
  accepted stops, wakes idle cars and joins every worker. Even a close before start
  drains queued work. Repeated close is safe; an interrupted closing caller gets
  its interrupt status restored after cleanup.
- Observer callbacks execute on the elevator's worker, outside its state lock.
  They must be thread-safe and finish promptly. One slow car does not block another
  car, but an indefinitely blocked callback prevents that car from draining.
  Calling `runUntilIdle()` or `close()` from a worker is rejected to avoid self-wait.
  Runtime callback failures and worker interruption are surfaced to callers waiting
  for completion and to `close()`; no automatic recovery is attempted.

`Display` buffers events and prints them after a drain, ordered by elevator ID while
preserving each car's arrival sequence. This makes the sample transcript stable;
actual arrivals across different workers are concurrent. There is no artificial
travel delay. Nearest selection reads synchronized live floor snapshots, so selection
while cars move is best-effort rather than a globally frozen position comparison.
Selection strategies remain quick, trusted callbacks under the controller lock.

### Other language worker editions — October 10 update

Python uses one `Thread` and `Condition` per elevator; C++ uses `std::thread`,
mutexes and condition variables; Go uses one goroutine and `sync.Cond` per car;
C# uses a `Thread` and monitor per car. These workers now own movement and waiting,
matching the Java design. Callbacks run outside the car's state lock. Requests
remain admissible while a car is processing another stop. Each controller starts
workers once, drains accepted work on close/dispose, joins/waits for every car,
and surfaces observer failures through idle waits and explicit close.

JavaScript/TypeScript use **one asynchronous task per car on a single Node event
loop**, yielding between floors. Idle cars have no running task until a new request
wakes them. `runUntilIdle()` and `close()` now return promises and must be awaited.
Observers may return promises, allowing another car to progress while one observer
awaits. Synchronous CPU-heavy or blocking callbacks still block the entire event
loop; these are not worker threads or parallel motors.

All displays buffer events and print stable per-car sequences after a drain. Callback
implementations must be thread-safe where applicable and finish promptly. Lifecycle
methods belong to the owner: callbacks must not wait for, close or destroy their own
system. Java/Python/C# explicitly reject worker self-waits; other editions rely on
this contract. C++ destructors clean up without throwing, so use explicit
`runUntilIdle()`/`close()` to observe failures. External worker cancellation and hard
shutdown deadlines are outside the translations' scope.

Hall direction is validated but does not affect assignment or boarding: nearest
selection considers distance, not passenger direction or elevator capacity.
Arrival notifications demonstrate Observer-style decoupling through one display
callback per elevator, rather than a dynamic subscriber registry. Requests are
in memory: crash durability, persistence/replay, real doors, capacity limits,
safety/emergency handling, and power optimization are future extensions, not
implemented features.

The demo exercises ascending stops 3 and 5, descending stops 4 and 1, a duplicate
request for 5, switching strategies, both cars serving ground-floor requests,
repeated idle execution, and invalid floor/direction rejection.

## Validation

```sh
python3 scripts/validate.py
python3 scripts/test_lock_elevator.py
python3 scripts/check_notification_splitwise.py
node scripts/test_lock_elevator_node.js
(cd Go && go test -race ./ReadWriteLockDemo && go vet ./...)
npm run build:typescript

# Java Runnable checks, compiled separately from unrelated lessons:
build_dir=$(mktemp -d)
javac -d "$build_dir" Java/InterviewQuestions/ElevatorSystemDemo.java scripts/java_checks/ElevatorRunnableChecks.java
java -cp "$build_dir" InterviewQuestions.ElevatorRunnableChecks
rm -r "$build_dir"

# Java custom-lock checks:
build_dir=$(mktemp -d)
javac -d "$build_dir" Java/ReadWriteLockDemo.java scripts/java_checks/ReadWriteLockChecks.java
java -cp "$build_dir" ReadWriteLockChecks
rm -r "$build_dir"
```

The full transcript suite contains 27 lessons × seven languages (189 runs).
Focused tests cover simultaneous readers, writer exclusion, release on exception,
Java waiting-writer interruption, concurrent cache access under Go's race detector,
elevator stop deduplication/reversal, invalid inputs, and strategy rotation.
Java Runnable checks also cover independent workers, requests during travel, idle
wakeup, interrupted waiting, callback failures, graceful draining and thread cleanup.

After editing TypeScript, `npm run sync:javascript` regenerates the JavaScript
lessons, adds declaration spacing, and applies Prettier. All seven language
implementations live in their named lesson files; Go uses one `main.go` per lesson.
