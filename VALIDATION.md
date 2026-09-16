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
