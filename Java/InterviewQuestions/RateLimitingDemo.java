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
 * - python3 scripts/run.py "Java" InterviewQuestions/RateLimitingDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

package InterviewQuestions;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public class RateLimitingDemo {
    record Request(String user, String ip, String endpoint) {}

    interface Strategy {
        boolean allow(String key, long now);
    }

    static class Bucket {
        int tokens;
        long boundary;
        long lastSeen;

        Bucket(int capacity, long now) {
            tokens = capacity;
            boundary = now;
            lastSeen = now;
        }
    }

    static class TokenBucket implements Strategy {
        private final int capacity;
        private final long period;
        private final int maxKeys;
        private final Map<String, Bucket> buckets = new HashMap<>();

        TokenBucket(int capacity, long period, int maxKeys) {
            validConfig(capacity, period, maxKeys);
            this.capacity = capacity;
            this.period = period;
            this.maxKeys = maxKeys;
        }

        public synchronized boolean allow(String key, long now) {
            validInput(key, now);
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                require(buckets.size() < maxKeys, "Key capacity reached");
                bucket = new Bucket(capacity, now);
                buckets.put(key, bucket);
            }
            require(now >= bucket.lastSeen, "Clock moved backwards");
            long refill = (now - bucket.boundary) / period;
            bucket.tokens += (int)Math.min(refill, capacity - bucket.tokens);
            bucket.boundary += refill * period;
            bucket.lastSeen = now;
            if (bucket.tokens == 0) {
                return false;
            }
            bucket.tokens--;
            return true;
        }
    }

    static class Window {
        final ArrayDeque<Long> accepted = new ArrayDeque<>();
        long lastSeen;
    }

    static class SlidingWindow implements Strategy {
        private final int limit;
        private final long duration;
        private final int maxKeys;
        private final Map<String, Window> windows = new HashMap<>();

        SlidingWindow(int limit, long duration, int maxKeys) {
            validConfig(limit, duration, maxKeys);
            this.limit = limit;
            this.duration = duration;
            this.maxKeys = maxKeys;
        }

        public synchronized boolean allow(String key, long now) {
            validInput(key, now);
            Window window = windows.get(key);
            if (window == null) {
                require(windows.size() < maxKeys, "Key capacity reached");
                window = new Window();
                windows.put(key, window);
            }
            require(now >= window.lastSeen, "Clock moved backwards");
            window.lastSeen = now;
            while (!window.accepted.isEmpty() && window.accepted.peekFirst() <= now - duration) {
                window.accepted.removeFirst();
            }
            if (window.accepted.size() == limit) {
                return false;
            }
            window.accepted.addLast(now);
            return true;
        }
    }

    static class RateLimiter {
        final String dimension;
        final Strategy strategy;

        RateLimiter(String dimension, Strategy strategy) {
            require(dimension != null &&
                        java.util.List.of("user", "ip", "endpoint").contains(dimension) &&
                        strategy != null,
                    "Invalid policy");
            this.dimension = dimension;
            this.strategy = strategy;
        }

        boolean allow(Request request, long now) {
            require(request != null, "Missing request");
            String key = switch (dimension) {
                case "user" -> request.user();
                case "ip" -> request.ip();
                default -> request.endpoint();
            };
            return strategy.allow(key, now);
        }
    }

    static void validConfig(int limit, long period, int maxKeys) {
        require(limit >= 1 && limit <= 100000 && period >= 1 && period <= 1_000_000_000 &&
                    maxKeys >= 1 && maxKeys <= 10000,
                "Invalid configuration");
    }

    static void validInput(String key, long now) {
        require(key != null && !key.isEmpty() && now >= 0 && now <= 1_000_000_000_000L,
                "Invalid key/time");
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Contract failed");
        }
    }

    static void rejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    public static void main(String[] args) {
        Request alice = new Request("alice", "ip1", "/orders");
        Request bob = new Request("bob", "ip1", "/orders");
        RateLimiter tokens = new RateLimiter("user", new TokenBucket(2, 1000, 2));
        check(tokens.allow(alice, 0));
        check(tokens.allow(alice, 0));
        check(!tokens.allow(alice, 999));
        check(tokens.allow(alice, 1000));
        check(!tokens.allow(alice, 1000));
        check(tokens.allow(bob, 0));
        rejected(() -> tokens.allow(alice, 999));
        rejected(() -> tokens.allow(new Request("charlie", "ip2", "/orders"), 1000));
        RateLimiter window = new RateLimiter("ip", new SlidingWindow(2, 1000, 2));
        check(window.allow(alice, 0));
        check(window.allow(bob, 500));
        check(!window.allow(alice, 999));
        check(window.allow(alice, 1000));
        check(!window.allow(bob, 1000));
        check(window.allow(bob, 1500));
        rejected(() -> window.allow(alice, 1499));
        rejected(() -> new TokenBucket(0, 1, 1));
        rejected(() -> new SlidingWindow(1, 0, 1));
        rejected(() -> tokens.allow(new Request("", "ip", "/"), 0));
        TokenBucket idle = new TokenBucket(2, 1000, 1);
        check(idle.allow("x", 0));
        check(idle.allow("x", 1_000_000));
        check(idle.allow("x", 1_000_000));
        check(!idle.allow("x", 1_000_000));
        RateLimiter endpoint = new RateLimiter("endpoint", new SlidingWindow(1, 1000, 2));
        check(endpoint.allow(alice, 0));
        check(!endpoint.allow(bob, 0));
        System.out.println("Token bucket: allow,allow,deny,allow");
        System.out.println("Sliding window: allow,allow,deny,allow");
        System.out.println("Key isolation and shared scopes: passed");
        System.out.println("Edge cases: passed");
    }
}
