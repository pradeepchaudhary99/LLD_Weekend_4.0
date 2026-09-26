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

The draft's incomplete background-thread loop is replaced by a **deterministic
tick simulation**. One tick advances each elevator by at most one floor;
`runUntilIdle` drains the current requests and returns. This avoids wall-clock
sleeps, missed notifications, and programs that never finish. The Java, Python,
C++, Go, and C# controller APIs serialize state changes with a lock; JS/TS methods
are synchronous and contain no `await`. State belongs to the controller: use its
request APIs rather than mutating an elevator or strategy directly.

Displays and selection strategies are trusted, quick callbacks. They must not
throw, block, or re-enter the controller; callbacks run while the controller owns
its lock. New requests from other threads wait while `runUntilIdle` is executing.
The simulation does not implement concurrent physical elevator motors.

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
node scripts/test_lock_elevator_node.js
(cd Go && go test -race ./ReadWriteLockDemo && go vet ./...)
npm run build:typescript

# Java custom-lock checks, compiled separately from unrelated lessons:
build_dir=$(mktemp -d)
javac -d "$build_dir" Java/ReadWriteLockDemo.java scripts/java_checks/ReadWriteLockChecks.java
java -cp "$build_dir" ReadWriteLockChecks
rm -r "$build_dir"
```

The full transcript suite contains 21 lessons × seven languages (147 runs).
Focused tests cover simultaneous readers, writer exclusion, release on exception,
Java waiting-writer interruption, concurrent cache access under Go's race detector,
elevator stop deduplication/reversal, invalid inputs, and strategy rotation.

After editing TypeScript, `npm run sync:javascript` regenerates the JavaScript
lessons, adds declaration spacing, and applies Prettier. All seven language
implementations live in their named lesson files; Go uses one `main.go` per lesson.
