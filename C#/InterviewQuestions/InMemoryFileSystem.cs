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
Run from repository root: python3 scripts/run.py "C#" InterviewQuestions/InMemoryFileSystem
See IN_MEMORY_FILESYSTEM_GUIDE.md for prerequisites, trade-offs and tests.
*/

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Diagnostics.CodeAnalysis;
namespace LLDWeekend4.InterviewQuestions;

public class InMemoryFileSystem
{
    abstract class Node
    {
        public string Name;
        public Directory? Parent;
        protected Node(string name, Directory? parent)
        {
            Name = name;
            Parent = parent;
        }

        public string Path()
        {
            List<string> parts = new();
            for (Node node = this; node.Parent != null; node = node.Parent)
            {
                parts.Add(node.Name);
            }
            parts.Reverse();
            return "/" + string.Join("/", parts);
        }
    }

    class Directory : Node
    {
        public readonly Dictionary<string, Node> Children = new();
        public Directory(string name, Directory? parent) : base(name, parent)
        {
        }
    }

    class File : Node
    {
        public string Content;
        public File(string name, Directory parent, string content) : base(name, parent)
        {
            Content = content;
        }
    }

    public class FileSystem
    {
        private readonly object gate = new();
        private readonly Directory root = new("", null);
        private Directory cwd;
        public FileSystem()
        {
            cwd = root;
        }

        private static void ValidateName(string name)
        {
            Require(name != null && Regex.IsMatch(name, @"\A[A-Za-z0-9._-]+\z") && name != "." &&
                    name != "..");
        }

        private static List<string> Parts(string path)
        {
            Require(path != null && path.StartsWith("/", StringComparison.Ordinal));
            if (path == "/")
            {
                return new();
            }
            List<string> parts = path[1..].Split('/').ToList();
            foreach (string part in parts)
            {
                ValidateName(part);
            }
            return parts;
        }

        private Node Resolve(List<string> parts)
        {
            Node node = root;
            foreach (string part in parts)
            {
                Require(node is Directory);
                Node? child = ((Directory)node).Children.GetValueOrDefault(part);
                Require(child != null);
                node = child;
            }
            return node;
        }

        private Directory Parent(List<string> parts)
        {
            Require(parts.Count > 0);
            Node node = Resolve(parts.Take(parts.Count - 1).ToList());
            Require(node is Directory);
            return (Directory)node;
        }

        public void Mkdir(string path)
        {
            lock (gate)
            {
                List<string> parts = Parts(path);
                Directory directory = root;
                foreach (string part in parts)
                {
                    Node? next = directory.Children.GetValueOrDefault(part);
                    if (next == null)
                    {
                        next = new Directory(part, directory);
                        directory.Children.Add(part, next);
                    }
                    Require(next is Directory);
                    directory = (Directory)next;
                }
            }
        }

        public void CreateFile(string path, string content)
        {
            lock (gate)
            {
                Require(content != null);
                List<string> parts = Parts(path);
                Directory parent = Parent(parts);
                string name = parts[^1];
                Require(!parent.Children.ContainsKey(name));
                parent.Children.Add(name, new File(name, parent, content));
            }
        }

        private File GetFile(string path)
        {
            Node node = Resolve(Parts(path));
            Require(node is File);
            return (File)node;
        }

        public string Read(string path)
        {
            lock (gate)
            {
                return GetFile(path).Content;
            }
        }

        public void Write(string path, string content)
        {
            lock (gate)
            {
                Require(content != null);
                GetFile(path).Content = content;
            }
        }

        public void Append(string path, string content)
        {
            lock (gate)
            {
                Require(content != null);
                GetFile(path).Content += content;
            }
        }

        public List<string> List(string path)
        {
            lock (gate)
            {
                Node node = Resolve(Parts(path));
                Require(node is Directory);
                return ((Directory)node)
                    .Children.Keys.OrderBy(name => name, StringComparer.Ordinal)
                    .ToList();
            }
        }

        public void Cd(string path)
        {
            lock (gate)
            {
                Node node = Resolve(Parts(path));
                Require(node is Directory);
                cwd = (Directory)node;
            }
        }

        public string Pwd()
        {
            lock (gate)
            {
                return cwd.Path();
            }
        }

        private bool Ancestor(Node possible, Node node)
        {
            for (Node? current = node; current != null; current = current.Parent)
            {
                if (current == possible)
                {
                    return true;
                }
            }
            return false;
        }

        public void Move(string source, string destination)
        {
            lock (gate)
            {
                Node node = Resolve(Parts(source));
                Require(node != root);
                List<string> target = Parts(destination);
                Directory parent = Parent(target);
                string name = target[^1];
                Require(!parent.Children.ContainsKey(name) && !Ancestor(node, parent));
                node.Parent!.Children.Remove(node.Name);
                node.Parent = parent;
                node.Name = name;
                parent.Children.Add(name, node);
            }
        }

        public void Rename(string path, string newName)
        {
            lock (gate)
            {
                ValidateName(newName);
                Node node = Resolve(Parts(path));
                Require(node != root);
                string parentPath = node.Parent!.Path();
                Move(path, parentPath + (parentPath == "/" ? "" : "/") + newName);
            }
        }

        public void Delete(string path, bool recursive = false)
        {
            lock (gate)
            {
                Node node = Resolve(Parts(path));
                Require(node != root && !Ancestor(node, cwd));
                Require(node is not Directory || recursive ||
                        ((Directory)node).Children.Count == 0);
                node.Parent!.Children.Remove(node.Name);
            }
        }

        public List<string> Find(string name)
        {
            lock (gate)
            {
                ValidateName(name);
                List<string> results = new();
                Stack<Node> pending = new();
                pending.Push(root);
                while (pending.Count > 0)
                {
                    Node node = pending.Pop();
                    if (node.Name == name)
                    {
                        results.Add(node.Path());
                    }
                    if (node is Directory directory)
                    {
                        foreach (Node child in directory.Children.Values)
                        {
                            pending.Push(child);
                        }
                    }
                }
                results.Sort(StringComparer.Ordinal);
                return results;
            }
        }

        public long Size(string path)
        {
            lock (gate)
            {
                long total = 0;
                Stack<Node> pending = new();
                pending.Push(Resolve(Parts(path)));
                while (pending.Count > 0)
                {
                    Node node = pending.Pop();
                    if (node is File file)
                    {
                        total += Encoding.UTF8.GetByteCount(file.Content);
                    }
                    else
                    {
                        foreach (Node child in ((Directory)node).Children.Values)
                        {
                            pending.Push(child);
                        }
                    }
                }
                return total;
            }
        }
    }

    static void Require([DoesNotReturnIf(false)] bool condition)
    {
        if (!condition)
        {
            throw new ArgumentException("Invalid file-system operation");
        }
    }

    static void Check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("File-system contract failed");
        }
    }

    static void Rejected(Action action)
    {
        try
        {
            action();
        }
        catch (ArgumentException)
        {
            return;
        }
        throw new Exception("Expected rejection");
    }

    public static void Main(string[] args)
    {
        FileSystem fs = new();
        fs.Mkdir("/courses/lld");
        fs.Mkdir("/courses/lld");
        fs.CreateFile("/courses/lld/notes.txt", "Hello");
        fs.Append("/courses/lld/notes.txt", " Java");
        Check(fs.Read("/courses/lld/notes.txt") == "Hello Java" && fs.Size("/courses") == 10);
        fs.Write("/courses/lld/notes.txt", "Hello Java");
        fs.Move("/courses/lld/notes.txt", "/courses/lesson.txt");
        fs.Rename("/courses/lesson.txt", "guide.txt");
        fs.Cd("/courses/lld");
        fs.Move("/courses", "/archive");
        Check(fs.Pwd() == "/archive/lld");
        Check(fs.Find("guide.txt").SequenceEqual(new[] { "/archive/guide.txt" }));
        Rejected(() => fs.Move("/archive", "/archive/lld/nested"));
        Rejected(() => fs.Delete("/archive", true));
        fs.Cd("/");
        fs.Delete("/archive/lld");
        fs.Delete("/archive/guide.txt");
        Check(fs.List("/archive").Count == 0);
        Console.WriteLine("Content: Hello Java\nMoved working directory: /archive/lld\nSearch: " +
                          "/archive/guide.txt\nCycle rejected: true\nWorking-directory deletion " +
                          "blocked: true\nCleanup: empty");
    }
}
