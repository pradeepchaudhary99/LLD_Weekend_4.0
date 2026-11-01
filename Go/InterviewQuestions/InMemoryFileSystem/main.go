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
Go uses one Node struct tagged by children: non-nil map = Directory, nil = File.
RWMutex allows concurrent read-only operations; mutations take the exclusive lock.
Run from repository root: python3 scripts/run.py "Go" InterviewQuestions/InMemoryFileSystem
See IN_MEMORY_FILESYSTEM_GUIDE.md for prerequisites, trade-offs and tests.
*/

package main

import (
	"errors"
	"fmt"
	"reflect"
	"regexp"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"
)

// A non-nil children map denotes a Directory; nil denotes a File.
type Node struct {
	name     string
	parent   *Node
	children map[string]*Node
	content  string
}

func directory(name string, parent *Node) *Node {
	return &Node{name: name, parent: parent, children: make(map[string]*Node)}
}

func (n *Node) path() string {
	parts := []string{}
	for current := n; current.parent != nil; current = current.parent {
		parts = append(parts, current.name)
	}
	for left, right := 0, len(parts)-1; left < right; left, right = left+1, right-1 {
		parts[left], parts[right] = parts[right], parts[left]
	}
	return "/" + strings.Join(parts, "/")
}

type FileSystem struct {
	mu        sync.RWMutex
	root, cwd *Node
}

func NewFileSystem() *FileSystem {
	root := directory("", nil)
	return &FileSystem{root: root, cwd: root}
}

var validName = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

func validateName(name string) error {
	if !validName.MatchString(name) || name == "." || name == ".." {
		return errors.New("invalid name")
	}
	return nil
}

func parts(path string) ([]string, error) {
	if !strings.HasPrefix(path, "/") {
		return nil, errors.New("absolute path required")
	}
	if path == "/" {
		return []string{}, nil
	}
	result := strings.Split(path[1:], "/")
	for _, part := range result {
		if err := validateName(part); err != nil {
			return nil, err
		}
	}
	return result, nil
}

func (fs *FileSystem) resolve(segments []string) (*Node, error) {
	node := fs.root
	for _, segment := range segments {
		if node.children == nil || node.children[segment] == nil {
			return nil, errors.New("missing path or not a directory")
		}
		node = node.children[segment]
	}
	return node, nil
}

func (fs *FileSystem) lookup(path string) (*Node, error) {
	segments, err := parts(path)
	if err != nil {
		return nil, err
	}
	return fs.resolve(segments)
}

func (fs *FileSystem) parent(segments []string) (*Node, error) {
	if len(segments) == 0 {
		return nil, errors.New("root has no parent")
	}
	node, err := fs.resolve(segments[:len(segments)-1])
	if err != nil {
		return nil, err
	}
	if node.children == nil {
		return nil, errors.New("parent is not directory")
	}
	return node, nil
}

func (fs *FileSystem) Mkdir(path string) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	segments, err := parts(path)
	if err != nil {
		return err
	}
	current := fs.root
	for _, segment := range segments {
		next := current.children[segment]
		if next == nil {
			next = directory(segment, current)
			current.children[segment] = next
		}
		if next.children == nil {
			return errors.New("file blocks directory path")
		}
		current = next
	}
	return nil
}

func (fs *FileSystem) CreateFile(path, content string) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if !utf8.ValidString(content) {
		return errors.New("invalid UTF-8")
	}
	segments, err := parts(path)
	if err != nil {
		return err
	}
	parent, err := fs.parent(segments)
	if err != nil {
		return err
	}
	name := segments[len(segments)-1]
	if parent.children[name] != nil {
		return errors.New("name exists")
	}
	parent.children[name] = &Node{name: name, parent: parent, content: content}
	return nil
}

func (fs *FileSystem) file(path string) (*Node, error) {
	node, err := fs.lookup(path)
	if err != nil {
		return nil, err
	}
	if node.children != nil {
		return nil, errors.New("not a file")
	}
	return node, nil
}

func (fs *FileSystem) Read(path string) (string, error) {
	fs.mu.RLock()
	defer fs.mu.RUnlock()
	node, err := fs.file(path)
	if err != nil {
		return "", err
	}
	return node.content, nil
}

func (fs *FileSystem) modify(path, content string, appendContent bool) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if !utf8.ValidString(content) {
		return errors.New("invalid UTF-8")
	}
	node, err := fs.file(path)
	if err != nil {
		return err
	}
	if appendContent {
		node.content += content
	} else {
		node.content = content
	}
	return nil
}

func (fs *FileSystem) Write(path, content string) error {
	return fs.modify(path, content, false)
}

func (fs *FileSystem) Append(path, content string) error {
	return fs.modify(path, content, true)
}

func (fs *FileSystem) List(path string) ([]string, error) {
	fs.mu.RLock()
	defer fs.mu.RUnlock()
	node, err := fs.lookup(path)
	if err != nil {
		return nil, err
	}
	if node.children == nil {
		return nil, errors.New("not directory")
	}
	result := []string{}
	for name := range node.children {
		result = append(result, name)
	}
	sort.Strings(result)
	return result, nil
}

func (fs *FileSystem) Cd(path string) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	node, err := fs.lookup(path)
	if err != nil {
		return err
	}
	if node.children == nil {
		return errors.New("not directory")
	}
	fs.cwd = node
	return nil
}

func (fs *FileSystem) Pwd() string {
	fs.mu.RLock()
	defer fs.mu.RUnlock()
	return fs.cwd.path()
}

func ancestor(possible, node *Node) bool {
	for current := node; current != nil; current = current.parent {
		if current == possible {
			return true
		}
	}
	return false
}

func (fs *FileSystem) move(source, destination string) error {
	node, err := fs.lookup(source)
	if err != nil {
		return err
	}
	if node == fs.root {
		return errors.New("cannot move root")
	}
	target, err := parts(destination)
	if err != nil {
		return err
	}
	parent, err := fs.parent(target)
	if err != nil {
		return err
	}
	name := target[len(target)-1]
	if parent.children[name] != nil || ancestor(node, parent) {
		return errors.New("name exists or cycle")
	}
	delete(node.parent.children, node.name)
	node.parent = parent
	node.name = name
	parent.children[name] = node
	return nil
}

func (fs *FileSystem) Move(source, destination string) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	return fs.move(source, destination)
}

func (fs *FileSystem) Rename(path, newName string) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if err := validateName(newName); err != nil {
		return err
	}
	node, err := fs.lookup(path)
	if err != nil {
		return err
	}
	if node == fs.root {
		return errors.New("cannot rename root")
	}
	return fs.move(path, strings.TrimSuffix(node.parent.path(), "/")+"/"+newName)
}

func (fs *FileSystem) Delete(path string, recursive bool) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	node, err := fs.lookup(path)
	if err != nil {
		return err
	}
	if node == fs.root || ancestor(node, fs.cwd) || (!recursive && len(node.children) > 0) {
		return errors.New("protected or nonempty directory")
	}
	delete(node.parent.children, node.name)
	return nil
}

func (fs *FileSystem) Find(name string) ([]string, error) {
	fs.mu.RLock()
	defer fs.mu.RUnlock()
	if err := validateName(name); err != nil {
		return nil, err
	}
	result := []string{}
	pending := []*Node{fs.root}
	for len(pending) > 0 {
		node := pending[len(pending)-1]
		pending = pending[:len(pending)-1]
		if node.name == name {
			result = append(result, node.path())
		}
		for _, child := range node.children {
			pending = append(pending, child)
		}
	}
	sort.Strings(result)
	return result, nil
}

func (fs *FileSystem) Size(path string) (int64, error) {
	fs.mu.RLock()
	defer fs.mu.RUnlock()
	node, err := fs.lookup(path)
	if err != nil {
		return 0, err
	}
	var total int64
	pending := []*Node{node}
	for len(pending) > 0 {
		node := pending[len(pending)-1]
		pending = pending[:len(pending)-1]
		if node.children == nil {
			total += int64(len(node.content))
		} else {
			for _, child := range node.children {
				pending = append(pending, child)
			}
		}
	}
	return total, nil
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func check(condition bool) {
	if !condition {
		panic("file-system contract failed")
	}
}

func main() {
	fs := NewFileSystem()
	must(fs.Mkdir("/courses/lld"))
	must(fs.Mkdir("/courses/lld"))
	must(fs.CreateFile("/courses/lld/notes.txt", "Hello"))
	must(fs.Append("/courses/lld/notes.txt", " Java"))
	content, err := fs.Read("/courses/lld/notes.txt")
	must(err)
	size, err := fs.Size("/courses")
	must(err)
	check(content == "Hello Java" && size == 10)
	must(fs.Write("/courses/lld/notes.txt", "Hello Java"))
	must(fs.Move("/courses/lld/notes.txt", "/courses/lesson.txt"))
	must(fs.Rename("/courses/lesson.txt", "guide.txt"))
	must(fs.Cd("/courses/lld"))
	must(fs.Move("/courses", "/archive"))
	check(fs.Pwd() == "/archive/lld")
	found, err := fs.Find("guide.txt")
	must(err)
	check(reflect.DeepEqual(found, []string{"/archive/guide.txt"}))
	check(fs.Move("/archive", "/archive/lld/nested") != nil)
	check(fs.Delete("/archive", true) != nil)
	must(fs.Cd("/"))
	must(fs.Delete("/archive/lld", false))
	must(fs.Delete("/archive/guide.txt", false))
	names, err := fs.List("/archive")
	must(err)
	check(len(names) == 0)
	fmt.Println("Content: Hello Java\nMoved working directory: /archive/lld\nSearch: /archive/guide.txt\nCycle rejected: true\nWorking-directory deletion blocked: true\nCleanup: empty")
}
