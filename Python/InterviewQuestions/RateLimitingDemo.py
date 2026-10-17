# INTERVIEW DESIGN NOTES - Rate Limiting
#
# Functional requirements:
# - Allow or deny requests using token-bucket or exact sliding-window policies.
# - Scope one limiter to user ID, IP address or API endpoint; separate keys have separate quotas.
# - Accept injected monotonic integer milliseconds for deterministic timing tests.
# - Reject invalid configuration, missing keys, backwards time and excess distinct keys.
#
# Non-functional requirements:
# - Atomically check/update quota for concurrent requests within one process.
# - Bound retained keys and timestamps; never silently evict a key and reset its quota.
# - Separate key selection from the rate-limiting algorithm using Strategy.
#
# Core entities and responsibilities:
# - Request: user, IP and endpoint identities, supplied by the trusted API boundary.
# - RateLimiter: selects one configured dimension and delegates to its owned strategy.
# - TokenBucket/Bucket: capacity, remaining tokens, refill boundary and last observed time.
# - SlidingWindow/Window: accepted timestamps and last observed time for one key.
#
# Operations and flow:
# - Request -> select key -> validate time -> expire/refill -> check quota -> record allowance.
# - Token bucket starts full; add one whole token per period, retaining partial-period time.
# - Sliding window counts accepted requests in (now-duration, now]; exact-boundary entries expire.
# - Denied requests advance lastSeen but do not consume tokens or add accepted timestamps.
#
# Design choices and invariants:
# - Integer arithmetic avoids fractional-token drift and refill truncation on frequent requests.
# - Full buckets discard excess credit; a long idle period cannot exceed capacity.
# - Native editions serialize mutable state. JS/TS have no await points on one event loop.
# - Time must be nondecreasing per key, even across denied calls; use elapsed monotonic time,
#   not epoch time. Same timestamp is allowed. Each instance owns its policy's key namespace.
#
# Assumptions and scope:
# - One policy per instance; combining several quotas atomically is not implemented.
# - Limits 1..100000, period 1..1000000000 ms, maxKeys 1..10000, elapsed time 0..10^12 ms.
# - At key capacity, new keys raise an error; HTTP adapters should define fail-closed behavior.
# - No automatic key eviction, durable state, Redis coordination, queuing or retry-after header.
# - Token state is O(keys); sliding-window state O(keys * limit). Configure modest bounds.
# - Java/Python/C# deque/queue expiry is amortized O(1) per accepted entry; JS/TS and Go
#   array filtering/compaction is O(limit) per request. C++ map lookup is O(log keys).
#
# Edge cases and interview discussion:
# - Initial burst, exact boundary, rapid polling, long idle, backwards clocks, same-time
#   concurrency, independent users, shared IP/endpoint and invalid/capacity-exceeded input.
# - Discuss distributed atomic updates, clock source, sharded locks, safe idle eviction,
#   trusted identity extraction, hot keys and policy changes without resetting quotas.
#
# Run commands (from repository root):
# - python3 scripts/run.py "Python" InterviewQuestions/RateLimitingDemo
# - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.

from collections import deque
from dataclasses import dataclass
from threading import Lock


@dataclass(frozen=True)
class Request:
    user: str
    ip: str
    endpoint: str


def require(condition, message):
    if not condition:
        raise ValueError(message)


def valid_config(limit, period, max_keys):
    require(
        all(type(value) is int for value in [limit, period, max_keys])
        and 1 <= limit <= 100000
        and 1 <= period <= 1_000_000_000
        and 1 <= max_keys <= 10000,
        "Invalid configuration",
    )


def valid_input(key, now):
    require(bool(key) and type(now) is int and 0 <= now <= 1_000_000_000_000, "Invalid key/time")


@dataclass
class Bucket:
    tokens: int
    boundary: int
    last_seen: int


class TokenBucket:
    def __init__(self, capacity, period, max_keys):
        valid_config(capacity, period, max_keys)
        self.capacity = capacity
        self.period = period
        self.max_keys = max_keys
        self.buckets = {}
        self.lock = Lock()

    def allow(self, key, now):
        with self.lock:
            valid_input(key, now)
            if key not in self.buckets:
                require(len(self.buckets) < self.max_keys, "Key capacity reached")
                self.buckets[key] = Bucket(self.capacity, now, now)
            bucket = self.buckets[key]
            require(now >= bucket.last_seen, "Clock moved backwards")
            refill = (now - bucket.boundary) // self.period
            bucket.tokens += min(refill, self.capacity - bucket.tokens)
            bucket.boundary += refill * self.period
            bucket.last_seen = now
            if bucket.tokens == 0:
                return False
            bucket.tokens -= 1
            return True


class SlidingWindow:
    def __init__(self, limit, duration, max_keys):
        valid_config(limit, duration, max_keys)
        self.limit = limit
        self.duration = duration
        self.max_keys = max_keys
        self.windows = {}
        self.last_seen = {}
        self.lock = Lock()

    def allow(self, key, now):
        with self.lock:
            valid_input(key, now)
            if key not in self.windows:
                require(len(self.windows) < self.max_keys, "Key capacity reached")
                self.windows[key] = deque()
                self.last_seen[key] = now
            require(now >= self.last_seen[key], "Clock moved backwards")
            self.last_seen[key] = now
            window = self.windows[key]
            while window and window[0] <= now - self.duration:
                window.popleft()
            if len(window) == self.limit:
                return False
            window.append(now)
            return True


class RateLimiter:
    def __init__(self, dimension, strategy):
        require(dimension in ["user", "ip", "endpoint"] and strategy is not None, "Invalid policy")
        self.dimension = dimension
        self.strategy = strategy

    def allow(self, request, now):
        require(request is not None, "Missing request")
        return self.strategy.allow(getattr(request, self.dimension), now)


def check(condition):
    if not condition:
        raise AssertionError("Contract failed")


def rejected(action):
    try:
        action()
    except ValueError:
        return
    raise AssertionError("Expected rejection")


def main():
    alice = Request("alice", "ip1", "/orders")
    bob = Request("bob", "ip1", "/orders")
    tokens = RateLimiter("user", TokenBucket(2, 1000, 2))
    check(tokens.allow(alice, 0))
    check(tokens.allow(alice, 0))
    check(not tokens.allow(alice, 999))
    check(tokens.allow(alice, 1000))
    check(not tokens.allow(alice, 1000))
    check(tokens.allow(bob, 0))
    rejected(lambda: tokens.allow(alice, 999))
    rejected(lambda: tokens.allow(Request("charlie", "ip2", "/orders"), 1000))
    window = RateLimiter("ip", SlidingWindow(2, 1000, 2))
    check(window.allow(alice, 0))
    check(window.allow(bob, 500))
    check(not window.allow(alice, 999))
    check(window.allow(alice, 1000))
    check(not window.allow(bob, 1000))
    check(window.allow(bob, 1500))
    rejected(lambda: window.allow(alice, 1499))
    rejected(lambda: TokenBucket(0, 1, 1))
    rejected(lambda: SlidingWindow(1, 0, 1))
    rejected(lambda: tokens.allow(Request("", "ip", "/"), 0))
    idle = TokenBucket(2, 1000, 1)
    check(idle.allow("x", 0))
    check(idle.allow("x", 1_000_000))
    check(idle.allow("x", 1_000_000))
    check(not idle.allow("x", 1_000_000))
    endpoint = RateLimiter("endpoint", SlidingWindow(1, 1000, 2))
    check(endpoint.allow(alice, 0))
    check(not endpoint.allow(bob, 0))
    print("Token bucket: allow,allow,deny,allow")
    print("Sliding window: allow,allow,deny,allow")
    print("Key isolation and shared scopes: passed")
    print("Edge cases: passed")


if __name__ == "__main__":
    main()
