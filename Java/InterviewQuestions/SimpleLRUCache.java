/*
 * SIMPLE JAVA - LRU cache
 *
 * Functional requirements (MVP): Read/update integer values; promote hits; evict the least recently
 * used key. Non-functional requirements: readable, in-memory, safe access within one JVM. Core
 * entities: LRUCache owns capacity and an access-ordered LinkedHashMap. Operations and flow: get
 * promotes a hit. put inserts/updates, then removes the oldest key if over capacity. Design choices
 * and concurrency: synchronized protects both values and recency; even get changes ordering.
 * Assumptions / omitted features: null means missing, so -1 remains valid. Zero capacity disables
 * storage. No TTL/loaders. Edge cases to discuss/test: Hit promotion, update without eviction,
 * missing keys, zero capacity, concurrent access. Run from repository root (Java 17+ and Python 3):
 * python3 scripts/run.py Java InterviewQuestions/SimpleLRUCache
 * Read SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class SimpleLRUCache {
    static class LRUCache {
        private final int capacity;
        // accessOrder=true: successful get/put moves that key to the newest end.
        private final LinkedHashMap<Integer, Integer> values = new LinkedHashMap<>(16, 0.75f, true);

        LRUCache(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("Negative capacity");
            }
            this.capacity = capacity;
        }

        // A get also changes recency, so it needs the lock too.
        synchronized Integer get(int key) {
            return values.get(key);
        }

        synchronized void put(int key, int value) {
            if (capacity == 0) {
                return;
            }
            values.put(key, value);
            if (values.size() > capacity) {
                int oldest = values.keySet().iterator().next();
                values.remove(oldest);
            }
        }

        synchronized List<Integer> oldestFirst() {
            return new ArrayList<>(values.keySet());
        }
    }

    public static void main(String[] args) {
        LRUCache cache = new LRUCache(2);
        cache.put(1, 10);
        cache.put(2, 20);
        cache.get(1);
        cache.put(3, 30);
        System.out.println("Evicted key 2: " + cache.get(2));
        System.out.println("Oldest first: " + cache.oldestFirst());
    }
}
