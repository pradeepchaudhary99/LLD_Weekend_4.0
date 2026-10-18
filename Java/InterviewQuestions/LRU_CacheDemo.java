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
 * - python3 scripts/run.py "Java" InterviewQuestions/LRU_CacheDemo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LRU_CacheDemo {
    static class LRUCache {
        private static class Node {
            final int key;
            int value;
            Node prev;
            Node next;

            Node(int key, int value) {
                this.key = key;
                this.value = value;
            }
        }

        private final int capacity;
        private final Map<Integer, Node> cache = new HashMap<>();
        private final Node head = new Node(0, 0);
        private final Node tail = new Node(0, 0);

        LRUCache(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("Negative capacity");
            }
            this.capacity = capacity;
            head.next = tail;
            tail.prev = head;
        }

        synchronized Integer get(int key) {
            Node node = cache.get(key);
            if (node == null) {
                return null;
            }
            remove(node);
            addToFront(node);
            return node.value;
        }

        synchronized void put(int key, int value) {
            if (capacity == 0) {
                return;
            }
            Node node = cache.get(key);
            if (node != null) {
                node.value = value;
                remove(node);
            } else {
                if (cache.size() == capacity) {
                    Node victim = tail.prev;
                    remove(victim);
                    cache.remove(victim.key);
                }
                node = new Node(key, value);
                cache.put(key, node);
            }
            addToFront(node);
        }

        private void remove(Node node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        private void addToFront(Node node) {
            node.prev = head;
            node.next = head.next;
            head.next.prev = node;
            head.next = node;
        }

        synchronized List<Integer> keys() {
            List<Integer> result = new ArrayList<>();
            for (Node node = head.next; node != tail; node = node.next) {
                result.add(node.key);
            }
            return result;
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Cache contract failed");
        }
    }

    public static void main(String[] args) {
        LRUCache cache = new LRUCache(2);
        cache.put(1, 10);
        cache.put(2, 20);
        check(cache.get(1) == 10);
        cache.put(3, 30);
        check(cache.get(2) == null);
        cache.put(1, -1);
        check(cache.keys().equals(List.of(1, 3)));
        check(cache.get(1) == -1);
        check(cache.get(999) == null);
        cache.keys().clear();
        check(cache.keys().equals(List.of(1, 3)));
        LRUCache zero = new LRUCache(0);
        zero.put(1, 1);
        check(zero.get(1) == null && zero.keys().isEmpty());
        LRUCache one = new LRUCache(1);
        one.put(1, 1);
        one.put(1, 2);
        one.put(2, 3);
        check(one.get(1) == null && one.get(2) == 3);
        boolean rejected = false;
        try {
            new LRUCache(-1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected);
        System.out.println("Evicted key 2: true");
        System.out.println("Stored negative value: -1");
        System.out.println("MRU order: 1,3");
        System.out.println("Edge cases: passed");
    }
}
