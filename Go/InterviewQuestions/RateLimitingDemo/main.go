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
 * - python3 scripts/run.py "Go" InterviewQuestions/RateLimitingDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

package main

import (
	"errors"
	"fmt"
	"sync"
)

type Request struct {
	User     string
	IP       string
	Endpoint string
}

type Strategy interface {
	Allow(key string, now int64) (bool, error)
}

func validConfig(limit int, period int64, maxKeys int) bool {
	return limit >= 1 && limit <= 100000 && period >= 1 && period <= 1000000000 && maxKeys >= 1 && maxKeys <= 10000
}

func validInput(key string, now int64) bool {
	return key != "" && now >= 0 && now <= 1000000000000
}

type Bucket struct {
	tokens   int
	boundary int64
	lastSeen int64
}

type TokenBucket struct {
	mu       sync.Mutex
	capacity int
	period   int64
	maxKeys  int
	buckets  map[string]*Bucket
}

func NewTokenBucket(capacity int, period int64, maxKeys int) (*TokenBucket, error) {
	if !validConfig(capacity, period, maxKeys) {
		return nil, errors.New("invalid configuration")
	}
	return &TokenBucket{capacity: capacity, period: period, maxKeys: maxKeys, buckets: make(map[string]*Bucket)}, nil
}

func (s *TokenBucket) Allow(key string, now int64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validInput(key, now) {
		return false, errors.New("invalid key/time")
	}
	bucket := s.buckets[key]
	if bucket == nil {
		if len(s.buckets) >= s.maxKeys {
			return false, errors.New("key capacity reached")
		}
		bucket = &Bucket{tokens: s.capacity, boundary: now, lastSeen: now}
		s.buckets[key] = bucket
	}
	if now < bucket.lastSeen {
		return false, errors.New("clock moved backwards")
	}
	refill := (now - bucket.boundary) / s.period
	addition := refill
	if addition > int64(s.capacity-bucket.tokens) {
		addition = int64(s.capacity - bucket.tokens)
	}
	bucket.tokens += int(addition)
	bucket.boundary += refill * s.period
	bucket.lastSeen = now
	if bucket.tokens == 0 {
		return false, nil
	}
	bucket.tokens--
	return true, nil
}

type Window struct {
	accepted []int64
	lastSeen int64
}

type SlidingWindow struct {
	mu       sync.Mutex
	limit    int
	duration int64
	maxKeys  int
	windows  map[string]*Window
}

func NewSlidingWindow(limit int, duration int64, maxKeys int) (*SlidingWindow, error) {
	if !validConfig(limit, duration, maxKeys) {
		return nil, errors.New("invalid configuration")
	}
	return &SlidingWindow{limit: limit, duration: duration, maxKeys: maxKeys, windows: make(map[string]*Window)}, nil
}

func (s *SlidingWindow) Allow(key string, now int64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validInput(key, now) {
		return false, errors.New("invalid key/time")
	}
	window := s.windows[key]
	if window == nil {
		if len(s.windows) >= s.maxKeys {
			return false, errors.New("key capacity reached")
		}
		window = &Window{}
		s.windows[key] = window
	}
	if now < window.lastSeen {
		return false, errors.New("clock moved backwards")
	}
	window.lastSeen = now
	expired := 0
	for expired < len(window.accepted) && window.accepted[expired] <= now-s.duration {
		expired++
	}
	// Compact in place so backing storage does not retain expired history.
	copy(window.accepted, window.accepted[expired:])
	window.accepted = window.accepted[:len(window.accepted)-expired]
	if len(window.accepted) == s.limit {
		return false, nil
	}
	window.accepted = append(window.accepted, now)
	return true, nil
}

type RateLimiter struct {
	dimension string
	strategy  Strategy
}

func NewRateLimiter(dimension string, strategy Strategy) (*RateLimiter, error) {
	if (dimension != "user" && dimension != "ip" && dimension != "endpoint") || strategy == nil {
		return nil, errors.New("invalid policy")
	}
	return &RateLimiter{dimension: dimension, strategy: strategy}, nil
}

func (s *RateLimiter) Allow(request Request, now int64) (bool, error) {
	key := request.Endpoint
	if s.dimension == "user" {
		key = request.User
	} else if s.dimension == "ip" {
		key = request.IP
	}
	return s.strategy.Allow(key, now)
}

func check(condition bool) {
	if !condition {
		panic("contract failed")
	}
}

func value[T any](result T, err error) T {
	if err != nil {
		panic(err)
	}
	return result
}

func main() {
	alice := Request{"alice", "ip1", "/orders"}
	bob := Request{"bob", "ip1", "/orders"}
	tokens := value(NewRateLimiter("user", value(NewTokenBucket(2, 1000, 2))))
	check(value(tokens.Allow(alice, 0)))
	check(value(tokens.Allow(alice, 0)))
	check(!value(tokens.Allow(alice, 999)))
	check(value(tokens.Allow(alice, 1000)))
	check(!value(tokens.Allow(alice, 1000)))
	check(value(tokens.Allow(bob, 0)))
	_, err := tokens.Allow(alice, 999)
	check(err != nil)
	_, err = tokens.Allow(Request{"charlie", "ip2", "/orders"}, 1000)
	check(err != nil)
	window := value(NewRateLimiter("ip", value(NewSlidingWindow(2, 1000, 2))))
	check(value(window.Allow(alice, 0)))
	check(value(window.Allow(bob, 500)))
	check(!value(window.Allow(alice, 999)))
	check(value(window.Allow(alice, 1000)))
	check(!value(window.Allow(bob, 1000)))
	check(value(window.Allow(bob, 1500)))
	_, err = window.Allow(alice, 1499)
	check(err != nil)
	_, err = NewTokenBucket(0, 1, 1)
	check(err != nil)
	_, err = NewSlidingWindow(1, 0, 1)
	check(err != nil)
	_, err = tokens.Allow(Request{"", "ip", "/"}, 0)
	check(err != nil)
	idle := value(NewTokenBucket(2, 1000, 1))
	check(value(idle.Allow("x", 0)))
	check(value(idle.Allow("x", 1000000)))
	check(value(idle.Allow("x", 1000000)))
	check(!value(idle.Allow("x", 1000000)))
	endpoint := value(NewRateLimiter("endpoint", value(NewSlidingWindow(1, 1000, 2))))
	check(value(endpoint.Allow(alice, 0)))
	check(!value(endpoint.Allow(bob, 0)))
	fmt.Println("Token bucket: allow,allow,deny,allow")
	fmt.Println("Sliding window: allow,allow,deny,allow")
	fmt.Println("Key isolation and shared scopes: passed")
	fmt.Println("Edge cases: passed")
}
