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
 * - python3 scripts/run.py "C#" InterviewQuestions/LRU_CacheDemo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

using System;
using System.Collections.Generic;
using System.Linq;

namespace LLDWeekend4.InterviewQuestions;

public class LRU_CacheDemo
{
    internal class LRUCache
    {
        private readonly int capacity;
        private readonly object gate = new();
        private readonly LinkedList<(int Key, int Value)> order = new();
        private readonly Dictionary<int, LinkedListNode<(int Key, int Value)>> cache = new();

        public LRUCache(int capacity)
        {
            if (capacity < 0)
            {
                throw new ArgumentException("Negative capacity");
            }
            this.capacity = capacity;
        }

        public int? Get(int key)
        {
            lock (gate)
            {
                if (!cache.TryGetValue(key, out var node))
                {
                    return null;
                }
                order.Remove(node);
                order.AddFirst(node);
                return node.Value.Value;
            }
        }

        public void Put(int key, int value)
        {
            lock (gate)
            {
                if (capacity == 0)
                {
                    return;
                }
                if (cache.TryGetValue(key, out var node))
                {
                    node.Value = (key, value);
                    order.Remove(node);
                    order.AddFirst(node);
                    return;
                }
                if (cache.Count == capacity)
                {
                    cache.Remove(order.Last!.Value.Key);
                    order.RemoveLast();
                }
                cache[key] = order.AddFirst((key, value));
            }
        }

        public List<int> Keys()
        {
            lock (gate)
            {
                return order.Select(entry => entry.Key).ToList();
            }
        }
    }

    static void Check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Cache contract failed");
        }
    }

    public static void Main()
    {
        var cache = new LRUCache(2);
        cache.Put(1, 10);
        cache.Put(2, 20);
        Check(cache.Get(1) == 10);
        cache.Put(3, 30);
        Check(cache.Get(2) == null);
        cache.Put(1, -1);
        Check(cache.Keys().SequenceEqual(new[] { 1, 3 }));
        Check(cache.Get(1) == -1);
        Check(cache.Get(999) == null);
        cache.Keys().Clear();
        Check(cache.Keys().SequenceEqual(new[] { 1, 3 }));
        var zero = new LRUCache(0);
        zero.Put(1, 1);
        Check(zero.Get(1) == null && zero.Keys().Count == 0);
        var one = new LRUCache(1);
        one.Put(1, 1);
        one.Put(1, 2);
        one.Put(2, 3);
        Check(one.Get(1) == null && one.Get(2) == 3);
        bool rejected = false;
        try
        {
            new LRUCache(-1);
        }
        catch (ArgumentException)
        {
            rejected = true;
        }
        Check(rejected);
        Console.WriteLine("Evicted key 2: true");
        Console.WriteLine("Stored negative value: -1");
        Console.WriteLine("MRU order: 1,3");
        Console.WriteLine("Edge cases: passed");
    }
}
