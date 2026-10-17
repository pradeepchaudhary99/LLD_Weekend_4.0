/*
 * INTERVIEW DESIGN NOTES - Rate Limiting
 *
 * Functional requirements:
 * - Allow or deny requests using token-bucket or exact sliding-window policies.
 * - Scope one limiter to user ID, IP address or API endpoint; separate keys have separate quotas.
 * - Accept injected monotonic integer milliseconds for deterministic timing tests.
 * - Reject invalid configuration, missing keys, backwards time and excess distinct keys.
 *
 * Non-functional requirements:
 * - Atomically check/update quota for concurrent requests within one process.
 * - Bound retained keys and timestamps; never silently evict a key and reset its quota.
 * - Separate key selection from the rate-limiting algorithm using Strategy.
 *
 * Core entities and responsibilities:
 * - Request: user, IP and endpoint identities, supplied by the trusted API boundary.
 * - RateLimiter: selects one configured dimension and delegates to its owned strategy.
 * - TokenBucket/Bucket: capacity, remaining tokens, refill boundary and last observed time.
 * - SlidingWindow/Window: accepted timestamps and last observed time for one key.
 *
 * Operations and flow:
 * - Request -> select key -> validate time -> expire/refill -> check quota -> record allowance.
 * - Token bucket starts full; add one whole token per period, retaining partial-period time.
 * - Sliding window counts accepted requests in (now-duration, now]; exact-boundary entries expire.
 * - Denied requests advance lastSeen but do not consume tokens or add accepted timestamps.
 *
 * Design choices and invariants:
 * - Integer arithmetic avoids fractional-token drift and refill truncation on frequent requests.
 * - Full buckets discard excess credit; a long idle period cannot exceed capacity.
 * - Native editions serialize mutable state. JS/TS have no await points on one event loop.
 * - Time must be nondecreasing per key, even across denied calls; use elapsed monotonic time,
 *   not epoch time. Same timestamp is allowed. Each instance owns its policy's key namespace.
 *
 * Assumptions and scope:
 * - One policy per instance; combining several quotas atomically is not implemented.
 * - Limits 1..100000, period 1..1000000000 ms, maxKeys 1..10000, elapsed time 0..10^12 ms.
 * - At key capacity, new keys raise an error; HTTP adapters should define fail-closed behavior.
 * - No automatic key eviction, durable state, Redis coordination, queuing or retry-after header.
 * - Token state is O(keys); sliding-window state O(keys * limit). Configure modest bounds.
 * - Java/Python/C# deque/queue expiry is amortized O(1) per accepted entry; JS/TS and Go
 *   array filtering/compaction is O(limit) per request. C++ map lookup is O(log keys).
 *
 * Edge cases and interview discussion:
 * - Initial burst, exact boundary, rapid polling, long idle, backwards clocks, same-time
 *   concurrency, independent users, shared IP/endpoint and invalid/capacity-exceeded input.
 * - Discuss distributed atomic updates, clock source, sharded locks, safe idle eviction,
 *   trusted identity extraction, hot keys and policy changes without resetting quotas.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "C#" InterviewQuestions/RateLimitingDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

using System;
using System.Collections.Generic;

namespace LLDWeekend4.InterviewQuestions;

public class RateLimitingDemo
{
    record Request(string user, string ip, string endpoint);

    interface Strategy
    {
        bool Allow(string key, long now);
    }

    class Bucket
    {
        public int tokens;
        public long boundary;
        public long lastSeen;

        public Bucket(int capacity, long now)
        {
            tokens = capacity;
            boundary = now;
            lastSeen = now;
        }
    }

    class TokenBucket : Strategy
    {
        private readonly object gate = new();
        private readonly int capacity;
        private readonly long period;
        private readonly int maxKeys;
        private readonly Dictionary<string, Bucket> buckets = new();

        public TokenBucket(int capacity, long period, int maxKeys)
        {
            ValidConfig(capacity, period, maxKeys);
            this.capacity = capacity;
            this.period = period;
            this.maxKeys = maxKeys;
        }

        public bool Allow(string key, long now)
        {
            lock (gate)
            {

                ValidInput(key, now);
                Bucket? bucket = buckets.GetValueOrDefault(key);
                if (bucket == null)
                {
                    Require(buckets.Count < maxKeys, "Key capacity reached");
                    bucket = new Bucket(capacity, now);
                    buckets[key] = bucket;
                }
                Require(now >= bucket.lastSeen, "Clock moved backwards");
                long refill = (now - bucket.boundary) / period;
                bucket.tokens += (int)Math.Min(refill, capacity - bucket.tokens);
                bucket.boundary += refill * period;
                bucket.lastSeen = now;
                if (bucket.tokens == 0)
                {
                    return false;
                }
                bucket.tokens--;
                return true;
            }
        }
    }

    class Window
    {
        public readonly Queue<long> accepted = new();
        public long lastSeen;
    }

    class SlidingWindow : Strategy
    {
        private readonly object gate = new();
        private readonly int limit;
        private readonly long duration;
        private readonly int maxKeys;
        private readonly Dictionary<string, Window> windows = new();

        public SlidingWindow(int limit, long duration, int maxKeys)
        {
            ValidConfig(limit, duration, maxKeys);
            this.limit = limit;
            this.duration = duration;
            this.maxKeys = maxKeys;
        }

        public bool Allow(string key, long now)
        {
            lock (gate)
            {

                ValidInput(key, now);
                Window? window = windows.GetValueOrDefault(key);
                if (window == null)
                {
                    Require(windows.Count < maxKeys, "Key capacity reached");
                    window = new Window();
                    windows[key] = window;
                }
                Require(now >= window.lastSeen, "Clock moved backwards");
                window.lastSeen = now;
                while (window.accepted.Count > 0 && window.accepted.Peek() <= now - duration)
                {
                    window.accepted.Dequeue();
                }
                if (window.accepted.Count == limit)
                {
                    return false;
                }
                window.accepted.Enqueue(now);
                return true;
            }
        }
    }

    class RateLimiter
    {
        public readonly string dimension;
        public readonly Strategy strategy;

        public RateLimiter(string dimension, Strategy strategy)
        {
            Require(dimension != null &&
                        new List<string> { "user", "ip", "endpoint" }.Contains(dimension) &&
                        strategy != null,
                    "Invalid policy");
            this.dimension = dimension;
            this.strategy = strategy;
        }

        public bool Allow(Request request, long now)
        {
            Require(request != null, "Missing request");
            string key = dimension switch { "user" => request.user, "ip" => request.ip,
                                            _ => request.endpoint };
            return strategy.Allow(key, now);
        }
    }

    static void ValidConfig(int limit, long period, int maxKeys)
    {
        Require(limit >= 1 && limit <= 100000 && period >= 1 && period <= 1_000_000_000 &&
                    maxKeys >= 1 && maxKeys <= 10000,
                "Invalid configuration");
    }

    static void ValidInput(string key, long now)
    {
        Require(key != null && key.Length > 0 && now >= 0 && now <= 1_000_000_000_000L,
                "Invalid key/time");
    }

    static void Require([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition,
                        string message)
    {
        if (!condition)
        {
            throw new ArgumentException(message);
        }
    }

    static void Check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Contract failed");
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
        Request alice = new Request("alice", "ip1", "/orders");
        Request bob = new Request("bob", "ip1", "/orders");
        RateLimiter tokens = new RateLimiter("user", new TokenBucket(2, 1000, 2));
        Check(tokens.Allow(alice, 0));
        Check(tokens.Allow(alice, 0));
        Check(!tokens.Allow(alice, 999));
        Check(tokens.Allow(alice, 1000));
        Check(!tokens.Allow(alice, 1000));
        Check(tokens.Allow(bob, 0));
        Rejected(() => tokens.Allow(alice, 999));
        Rejected(() => tokens.Allow(new Request("charlie", "ip2", "/orders"), 1000));
        RateLimiter window = new RateLimiter("ip", new SlidingWindow(2, 1000, 2));
        Check(window.Allow(alice, 0));
        Check(window.Allow(bob, 500));
        Check(!window.Allow(alice, 999));
        Check(window.Allow(alice, 1000));
        Check(!window.Allow(bob, 1000));
        Check(window.Allow(bob, 1500));
        Rejected(() => window.Allow(alice, 1499));
        Rejected(() => new TokenBucket(0, 1, 1));
        Rejected(() => new SlidingWindow(1, 0, 1));
        Rejected(() => tokens.Allow(new Request("", "ip", "/"), 0));
        TokenBucket idle = new TokenBucket(2, 1000, 1);
        Check(idle.Allow("x", 0));
        Check(idle.Allow("x", 1_000_000));
        Check(idle.Allow("x", 1_000_000));
        Check(!idle.Allow("x", 1_000_000));
        RateLimiter endpoint = new RateLimiter("endpoint", new SlidingWindow(1, 1000, 2));
        Check(endpoint.Allow(alice, 0));
        Check(!endpoint.Allow(bob, 0));
        Console.WriteLine("Token bucket: allow,allow,deny,allow");
        Console.WriteLine("Sliding window: allow,allow,deny,allow");
        Console.WriteLine("Key isolation and shared scopes: passed");
        Console.WriteLine("Edge cases: passed");
    }
}
