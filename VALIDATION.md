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
