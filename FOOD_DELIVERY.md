# Food delivery: runnable interview lesson

The Java classroom outline is now a runnable lesson in Java, Python, C++, Go,
C#, JavaScript and TypeScript. Every source begins with functional requirements,
non-functional requirements, core entities, flow, invariants, scope and run commands.
The original food-delivery and Splitwise classroom drafts are archived verbatim in
[classroom-drafts/2026-10-11](classroom-drafts/2026-10-11/).
The Java Splitwise entry point uses the previously validated complete implementation;
its new interview notes remain in place.

## Prerequisites and commands

Run from the repository root. Install Python 3.10+, Java 17+, a C++17 compiler,
Go 1.26+ (matching `Go/go.mod`), Node.js 18+ and .NET 8 SDK for the languages you want to use.
Run `npm ci` for the repository's pinned TypeScript compiler.
If .NET is outside PATH, set `DOTNET` to the full path of its executable.

```sh
python3 scripts/run.py Java InterviewQuestions/Zomato_Food_Delivery
python3 scripts/run.py Python InterviewQuestions/Zomato_Food_Delivery
python3 scripts/run.py 'C++' InterviewQuestions/Zomato_Food_Delivery
python3 scripts/run.py Go InterviewQuestions/Zomato_Food_Delivery
python3 scripts/run.py 'C#' InterviewQuestions/Zomato_Food_Delivery
python3 scripts/run.py JavaScript InterviewQuestions/Zomato_Food_Delivery
python3 scripts/run.py TypeScript InterviewQuestions/Zomato_Food_Delivery
```

Each demo checks its own failure paths and prints:

```text
Delivered: DELIVERED
Cancelled: CANCELLED
Rejected: REJECTED
Failed payment: PAYMENT_FAILED
Edge cases: passed
```

## Scope and walkthrough

This MVP represents customers and restaurants by IDs. A request is a one-line cart
with a quantity, delivery address and restaurant ID. Two fixed menus and one rider
make contention easy to demonstrate. Amounts are integer minor currency units;
`12000` means 120.00 in the chosen currency. Catalog prices are fixed and quantities
are limited to 1–100, so every multiplication fits the integer types used here.
There is no separate editable cart repository or production pricing engine yet.

1. Browse a copied menu and submit an order ID with immutable/copied request data.
2. Validate before charging. Identical repeated submissions return the current
   status without charging; changed input with the same ID is rejected. A declined
   charge is retained as `PAYMENT_FAILED`, so retries need a new order ID.
3. Restaurant operations move `PLACED → ACCEPTED → PREPARING → READY`.
4. Assignment atomically reserves the rider and moves `READY → ASSIGNED`.
   With no available rider the order remains ready and the caller can retry.
5. Pickup and delivery move `ASSIGNED → PICKED_UP → DELIVERED`; delivery frees
   the rider. State changes cannot skip steps or reverse a terminal result.
6. Cancellation is allowed before pickup. Restaurant rejection is allowed only
   while placed. Both require a successful full refund before committing the new
   status or freeing a reserved rider. A failed refund is retryable. Repeating an
   already successful cancellation/rejection has no additional payment effect.

Transition events are a copied in-memory audit list, representing the input to a
notification consumer. They do not send SMS or push notifications. Authentication,
customer ownership checks and restaurant/rider authorization belong at the API
boundary and are not implemented here.

## Concurrency and production discussion

Java, Python, C++, C# and Go serialize service operations with a monitor or mutex.
JavaScript/TypeScript operations are synchronous without `await`, so they execute
atomically relative to other tasks on one event loop. This is not coordination
between processes or worker threads.

The gateway is a deterministic, immediate fake owned by one service. Configure its
success/failure flags only while that service is idle. Holding a service lock during
these fake calls keeps the lesson small. A real provider needs a different design:
persist payment intent, call outside the lock with a stable provider idempotency key,
reconcile ambiguous results, and atomically commit status and an outbox event.
This example does not claim crash-safe or distributed exactly-once charging.

Useful next interview extensions: multiple cart lines, item availability and restaurant
closure, pricing Strategy, rider selection Strategy, rider rejection/reassignment,
timeouts, partial refunds, durable repositories, bounded audit history and an outbox.
The original draft's additional entities are preserved as design discussion material.

## Validation

All seven demos assert duplicate/conflicting IDs, invalid carts and transitions,
rider contention, refund failure/retry, cancellation idempotency, cancellation after
pickup, rejected payments, unknown orders and detached event snapshots.
Additional Python tests cover quantity boundaries, menu snapshots and rider reuse.
Java/Python/Go tests exercise simultaneous duplicate submissions and rider reservation.

```sh
python3 scripts/validate.py
python3 scripts/test_food_delivery.py
(cd Go && go test -race ./InterviewQuestions/Zomato_Food_Delivery)
mkdir -p /tmp/lld-food-checks
javac -d /tmp/lld-food-checks Java/InterviewQuestions/Zomato_Food_Delivery.java scripts/java_checks/FoodDeliveryChecks.java
java -cp /tmp/lld-food-checks InterviewQuestions.FoodDeliveryChecks
```

The runner compiles each Java lesson in isolation because independent classroom
examples may reuse class names. Do not compile every Java source in one invocation.
