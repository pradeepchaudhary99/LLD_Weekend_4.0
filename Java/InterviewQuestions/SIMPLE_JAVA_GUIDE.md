# Simple Java interview examples

Start here for the minimum viable design. The existing interview examples are
unchanged; each `Simple...java` file is an independent, runnable alternative.
These versions are Java-only for review before translating to other languages.

Each source starts with MVP requirements, entities, flow, concurrency, omitted
features, edge cases and its exact run command. All examples require Java 17+.
They use in-memory state and simulated providers, with no external services.

## Suggested reading order

| New file | MVP | Concurrency lesson | Intentionally omitted |
| --- | --- | --- | --- |
| [SimpleParkingLot.java](SimpleParkingLot.java) | Park, leave, reuse a space | Lock finding **and** reserving a space together | Fees, tickets, floors, vehicle categories |
| [SimpleLRUCache.java](SimpleLRUCache.java) | Get, put, evict least recently used | A cache read changes recency and needs the lock | Manual linked-list implementation, TTL |
| [SimpleRateLimiter.java](SimpleRateLimiter.java) | Per-user fixed-window quota | Check and increment under one lock | Token/sliding algorithms, distributed quota, key cleanup |
| [SimpleLoadBalancer.java](SimpleLoadBalancer.java) | Round robin over healthy servers | Protect health and shared cursor | Other strategies, removal, real health probes |
| [SimplePaymentService.java](SimplePaymentService.java) | Simulated pay, decline, full refund | Concurrent duplicates must charge only once | Real bank, webhooks, timeouts, durable idempotency |
| [SimpleSplitwise.java](SimpleSplitwise.java) | Equal splits and net-balance settlement | Commit an entire expense atomically | Groups, history, percentage/exact splits, pairwise debts |
| [SimpleFoodDelivery.java](SimpleFoodDelivery.java) | Order, pay, prepare, dispatch, deliver | Reserve rider and update order together | Real payments, addresses, paid cancellation/refunds |
| [SimpleElevator.java](SimpleElevator.java) | FIFO floor requests; one Runnable per elevator | Queue hands work to one state-owning thread | Dispatch optimization, doors, travel timing |
| [SimpleNotifications.java](SimpleNotifications.java) | Async simulated email/SMS | Executor queues tasks; Future reports completion | Retry, templates, priorities, preferences |
| [SimpleGameLoop.java](SimpleGameLoop.java) | Input → update → render; pause and quit | Only the loop thread changes game state | Frame timing, UI, physics |
| [SimpleLeakyBucket.java](SimpleLeakyBucket.java) | Bounded FIFO worker with delay | Atomic admission/close; handler outside lock | Durable queue, retry, timeout, exact start-to-start pacing |
| [SimpleChess.java](SimpleChess.java) | Ordinary moves, king safety, mate/stalemate | Protect board and turn as one unit | Castling, en passant, underpromotion, special draws |

Chess is the longest because legal movement and king safety require more rules.
Read it after the smaller examples. It uses one class for the board/game rather
than introducing a separate class hierarchy for every piece.

## How to read an example

1. Read the header: what must work, and what is outside this version?
2. Find the class that owns the mutable state.
3. Read its public operations and identify the steps that must happen together.
4. Read `main` for a small, concrete scenario.
5. Compare with the existing detailed example only after the MVP is clear.

The demos contain the teaching flow. `SimpleExamplesChecks.java` is separate
verification code, so students do not have to read test utilities first.

## Four concurrency ideas used here

**One shared object, one lock.** A `synchronized` instance method takes that object's
monitor. Two callers using the same object cannot run its synchronized operations
at the same time. Different parking lots/elevators have different locks. This
protects one JVM's state; it does not coordinate multiple servers.

**Keep the complete decision inside the lock.** Checking that a parking space is
free and later assigning it in a separate operation is unsafe. Another thread could
claim it between those steps. The same issue appears in quota checks, payment-ID
checks and rider assignment.

**Give one thread ownership of changing state.** The elevator worker owns its floor.
The game-loop thread owns the position and pause flag. Producers send commands
through `BlockingQueue`; they do not edit these fields themselves. `take()` waits
for input without a busy loop. Create only one loop thread for each game/elevator.

**Finish accepted work before shutdown.** The elevator's STOP message follows earlier
requests. Notifications use executor shutdown. The leaky bucket closes admission
and lets its worker drain. `join()` and executor termination waits happen outside
locks needed by workers. Owners close workers after producers finish; callbacks
must finish and must not close their own worker.

## A few important MVP decisions

- **LRU:** Java's access-ordered `LinkedHashMap` teaches behavior with less pointer
  code. Read the original `LRU_CacheDemo` when learning the manual linked-list design.
  `null` means missing, so a stored `-1` remains a valid value.
- **Rate limiter:** each user's window begins with their first request, and resets
  on the first request after expiry. A fixed window can admit a burst across its
  boundary. Pass elapsed monotonic milliseconds; the demo injects time to avoid sleeps.
  User entries are not evicted, so this is a small teaching example.
- **Payments:** the fake bank responds immediately and declines amounts above
  100000 minor units purely to demonstrate failure. The local lock is not a recipe
  for holding a lock during a real network payment. Refund only changes local state.
- **Splitwise:** amounts use integer minor units. Leftover units go to participants
  in list order. Balances are net totals: any debtor can settle with any creditor.
  This deliberately does not preserve the original pairwise debt graph. Expense IDs
  and retries are not deduplicated. A copied ledger prevents partial overflow updates.
- **Food delivery:** one restaurant/customer context, fixed menu, one item per order
  and one rider. Payment is simulated; only unpaid orders can be cancelled.
- **Workers:** elevator, game and notification queues are unbounded for clarity;
  backpressure is an extension. The leaky bucket explicitly demonstrates a bounded
  queue. Its scheduled worker waits a fixed delay **after** each attempt, and polls
  on idle ticks. This is simpler than maintaining precise start-to-start cadence.
- **Chess:** kings are never captured. A move is simulated and restored before a
  legal move is committed. Pawns automatically promote to queens. Omitted special
  moves/draw rules mean this is not a complete tournament chess engine.

## Run one example

From `/Users/pradeepkumar/LLD_Weekend_4_0`, use the existing runner:

```sh
python3 scripts/run.py Java InterviewQuestions/SimpleParkingLot
python3 scripts/run.py Java InterviewQuestions/SimpleElevator
python3 scripts/run.py Java InterviewQuestions/SimpleChess
```

Replace the last name with any file stem in the table. Every file also includes its
exact command in its header. No changes to the existing runner are needed.

## Compile all simple versions and check them

```sh
mkdir -p /tmp/lld-simple-build
javac -d /tmp/lld-simple-build Java/InterviewQuestions/Simple*.java
java -cp /tmp/lld-simple-build InterviewQuestions.SimpleExamplesChecks
```

The check program verifies behavior and concurrent scenarios: one winner for one
parking space, no duplicate cache keys, balanced routing, quota limits, one charge
for duplicate payment submissions, atomic expense updates, one rider reservation,
worker draining, bounded queue admission, handler failure continuation and legal
chess moves. The main game-loop demo shows pause/resume/quit behavior.

To check the existing Java lessons independently:

```sh
python3 scripts/validate.py --languages Java
```

During this change, all twelve new demos were also compiled/run through the existing
validator's `check()` function with their expected output, without modifying the
existing validator or any language folder besides Java. The original Java source
files were compared byte-for-byte with their pre-change hashes.
