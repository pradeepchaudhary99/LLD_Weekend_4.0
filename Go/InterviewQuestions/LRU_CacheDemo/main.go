/*
 * INTERVIEW DESIGN NOTES - LRU Cache
 *
 * Functional requirements:
 * - Put/update integer key-value pairs and retrieve a value without confusing a miss with -1.
 * - Successful get and every put promote the key to most recently used (MRU).
 * - Evict the least recently used key when adding to a full cache.
 * - Expose a copied MRU-to-LRU key list for demonstration and diagnostics.
 *
 * Non-functional requirements:
 * - Average O(1) lookup/update/eviction and O(capacity) retained state.
 * - Serialize mutations in native editions; a get is also a write to recency ordering.
 * - Keep map membership and ordering consistent; never expose mutable internal nodes.
 *
 * Core entities and responsibilities:
 * - LRUCache: owns capacity, lookup map and recency order.
 * - Node/Entry: integer key and value, with list links or library-managed ordering.
 * - Java shows explicit dummy head/tail nodes and both pointer updates on removal.
 * - Python uses OrderedDict; C++/C#/Go pair a lookup map with a linked list.
 * - JS/TS use insertion-ordered Map, deleting/reinserting touched keys at the MRU end.
 *
 * Operations and flow:
 * - get -> lookup -> on hit unlink/reinsert at MRU -> return value; miss leaves order unchanged.
 * - put existing -> update and promote; put new -> evict LRU if full -> insert at MRU.
 * - keys returns a detached MRU-first snapshot in O(number of entries).
 *
 * Design choices and invariants:
 * - At most capacity entries; exactly one ordering entry per map key.
 * - Java sentinel nodes are never placed in the map or evicted.
 * - Zero capacity is a disabled cache; negative capacity is rejected.
 * - Miss: Java/C# null, Python None, C++ optional, Go found=false, JS/TS undefined.
 *
 * Assumptions and scope:
 * - Integer keys/values; numeric range follows the language (JS/TS require safe integers).
 * - In-memory, count-based capacity; no TTL, persistence, loading callback or byte budget.
 * - JS/TS synchronous operations are confined to one event loop, not shared workers.
 * - Individual native operations are atomic; a caller's get-then-put sequence is not.
 *
 * Edge cases and discussion:
 * - Capacity zero/one, missing key, updating without eviction, hit promotion, negative
 *   values, copied key snapshots and concurrent puts/gets without list corruption.
 * - Discuss TTL, scan pollution, admission policy, cache stampedes and sharded locking.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "Go" InterviewQuestions/LRU_CacheDemo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

package main

import (
	"container/list"
	"errors"
	"fmt"
	"reflect"
	"sync"
)

type Entry struct {
	key   int
	value int
}

type LRUCache struct {
	mu       sync.Mutex
	capacity int
	order    *list.List
	cache    map[int]*list.Element
}

func NewLRUCache(capacity int) (*LRUCache, error) {
	if capacity < 0 {
		return nil, errors.New("negative capacity")
	}
	return &LRUCache{capacity: capacity, order: list.New(), cache: make(map[int]*list.Element)}, nil
}

func (c *LRUCache) Get(key int) (int, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	node, ok := c.cache[key]
	if !ok {
		return 0, false
	}
	c.order.MoveToFront(node)
	return node.Value.(Entry).value, true
}

func (c *LRUCache) Put(key int, value int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.capacity == 0 {
		return
	}
	if node, ok := c.cache[key]; ok {
		node.Value = Entry{key, value}
		c.order.MoveToFront(node)
		return
	}
	if len(c.cache) == c.capacity {
		victim := c.order.Back()
		delete(c.cache, victim.Value.(Entry).key)
		c.order.Remove(victim)
	}
	c.cache[key] = c.order.PushFront(Entry{key, value})
}

func (c *LRUCache) Keys() []int {
	c.mu.Lock()
	defer c.mu.Unlock()
	result := make([]int, 0, len(c.cache))
	for node := c.order.Front(); node != nil; node = node.Next() {
		result = append(result, node.Value.(Entry).key)
	}
	return result
}

func check(condition bool) {
	if !condition {
		panic("cache contract failed")
	}
}

func newCache(capacity int) *LRUCache {
	result, err := NewLRUCache(capacity)
	if err != nil {
		panic(err)
	}
	return result
}

func main() {
	cache := newCache(2)
	cache.Put(1, 10)
	cache.Put(2, 20)
	v, ok := cache.Get(1)
	check(ok && v == 10)
	cache.Put(3, 30)
	_, ok = cache.Get(2)
	check(!ok)
	cache.Put(1, -1)
	check(reflect.DeepEqual(cache.Keys(), []int{1, 3}))
	v, ok = cache.Get(1)
	check(ok && v == -1)
	_, ok = cache.Get(999)
	check(!ok)
	snapshot := cache.Keys()
	snapshot[0] = 99
	check(reflect.DeepEqual(cache.Keys(), []int{1, 3}))
	zero := newCache(0)
	zero.Put(1, 1)
	_, ok = zero.Get(1)
	check(!ok && len(zero.Keys()) == 0)
	one := newCache(1)
	one.Put(1, 1)
	one.Put(1, 2)
	one.Put(2, 3)
	_, ok = one.Get(1)
	check(!ok)
	v, ok = one.Get(2)
	check(ok && v == 3)
	_, err := NewLRUCache(-1)
	check(err != nil)
	fmt.Println("Evicted key 2: true")
	fmt.Println("Stored negative value: -1")
	fmt.Println("MRU order: 1,3")
	fmt.Println("Edge cases: passed")
}
