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
 * - python3 scripts/run.py "TypeScript" InterviewQuestions/RateLimitingDemo
 * - python3 scripts/run.py JavaScript InterviewQuestions/RateLimitingDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

export {};

type Request = Readonly<{ user: string; ip: string; endpoint: string }>;
interface Strategy {
    allow(key: string, now: number): boolean;
}

function requireCondition(condition: boolean, message: string): asserts condition {
    if (!condition) {
        throw new Error(message);
    }
}

function validConfig(limit: number, period: number, maxKeys: number): void {
    requireCondition(
        [limit, period, maxKeys].every(Number.isSafeInteger) &&
            limit >= 1 &&
            limit <= 100000 &&
            period >= 1 &&
            period <= 1_000_000_000 &&
            maxKeys >= 1 &&
            maxKeys <= 10000,
        "Invalid configuration",
    );
}

function validInput(key: string, now: number): void {
    requireCondition(
        key.length > 0 && Number.isSafeInteger(now) && now >= 0 && now <= 1_000_000_000_000,
        "Invalid key/time",
    );
}

type Bucket = { tokens: number; boundary: number; lastSeen: number };
class TokenBucket implements Strategy {
    private readonly buckets = new Map<string, Bucket>();
    constructor(
        private readonly capacity: number,
        private readonly period: number,
        private readonly maxKeys: number,
    ) {
        validConfig(capacity, period, maxKeys);
    }

    allow(key: string, now: number): boolean {
        validInput(key, now);
        let bucket = this.buckets.get(key);
        if (!bucket) {
            requireCondition(this.buckets.size < this.maxKeys, "Key capacity reached");
            bucket = { tokens: this.capacity, boundary: now, lastSeen: now };
            this.buckets.set(key, bucket);
        }
        requireCondition(now >= bucket.lastSeen, "Clock moved backwards");
        const refill = Math.floor((now - bucket.boundary) / this.period);
        bucket.tokens += Math.min(refill, this.capacity - bucket.tokens);
        bucket.boundary += refill * this.period;
        bucket.lastSeen = now;
        if (bucket.tokens === 0) {
            return false;
        }
        bucket.tokens--;
        return true;
    }
}

type Window = { accepted: number[]; lastSeen: number };
class SlidingWindow implements Strategy {
    private readonly windows = new Map<string, Window>();
    constructor(
        private readonly limit: number,
        private readonly duration: number,
        private readonly maxKeys: number,
    ) {
        validConfig(limit, duration, maxKeys);
    }

    allow(key: string, now: number): boolean {
        validInput(key, now);
        let window = this.windows.get(key);
        if (!window) {
            requireCondition(this.windows.size < this.maxKeys, "Key capacity reached");
            window = { accepted: [], lastSeen: now };
            this.windows.set(key, window);
        }
        requireCondition(now >= window.lastSeen, "Clock moved backwards");
        window.lastSeen = now;
        window.accepted = window.accepted.filter((timestamp) => timestamp > now - this.duration);
        if (window.accepted.length === this.limit) {
            return false;
        }
        window.accepted.push(now);
        return true;
    }
}

class RateLimiter {
    constructor(
        private readonly dimension: keyof Request,
        private readonly strategy: Strategy,
    ) {
        requireCondition(
            ["user", "ip", "endpoint"].includes(dimension) &&
                strategy !== null &&
                strategy !== undefined,
            "Invalid policy",
        );
    }
    allow(request: Request, now: number): boolean {
        return this.strategy.allow(request[this.dimension], now);
    }
}

function check(condition: boolean): void {
    if (!condition) {
        throw new Error("Contract failed");
    }
}

function rejected(action: () => unknown): void {
    try {
        action();
    } catch {
        return;
    }
    throw new Error("Expected rejection");
}

const alice: Request = { user: "alice", ip: "ip1", endpoint: "/orders" };
const bob: Request = { user: "bob", ip: "ip1", endpoint: "/orders" };
const tokens = new RateLimiter("user", new TokenBucket(2, 1000, 2));
check(tokens.allow(alice, 0));
check(tokens.allow(alice, 0));
check(!tokens.allow(alice, 999));
check(tokens.allow(alice, 1000));
check(!tokens.allow(alice, 1000));
check(tokens.allow(bob, 0));
rejected(() => tokens.allow(alice, 999));
rejected(() => tokens.allow({ ...alice, user: "charlie" }, 1000));
const window = new RateLimiter("ip", new SlidingWindow(2, 1000, 2));
check(window.allow(alice, 0));
check(window.allow(bob, 500));
check(!window.allow(alice, 999));
check(window.allow(alice, 1000));
check(!window.allow(bob, 1000));
check(window.allow(bob, 1500));
rejected(() => window.allow(alice, 1499));
rejected(() => new TokenBucket(0, 1, 1));
rejected(() => new SlidingWindow(1, 0, 1));
rejected(() => tokens.allow({ ...alice, user: "" }, 0));
const idle = new TokenBucket(2, 1000, 1);
check(idle.allow("x", 0));
check(idle.allow("x", 1_000_000));
check(idle.allow("x", 1_000_000));
check(!idle.allow("x", 1_000_000));
const endpoint = new RateLimiter("endpoint", new SlidingWindow(1, 1000, 2));
check(endpoint.allow(alice, 0));
check(!endpoint.allow(bob, 0));
console.log("Token bucket: allow,allow,deny,allow");
console.log("Sliding window: allow,allow,deny,allow");
console.log("Key isolation and shared scopes: passed");
console.log("Edge cases: passed");
