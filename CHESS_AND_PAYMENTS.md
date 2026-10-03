# Chess and Payment Service — runnable interview lessons

These complete the two Java classroom drafts added under `InterviewQuestions`.
Java remains the reference; Python, C++, Go, C#, JavaScript and TypeScript have
standalone implementations of the same behavior. No external services are used.

## Prerequisites and commands

Run from the repository root. The shared runner needs Python 3.10+. Install only
your chosen language's toolchain: JDK 17+, Python 3.10+, C++17 compiler, Go 1.26+
(the module's requirement), .NET 8 SDK, or Node.js 22+. For TypeScript, run `npm ci`
once. The runner uses temporary build directories and leaves source folders clean.

| Language | Chess | Payment Service |
| --- | --- | --- |
| Java | `python3 scripts/run.py Java InterviewQuestions/ChessGameDemo` | `python3 scripts/run.py Java InterviewQuestions/PaymentServiceLLD` |
| Python | `python3 scripts/run.py Python InterviewQuestions/ChessGameDemo` | `python3 scripts/run.py Python InterviewQuestions/PaymentServiceLLD` |
| C++ | `python3 scripts/run.py 'C++' InterviewQuestions/ChessGameDemo` | `python3 scripts/run.py 'C++' InterviewQuestions/PaymentServiceLLD` |
| Go | `python3 scripts/run.py Go InterviewQuestions/ChessGameDemo` | `python3 scripts/run.py Go InterviewQuestions/PaymentServiceLLD` |
| C# | `python3 scripts/run.py 'C#' InterviewQuestions/ChessGameDemo` | `python3 scripts/run.py 'C#' InterviewQuestions/PaymentServiceLLD` |
| JavaScript | `python3 scripts/run.py JavaScript InterviewQuestions/ChessGameDemo` | `python3 scripts/run.py JavaScript InterviewQuestions/PaymentServiceLLD` |
| TypeScript | `python3 scripts/run.py TypeScript InterviewQuestions/ChessGameDemo` | `python3 scripts/run.py TypeScript InterviewQuestions/PaymentServiceLLD` |

If .NET is outside `PATH`, set `DOTNET=/absolute/path/to/dotnet` before the command.

The files live under `<language>/InterviewQuestions/<lesson>.<extension>`;
Go uses `Go/InterviewQuestions/<lesson>/main.go`. JavaScript is generated from
TypeScript and formatted; `npm run sync:javascript` refreshes the translations.

## Chess: separate movement, king safety, and game state

`Board` owns the squares and move rules. `ChessGame` owns the active side and
status and serializes commands. Uppercase pieces are white, lowercase are black;
`N/n` denotes a knight and `.` is empty. The 64-character position runs from a8
to h1. Public moves use algebraic squares such as `e2` and `e4`.

A move follows these steps:

1. Validate coordinates, active game, ownership and destination.
2. Check piece geometry and sliding paths. Pawns advance into empty squares,
   capture diagonally, and can advance two squares only from their starting rank
   with both squares clear.
3. Temporarily apply the move, test the mover's king, then restore the board.
   A pinned piece cannot expose its king. Kings cannot capture one another.
4. Apply a legal move, switch sides, and search **every piece's legal replies**.
   No reply plus check is checkmate; no reply without check is stalemate.

Attack detection is distinct from legal movement: pawns attack diagonally even
when the target square is empty. King attacks are geometric, preventing adjacent
kings without recursively invoking move legality. Blocking or capturing a checking
piece can save a game even when the king itself cannot move.

The demo plays Fool's Mate, then loads stalemate and bare-kings positions:

```text
Fool's mate: CHECKMATE
No legal reply: STALEMATE
Bare kings: DRAW
```

As in the original draft, **castling, en passant and promotion are excluded**.
A pawn move reaching the last rank is rejected rather than silently leaving a pawn
there. Bare kings produce `DRAW`; repetition, the fifty-move rule, other dead
positions, clocks, resignation, draw offers and move history are not implemented.
Thus terminal states apply to this teaching ruleset, not a full tournament engine.
Custom positions validate square count, symbols and exactly one king per side;
callers supply otherwise legal setups, not arbitrary FEN or a position-history proof.

Piece rules are localized in a dispatch method to make all six geometries easy to
compare. An interview extension is to replace each branch with a `Piece` strategy,
then add promotion and history without mixing them into king-safety checks.

Java uses synchronized game methods; Python uses an `RLock`; C++/Go/C# use one
lock per game. JavaScript/TypeScript commands are synchronous within one event
loop. No version claims synchronization across processes or shared worker heaps.

## Payments: preserve identity through ambiguous outcomes

`Request` holds an order, positive integer amount in **minor units of one assumed
currency (INR)**, method and gateway name. The example amount `1699900` means
₹16,999. CARD, UPI, NET_BANKING and WALLET are supported method identifiers;
no card numbers, credentials or real method-specific authorization are collected.

`PaymentService` owns an in-memory payment repository and idempotency/event indexes.
The `Gateway` contract separates orchestration from a provider adapter. Register a
fake adapter under `stripe`, `razorpay`, `paypal`, or another name to select it.
These names do not connect to those companies. `FakeGateway` simulates success,
decline, and a charge whose first response is lost.

- A new idempotency key creates a `PROCESSING` payment. The key is also its gateway
  payment ID, scoped to one service instance. Reusing a key with the same request
  returns its current snapshot; changed order, amount, method or gateway conflicts.
- A simulated `UNKNOWN` response is retried up to three times **with the same ID**.
  The fake gateway remembers charged IDs, so a lost success response cannot create
  another charge. A definitive decline stops immediately as `FAILED`.
- Exhausted ambiguity remains `PROCESSING`, not `FAILED`. A repeated client request
  returns that state without restarting attempts. A trusted webhook reconciles it.
- Full refunds require `SUCCESS`. Repeated refunds return `REFUNDED`. A gateway
  refund failure preserves `SUCCESS`, so retrying uses the same payment ID.
- Webhooks validate payment/provider association and deduplicate `(provider, event)`.
  Only `PROCESSING` transitions to a reported success/failure. Late events cannot
  revert `SUCCESS`, `FAILED`, or `REFUNDED`. Provider outcomes are assumed final;
  conflicting final events require a reconciliation policy beyond this demo.
- Returned immutable snapshots (or native value copies) prevent callers from
  mutating repository state. Monetary arithmetic never uses floating-point fractions;
  JavaScript/TypeScript additionally require safe integers.

```text
Payment: SUCCESS
Gateway charges: 1
Refund: REFUNDED
Late webhook: REFUNDED
```

The service lock covers the complete **synchronous fake** operation. This makes
same-key requests deterministic in one process, at the cost of serializing unrelated
payments. A real adapter would move network I/O outside this coarse lock, use durable
unique constraints and transactional state changes, provider-scoped idempotency,
authenticated webhooks, reconciliation, and refund records. Bounded immediate
retries here avoid sleeping in a teaching demo; real transient calls need deadlines
and backoff. Partial refunds, persistent storage, restart recovery, asynchronous
workers and real gateway signatures are intentionally outside this example.

## Checks students can run

With all toolchains installed:

```sh
npm ci
python3 scripts/validate.py
python3 scripts/check_chess_payments.py
```

The first command suite checks 25 lesson transcripts in seven languages. The second
builds native edge-case harnesses for both new lessons in all seven languages:
blocked moves, turns, invalid squares, captures, pins, check evasion, pawn attacks,
adjacent kings, excluded promotion, mate/stalemate/draw, stable state after rejection,
idempotency conflicts, bounded ambiguous retries, declines, refunds, wrong-provider
and stale events, and snapshot independence. Java, Python, C++, Go and C# additionally
exercise concurrent same-key payments; Go uses the race detector. JS/TS exercise
synchronous repeated calls, not shared-memory concurrency.
