# Readable teaching code

These lessons are for students learning LLD. Prioritize readability in every language.

- Put class, interface, and method bodies on multiple lines, even for short methods.
- Put each executable statement on its own line. Do not pack statements with semicolons.
- Separate classes and methods with blank lines; keep related statements together.
- Use descriptive names and short comments where they explain a design decision.
- Follow .clang-format for Java/C++/C#, Black for Python, Prettier for JS/TS,
  and scripts/format-go.go followed by gofmt for Go.
- When regenerating JavaScript from TypeScript, run the formatting step too.
- Preserve example behavior and validate changed lessons with scripts/validate.py.
