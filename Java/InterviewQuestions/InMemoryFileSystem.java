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
Run from repository root: python3 scripts/run.py "Java" InterviewQuestions/InMemoryFileSystem
See IN_MEMORY_FILESYSTEM_GUIDE.md for prerequisites, trade-offs and tests.
*/

package InterviewQuestions;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class InMemoryFileSystem {
    static abstract class Node {
        String name;
        Directory parent;
        Node(String name, Directory parent) {
            this.name = name;
            this.parent = parent;
        }

        String path() {
            List<String> parts = new ArrayList<>();
            for (Node node = this; node.parent != null; node = node.parent) {
                parts.add(node.name);
            }
            Collections.reverse(parts);
            return "/" + String.join("/", parts);
        }
    }

    static class Directory extends Node {
        final Map<String, Node> children = new HashMap<>();
        Directory(String name, Directory parent) {
            super(name, parent);
        }
    }

    static class File extends Node {
        String content;
        File(String name, Directory parent, String content) {
            super(name, parent);
            this.content = content;
        }
    }

    static class FileSystem {
        private final Directory root = new Directory("", null);
        private Directory cwd = root;
        private static void name(String name) {
            require(name != null && name.matches("[A-Za-z0-9._-]+") && !name.equals(".") &&
                    !name.equals(".."));
        }

        private static List<String> parts(String path) {
            require(path != null && path.startsWith("/"));
            if (path.equals("/")) {
                return List.of();
            }
            List<String> parts = Arrays.asList(path.substring(1).split("/", -1));
            for (String part : parts) {
                name(part);
            }
            return parts;
        }

        private Node resolve(List<String> parts) {
            Node node = root;
            for (String part : parts) {
                require(node instanceof Directory);
                node = ((Directory)node).children.get(part);
                require(node != null);
            }
            return node;
        }

        private Directory parent(List<String> parts) {
            require(!parts.isEmpty());
            Node node = resolve(parts.subList(0, parts.size() - 1));
            require(node instanceof Directory);
            return (Directory)node;
        }

        synchronized void mkdir(String path) {
            List<String> parts = parts(path);
            Directory directory = root;
            for (String part : parts) {
                Node next = directory.children.get(part);
                if (next == null) {
                    next = new Directory(part, directory);
                    directory.children.put(part, next);
                }
                require(next instanceof Directory);
                directory = (Directory)next;
            }
        }

        synchronized void createFile(String path, String content) {
            require(content != null);
            List<String> parts = parts(path);
            Directory parent = parent(parts);
            String name = parts.get(parts.size() - 1);
            require(!parent.children.containsKey(name));
            parent.children.put(name, new File(name, parent, content));
        }

        private File file(String path) {
            Node node = resolve(parts(path));
            require(node instanceof File);
            return (File)node;
        }

        synchronized String read(String path) {
            return file(path).content;
        }

        synchronized void write(String path, String content) {
            require(content != null);
            file(path).content = content;
        }

        synchronized void append(String path, String content) {
            require(content != null);
            File file = file(path);
            file.content += content;
        }

        synchronized List<String> list(String path) {
            Node node = resolve(parts(path));
            require(node instanceof Directory);
            List<String> names = new ArrayList<>(((Directory)node).children.keySet());
            Collections.sort(names);
            return names;
        }

        synchronized void cd(String path) {
            Node node = resolve(parts(path));
            require(node instanceof Directory);
            cwd = (Directory)node;
        }

        synchronized String pwd() {
            return cwd.path();
        }

        private boolean ancestor(Node possible, Node node) {
            for (Node current = node; current != null; current = current.parent) {
                if (current == possible) {
                    return true;
                }
            }
            return false;
        }

        synchronized void move(String source, String destination) {
            Node node = resolve(parts(source));
            require(node != root);
            List<String> target = parts(destination);
            Directory parent = parent(target);
            String name = target.get(target.size() - 1);
            require(!parent.children.containsKey(name) && !ancestor(node, parent));
            // Validate before changing either parent; readers cannot observe a half-move.
            node.parent.children.remove(node.name);
            node.parent = parent;
            node.name = name;
            parent.children.put(name, node);
        }

        synchronized void rename(String path, String newName) {
            name(newName);
            Node node = resolve(parts(path));
            require(node != root);
            String parentPath = node.parent.path();
            move(path, parentPath + (parentPath.equals("/") ? "" : "/") + newName);
        }

        synchronized void delete(String path, boolean recursive) {
            Node node = resolve(parts(path));
            require(node != root && !ancestor(node, cwd));
            require(!(node instanceof Directory) || recursive ||
                    ((Directory)node).children.isEmpty());
            node.parent.children.remove(node.name); // Detached subtree becomes unreachable.
        }

        synchronized List<String> find(String name) {
            name(name);
            List<String> results = new ArrayList<>();
            Deque<Node> pending = new ArrayDeque<>();
            pending.push(root);
            while (!pending.isEmpty()) {
                Node node = pending.pop();
                if (node.name.equals(name)) {
                    results.add(node.path());
                }
                if (node instanceof Directory) {
                    pending.addAll(((Directory)node).children.values());
                }
            }
            Collections.sort(results);
            return results;
        }

        synchronized long size(String path) {
            long total = 0;
            Deque<Node> pending = new ArrayDeque<>();
            pending.push(resolve(parts(path)));
            while (!pending.isEmpty()) {
                Node node = pending.pop();
                if (node instanceof File) {
                    total += ((File)node).content.getBytes(StandardCharsets.UTF_8).length;
                } else {
                    pending.addAll(((Directory)node).children.values());
                }
            }
            return total;
        }
    }

    static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid file-system operation");
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("File-system contract failed");
        }
    }

    static void rejected(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    public static void main(String[] args) {
        FileSystem fs = new FileSystem();
        fs.mkdir("/courses/lld");
        fs.mkdir("/courses/lld");
        fs.createFile("/courses/lld/notes.txt", "Hello");
        fs.append("/courses/lld/notes.txt", " Java");
        check(fs.read("/courses/lld/notes.txt").equals("Hello Java") && fs.size("/courses") == 10);
        fs.write("/courses/lld/notes.txt", "Hello Java");
        fs.move("/courses/lld/notes.txt", "/courses/lesson.txt");
        fs.rename("/courses/lesson.txt", "guide.txt");
        fs.cd("/courses/lld");
        fs.move("/courses", "/archive");
        check(fs.pwd().equals("/archive/lld"));
        check(fs.find("guide.txt").equals(List.of("/archive/guide.txt")));
        rejected(() -> fs.move("/archive", "/archive/lld/nested"));
        rejected(() -> fs.delete("/archive", true));
        fs.cd("/");
        fs.delete("/archive/lld", false);
        fs.delete("/archive/guide.txt", false);
        check(fs.list("/archive").isEmpty());
        System.out.println("Content: Hello Java\nMoved working directory: /archive/lld\nSearch: " +
                           "/archive/guide.txt\nCycle rejected: true\nWorking-directory deletion " +
                           "blocked: true\nCleanup: empty");
    }
}
