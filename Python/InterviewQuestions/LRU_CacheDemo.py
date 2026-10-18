# INTERVIEW DESIGN NOTES - LRU Cache
#
# Functional requirements:
# - Put/update integer key-value pairs and retrieve a value without confusing a miss with -1.
# - Successful get and every put promote the key to most recently used (MRU).
# - Evict the least recently used key when adding to a full cache.
# - Expose a copied MRU-to-LRU key list for demonstration and diagnostics.
#
# Non-functional requirements:
# - Average O(1) lookup/update/eviction and O(capacity) retained state.
# - Serialize mutations in native editions; a get is also a write to recency ordering.
# - Keep map membership and ordering consistent; never expose mutable internal nodes.
#
# Core entities and responsibilities:
# - LRUCache: owns capacity, lookup map and recency order.
# - Node/Entry: integer key and value, with list links or library-managed ordering.
# - Java shows explicit dummy head/tail nodes and both pointer updates on removal.
# - Python uses OrderedDict; C++/C#/Go pair a lookup map with a linked list.
# - JS/TS use insertion-ordered Map, deleting/reinserting touched keys at the MRU end.
#
# Operations and flow:
# - get -> lookup -> on hit unlink/reinsert at MRU -> return value; miss leaves order unchanged.
# - put existing -> update and promote; put new -> evict LRU if full -> insert at MRU.
# - keys returns a detached MRU-first snapshot in O(number of entries).
#
# Design choices and invariants:
# - At most capacity entries; exactly one ordering entry per map key.
# - Java sentinel nodes are never placed in the map or evicted.
# - Zero capacity is a disabled cache; negative capacity is rejected.
# - Miss: Java/C# null, Python None, C++ optional, Go found=false, JS/TS undefined.
#
# Assumptions and scope:
# - Integer keys/values; numeric range follows the language (JS/TS require safe integers).
# - In-memory, count-based capacity; no TTL, persistence, loading callback or byte budget.
# - JS/TS synchronous operations are confined to one event loop, not shared workers.
# - Individual native operations are atomic; a caller's get-then-put sequence is not.
#
# Edge cases and discussion:
# - Capacity zero/one, missing key, updating without eviction, hit promotion, negative
#   values, copied key snapshots and concurrent puts/gets without list corruption.
# - Discuss TTL, scan pollution, admission policy, cache stampedes and sharded locking.
#
# Run commands (from repository root):
# - python3 scripts/run.py "Python" InterviewQuestions/LRU_CacheDemo
# - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.

from collections import OrderedDict
from threading import Lock


class LRUCache:
    def __init__(self, capacity):
        if type(capacity) is not int or capacity < 0:
            raise ValueError("Invalid capacity")
        self._capacity = capacity
        self._cache = OrderedDict()
        self._lock = Lock()

    def get(self, key):
        with self._lock:
            if key not in self._cache:
                return None
            self._cache.move_to_end(key, last=False)
            return self._cache[key]

    def put(self, key, value):
        if type(key) is not int or type(value) is not int:
            raise ValueError("Expected integer key/value")
        with self._lock:
            if self._capacity == 0:
                return
            if key not in self._cache and len(self._cache) == self._capacity:
                self._cache.popitem(last=True)
            self._cache[key] = value
            self._cache.move_to_end(key, last=False)

    def keys(self):
        with self._lock:
            return list(self._cache)


def check(condition):
    if not condition:
        raise AssertionError("Cache contract failed")


def main():
    cache = LRUCache(2)
    cache.put(1, 10)
    cache.put(2, 20)
    check(cache.get(1) == 10)
    cache.put(3, 30)
    check(cache.get(2) is None)
    cache.put(1, -1)
    check(cache.keys() == [1, 3])
    check(cache.get(1) == -1)
    check(cache.get(999) is None)
    cache.keys().clear()
    check(cache.keys() == [1, 3])
    zero = LRUCache(0)
    zero.put(1, 1)
    check(zero.get(1) is None and zero.keys() == [])
    one = LRUCache(1)
    one.put(1, 1)
    one.put(1, 2)
    one.put(2, 3)
    check(one.get(1) is None and one.get(2) == 3)
    try:
        LRUCache(-1)
    except ValueError:
        pass
    else:
        raise AssertionError("Expected rejection")
    print("Evicted key 2: true")
    print("Stored negative value: -1")
    print("MRU order: 1,3")
    print("Edge cases: passed")


if __name__ == "__main__":
    main()
