# Notification System and Splitwise

These complete the two October 10 Java classroom drafts. Each lesson runs
independently in Java, Python, C++, Go, C#, JavaScript and TypeScript. The notification
providers and expense settlements are local simulations: no accounts or network
services are needed.

## Prerequisites and exact commands

Run from the repository root. The runner needs Python 3.10+. For your chosen
language, install JDK 17+, Python 3.10+, a C++17 compiler, Go 1.26+ (the module
requirement), .NET 8 SDK, or Node.js 22+. TypeScript additionally needs `npm ci`.
If .NET is outside PATH, prefix its commands with `DOTNET=/absolute/path/to/dotnet`.

| Language | Notification System | Splitwise |
| --- | --- | --- |
| Java | `python3 scripts/run.py Java InterviewQuestions/NotificationSystemDemo` | `python3 scripts/run.py Java InterviewQuestions/SplitWiseDemo` |
| Python | `python3 scripts/run.py Python InterviewQuestions/NotificationSystemDemo` | `python3 scripts/run.py Python InterviewQuestions/SplitWiseDemo` |
| C++ | `python3 scripts/run.py 'C++' InterviewQuestions/NotificationSystemDemo` | `python3 scripts/run.py 'C++' InterviewQuestions/SplitWiseDemo` |
| Go | `python3 scripts/run.py Go InterviewQuestions/NotificationSystemDemo` | `python3 scripts/run.py Go InterviewQuestions/SplitWiseDemo` |
| C# | `python3 scripts/run.py 'C#' InterviewQuestions/NotificationSystemDemo` | `python3 scripts/run.py 'C#' InterviewQuestions/SplitWiseDemo` |
| JavaScript | `python3 scripts/run.py JavaScript InterviewQuestions/NotificationSystemDemo` | `python3 scripts/run.py JavaScript InterviewQuestions/SplitWiseDemo` |
| TypeScript | `python3 scripts/run.py TypeScript InterviewQuestions/NotificationSystemDemo` | `python3 scripts/run.py TypeScript InterviewQuestions/SplitWiseDemo` |

Files follow `<language>/InterviewQuestions/<lesson>.<extension>`; Go uses
`Go/InterviewQuestions/<lesson>/main.go`. JavaScript is generated from TypeScript
and formatted through `npm run sync:javascript`. The runner builds in temporary
directories so compiled outputs do not clutter the teaching folders.

## Notification System

`NotificationService` owns templates, recipient preferences, the idempotency index,
per-channel delivery records, and a priority queue. A request contains an ID, user,
template, name and priority. The renderer replaces the literal `{name}` placeholder;
it is deliberately a small string template, not an HTML or scripting engine.
Templates and preferences are copied at construction and remain fixed for that
service instance. An unknown user/template or unregistered channel is rejected
before accepting work. An empty channel list opts out and produces `SKIPPED`.

`NotificationChannel` is the provider boundary. EMAIL, SMS and PUSH register fake
adapters using this contract. Adding a channel means registering another adapter
and adding its key to preferences. No mutable global singleton or live Twilio,
email or push credentials are required.

Submission validates and queues one delivery per selected channel. It does not
wait for sending. `start()` activates the worker; `awaitIdle()` starts it if needed
and waits for queued and active work. Queue requests before the first start when
you want to demonstrate priority ordering deterministically:

| Priority | Value | Scheduling |
| --- | --- | --- |
| High | 0 | First among pending deliveries |
| Normal | 1 | After pending high-priority work |
| Low | 2 | After pending high/normal work |

Equal-priority deliveries preserve submission order, including preference-list
order within a request. Priority does not preempt a provider call already running.
Each delivery gets at most **three total attempts**. A successful channel is never
requeued just because another channel failed. The demo's SMS adapter fails once
before succeeding; its PUSH adapter fails all three attempts.

A repeated ID with identical fields returns without creating more deliveries.
A repeated ID with changed fields is an idempotency conflict. Aggregate status is
`QUEUED` while any channel is queued/processing, then `FAILED` if any channel failed,
otherwise `SENT`. No selected channels means `SKIPPED`. Attempt counts and sent-order
snapshots are available for inspection.

```text
Sent: high/EMAIL,high/SMS,low/EMAIL,low/SMS
High: SENT
SMS attempts: 2
Push: FAILED
Opt-out: SKIPPED
```

Java/Python/C++/C# use one background worker thread per service; Go uses one
worker goroutine. State changes are protected, and provider calls occur outside
the service lock. JavaScript/TypeScript use an asynchronous task on one Node event
loop; their lifecycle methods return promises. In all editions, close/dispose
rejects new work and drains accepted work, even if the worker was not started yet.
Use the shown context manager, try-with-resources, `using`, RAII, `defer`, or
`try/finally` lifecycle pattern so the program exits cleanly.

Adapters must finish their calls and report delivery failure as `false` (or a
rejected promise in JS/TS). Native exception-based editions also convert ordinary
provider exceptions to failed attempts. Go adapters must not panic. Providers must
not call blocking lifecycle methods on their own service. There are no per-channel
worker pools, network timeouts, durable queues, restart recovery, queue size limits,
backoff timers or fairness guarantees under endless high-priority submissions.

Idempotency is **in-memory submission deduplication**, not exactly-once external
delivery. A real provider could accept a message and lose the response; production
adapters need provider idempotency/reconciliation, durable delivery records and
bounded backoff. This lesson's fake failures occur before acceptance.

## Splitwise

`SplitWise` manages registered user IDs, groups, members, group-scoped transaction
history and bilateral balances. `Group` owns its member set and ledger. Java uses
`SplitStrategy` implementations for equal/exact/percentage allocation; other editions
use a localized split function with the same rules.

All amounts are integer minor units in one assumed currency (for example, paise).
Expense amounts must be 1 through 1,000,000,000 minor units; a group has at most
100 members/participants. Bilateral balances cannot exceed 1,000,000,000,000 in
absolute value. These explicit teaching limits keep intermediate arithmetic exact,
including in JavaScript's safe-integer range. IDs use letters, digits, `_` and `-`.

- **Equal:** divide by participant count; distribute leftover minor units to the
  first participants in the supplied order. Splitting 100 three ways yields 34,33,33.
- **Exact:** supply nonnegative minor-unit shares summing exactly to the total.
- **Percentage:** use integer basis points summing to 10,000. For example 50%,25%,25%
  is 5000,2500,2500. Allocate each floor amount, then give remaining units to the
  largest fractional remainders, breaking ties by participant order. Splitting 7
  in these percentages yields 3,2,2. A zero percentage receives no rounding unit.

Participants must be distinct group members. The payer must be a member but need
not be in the participant list. Every participant's share creates a debt to the
payer except the payer's own share. Opposite debts cancel in the same pair; there
is no cross-group or multilateral debt simplification. `balance(group, A, B)` is
positive when A owes B and negative when B owes A.

All validation and balance-limit checks finish on a copied ledger before committing.
A bad split, duplicate transaction ID, unknown member, excessive settlement or
balance overflow leaves balances and history unchanged. Expense IDs and settlement
IDs share one namespace **within each group**. A user can leave only after all
bilateral balances with that group are zero; history remains available afterward.
Settlement records payment in the ledger but does not transfer money externally.

Each accepted transaction appends a readable history entry containing its ID,
payer/debtor, total and participant shares (or settlement recipient). The service
returns copied/immutable snapshots. User IDs and descriptive expense IDs stand in
for a full user-profile and expense-description UI.

```text
Equal shares: 34,33,33
B owes A: 13
A owes C: 18
After settlement: 0
History entries: 4
```

Java/Python/C++/Go/C# serialize ledger transactions with a service lock.
JavaScript/TypeScript methods are synchronous and atomic within one event loop.
There is no database, authentication, shared process coordination, foreign currency,
interest, expense editing/deletion or money transfer integration.

## Elevator translations and validation

The previously Java-only per-elevator worker design now has corresponding Python,
C++, Go and C# workers and JS/TS asynchronous tasks. See `LOCK_AND_ELEVATOR.md` for
lifecycle details, limitations and exact run commands.

With every toolchain available:

```sh
npm ci
python3 scripts/validate.py
python3 scripts/check_notification_splitwise.py
npm run build:typescript
```

The transcript suite covers **27 lessons × seven languages = 189 runs**. The focused
suite exercises all three updated lessons in every language: priority/FIFO, bounded
retries, failed and opted-out recipients, template rendering, idempotency conflicts,
close-before-start draining, rounding, exact-sum validation, duplicate/invalid members,
settlement and membership rules, history snapshots, balance-limit rollback, and
independent elevator progress while another car's observer is suspended. Threaded
editions also test concurrent submissions/expense updates, and Go runs with `-race`.
