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
 * - python3 scripts/run.py "C++" InterviewQuestions/LRU_CacheDemo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

#include <iostream>
#include <list>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <unordered_map>
#include <vector>

class LRUCache {
    size_t capacity;
    std::mutex mutex;
    std::list<std::pair<int, int>> order;
    std::unordered_map<int, decltype(order)::iterator> cache;

  public:
    explicit LRUCache(int capacity) {
        if (capacity < 0) {
            throw std::invalid_argument("Negative capacity");
        }
        this->capacity = static_cast<size_t>(capacity);
    }
    std::optional<int> get(int key) {
        std::lock_guard<std::mutex> lock(mutex);
        auto found = cache.find(key);
        if (found == cache.end()) {
            return std::nullopt;
        }
        order.splice(order.begin(), order, found->second);
        return found->second->second;
    }
    void put(int key, int value) {
        std::lock_guard<std::mutex> lock(mutex);
        if (capacity == 0) {
            return;
        }
        auto found = cache.find(key);
        if (found != cache.end()) {
            found->second->second = value;
            order.splice(order.begin(), order, found->second);
            return;
        }
        if (cache.size() == capacity) {
            cache.erase(order.back().first);
            order.pop_back();
        }
        order.emplace_front(key, value);
        cache.emplace(key, order.begin());
    }
    std::vector<int> keys() {
        std::lock_guard<std::mutex> lock(mutex);
        std::vector<int> result;
        for (const auto &entry : order) {
            result.push_back(entry.first);
        }
        return result;
    }
};

void check(bool condition) {
    if (!condition) {
        throw std::runtime_error("Cache contract failed");
    }
}

int main() {
    LRUCache cache(2);
    cache.put(1, 10);
    cache.put(2, 20);
    check(cache.get(1) == 10);
    cache.put(3, 30);
    check(!cache.get(2));
    cache.put(1, -1);
    check(cache.keys() == std::vector<int>({1, 3}));
    check(cache.get(1) == -1);
    check(!cache.get(999));
    cache.keys().clear();
    check(cache.keys() == std::vector<int>({1, 3}));
    LRUCache zero(0);
    zero.put(1, 1);
    check(!zero.get(1) && zero.keys().empty());
    LRUCache one(1);
    one.put(1, 1);
    one.put(1, 2);
    one.put(2, 3);
    check(!one.get(1) && one.get(2) == 3);
    bool rejected = false;
    try {
        LRUCache invalid(-1);
    } catch (const std::invalid_argument &) {
        rejected = true;
    }
    check(rejected);
    std::cout
        << "Evicted key 2: true\nStored negative value: -1\nMRU order: 1,3\nEdge cases: passed\n";
    return 0;
}
