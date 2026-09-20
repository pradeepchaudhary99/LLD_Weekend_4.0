# Concurrency and Chain of Responsibility lessons

These five lessons complete the September classroom drafts. Each program exits
on its own. Java is the reference; every lesson also has Python, C++, Go, C#,
JavaScript, and TypeScript entry points.

## Run

Run from the repository root. The shared runner needs Python 3.10+. Install your
chosen language's toolchain: JDK 17+, Python 3.10+, a C++17 compiler, Go 1.26+,
.NET 8 SDK, or Node.js 22+. TypeScript additionally needs `npm ci` (the lockfile
includes TypeScript and Node type definitions). JavaScript needs only Node.

```sh
python3 scripts/run.py Java ConcurrencyFundamentals
python3 scripts/run.py Java ProducerConsumerDemo
python3 scripts/run.py Java ThreadPool
python3 scripts/run.py Java ThreadPoolLLD
python3 scripts/run.py Java DesignPatterns/ChainOfResponsibility
```

Replace `Java` with `Python`, `'C++'`, `Go`, `'C#'`, `JavaScript`, or `TypeScript`.
For example:

```sh
python3 scripts/run.py Python ThreadPool
python3 scripts/run.py 'C++' ThreadPool
python3 scripts/run.py Go ThreadPool
python3 scripts/run.py 'C#' ThreadPool
python3 scripts/run.py JavaScript ThreadPool
python3 scripts/run.py TypeScript ThreadPool
```

If necessary, prefix C# commands with `DOTNET=/absolute/path/to/dotnet`.
Compile Java examples individually; other classroom files reuse helper names.

## What to read and observe

| Lesson | Behavior and teaching point |
| --- | --- |
| `ConcurrencyFundamentals` | Four workers each increment 1,000 times. Prints `Counter: 4000`. Mutual exclusion or atomic operations protect updates; joining/waiting separately ensures completion before reading. |
| `ProducerConsumerDemo` | Capacity one forces producer backpressure. Ten values, 1–10, are consumed exactly once in normal execution; prints `Consumed sum: 55`. Rejects zero capacity. |
| `ThreadPool` | Three workers drain accepted tasks, record one intentional task failure, and finish with sum 55. Repeated close is harmless, submission after close fails, and zero workers is invalid. |
| `ThreadPoolLLD` | Runs ten jobs with at most three workers/concurrent operations, collects results, prints `Executor sum: 55`, and releases owned workers. |
| `DesignPatterns/ChainOfResponsibility` | Levels 0–1 → Warning, 2–3 → Error, 4–5 → Fatal, 6+ → Unhandled. Also runs a truncated chain and rejects a negative level. |

A shared counter is on the heap; each thread has its own stack. Java `count++`
is a read, modify, write sequence. `volatile` provides visibility but does not
make that sequence atomic. A synchronized increment protects the sequence.
Starting threads does not wait for them; `join()` is still required. The draft's
invalid top-level thread construction and busy-spin elevator fragment are
replaced by the finite worker demonstration. There is no intentionally racy
execution whose output students must hope to reproduce.

Java's bounded buffer waits inside `while` checks because waking up does not
promise the queue condition still holds. It returns the item before doing
consumer work so the queue's monitor is not held during processing. Fixed item
counts or channel/collection completion let consumers stop instead of waiting
forever.

The custom pool separates admission, task execution, and shutdown. Shutdown
stops admission, drains accepted work, then waits for workers. A task exception
is recorded without ending its worker. These are teaching pools: jobs must
finish, lifecycle calls belong to the external owner, and jobs must not wait
for work they submit to the same pool. There is no forced cancellation, worker
replacement, deadline scheduler, or production resource-exhaustion handling.
Java/Python/C++/C#/Node pool queues are unbounded; Go's queue holds 16 tasks and
applies backpressure. Only the producer–consumer buffer has capacity one.

## Language differences and implementation files

| Language | Implementation | Relevant difference |
| --- | --- | --- |
| Java | Each named `.java` file | `synchronized`/`wait`/`notifyAll`, a `BlockingQueue` custom pool, and `ExecutorService` with futures. |
| Python | `Python/concurrency_lessons.py` | `Lock`, `Queue`, `Thread`, and `ThreadPoolExecutor`. Threads teach synchronization; CPython's GIL does not promise CPU parallel speedup. |
| C++ | `C++/concurrency_lessons.hpp` | Atomics, condition variables, RAII worker ownership. C++17 has no standard fixed executor, so `ThreadPoolLLD` layers packaged tasks/futures on the custom pool. |
| Go | `Go/concurrency/lessons.go` | Goroutines are runtime-scheduled, not one OS thread each. Channels carry jobs/results; WaitGroup and atomics synchronize. There is no Java-style ExecutorService. |
| C# | `C#/ConcurrencyLessons.cs` | `Interlocked`, `BlockingCollection`, explicit custom threads, and TPL `Parallel.ForEach` limited to three concurrent operations. TPL owns its shared threads. |
| TypeScript | `TypeScript/concurrency_lessons.ts` | Node worker threads use shared memory/Atomics for the counter and a one-slot producer–consumer buffer. The one-slot implementation supports exactly one producer and one consumer. |
| JavaScript | `JavaScript/concurrency_lessons.js` | Readable CommonJS output of the TypeScript lesson, using the same worker-thread behavior. |

Node workers exchange serializable numeric jobs because arbitrary functions and
closures cannot be sent to another worker. Negative input intentionally fails a
job; the worker reports the failure and remains available. Node has no built-in
ExecutorService, so its result-oriented lesson uses promises with the custom
pool. This is real worker-thread execution; `Promise.all` alone would not create
parallel CPU workers. Worker source strings use JavaScript because Node executes
them directly. A worker runtime crash rejects pending work and closes the pool.

Named entry points remain beside the existing root lessons, except Go uses
`Go/<lesson>/main.go` so every lesson can have its own `main`. The runner handles
that layout. Chain of Responsibility is self-contained in each language's
`DesignPatterns` folder; Go uses a handler interface and composition.

## Validate and regenerate

```sh
npm ci
python3 scripts/validate.py
python3 scripts/test_behaviors.py
python3 scripts/test_concurrency.py
node scripts/test_concurrency_node.js
(cd Go && go test -race ./concurrency && go vet ./...)
npm run build:typescript
```

The main suite compiles/runs 19 lessons in all seven languages (133 checks),
compares explicit output, and fails on missing compilers, errors, or timeouts.
Focused tests additionally check empty/repeated shutdown, one worker recovering
from failure and draining 100 queued jobs, bounded-buffer backpressure, invalid
inputs, and Go submission racing with shutdown under the race detector.

After changing TypeScript:

```sh
npm run sync:javascript
```

The command regenerates both patterns and new concurrency examples and applies
Prettier. Follow the repository's `AGENTS.md` formatting rules for all languages.
