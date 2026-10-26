# Validation — 2026-09-13

- Pattern suite: **98/98 passed** (14 lessons × Java, Python, C++, Go, C#,
  JavaScript, TypeScript).
- Additional Python behavior suite: **12/12 passed**.
- Core OOP/SOLID/relationship smoke runs: **21/21 passed** (three lessons
  in all seven languages).
- Full TypeScript project compilation: passed.

Pattern transcripts explicitly check factory type rejection and cache reuse,
builder values, notification delivery, payment amount validation, proxy cache
hits and write invalidation, observer deduplication/removal, strategy selection,
empty server pools, State transitions, ATM completion/cancellation, file edits,
recursive sizes, cycle/duplicate-name rejection, and facade orchestration.

The behavior suite additionally checks ATM invalid-action stability and duplicate
completion; empty cached strings and newly inserted keys; self-unsubscription;
round-robin wraparound and least-connection ties; rename lookup consistency;
parent ownership; appending/overwriting content; retry success and exhaustion;
and builder snapshot independence.

The .NET 8 SDK used for validation was installed under a temporary directory,
not included in the repository. Students need their own SDK and other chosen
language toolchains as described in README.md.

## Readability pass — 2026-09-16

All seven language folders were reformatted with expanded class/method bodies,
one statement per line, consistent indentation, and spacing between declarations.
Formatter configuration is now checked in; JavaScript regeneration also formats
its output. Existing lesson behavior is preserved.

Revalidated after formatting: **98/98 pattern runs**, **21/21 core lesson runs**,
and **12/12 behavior tests** passed. The full TypeScript build, Go vet, Black
format check, and Prettier format check also passed.

## Concurrency and handler chain — 2026-09-20

Completed five previously untracked Java classroom drafts and added all six
translations: ConcurrencyFundamentals, ProducerConsumerDemo, ThreadPool,
ThreadPoolLLD, and ChainOfResponsibility. Original drafts were snapshotted before
editing. All code uses expanded, readable teaching style.

- Expanded transcript suite: **133/133 passed**, 19 lessons × seven languages.
  This includes **35/35 new lesson runs** and all 98 existing pattern runs.
- Existing Python invariant tests: **12/12 passed**.
- New Python concurrency tests: **4/4 passed** (empty/repeated close, one-worker
  failure recovery and FIFO drain, actual bounded-queue blocking, invalid inputs).
- Node worker-pool lifecycle checks: passed (empty/repeated close, one-worker
  failure recovery, 100 queued results, rejected submission and invalid sizes).
- Go concurrency tests: **3/3 passed with `-race`**; includes concurrent submission
  racing with close. `go vet ./...` passed.
- Full TypeScript project build: passed. Changed JS/TS lessons were rerun after
  the final worker-failure guard and formatting changes.
- Whitespace/error check: `git diff --check` passed.

The first full run passed 114 checks and failed all 19 C# checks because a
previous temporary .NET SDK installation had missing runtime files. A fresh
.NET SDK 8.0.425 was installed outside the repository; rerunning the complete
C# suite passed 19/19. No language was skipped and no toolchain remains missing.

The main demos check atomic counts, consumed/completed sums, task-failure counts,
shutdown rejection, invalid sizes, handler thresholds (0 through 6), a truncated
chain, and negative-level rejection. Tests do not claim to prove correctness for
all possible schedules. See CONCURRENCY.md for ownership and cancellation limits,
Node's serializable job model, and the language-specific executor equivalents.

## Read/write lock and elevator — 2026-09-26

Completed the two new Java drafts and added Python, C++, Go, C#, JavaScript,
and TypeScript translations with matching topic folders. Original Java drafts
were copied to `/tmp/lld-originals-2026-09-26` before editing.

- Expanded transcript suite: **147/147 passed** (21 lessons × seven languages),
  including **14/14 new lesson runs**.
- New Python lock/elevator checks: **4/4 passed**.
- New Node lock/elevator checks: passed (overlapping async readers, queued writer
  priority, release on rejection, routing, deduplication, strategy rotation,
  and invalid inputs).
- Java custom-lock checks: passed (overlapping readers, writer exclusion,
  waiting-writer interruption and subsequent reader progress).
- Go concurrent cache test: passed with **`-race`**; `go vet ./...` passed.
- Full TypeScript project build: passed.
- `git diff --check`: passed.

The first suite run passed 126 checks and failed all 21 C# checks because the
previous temporary .NET SDK had been removed. After reinstalling .NET SDK
8.0.425 outside the repository, the complete C# suite passed 21/21. No language
was skipped. Temporary Black and clang-format tools were also reinstalled and
applied to the new sources.

The elevator is a deterministic tick simulation, not a background motor system.
It does not implement persistent requests or direction-aware passenger boarding.
The Node lock coordinates async callbacks in one event loop, not OS threads.
See LOCK_AND_ELEVATOR.md for exact commands and teaching scope.

## Parking Lot and Game Loop — 2026-09-27

Completed the two new Java drafts and all six translations. Original drafts were
snapshotted under `/tmp/lld-originals-2026-09-27` before editing.

- Expanded transcript suite: **161/161 passed** (23 lessons × seven languages),
  including **14/14 new lesson runs**. No missing toolchains or skipped languages.
- New Python behavior suite: **5/5 passed**, including eight competing entry
  gates, fee boundaries, payment failure/exception retention, duplicate charging
  prevention, selection/input validation, and game-loop boundary cases.
- New Node parking/game-loop checks: passed.
- Go parking tests: **2/2 passed with `-race`**, including competing entry and
  exit gates, one slot allocation, one payment callback, and failed-payment
  occupancy retention. `go vet ./...` passed.
- Full TypeScript project build: passed.
- C++ parking demo rerun after the overflow-safe rounding adjustment: passed.
- Readability formatting and `git diff --check`: passed.

Parking payment is a synchronous in-memory simulation, not a real integration.
The game loop uses logical ticks, not wall-clock pacing. See
PARKING_AND_GAME_LOOP.md for exact commands and scope.

## Chess and Payment Service — 2026-10-03

Completed the two new, previously untracked Java drafts and translated each into
Python, C++, Go, C#, JavaScript and TypeScript. Original drafts were snapshotted
before editing. See `CHESS_AND_PAYMENTS.md` for exact run commands and scope.

- `python3 scripts/validate.py`: **175/175 passed** (25 lessons × seven languages).
- After C# naming cleanup, `python3 scripts/validate.py --languages 'C#'`:
  **25/25 passed** again.
- `python3 scripts/check_chess_payments.py`: **all native suites passed** in all
  seven languages. Chess fixtures cover legal/blocked movement, capture, turns,
  invalid squares, pinned pieces, blocking/capturing a checking piece, pawn attacks,
  adjacent kings, excluded promotion, mate, stalemate, draw and rejected-move stability.
- Payment checks cover conflicting keys, amount/provider validation, all four method
  identifiers, retry exhaustion without false failure, no retry on client replay,
  failed/full/duplicate refunds, duplicate and stale webhooks, wrong-provider
  rejection and snapshot independence. Concurrent same-key tests passed in Java,
  Python, C++, Go and C#; Go's race detector passed. JS/TS calls are synchronous.
- `npm run build:typescript`: passed for the complete TypeScript project.
- JavaScript generated from the new TypeScript lessons and formatted; all new
  teaching sources follow their language formatter and use expanded method bodies.

Used JDK 22, Python 3.13, the C++17 compiler, Go 1.26, Node.js 22, TypeScript 5.9.3
and .NET SDK 8.0.425. The .NET SDK was installed temporarily outside the repository;
no toolchains or build artifacts are committed. Chess follows the documented
reduced ruleset, and payment gateways are in-memory simulations, not live integrations.

## Java elevator Runnable rewrite — 2026-10-06

- `python3 scripts/validate.py --languages Java`: **25/25 passed**.
- `ElevatorRunnableChecks`: **10/10 repeated runs passed**, covering separate
  worker threads, new requests while a car's callback is running, stop deduplication,
  sweep/reversal, idle wakeup, current-floor stops, invalid inputs, round-robin
  assignment, interrupted waiting, draining on close, preserved caller interruption,
  callback failure propagation and no remaining elevator threads.
- The Java demo keeps the shared expected transcript by buffering display events.
  Other language editions were not changed or revalidated in this Java-only rewrite.

Compile and run the focused checks using the commands in `LOCK_AND_ELEVATOR.md`.

## Notifications, Splitwise and elevator translations — 2026-10-10

Completed the two newly added Java drafts (`NotificationSystemDemo` and
`SplitWiseDemo`) and translated them into Python, C++, Go, C#, JavaScript and
TypeScript. Originals were snapshotted before editing. Also translated the
October 6 Java elevator worker rewrite into all six other languages.

- `python3 scripts/validate.py`: **189/189 passed**, including the final worker
  editions and both new lessons (27 lessons × seven languages).
- `python3 scripts/check_notification_splitwise.py`: **all native suites passed**.
  Includes Python and Node lock/elevator regression checks, Java's existing
  Runnable tests, new notification/ledger checks, C++/C# worker checks, native Go
  tests, and separately compiled TypeScript checks.
- Notification checks: pending priority/FIFO ordering, per-channel retry counts,
  bounded failures, opt-out, template substitution, unknown IDs/templates, invalid
  priority, conflicting/repeated IDs, concurrent duplicate submission in threaded
  editions, and close-before-start draining. JS/TS also check literal `$&` template
  values so replacement text cannot accidentally expand substitution tokens.
- Ledger checks: equal and largest-remainder percentage rounding, zero percentages,
  exact sums, antisymmetric/group-isolated balances, unknown/duplicate participants,
  amount bounds, duplicate transaction IDs, over-settlement, membership changes,
  concurrent expense updates in threaded editions, snapshots, and full rollback
  when a later participant would exceed the bilateral balance limit.
- Elevator checks: a blocked/suspended observer on one car does not prevent another
  car from arriving; new stops preserve upward sweep then reversal. Shutdown rejects
  new work, drains and waits for workers. Go also checks observer-failure propagation.
- `go test -race -count=1` passed for all three changed Go lessons.
- `npm run build:typescript`: full project passed.

Toolchains: JDK 22, Python 3.13, C++17 compiler, Go 1.26, Node.js 22, TypeScript
5.9.3 and temporary .NET SDK 8.0.425. No missing toolchains or failed checks remain.
See `NOTIFICATIONS_AND_SPLITWISE.md` and `LOCK_AND_ELEVATOR.md` for exact commands,
concurrency models and teaching-simulation limits. No build artifacts are committed.

## 2026-10-11: interview context and food delivery

- Added requirements, entities, operations, design decisions, scope and run commands
  to all eight interview topics in seven languages (56 source files).
- Preserved the unfinished Java Splitwise and food-delivery classroom sources under
  `classroom-drafts/2026-10-11/` as non-compiling reference text. The final Java
  Splitwise source retains the previously validated complete implementation.
- Completed the scoped food-delivery MVP in all seven languages; see
  [FOOD_DELIVERY.md](FOOD_DELIVERY.md) for assumptions and exact run/check commands.
- `scripts/validate.py`: **196/196 passed** (28 lessons in seven languages).
- Food-delivery Python boundary/snapshot/concurrency suite: **3 tests passed**.
- Food-delivery Java concurrent submission/reservation checks: **passed**.
- Food-delivery Go race-detector concurrency check: **passed**.
- `scripts/check_notification_splitwise.py`: **all checks passed**, including native
  Java/C++/C# suites, Go race checks, Python, JavaScript and freshly compiled TypeScript.
- .NET was invoked using the `DOTNET` environment override for the available .NET 8
  SDK. An initial focused-suite invocation omitted that override and could not locate
  `dotnet`; rerunning with the configured SDK passed. No toolchain checks remain skipped.

## 2026-10-17: load balancer and rate limiter

- Completed both new Java classroom sketches and added Python, C++, Go, C#,
  JavaScript and TypeScript equivalents (14 runnable source files).
- All sources include requirements, entities, flow, invariants, scope and run commands.
  Original sketches are retained verbatim under `classroom-drafts/2026-10-17/`.
- `scripts/validate.py`: **210/210 passed** (30 lessons in seven languages).
- `scripts/test_balancer_rate_limit.py`: **5 tests passed**, including concurrent
  quotas/leases, frequent token checks, window boundaries, key capacity and membership.
- `InterviewQuestions.BalancerRateChecks`: **passed** concurrent quota and reservation checks.
- Go `go test -race` for both new lessons: **passed**, including concurrent checks
  and frequent-refill boundary checks.
- The prior temporary .NET SDK was unavailable; a temporary .NET 8.0.426 SDK was
  installed and supplied through `DOTNET`. All seven language checks ran successfully.
- Scope: simulated health reports, not network probe scheduling; local in-memory
  quotas, not distributed limits. Details and reproducible commands are in
  [BALANCING_AND_RATE_LIMITING.md](BALANCING_AND_RATE_LIMITING.md).

## 2026-10-18: LRU cache and queued leaky bucket

- Completed both Java sketches and six translations per lesson (14 runnable sources).
  Preserved the originals verbatim in `classroom-drafts/2026-10-18/`.
- Added requirements, entities, flow, invariants, scope and exact run commands to
  every source. See [CACHE_AND_LEAKY_BUCKET.md](CACHE_AND_LEAKY_BUCKET.md).
- `scripts/validate.py`: **224/224 passed** (32 lessons in seven languages).
- Python focused suite: **4 tests passed**, including a randomized LRU reference
  model, concurrent cache operations, queue admission, idle wakeup, failure continuation,
  cadence and drain-before-start.
- Java focused suite: **passed** cache/queue concurrency, wakeup, failure and cadence.
- Go race-detector suites for both lessons: **passed** (three focused tests).
- C++, C# and Node focused suites: **passed** cache/queue lifecycle and cadence checks.
- The two C++ demos were rerun after adding explicit successful return values for
  inclusion in the native test harness; both passed. No toolchain checks were skipped.
- Scope: count-based in-memory cache; bounded non-durable FIFO shaper. Handler callbacks
  must finish and must not close their own bucket; no forced shutdown timeout is promised.

## October 26, 2026 — task scheduler

- Full regression suite: **231/231 passed** (33 lessons × seven languages).
- New scheduler demos passed in Java, Python, C++, Go, C#, JavaScript and TypeScript.
- Java barrier/deadline checks and JavaScript async-worker/deadline checks passed.
- Four focused Python concurrency/lifecycle checks passed.
- Go concurrency checks passed with the race detector.
- Full TypeScript project compilation passed.
- .NET 8 SDK was installed temporarily under `/tmp/lld-dotnet-oct26` for validation;
  no SDK binaries or dependencies are committed. No toolchain checks were skipped.

See TASK_SCHEDULER_GUIDE.md for reproducible commands, supported behavior and
limitations. The original unfinished classroom source is archived verbatim.
The Simple Java editions were intentionally left unchanged and untranslated.
