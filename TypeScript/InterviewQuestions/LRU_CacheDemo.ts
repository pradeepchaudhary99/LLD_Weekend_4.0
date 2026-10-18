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
 * - python3 scripts/run.py "TypeScript" InterviewQuestions/LRU_CacheDemo
 * - python3 scripts/run.py JavaScript InterviewQuestions/LRU_CacheDemo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

export {};

class LRUCache {
    private readonly cache = new Map<number, number>();
    constructor(private readonly capacity: number) {
        if (!Number.isSafeInteger(capacity) || capacity < 0) {
            throw new Error("Invalid capacity");
        }
    }
    get(key: number): number | undefined {
        const value = this.cache.get(key);
        if (value !== undefined) {
            this.cache.delete(key);
            this.cache.set(key, value);
        }
        return value;
    }
    put(key: number, value: number): void {
        if (!Number.isSafeInteger(key) || !Number.isSafeInteger(value)) {
            throw new Error("Expected safe integer key/value");
        }
        if (this.capacity === 0) {
            return;
        }
        if (this.cache.has(key)) {
            this.cache.delete(key);
        } else if (this.cache.size === this.capacity) {
            const oldest = this.cache.keys().next().value!;
            this.cache.delete(oldest);
        }
        this.cache.set(key, value);
    }
    keys(): number[] {
        return [...this.cache.keys()].reverse();
    }
}

function check(condition: boolean): void {
    if (!condition) {
        throw new Error("Cache contract failed");
    }
}

const cache = new LRUCache(2);
cache.put(1, 10);
cache.put(2, 20);
check(cache.get(1) === 10);
cache.put(3, 30);
check(cache.get(2) === undefined);
cache.put(1, -1);
check(cache.keys().join() === "1,3");
check(cache.get(1) === -1);
check(cache.get(999) === undefined);
cache.keys().length = 0;
check(cache.keys().join() === "1,3");
const zero = new LRUCache(0);
zero.put(1, 1);
check(zero.get(1) === undefined && zero.keys().length === 0);
const one = new LRUCache(1);
one.put(1, 1);
one.put(1, 2);
one.put(2, 3);
check(one.get(1) === undefined && one.get(2) === 3);
let rejected = false;
try {
    new LRUCache(-1);
} catch {
    rejected = true;
}
check(rejected);
console.log("Evicted key 2: true");
console.log("Stored negative value: -1");
console.log("MRU order: 1,3");
console.log("Edge cases: passed");
