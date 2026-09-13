# LLD Weekend 4.0

Runnable Low Level Design lessons in **Java**, with translations in **Python,
C++, Go, C#, JavaScript, and TypeScript**. Java is the classroom reference.
Everything runs locally: no payment account, Redis instance, or other server is required.

## Start here

Run commands from this repository's root. Install Python 3.10+ for the runner,
and the toolchain for your chosen language:

| Language | Prerequisite | Run the ATM State lesson |
| --- | --- | --- |
| Java | JDK 17+ (`java`, `javac`) | `python3 scripts/run.py Java DesignPatterns/ATMMachineStateDesign` |
| Python | Python 3.10+ | `python3 scripts/run.py Python DesignPatterns/ATMMachineStateDesign` |
| C++ | C++17 compiler (`c++`) | `python3 scripts/run.py 'C++' DesignPatterns/ATMMachineStateDesign` |
| Go | Go 1.26+ (existing module requirement) | `python3 scripts/run.py Go DesignPatterns/ATMMachineStateDesign` |
| C# | .NET 8 SDK (`dotnet`) | `python3 scripts/run.py 'C#' DesignPatterns/ATMMachineStateDesign` |
| JavaScript | Node.js 22+ | `python3 scripts/run.py JavaScript DesignPatterns/ATMMachineStateDesign` |
| TypeScript | Node.js 22+, then `npm ci` | `python3 scripts/run.py TypeScript DesignPatterns/ATMMachineStateDesign` |

The runner compiles one lesson into a temporary directory, runs it, and cleans
up. **Compile Java lessons separately:** different lessons deliberately reuse
helper names such as `Application`, `Student`, and `Notification`.
`javac Java/*.java` is therefore not the correct command for this collection.

If the .NET SDK is outside your PATH, set `DOTNET=/absolute/path/to/dotnet` before
the runner command. The repository does not include any SDK binaries.

## Choose a lesson

Replace `DesignPatterns/ATMMachineStateDesign` above with any path below.

| Lesson | What to observe |
| --- | --- |
| `Main` | Core OOP, encapsulation, polymorphism, associations, ownership, static members |
| `SolidDemo` | Five SOLID principles; add a Diwali discount without changing the good calculator |
| `Relationship` | Teacher/student association, course aggregation, directory/file composition |
| `DesignPatterns/SimpleFactoryDesignPattern` | Select notification types, reuse cached stateless products, reject unknown types |
| `DesignPatterns/FactoryMethodDesignPattern` | Product creation through interchangeable notification factories |
| `DesignPatterns/Abstract_FactoryDesign` | A matching family of UI buttons, modals, and screens |
| `DesignPatterns/BuilderDesignPattern` | Fluent builder copies its configured values into a student |
| `DesignPatterns/Singleton` | Two accesses return the same configuration instance |
| `DesignPatterns/DecoratorDesignPattern` | Trim a message, then wrap delivery with up to three attempts |
| `DesignPatterns/FileSystem_Node` | Recursive sizes, content edits, rename lookup, ownership and cycle checks |
| `DesignPatterns/AdapterDesignPattern` | Switch the application's payment interface to a third-party adapter |
| `DesignPatterns/FacadePatternDemo` | Start three home-theater subsystems with one call |
| `DesignPatterns/ProxyDesignPattern` | Cache reads, invalidate on writes, handle a missing key |
| `DesignPatterns/ObserverDesignPattern` | Deduplicate subscriptions, ignore unchanged prices, unsubscribe; elevator displays |
| `DesignPatterns/StrategyDesign` | Switch round-robin to least-connections and reject an empty server pool |
| `DesignPatterns/StateDesignPattern` | Play/pause behavior delegated to state objects |
| `DesignPatterns/ATMMachineStateDesign` | No-card, has-card, and dispensing states; valid and invalid transitions |

Example:

```sh
python3 scripts/run.py Java DesignPatterns/StrategyDesign
```

```text
r1 -> A
r2 -> B
r3 -> B
No servers available
```

The first two requests use round-robin. Completing `r1` decreases A's active
connection count; switching strategies sends `r3` to B, which has fewer active
connections. Ties choose the first server.

## Where to read the code

Java lessons include their implementations and `main` in the named file.
Translations keep a named entry-point file for each lesson, with implementations
collected in one clearly named source file per language:

| Language | Pattern implementations | Entry points |
| --- | --- | --- |
| Python | [patterns.py](Python/DesignPatterns/patterns.py) | `Python/DesignPatterns/<lesson>.py` |
| C++ | [patterns.hpp](C++/DesignPatterns/patterns.hpp) | `C++/DesignPatterns/<lesson>.cpp` |
| Go | [patterns.go](Go/DesignPatterns/patterns/patterns.go) | `Go/DesignPatterns/<lesson>/main.go` |
| C# | [Patterns.cs](C%23/DesignPatterns/Patterns.cs) | `C#/DesignPatterns/<lesson>.cs` |
| JavaScript | [patterns.js](JavaScript/DesignPatterns/patterns.js) | `JavaScript/DesignPatterns/<lesson>.js` |
| TypeScript | [patterns.ts](TypeScript/DesignPatterns/patterns.ts) | `TypeScript/DesignPatterns/<lesson>.ts` |

Search for the lesson name in the implementation file to find its demo, then
follow the named classes above it. Shared implementations avoid duplicate helper
types in languages whose examples compile as a package or project. The existing
root OOP/SOLID/relationship translations remain in their individual files.

The JavaScript pattern files are checked-in CommonJS output from the TypeScript
lessons. They run with Node alone. To update them after editing TypeScript:

```sh
npm ci
npm run sync:javascript
npm run build:typescript
```

`exte` is a reserved classroom scratch file, not an additional design-pattern
lesson; it is excluded from the pattern validation suite.

## Teaching assumptions

- These are small, single-threaded examples. Notification factory caches, the
  proxy, observers, and load balancer do not implement concurrent access control.
- The proxy's backing database is an in-memory stand-in for Redis. All writes in
  the demo go through the proxy; external writes would require another invalidation
  mechanism. Missing values are distinct from empty strings and are not cached.
- The ATM simulates one fixed cash amount. `completeDispense`/`Complete` represents
  the cash hardware callback. Repeated completion cannot dispense twice. PINs,
  balances, cash inventory, and persistence are outside this State-pattern lesson.
- Retry means at most three total attempts and stops at the first success. This
  demonstrates composition, not safe retries of real financial operations. The
  success demo does not artificially fail; behavior tests exercise transient and
  terminal failures.
- File sizes and edit offsets use native string units: UTF-16 units in Java,
  C#, and JS/TS; Unicode code points in Python; bytes in C++ and Go. Demos use
  ASCII so behavior matches. This is a model tree, not access to the real disk.
- Tree nodes have one parent; adding an ancestor, duplicate name, or already-owned
  node is rejected. Renaming updates the parent lookup. Public teaching fields
  should be changed through the provided methods to maintain these invariants.
- Python uses a module-level configuration object as its singleton; other
  languages use their static/lazy initialization mechanisms. Factory products
  are stateless. The parameterized UI factories in translations produce a
  coherent family of products.
- The deliberately bad SOLID examples remain as contrasts; the normal entry
  points run the good examples without crashing.

## Validate

Install all desired toolchains and run `npm ci` first.

```sh
# 14 lessons × 7 languages, checked against explicit expected transcripts:
python3 scripts/validate.py

# Or select only installed toolchains:
python3 scripts/validate.py --languages Java Python 'C++'

# Additional state, cache, ownership, builder, observer, and retry invariants:
python3 scripts/test_behaviors.py
```

The validator fails on a compile/runtime error or output mismatch. It does not
silently skip missing compilers. Use `--report /tmp/lld-validation.json` to save
structured results. See [VALIDATION.md](VALIDATION.md) for this change's results.
