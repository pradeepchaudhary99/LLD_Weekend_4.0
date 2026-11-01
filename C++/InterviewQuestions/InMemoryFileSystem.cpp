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
C++ children own nodes through unique_ptr; parent/cwd pointers are non-owning.
Use remove() for deletion because delete is a reserved keyword.
Run from repository root: python3 scripts/run.py "C++" InterviewQuestions/InMemoryFileSystem
See IN_MEMORY_FILESYSTEM_GUIDE.md for prerequisites, trade-offs and tests.
*/

#include <algorithm>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <regex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
using namespace std;
void require(bool condition) {
    if (!condition)
        throw invalid_argument("Invalid file-system operation");
}

void check(bool condition) {
    if (!condition)
        throw runtime_error("File-system contract failed");
}

struct Directory;
struct Node {
    string name;
    Directory *parent;
    Node(string name, Directory *parent) : name(std::move(name)), parent(parent) {
    }
    virtual ~Node() = default;
    string path() const;
};

struct Directory : Node {
    unordered_map<string, unique_ptr<Node>> children;
    Directory(string name, Directory *parent) : Node(std::move(name), parent) {
    }
};

struct File : Node {
    string content;
    File(string name, Directory *parent, string content)
        : Node(std::move(name), parent), content(std::move(content)) {
    }
};

string Node::path() const {
    vector<string> parts;
    for (const Node *node = this; node->parent != nullptr; node = node->parent)
        parts.push_back(node->name);
    reverse(parts.begin(), parts.end());
    string result;
    for (const auto &part : parts)
        result += "/" + part;
    return result.empty() ? "/" : result;
}

class FileSystem {
    recursive_mutex gate;
    Directory root{"", nullptr};
    Directory *cwd = &root;
    static void name(const string &name) {
        static const regex valid("[A-Za-z0-9._-]+");
        require(regex_match(name, valid) && name != "." && name != "..");
    }

    static vector<string> parts(const string &path) {
        require(!path.empty() && path[0] == '/');
        if (path == "/")
            return {};
        vector<string> result;
        size_t start = 1;
        for (;;) {
            size_t end = path.find('/', start);
            string part = path.substr(start, end == string::npos ? end : end - start);
            name(part);
            result.push_back(part);
            if (end == string::npos)
                break;
            start = end + 1;
        }
        return result;
    }
    Node *resolve(const vector<string> &parts) {
        Node *node = &root;
        for (const auto &part : parts) {
            auto directory = dynamic_cast<Directory *>(node);
            require(directory != nullptr);
            auto found = directory->children.find(part);
            require(found != directory->children.end());
            node = found->second.get();
        }
        return node;
    }
    Directory *parent(const vector<string> &parts) {
        require(!parts.empty());
        auto directory =
            dynamic_cast<Directory *>(resolve(vector<string>(parts.begin(), parts.end() - 1)));
        require(directory != nullptr);
        return directory;
    }
    File *file(const string &path) {
        auto result = dynamic_cast<File *>(resolve(parts(path)));
        require(result != nullptr);
        return result;
    }

    bool ancestor(Node *possible, Node *node) {
        for (Node *current = node; current != nullptr; current = current->parent)
            if (current == possible)
                return true;
        return false;
    }

  public:
    void mkdir(const string &path) {
        lock_guard<recursive_mutex> lock(gate);
        auto segments = parts(path);
        Directory *directory = &root;
        for (const auto &part : segments) {
            auto found = directory->children.find(part);
            if (found == directory->children.end()) {
                directory->children.emplace(part, make_unique<Directory>(part, directory));
                found = directory->children.find(part);
            }
            auto next = dynamic_cast<Directory *>(found->second.get());
            require(next != nullptr);
            directory = next;
        }
    }

    void createFile(const string &path, const string &content) {
        lock_guard<recursive_mutex> lock(gate);
        auto segments = parts(path);
        auto directory = parent(segments);
        const auto &leaf = segments.back();
        require(!directory->children.count(leaf));
        directory->children.emplace(leaf, make_unique<File>(leaf, directory, content));
    }

    string read(const string &path) {
        lock_guard<recursive_mutex> lock(gate);
        return file(path)->content;
    }

    void write(const string &path, const string &content) {
        lock_guard<recursive_mutex> lock(gate);
        file(path)->content = content;
    }

    void append(const string &path, const string &content) {
        lock_guard<recursive_mutex> lock(gate);
        file(path)->content += content;
    }

    vector<string> list(const string &path) {
        lock_guard<recursive_mutex> lock(gate);
        auto directory = dynamic_cast<Directory *>(resolve(parts(path)));
        require(directory != nullptr);
        vector<string> result;
        for (const auto &entry : directory->children)
            result.push_back(entry.first);
        sort(result.begin(), result.end());
        return result;
    }

    void cd(const string &path) {
        lock_guard<recursive_mutex> lock(gate);
        auto directory = dynamic_cast<Directory *>(resolve(parts(path)));
        require(directory != nullptr);
        cwd = directory;
    }

    string pwd() {
        lock_guard<recursive_mutex> lock(gate);
        return cwd->path();
    }

    void move(const string &source, const string &destination) {
        lock_guard<recursive_mutex> lock(gate);
        Node *node = resolve(parts(source));
        require(node != &root);
        auto target = parts(destination);
        auto directory = parent(target);
        string leaf = target.back();
        require(!directory->children.count(leaf) && !ancestor(node, directory));
        auto owned = std::move(node->parent->children.at(node->name));
        node->parent->children.erase(node->name);
        node->parent = directory;
        node->name = leaf;
        directory->children.emplace(leaf, std::move(owned));
    }

    void rename(const string &path, const string &newName) {
        lock_guard<recursive_mutex> lock(gate);
        name(newName);
        Node *node = resolve(parts(path));
        require(node != &root);
        string parentPath = node->parent->path();
        move(path, parentPath + (parentPath == "/" ? "" : "/") + newName);
    }

    void remove(const string &path, bool recursive = false) {
        lock_guard<recursive_mutex> lock(gate);
        Node *node = resolve(parts(path));
        require(node != &root && !ancestor(node, cwd));
        auto directory = dynamic_cast<Directory *>(node);
        require(directory == nullptr || recursive || directory->children.empty());
        string leaf = node->name;
        node->parent->children.erase(leaf);
    }

    vector<string> find(const string &target) {
        lock_guard<recursive_mutex> lock(gate);
        name(target);
        vector<string> result;
        vector<Node *> pending{&root};
        while (!pending.empty()) {
            Node *node = pending.back();
            pending.pop_back();
            if (node->name == target)
                result.push_back(node->path());
            if (auto directory = dynamic_cast<Directory *>(node))
                for (const auto &child : directory->children)
                    pending.push_back(child.second.get());
        }
        sort(result.begin(), result.end());
        return result;
    }

    size_t size(const string &path) {
        lock_guard<recursive_mutex> lock(gate);
        size_t total = 0;
        vector<Node *> pending{resolve(parts(path))};
        while (!pending.empty()) {
            Node *node = pending.back();
            pending.pop_back();
            if (auto document = dynamic_cast<File *>(node))
                total += document->content.size();
            else
                for (const auto &child : static_cast<Directory *>(node)->children)
                    pending.push_back(child.second.get());
        }
        return total;
    }
};

void rejected(const function<void()> &action) {
    try {
        action();
    } catch (const invalid_argument &) {
        return;
    }
    throw runtime_error("Expected rejection");
}

int main() {
    FileSystem fs;
    fs.mkdir("/courses/lld");
    fs.mkdir("/courses/lld");
    fs.createFile("/courses/lld/notes.txt", "Hello");
    fs.append("/courses/lld/notes.txt", " Java");
    check(fs.read("/courses/lld/notes.txt") == "Hello Java" && fs.size("/courses") == 10);
    fs.write("/courses/lld/notes.txt", "Hello Java");
    fs.move("/courses/lld/notes.txt", "/courses/lesson.txt");
    fs.rename("/courses/lesson.txt", "guide.txt");
    fs.cd("/courses/lld");
    fs.move("/courses", "/archive");
    check(fs.pwd() == "/archive/lld");
    check(fs.find("guide.txt") == vector<string>{"/archive/guide.txt"});
    rejected([&] {
        fs.move("/archive", "/archive/lld/nested");
    });
    rejected([&] {
        fs.remove("/archive", true);
    });
    fs.cd("/");
    fs.remove("/archive/lld");
    fs.remove("/archive/guide.txt");
    check(fs.list("/archive").empty());
    cout << "Content: Hello Java\nMoved working directory: /archive/lld\nSearch: "
            "/archive/guide.txt\nCycle rejected: true\nWorking-directory deletion blocked: "
            "true\nCleanup: empty\n";
    return 0;
}
