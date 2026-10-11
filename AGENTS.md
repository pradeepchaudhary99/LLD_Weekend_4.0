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

# Interview-question context

- Start every interview-question example, including each translation, with readable
  notes covering functional and non-functional requirements, core entities and
  responsibilities, operations and flow, design choices, assumptions, scope,
  meaningful edge cases, and the exact run command.
- Keep these notes aligned with each language's actual implementation. Clearly
  distinguish unfinished drafts and proposed features from working behavior.
- Preserve and update the notes when rewriting or regenerating an example.
