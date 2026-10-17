# Load balancing and rate limiting

Two runnable interview lessons are available in Java, Python, C++, Go, C#,
JavaScript and TypeScript. Each source begins with requirements, core entities,
operations, design choices, assumptions, edge cases and exact run commands.
The original Java sketches are preserved verbatim in
`classroom-drafts/2026-10-17/*.java.txt`.

## Prerequisites and run commands

Use Python 3.10+, Java 17+, a C++17 compiler, Go 1.26+ (matching `Go/go.mod`),
Node.js 18+, and .NET 8 SDK for the languages you want to run.
Run `npm ci` to install the pinned TypeScript compiler. If .NET is not on PATH,
set `DOTNET` to the full path of its executable.

Run from the repository root:

```sh
python3 scripts/run.py Java InterviewQuestions/LoadBalancerDemo
python3 scripts/run.py Python InterviewQuestions/LoadBalancerDemo
python3 scripts/run.py 'C++' InterviewQuestions/LoadBalancerDemo
python3 scripts/run.py Go InterviewQuestions/LoadBalancerDemo
python3 scripts/run.py 'C#' InterviewQuestions/LoadBalancerDemo
python3 scripts/run.py JavaScript InterviewQuestions/LoadBalancerDemo
python3 scripts/run.py TypeScript InterviewQuestions/LoadBalancerDemo

python3 scripts/run.py Java InterviewQuestions/RateLimitingDemo
python3 scripts/run.py Python InterviewQuestions/RateLimitingDemo
python3 scripts/run.py 'C++' InterviewQuestions/RateLimitingDemo
python3 scripts/run.py Go InterviewQuestions/RateLimitingDemo
python3 scripts/run.py 'C#' InterviewQuestions/RateLimitingDemo
python3 scripts/run.py JavaScript InterviewQuestions/RateLimitingDemo
python3 scripts/run.py TypeScript InterviewQuestions/RateLimitingDemo
```

Java lessons compile in isolation through the runner. They intentionally use nested
entities so the two new examples can also be tested together without class-name clashes.

## Load balancer walkthrough

`LoadBalancer` is the facade that owns registered servers and request leases.
`Strategy` chooses a healthy candidate; `HealthService` reports health through a
`HealthObserver` boundary. A server's ID represents its backend address in this
simulation; there is no real network forwarding.

1. Register servers A and B with positive weights. Reject duplicates and invalid weights.
2. Acquire a unique active request ID. Under one lock, filter healthy servers, choose
   one, increase its active count and retain the request-to-server mapping.
3. Process work, then call release in `finally`/`defer` even if processing fails.
   Unknown or already released requests are rejected; counts cannot become negative.
4. An unhealthy report prevents new assignments. Existing leases remain valid and
   can still be released. Removing a busy backend is rejected until it drains.
5. Replacing the strategy changes later routing. A fresh instance starts fresh cursor
   state; server counts and outstanding leases remain unchanged.

| Policy | Selection rule | Teaching trade-off |
| --- | --- | --- |
| Round robin | Next healthy candidate, wrapping each time | Simple; ignores request duration |
| Random | Uniform random candidate | Distribution is probabilistic; RNG sequences differ by language |
| Least connections | Smallest active count, registration-order tie break | Selection and reservation must be atomic |
| Weighted round robin | Consecutive tickets proportional to weight | A=2, B=1 yields A,A,B; this is not smooth weighting |

The demo asserts A/B round-robin routing, least-connections selection, unhealthy
fallback, no-healthy-server rejection, duplicate requests, busy removal, double
release, strategy changes and a 4:2 weighted distribution over six assignments.

All policies first construct the healthy list in O(n). Round-robin/random selection
is O(1) afterward; least-connections and weighted selection are O(n). Registry size
is capped at 1000, weights at 1000, and active requests at 1,000,000 per server.
Strategy instances belong to one balancer and must not mutate candidates, call back
into the facade or perform blocking work. They are trusted application components.

Health reports are explicit inputs to the lesson. A real recurring probe loop,
timeouts, consecutive-failure thresholds, stale-probe generations, HTTP proxying,
connection timeouts and automatic lease expiry remain interview extensions. This
example does not claim to run continuous network health checks.

## Rate limiter walkthrough

`RateLimiter` extracts one configured dimension from `Request`: user, IP or endpoint.
It delegates to an owned `Strategy`; different instances have independent namespaces.
Pass **elapsed monotonic milliseconds**, not epoch timestamps, and do not move time
backward for a key. Accepting time as an argument makes exact-boundary tests repeatable.

| Policy | State and decision | Cost |
| --- | --- | --- |
| Token bucket | Starts full; replenishes one whole token each period; consumes one on allowance | O(1) state/update per key, plus map lookup |
| Sliding window | Retains accepted timestamps in `(now-duration, now]`; denies at the limit | O(limit) state per key; deque expiry amortized per entry |

Token refill advances its boundary only by whole periods, preserving partial elapsed
time across frequent denied calls. Unused refill beyond capacity is discarded. For
capacity 2 and period 1000 ms, two requests at 0 pass, one at 999 is denied, and one
at 1000 passes. This is a discrete-refill token bucket, not a fractional-token model.

For a sliding window of 1000 ms and limit 2, requests at 0 and 500 pass. At 999 a
third is denied. At 1000, the timestamp at 0 expires, making room for one request;
the timestamp at 500 remains until 1500. Denials do not extend the accepted history.
Both algorithms record the last observed timestamp even on denial.

Each instance has a `maxKeys` bound. Once reached, a new key produces an error rather
than silently resetting an old quota through eviction. Existing keys remain usable.
The caller should translate capacity errors into a defined fail-closed response.
Use modest limits: sliding-window worst-case storage is `maxKeys * limit` timestamps.
JS/TS filter arrays and Go compacts slices, costing O(limit) per check; native deque
editions remove each expired entry once. C++ ordered-map lookup costs O(log keys).

No distributed quota, persistence, HTTP headers, queuing or atomic combination of
multiple policies is implemented. Discuss trusted identity extraction, key expiry,
sharded locks, Redis atomic scripts and configuration migration in the interview.
Go APIs return errors for invalid input; other editions raise exceptions. Reaching
a valid request quota returns false in every edition.

## Concurrency and validation

Java, Python, C++, Go and C# protect mutable service state with a monitor or mutex.
JavaScript/TypeScript methods are synchronous without `await`; they are atomic only
relative to other tasks on the same event loop, not across worker threads/processes.
Keep policy objects owned by one service; do not mutate their internals externally.

All 14 demos include representative failure-path checks. Additional Python tests
cover frequent refill checks, window denial behavior, bounds, key capacity,
registration order and invalid custom strategy results. Java and Go concurrency
tests verify exactly 17 allowances from 200 same-time attempts and 100/100
least-connections assignments from 200 concurrently acquired leases.

```sh
python3 scripts/validate.py
python3 scripts/test_balancer_rate_limit.py
(cd Go && go test -race ./InterviewQuestions/LoadBalancerDemo ./InterviewQuestions/RateLimitingDemo)
mkdir -p /tmp/lld-balancer-rate-checks
javac -d /tmp/lld-balancer-rate-checks Java/InterviewQuestions/LoadBalancerDemo.java Java/InterviewQuestions/RateLimitingDemo.java scripts/java_checks/BalancerRateChecks.java
java -cp /tmp/lld-balancer-rate-checks InterviewQuestions.BalancerRateChecks
```
