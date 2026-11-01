# In-memory file system

The unfinished `InMemoryFileSystem.java` sketch is now a runnable lesson in Java,
Python, C++, Go, C#, JavaScript and TypeScript. The original is preserved verbatim
in `classroom-drafts/2026-11-01/InMemoryFileSystem.java.txt`.

## Requirements and entities

Support nested directories, file creation, reading, replacing and appending content,
listing, rename, move, deletion, exact-name search, working-directory navigation and
full paths. Operations must prevent duplicate sibling names and tree cycles and
remain consistent under concurrent access. UTF-8 byte-size queries illustrate
traversing a subtree.

Node holds a name and parent reference. Directory indexes children by name. File
holds content. FileSystem owns the root, current working directory and lock. Java,
Python, C++, C# and TypeScript use Node/File/Directory classes. Go uses a Node struct
whose non-nil children map denotes a directory; nil denotes a file.

The root's parent is absent, and its path is `/`. Other paths are assembled by
walking parent references. No public operation returns an internal node. Lists and
search results are detached copies, so callers cannot mutate the tree through them.
C++ uses `unique_ptr` ownership for children and non-owning parent/cwd pointers.

## Minimal runnable flow

```text
mkdir /courses/lld
create /courses/lld/notes.txt with "Hello"
append " Java"
move notes.txt to /courses/lesson.txt
rename lesson.txt to guide.txt
cd /courses/lld
move /courses to /archive
pwd -> /archive/lld
find guide.txt -> /archive/guide.txt
```

Moving a directory preserves the identities of all its descendants. The current
working directory therefore follows the move; a cached path string would become
stale. The demo then rejects a cycle-producing move and deletion of cwd's ancestor,
returns to root, deletes the leaf entries and verifies that `/archive` is empty.

## Operations and rules

| Operation | Contract |
|---|---|
| `mkdir(path)` | Recursively creates missing directories; existing directories are a no-op. A file blocking the path is an error. |
| `createFile(path, content)` | Parent must exist; any existing target is rejected. |
| `read(path)` | Returns a file's content; directories are rejected. |
| `write(path, content)` | Replaces an existing file's content. |
| `append(path, content)` | Appends atomically to an existing file. |
| `list(path)` | Returns sorted immediate child names of a directory. |
| `cd(path)` / `pwd()` | Changes the shared working-directory reference / derives its current full path. |
| `move(source, destination)` | Destination is the full new path. Parent must exist and target must not exist. Root moves and cycles are rejected. |
| `rename(path, newName)` | A move within the same parent. The argument is one name, not a path. |
| `delete(path, recursive)` | Removes a file or empty directory; nonempty directories require recursive=true. Root, cwd and cwd's ancestors are protected. |
| `find(name)` | Searches the whole tree for an exact name and returns sorted absolute paths. |
| `size(path)` | Sums UTF-8 file-content bytes beneath a node; directory metadata contributes zero. |

Names are case-sensitive ASCII letters, digits, dot, underscore and hyphen, excluding
`.` and `..`. Paths are strictly absolute. Repeated slashes, empty components and
trailing slashes (except root) are errors. Relative paths, normalization, Unicode
names and OS-specific path behavior are intentionally outside this lesson. File
content can be well-formed Unicode text; `é🙂` occupies six UTF-8 bytes.

The C++ deletion method is `remove`, because `delete` is a keyword. Python methods
use snake_case and Go/C# methods use exported/PascalCase names. The demos show the
exact calls in each language. Go reports invalid operations as errors; the other
versions throw exceptions.

## Concurrency and failure behavior

Native versions lock each complete public operation. Go uses an RWMutex, allowing
read-only operations to run concurrently. Java, Python, C++ and C# use a coarse
reentrant lock/monitor, making helpers such as rename-to-move straightforward.
JavaScript and TypeScript methods are synchronous on one event loop with no await;
this does not synchronize separate workers or processes.

A move validates both paths, destination vacancy and the ancestor chain before
changing either parent's children. A competing create cannot insert the same name
between validation and mutation. Readers cannot observe a half-completed move.
Directory creation validates the entire path before creating anything. Once the
first missing directory is created, all subsequent missing descendants are new,
so an existing file cannot cause a later partial failure along that path.

`append` holds the lock across the read/modify/write operation. A caller implementing
append through separate `read` and `write` calls could lose concurrent updates.
Locks protect single calls, not multi-call transactions. `cwd` is shared by all
callers; a real shell service would usually keep it per session.

Validation failures leave the tree unchanged. This is not a transactional database:
resource exhaustion, fatal runtime errors and process crashes have no rollback or
recovery guarantee. Directory entries, content and all state live only in memory.

## Interview extensions and edge cases

- Duplicate target: reject without overwriting content. Unchanged rename/self-move
  is also rejected as an existing target.
- Move into a descendant: walk upward from the destination parent; reject if the
  source is encountered. No cycles are introduced.
- Delete cwd or its ancestor: reject until the caller changes cwd. This avoids a
  detached working-directory reference.
- Delete a nonempty directory: require explicit recursive deletion. Root always stays.
- Search and listing: return copies; mutations to returned lists do not affect state.
- Permissions: add policy checks at the service boundary before mutation. The code
  does not implement users, permissions, timestamps, quotas, symlinks, persistence,
  open handles or real disk I/O.

Child maps provide average O(depth) lookup, excluding parsing/name hashing. Listing
sorts the children. Search and size visit the relevant nodes; search also constructs
and sorts matching paths. String append can copy the existing content. A coarse
lock favors a clear correctness argument over maximum throughput. Memory is
unbounded; production would need limits, storage and a more detailed failure model.

## Prerequisites and run commands

From repository root, install Python 3.10+ for the runner and the selected toolchain:
JDK 17+, C++17 compiler, Go 1.22+, .NET SDK 8, or Node.js 18+ (20+ recommended).
Run `npm ci` for the JavaScript/TypeScript tooling.

```sh
python3 scripts/run.py Java InterviewQuestions/InMemoryFileSystem
python3 scripts/run.py Python InterviewQuestions/InMemoryFileSystem
python3 scripts/run.py 'C++' InterviewQuestions/InMemoryFileSystem
python3 scripts/run.py Go InterviewQuestions/InMemoryFileSystem
python3 scripts/run.py 'C#' InterviewQuestions/InMemoryFileSystem
python3 scripts/run.py JavaScript InterviewQuestions/InMemoryFileSystem
python3 scripts/run.py TypeScript InterviewQuestions/InMemoryFileSystem
```

Expected output:

```text
Content: Hello Java
Moved working directory: /archive/lld
Search: /archive/guide.txt
Cycle rejected: true
Working-directory deletion blocked: true
Cleanup: empty
```

## Focused validation

```sh
python3 scripts/test_memory_filesystem.py
node scripts/test-memory-filesystem.cjs
mkdir -p /tmp/lld-filesystem-checks
javac -d /tmp/lld-filesystem-checks Java/InterviewQuestions/InMemoryFileSystem.java tests/memory-filesystem/FileSystemChecks.java
java -cp /tmp/lld-filesystem-checks InterviewQuestions.FileSystemChecks
(cd Go && go test -race ./InterviewQuestions/InMemoryFileSystem)
c++ -std=c++17 -pthread -fsanitize=address,undefined -g tests/memory-filesystem/checks.cpp -o /tmp/lld-filesystem-cpp-checks
/tmp/lld-filesystem-cpp-checks
python3 scripts/validate.py
npm run build:typescript
```

Focused checks cover invalid paths, failure atomicity, UTF-8 sizes, protected
working-directory deletion, snapshots, duplicate creates, concurrent appends and
competing moves. All seven demos exercise the common contract; deeper tests run
in Java, Python, C++, Go and JavaScript. Sanitizers/race detectors supplement tests
but do not prove correctness for every possible interleaving.

The existing `FileSystem_Node` lesson and Simple Java MVP editions are unchanged.
