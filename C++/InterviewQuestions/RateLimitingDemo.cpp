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
 * - python3 scripts/run.py "C++" InterviewQuestions/RateLimitingDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

#include <deque>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

struct Request {
    std::string user;
    std::string ip;
    std::string endpoint;
};

struct Strategy {
    virtual bool allow(const std::string &key, long long now) = 0;
    virtual ~Strategy() = default;
};

void require(bool condition, const std::string &message) {
    if (!condition) {
        throw std::invalid_argument(message);
    }
}

void validConfig(int limit, long long period, int maxKeys) {
    require(limit >= 1 && limit <= 100000 && period >= 1 && period <= 1000000000LL &&
                maxKeys >= 1 && maxKeys <= 10000,
            "Invalid configuration");
}

void validInput(const std::string &key, long long now) {
    require(!key.empty() && now >= 0 && now <= 1000000000000LL, "Invalid key/time");
}

struct Bucket {
    int tokens;
    long long boundary;
    long long lastSeen;
};

class TokenBucket : public Strategy {
    int capacity;
    long long period;
    int maxKeys;
    std::map<std::string, Bucket> buckets;
    std::mutex mutex;

  public:
    TokenBucket(int capacity, long long period, int maxKeys)
        : capacity(capacity), period(period), maxKeys(maxKeys) {
        validConfig(capacity, period, maxKeys);
    }

    bool allow(const std::string &key, long long now) override {
        std::lock_guard<std::mutex> lock(mutex);
        validInput(key, now);
        if (!buckets.count(key)) {
            require(buckets.size() < static_cast<size_t>(maxKeys), "Key capacity reached");
            buckets.emplace(key, Bucket{capacity, now, now});
        }
        auto &bucket = buckets.at(key);
        require(now >= bucket.lastSeen, "Clock moved backwards");
        long long refill = (now - bucket.boundary) / period;
        bucket.tokens +=
            static_cast<int>(std::min(refill, static_cast<long long>(capacity - bucket.tokens)));
        bucket.boundary += refill * period;
        bucket.lastSeen = now;
        if (bucket.tokens == 0) {
            return false;
        }
        bucket.tokens--;
        return true;
    }
};

struct Window {
    std::deque<long long> accepted;
    long long lastSeen = 0;
};

class SlidingWindow : public Strategy {
    int limit;
    long long duration;
    int maxKeys;
    std::map<std::string, Window> windows;
    std::mutex mutex;

  public:
    SlidingWindow(int limit, long long duration, int maxKeys)
        : limit(limit), duration(duration), maxKeys(maxKeys) {
        validConfig(limit, duration, maxKeys);
    }

    bool allow(const std::string &key, long long now) override {
        std::lock_guard<std::mutex> lock(mutex);
        validInput(key, now);
        if (!windows.count(key)) {
            require(windows.size() < static_cast<size_t>(maxKeys), "Key capacity reached");
            windows.emplace(key, Window{});
        }
        auto &window = windows.at(key);
        require(now >= window.lastSeen, "Clock moved backwards");
        window.lastSeen = now;
        while (!window.accepted.empty() && window.accepted.front() <= now - duration) {
            window.accepted.pop_front();
        }
        if (window.accepted.size() == static_cast<size_t>(limit)) {
            return false;
        }
        window.accepted.push_back(now);
        return true;
    }
};

class RateLimiter {
    std::string dimension;
    std::unique_ptr<Strategy> strategy;

  public:
    RateLimiter(std::string dimension, std::unique_ptr<Strategy> strategy)
        : dimension(dimension), strategy(std::move(strategy)) {
        require((dimension == "user" || dimension == "ip" || dimension == "endpoint") &&
                    this->strategy != nullptr,
                "Invalid policy");
    }

    bool allow(const Request &request, long long now) {
        const std::string &key = dimension == "user" ? request.user
                                 : dimension == "ip" ? request.ip
                                                     : request.endpoint;
        return strategy->allow(key, now);
    }
};

void check(bool condition) {
    if (!condition) {
        throw std::runtime_error("Contract failed");
    }
}

void rejected(const std::function<void()> &action) {
    try {
        action();
    } catch (const std::invalid_argument &) {
        return;
    }
    throw std::runtime_error("Expected rejection");
}

int main() {
    Request alice{"alice", "ip1", "/orders"};
    Request bob{"bob", "ip1", "/orders"};
    RateLimiter tokens("user", std::make_unique<TokenBucket>(2, 1000, 2));
    check(tokens.allow(alice, 0));
    check(tokens.allow(alice, 0));
    check(!tokens.allow(alice, 999));
    check(tokens.allow(alice, 1000));
    check(!tokens.allow(alice, 1000));
    check(tokens.allow(bob, 0));
    rejected([&] {
        tokens.allow(alice, 999);
    });
    rejected([&] {
        tokens.allow(Request{"charlie", "ip2", "/orders"}, 1000);
    });
    RateLimiter window("ip", std::make_unique<SlidingWindow>(2, 1000, 2));
    check(window.allow(alice, 0));
    check(window.allow(bob, 500));
    check(!window.allow(alice, 999));
    check(window.allow(alice, 1000));
    check(!window.allow(bob, 1000));
    check(window.allow(bob, 1500));
    rejected([&] {
        window.allow(alice, 1499);
    });
    rejected([&] {
        std::make_unique<TokenBucket>(0, 1, 1);
    });
    rejected([&] {
        std::make_unique<SlidingWindow>(1, 0, 1);
    });
    rejected([&] {
        tokens.allow(Request{"", "ip", "/"}, 0);
    });
    TokenBucket idle(2, 1000, 1);
    check(idle.allow("x", 0));
    check(idle.allow("x", 1000000));
    check(idle.allow("x", 1000000));
    check(!idle.allow("x", 1000000));
    RateLimiter endpoint("endpoint", std::make_unique<SlidingWindow>(1, 1000, 2));
    check(endpoint.allow(alice, 0));
    check(!endpoint.allow(bob, 0));
    std::cout << "Token bucket: allow,allow,deny,allow" << "\n";
    std::cout << "Sliding window: allow,allow,deny,allow" << "\n";
    std::cout << "Key isolation and shared scopes: passed" << "\n";
    std::cout << "Edge cases: passed" << "\n";
}
