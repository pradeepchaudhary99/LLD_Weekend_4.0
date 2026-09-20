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
