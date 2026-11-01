/*
In-memory file system — a tree model; never accesses the host file system.
Functional requirements: nested mkdir; create/read/write/append files; list a
directory; rename/move/delete nodes; find by exact name; cd/pwd; UTF-8 byte size.
Non-functional requirements: thread-safe compound operations, fast child lookup,
unique sibling names, valid parent links, no cycles, and detached result lists.
Core entities: Node(name,parent,path), Directory(children indexed by name),
File(content), FileSystem(root, shared working-directory reference and lock).
Flow: validate every path component -> resolve parent/node -> check all structural
preconditions -> mutate under one lock. Move updates both parents as one operation.
Paths are absolute and case-sensitive. Root is '/'; names use ASCII letters,
digits, '.', '_' and '-' but not '.' or '..'. Reject empty components, repeated
slashes and trailing slash except root. No relative paths or normalization.
mkdir creates missing ancestors and is idempotent for existing directories.
createFile requires an existing parent and never overwrites. write replaces;
append is atomic (unlike a caller's separate read followed by write).
move destination is a full new path, not an existing container. Existing targets,
root changes, and moving into one's own subtree are rejected. rename takes one
new name. Self-moves/unchanged renames are rejected as duplicate targets.
Delete files or empty directories normally; recursive=true removes a subtree.
Never delete root, cwd, or an ancestor of cwd. Moving/renaming cwd or its ancestor
is allowed: pwd is rebuilt from parent links. cwd is shared, not per-thread.
list and find return sorted copies. find scans the whole tree for exact names.
size sums file content encoded as UTF-8; directory metadata contributes no bytes.
Use well-formed Unicode content. No internal node reference is exposed by the API.
Concurrency: native implementations protect each public operation with a coarse
lock. A sequence of calls is not a transaction. State snapshots can become stale.
Scope: trusted callers, in-memory only, no persistence, quotas, permissions,
symlinks, timestamps, file handles, OS path compatibility or crash recovery.
Permissions/timestamps can be added at the service boundary; not implemented here.
Edge cases: duplicate names, missing parents, file-as-directory, malformed paths,
cycle moves, destination collisions, protected cwd deletion, and parallel writes.
Lookup is O(depth) on average with hash-indexed children; find/size visit the
subtree, and sorting/output-path construction add cost. Memory is unbounded.
JS/TS operations are synchronous on one event loop, without await; this does
not synchronize separate workers/processes. Byte counts use Node Buffer UTF-8.
Run from repository root: python3 scripts/run.py "TypeScript" InterviewQuestions/InMemoryFileSystem
Generated JavaScript: python3 scripts/run.py JavaScript InterviewQuestions/InMemoryFileSystem
See IN_MEMORY_FILESYSTEM_GUIDE.md for prerequisites, trade-offs and tests.
*/

export {};
abstract class Node {
    constructor(
        public name: string,
        public parent: Directory | undefined,
    ) {}
    path(): string {
        const parts: string[] = [];
        for (let node: Node = this; node.parent; node = node.parent) parts.push(node.name);
        return "/" + parts.reverse().join("/");
    }
}
class Directory extends Node {
    children = new Map<string, Node>();
}
class File extends Node {
    constructor(
        name: string,
        parent: Directory,
        public content: string,
    ) {
        super(name, parent);
    }
}
class FileSystem {
    private root = new Directory("", undefined);
    private cwd = this.root;
    private name(name: string): void {
        requireCondition(
            name.length > 0 && !/[^A-Za-z0-9._-]/.test(name) && name !== "." && name !== "..",
        );
    }
    private parts(path: string): string[] {
        requireCondition(path.startsWith("/"));
        if (path === "/") return [];
        const parts = path.slice(1).split("/");
        parts.forEach((part) => this.name(part));
        return parts;
    }
    private resolve(parts: string[]): Node {
        let node: Node = this.root;
        for (const part of parts) {
            requireCondition(node instanceof Directory);
            const child: Node | undefined = node.children.get(part);
            requireCondition(child);
            node = child;
        }
        return node;
    }
    private parent(parts: string[]): Directory {
        requireCondition(parts.length > 0);
        const node = this.resolve(parts.slice(0, -1));
        requireCondition(node instanceof Directory);
        return node;
    }
    mkdir(path: string): void {
        const parts = this.parts(path);
        let directory = this.root;
        for (const part of parts) {
            let next = directory.children.get(part);
            if (!next) {
                next = new Directory(part, directory);
                directory.children.set(part, next);
            }
            requireCondition(next instanceof Directory);
            directory = next;
        }
    }
    createFile(path: string, content: string): void {
        requireCondition(typeof content === "string");
        const parts = this.parts(path),
            parent = this.parent(parts),
            name = parts[parts.length - 1];
        requireCondition(!parent.children.has(name));
        parent.children.set(name, new File(name, parent, content));
    }
    private file(path: string): File {
        const node = this.resolve(this.parts(path));
        requireCondition(node instanceof File);
        return node;
    }
    read(path: string): string {
        return this.file(path).content;
    }
    write(path: string, content: string): void {
        requireCondition(typeof content === "string");
        this.file(path).content = content;
    }
    append(path: string, content: string): void {
        requireCondition(typeof content === "string");
        this.file(path).content += content;
    }
    list(path: string): string[] {
        const node = this.resolve(this.parts(path));
        requireCondition(node instanceof Directory);
        return [...node.children.keys()].sort();
    }
    cd(path: string): void {
        const node = this.resolve(this.parts(path));
        requireCondition(node instanceof Directory);
        this.cwd = node;
    }
    pwd(): string {
        return this.cwd.path();
    }
    private ancestor(possible: Node, node: Node): boolean {
        for (let current: Node | undefined = node; current; current = current.parent) {
            if (current === possible) return true;
        }
        return false;
    }
    move(source: string, destination: string): void {
        const node = this.resolve(this.parts(source));
        requireCondition(node !== this.root);
        const target = this.parts(destination),
            parent = this.parent(target),
            name = target[target.length - 1];
        requireCondition(!parent.children.has(name) && !this.ancestor(node, parent));
        node.parent!.children.delete(node.name);
        node.parent = parent;
        node.name = name;
        parent.children.set(name, node);
    }
    rename(path: string, newName: string): void {
        this.name(newName);
        const node = this.resolve(this.parts(path));
        requireCondition(node !== this.root);
        const parentPath = node.parent!.path();
        this.move(path, parentPath + (parentPath === "/" ? "" : "/") + newName);
    }
    delete(path: string, recursive = false): void {
        const node = this.resolve(this.parts(path));
        requireCondition(node !== this.root && !this.ancestor(node, this.cwd));
        requireCondition(!(node instanceof Directory) || recursive || node.children.size === 0);
        node.parent!.children.delete(node.name);
    }
    find(name: string): string[] {
        this.name(name);
        const results: string[] = [],
            pending: Node[] = [this.root];
        while (pending.length) {
            const node = pending.pop()!;
            if (node.name === name) results.push(node.path());
            if (node instanceof Directory) {
                for (const child of node.children.values()) pending.push(child);
            }
        }
        return results.sort();
    }
    size(path: string): number {
        let total = 0;
        const pending = [this.resolve(this.parts(path))];
        while (pending.length) {
            const node = pending.pop()!;
            if (node instanceof File) total += Buffer.byteLength(node.content, "utf8");
            else if (node instanceof Directory) {
                for (const child of node.children.values()) pending.push(child);
            }
        }
        return total;
    }
}
function requireCondition(condition: unknown): asserts condition {
    if (!condition) throw new Error("Invalid file-system operation");
}
function check(condition: boolean): void {
    if (!condition) throw new Error("File-system contract failed");
}
function rejected(operation: () => void): void {
    try {
        operation();
    } catch {
        return;
    }
    throw new Error("Expected rejection");
}
function main(): void {
    const fs = new FileSystem();
    fs.mkdir("/courses/lld");
    fs.mkdir("/courses/lld");
    fs.createFile("/courses/lld/notes.txt", "Hello");
    fs.append("/courses/lld/notes.txt", " Java");
    check(fs.read("/courses/lld/notes.txt") === "Hello Java" && fs.size("/courses") === 10);
    fs.write("/courses/lld/notes.txt", "Hello Java");
    fs.move("/courses/lld/notes.txt", "/courses/lesson.txt");
    fs.rename("/courses/lesson.txt", "guide.txt");
    fs.cd("/courses/lld");
    fs.move("/courses", "/archive");
    check(fs.pwd() === "/archive/lld");
    check(JSON.stringify(fs.find("guide.txt")) === '["/archive/guide.txt"]');
    rejected(() => fs.move("/archive", "/archive/lld/nested"));
    rejected(() => fs.delete("/archive", true));
    fs.cd("/");
    fs.delete("/archive/lld");
    fs.delete("/archive/guide.txt");
    check(fs.list("/archive").length === 0);
    console.log(
        "Content: Hello Java\nMoved working directory: /archive/lld\nSearch: /archive/guide.txt\nCycle rejected: true\nWorking-directory deletion blocked: true\nCleanup: empty",
    );
}
main();
