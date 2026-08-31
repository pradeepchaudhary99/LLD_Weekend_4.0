# LLD Weekend 4.0

Low Level Design practice from the Weekend 4.0 cohort. Each concept is written
once in Java and mirrored into six other languages.

## Languages

| Folder        | Run                                                   |
|---------------|-------------------------------------------------------|
| `Java/`       | `cd Java && javac *.java && java Main`                 |
| `Python/`     | `python3 Python/Main.py`                               |
| `C++/`        | `g++ -std=c++17 C++/Main.cpp -o main && ./main`        |
| `Go/`         | `cd Go && go run .`                                    |
| `C#/`         | `cd 'C#' && dotnet run`                                |
| `JavaScript/` | `node JavaScript/Main.js`                              |
| `TypeScript/` | `cd TypeScript && npx tsc && node dist/Main.js`        |

## Topics

- **Main** – core OOP: class/object, encapsulation, abstraction, inheritance,
  polymorphism, interface, composition, association, aggregation, static, final.
- **SolidDemo** – the five SOLID principles, each with a "bad" and a "good" form.
- **Relationship** – association vs. aggregation vs. composition.

The Java sources are the reference; the other languages are idiomatic
translations (pure virtual structs / `abc.ABC` / interfaces for abstraction,
smart pointers or GC-native ownership for composition, etc.).
