# Parking Lot and Game Loop lessons

Completed from the September 27 classroom drafts. Java is the reference; Python,
C++, Go, C#, JavaScript, and TypeScript provide the same demonstrated behavior.
Each implementation lives in its language's `InterviewQuestions` folder.

## Run

The runner requires Python 3.10+. Install your chosen language toolchain: JDK 17+,
Python 3.10+, a C++17 compiler, Go 1.26+, .NET 8 SDK, or Node.js 22+. TypeScript
also requires `npm ci` in the repository root. JavaScript only needs Node.

```sh
python3 scripts/run.py Java InterviewQuestions/ParkingLotDemo
python3 scripts/run.py Java InterviewQuestions/GameLoopPattern
```

Replace `Java` with `Python`, `'C++'`, `Go`, `'C#'`, `JavaScript`, or `TypeScript`.
To run both lessons in all languages:

```sh
for language in Java Python 'C++' Go 'C#' JavaScript TypeScript; do
    python3 scripts/run.py "$language" InterviewQuestions/ParkingLotDemo
    python3 scripts/run.py "$language" InterviewQuestions/GameLoopPattern
done
```

If .NET is outside PATH, prefix C# commands with `DOTNET=/absolute/path/to/dotnet`.
Java sources have a package declaration; the runner compiles and invokes the
qualified class name automatically. Compile Java lessons separately.

## Parking Lot

Slots carry a level ID, slot ID, vehicle type, configured distance to an exit,
and occupancy. The level ID groups slots into floors without needing another
container class. Vehicles are bikes, cars, or trucks. Compatibility is deliberately
**exact type matching**: a car does not consume a truck slot. Plates are nonblank,
case-sensitive identifiers; real-world plate normalization is outside this lesson.

Entry gates share one manager. Under one manager operation, the code rejects a
duplicate plate, selects a compatible free slot, reserves it, and issues a ticket.
First-fit scans configured order. Nearest-exit picks the lowest configured distance;
ties preserve configured order. These strategy implementations can be replaced.
Distance is a supplied scalar, not a route calculation to a particular exit gate.

Exit gates look up the active ticket, calculate a fee, invoke the simulated
payment, and only then release the slot and close the ticket. Failed payments
leave the ticket and occupancy intact. A closed or unknown ticket fails before
the payment callback, preventing a second charge through the same manager.
Different gates coordinate through the same manager instance.

Fees are integer rupees: ₹50 per started hour, minimum one hour. Times are integer
minutes on one logical clock. Examples: 0, 59, and 60 minutes cost ₹50; 61 and 120
minutes cost ₹100. An exit before entry is rejected before payment. Tickets use
process-local increasing IDs, not globally unique or durable identifiers.

Java uses synchronized manager methods, Python a lock, C++ a mutex, Go a mutex,
and C# a monitor. JS/TS uses synchronous event-loop methods with no `await`.
The Node version is not shared across worker threads or server processes.

Payment and selection callbacks are trusted, synchronous, in-memory teaching
implementations. They must not re-enter the manager, block on I/O, or mutate its
state. Callbacks execute while the native-language manager owns its lock. No
real money is charged. A real payment integration needs durable payment states,
idempotency keys and recovery from ambiguous provider results; a boolean callback
does not supply those guarantees. The demo does not provide cross-process locks,
crash recovery, persistent tickets, or safety hardware controls.

Configuration belongs to one manager: slot identities should be unique, distances
nonnegative, and slot objects should not be shared with another manager. Access
occupancy through manager operations, not through returned ticket references.
Strategies and fee implementations must remain valid for the lifetime of the lot.

The transcript exercises two levels and two entry gates, strategy replacement,
duplicate entry, full compatible capacity, time validation, failed payment with
continued occupancy, successful payment, duplicate exit, slot reuse, and bike
and truck allocation.

## Game Loop

The empty draft now demonstrates the sequence **process input → update → render**.
Each iteration represents one fixed simulation tick. RIGHT sets velocity to one
position unit per tick; PAUSE stops movement while ticks/rendering continue;
RESUME restores updates; QUIT stops before another update or render.

Input and rendering are injected functions, so tests use scripted commands and
captured frames. The demo moves to positions 1 and 2, pauses at 2, resumes to 3,
then quits after four rendered ticks. A nonnegative tick budget guarantees each
`run` call is finite even without a QUIT command. A zero budget does nothing;
a later call can continue a budget-limited loop. Once quit, the same game instance
does not restart.

This is a deterministic console simulation, not a graphical real-time engine.
There is no wall-clock pacing, input polling thread, frame interpolation, or
catch-up accumulator. The caller supplies valid commands; keep callbacks fast
and non-reentrant. Counters and positions are intended for short classroom runs.

## Validation and editing

```sh
python3 scripts/validate.py
python3 scripts/test_parking_game.py
node scripts/test_parking_game_node.js
(cd Go && go test -race ./InterviewQuestions/ParkingLotDemo && go vet ./...)
npm run build:typescript
```

The full suite checks 23 lessons × seven languages (161 runs). New focused tests
exercise competing gates, payment failure and exception retention, duplicate
charging prevention, hourly rounding boundaries, invalid requests, nearest-slot
selection, and game-loop zero budget, continuation, pause, resume, and quit.

Edit the TypeScript source and use `npm run sync:javascript` to regenerate its
JavaScript counterpart with readable declaration spacing and Prettier formatting.
Follow `AGENTS.md` for formatting all languages.
