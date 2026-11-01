# In-memory file system — a tree model; never accesses the host file system.
# Functional requirements: nested mkdir; create/read/write/append files; list a
# directory; rename/move/delete nodes; find by exact name; cd/pwd; UTF-8 byte size.
# Non-functional requirements: thread-safe compound operations, fast child lookup,
# unique sibling names, valid parent links, no cycles, and detached result lists.
# Core entities: Node(name,parent,path), Directory(children indexed by name),
# File(content), FileSystem(root, shared working-directory reference and lock).
# Flow: validate every path component -> resolve parent/node -> check all structural
# preconditions -> mutate under one lock. Move updates both parents as one operation.
# Paths are absolute and case-sensitive. Root is '/'; names use ASCII letters,
# digits, '.', '_' and '-' but not '.' or '..'. Reject empty components, repeated
# slashes and trailing slash except root. No relative paths or normalization.
# mkdir creates missing ancestors and is idempotent for existing directories.
# createFile requires an existing parent and never overwrites. write replaces;
# append is atomic (unlike a caller's separate read followed by write).
# move destination is a full new path, not an existing container. Existing targets,
# root changes, and moving into one's own subtree are rejected. rename takes one
# new name. Self-moves/unchanged renames are rejected as duplicate targets.
# Delete files or empty directories normally; recursive=true removes a subtree.
# Never delete root, cwd, or an ancestor of cwd. Moving/renaming cwd or its ancestor
# is allowed: pwd is rebuilt from parent links. cwd is shared, not per-thread.
# list and find return sorted copies. find scans the whole tree for exact names.
# size sums file content encoded as UTF-8; directory metadata contributes no bytes.
# Use well-formed Unicode content. No internal node reference is exposed by the API.
# Concurrency: native implementations protect each public operation with a coarse
# lock. A sequence of calls is not a transaction. State snapshots can become stale.
# Scope: trusted callers, in-memory only, no persistence, quotas, permissions,
# symlinks, timestamps, file handles, OS path compatibility or crash recovery.
# Permissions/timestamps can be added at the service boundary; not implemented here.
# Edge cases: duplicate names, missing parents, file-as-directory, malformed paths,
# cycle moves, destination collisions, protected cwd deletion, and parallel writes.
# Lookup is O(depth) on average with hash-indexed children; find/size visit the
# subtree, and sorting/output-path construction add cost. Memory is unbounded.
# Run from repository root: python3 scripts/run.py "Python" InterviewQuestions/InMemoryFileSystem
# See IN_MEMORY_FILESYSTEM_GUIDE.md for prerequisites, trade-offs and tests.

import re
from threading import RLock


class Node:
    def __init__(self, name, parent):
        self.name, self.parent = name, parent

    def path(self):
        parts = []
        current = self
        while current.parent is not None:
            parts.append(current.name)
            current = current.parent
        return "/" + "/".join(reversed(parts))


class Directory(Node):
    def __init__(self, name, parent):
        super().__init__(name, parent)
        self.children = {}


class File(Node):
    def __init__(self, name, parent, content):
        super().__init__(name, parent)
        self.content = content


class FileSystem:
    def __init__(self):
        self._root = Directory("", None)
        self._cwd = self._root
        self._lock = RLock()

    @staticmethod
    def _name(name):
        require(
            isinstance(name, str)
            and re.fullmatch(r"[A-Za-z0-9._-]+", name)
            and name not in (".", "..")
        )

    @classmethod
    def _parts(cls, path):
        require(isinstance(path, str) and path.startswith("/"))
        if path == "/":
            return []
        parts = path[1:].split("/")
        for part in parts:
            cls._name(part)
        return parts

    def _resolve(self, parts):
        node = self._root
        for part in parts:
            require(isinstance(node, Directory) and part in node.children)
            node = node.children[part]
        return node

    def _parent(self, parts):
        require(parts)
        node = self._resolve(parts[:-1])
        require(isinstance(node, Directory))
        return node

    def mkdir(self, path):
        with self._lock:
            parts = self._parts(path)
            directory = self._root
            for part in parts:
                if part not in directory.children:
                    directory.children[part] = Directory(part, directory)
                node = directory.children[part]
                require(isinstance(node, Directory))
                directory = node

    def create_file(self, path, content):
        with self._lock:
            require(isinstance(content, str))
            parts = self._parts(path)
            parent = self._parent(parts)
            name = parts[-1]
            require(name not in parent.children)
            parent.children[name] = File(name, parent, content)

    def _file(self, path):
        node = self._resolve(self._parts(path))
        require(isinstance(node, File))
        return node

    def read(self, path):
        with self._lock:
            return self._file(path).content

    def write(self, path, content):
        with self._lock:
            require(isinstance(content, str))
            self._file(path).content = content

    def append(self, path, content):
        with self._lock:
            require(isinstance(content, str))
            self._file(path).content += content

    def list(self, path):
        with self._lock:
            node = self._resolve(self._parts(path))
            require(isinstance(node, Directory))
            return sorted(node.children)

    def cd(self, path):
        with self._lock:
            node = self._resolve(self._parts(path))
            require(isinstance(node, Directory))
            self._cwd = node

    def pwd(self):
        with self._lock:
            return self._cwd.path()

    @staticmethod
    def _ancestor(possible, node):
        while node is not None:
            if node is possible:
                return True
            node = node.parent
        return False

    def move(self, source, destination):
        with self._lock:
            node = self._resolve(self._parts(source))
            require(node is not self._root)
            target = self._parts(destination)
            parent = self._parent(target)
            name = target[-1]
            require(name not in parent.children and not self._ancestor(node, parent))
            del node.parent.children[node.name]
            node.parent, node.name = parent, name
            parent.children[name] = node

    def rename(self, path, new_name):
        with self._lock:
            self._name(new_name)
            node = self._resolve(self._parts(path))
            require(node is not self._root)
            self.move(path, node.parent.path().rstrip("/") + "/" + new_name)

    def delete(self, path, recursive=False):
        with self._lock:
            node = self._resolve(self._parts(path))
            require(node is not self._root and not self._ancestor(node, self._cwd))
            require(not isinstance(node, Directory) or recursive or not node.children)
            del node.parent.children[node.name]

    def find(self, name):
        with self._lock:
            self._name(name)
            results, pending = [], [self._root]
            while pending:
                node = pending.pop()
                if node.name == name:
                    results.append(node.path())
                if isinstance(node, Directory):
                    pending.extend(node.children.values())
            return sorted(results)

    def size(self, path):
        with self._lock:
            total, pending = 0, [self._resolve(self._parts(path))]
            while pending:
                node = pending.pop()
                if isinstance(node, File):
                    total += len(node.content.encode("utf-8"))
                else:
                    pending.extend(node.children.values())
            return total


def require(condition):
    if not condition:
        raise ValueError("Invalid file-system operation")


def check(condition):
    if not condition:
        raise AssertionError("File-system contract failed")


def rejected(operation):
    try:
        operation()
    except ValueError:
        return
    raise AssertionError("Expected rejection")


def main():
    fs = FileSystem()
    fs.mkdir("/courses/lld")
    fs.mkdir("/courses/lld")
    fs.create_file("/courses/lld/notes.txt", "Hello")
    fs.append("/courses/lld/notes.txt", " Java")
    check(fs.read("/courses/lld/notes.txt") == "Hello Java" and fs.size("/courses") == 10)
    fs.write("/courses/lld/notes.txt", "Hello Java")
    fs.move("/courses/lld/notes.txt", "/courses/lesson.txt")
    fs.rename("/courses/lesson.txt", "guide.txt")
    fs.cd("/courses/lld")
    fs.move("/courses", "/archive")
    check(fs.pwd() == "/archive/lld")
    check(fs.find("guide.txt") == ["/archive/guide.txt"])
    rejected(lambda: fs.move("/archive", "/archive/lld/nested"))
    rejected(lambda: fs.delete("/archive", True))
    fs.cd("/")
    fs.delete("/archive/lld")
    fs.delete("/archive/guide.txt")
    check(fs.list("/archive") == [])
    print(
        "Content: Hello Java\nMoved working directory: /archive/lld\nSearch: /archive/guide.txt\nCycle rejected: true\nWorking-directory deletion blocked: true\nCleanup: empty"
    )


if __name__ == "__main__":
    main()
