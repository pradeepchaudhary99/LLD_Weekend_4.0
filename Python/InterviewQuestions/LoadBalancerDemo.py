# INTERVIEW DESIGN NOTES - Load Balancer
#
# Functional requirements:
# - Register/remove servers and route requests only to currently healthy servers.
# - Support round robin, random, least connections and weighted round robin.
# - Replace selection strategy at runtime; report health through an Observer boundary.
# - Acquire a request lease and release it when work finishes, even after a health failure.
#
# Non-functional requirements:
# - Serialize selection and active-count updates so least-connections decisions are atomic.
# - Bound cursor arithmetic and reject invalid weights, duplicate IDs and double release.
# - Keep routing separate from health observation and selection policy.
#
# Core entities and responsibilities:
# - Server: ID, positive weight, health flag and active request count.
# - Strategy: chooses an index from the nonempty healthy snapshot.
# - LoadBalancer: owns registry, live request leases, counts and strategy replacement.
# - HealthService/HealthObserver: reports simulated probe results to the balancer.
# - Request: represented by its unique active request ID; no payload forwarding here.
#
# Operations and flow:
# - register -> health report -> acquire -> filter healthy -> select -> increment count.
# - Caller processes work and releases in finally/defer; release decrements exactly once.
# - Marking unhealthy stops new selection but does not cancel existing requests.
#
# Design choices and invariants:
# - Strategy and Observer keep the facade independent of policy/probe implementations.
# - Round robin wraps its cursor every selection; least connections breaks ties by registration order.
# - Weighted round robin uses consecutive tickets, not smooth weighting: 2:1 yields A,A,B.
# - Selection and reservation share one lock in native editions. Strategies belong to one
#   balancer and must return promptly without mutation, reentrancy or network calls.
# - Registration order is stable; removing a busy server is rejected until leases drain.
#
# Assumptions and scope:
# - In-memory single-process simulation; no sockets, HTTP forwarding, retries or persistence.
# - HealthService accepts explicit simulated reports; periodic probe scheduling, failure
#   thresholds and stale-probe generation checks are production extensions, not implemented.
# - At most 1000 servers; weights 1..1000; at most 1000000 live leases per server.
# - Strategy replacement does not migrate its cursor; use a fresh strategy to reset it.
# - All choices filter in O(number of servers); random/round-robin selection is O(1)
#   after filtering. C++ registry lookup is O(log n); hash-map editions average O(1).
# - JavaScript/TypeScript run synchronously on one event loop; no cross-worker guarantee.
#
# Edge cases and interview discussion:
# - Empty/unhealthy fleet, duplicate IDs, invalid weights, busy removal, double release,
#   health changes with outstanding work, membership changes and weighted distribution.
# - Discuss health-probe polling, connection leaks, passive failures and draining/removal.
#
# Run commands (from repository root):
# - python3 scripts/run.py "Python" InterviewQuestions/LoadBalancerDemo
# - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.

from dataclasses import dataclass
from random import Random
from threading import RLock


@dataclass
class Server:
    id: str
    weight: int
    healthy: bool = True
    active: int = 0


class RoundRobin:
    def __init__(self):
        self.cursor = 0

    def select(self, servers):
        index = self.cursor % len(servers)
        self.cursor = (index + 1) % len(servers)
        return index


class LeastConnections:
    def select(self, servers):
        return min(range(len(servers)), key=lambda index: servers[index].active)


class WeightedRoundRobin:
    def __init__(self):
        self.cursor = 0

    def select(self, servers):
        total = sum(server.weight for server in servers)
        ticket = self.cursor % total
        self.cursor = (ticket + 1) % total
        for index, server in enumerate(servers):
            ticket -= server.weight
            if ticket < 0:
                return index
        raise RuntimeError("Invalid weights")


class RandomStrategy:
    def __init__(self):
        self.random = Random(7)

    def select(self, servers):
        return self.random.randrange(len(servers))


class HealthService:
    def __init__(self, observer):
        self.observer = observer

    def report(self, server_id, healthy):
        self.observer.on_health(server_id, healthy)


def require(condition, message):
    if not condition:
        raise ValueError(message)


class LoadBalancer:
    def __init__(self):
        self._lock = RLock()
        self._servers = {}
        self._leases = {}
        self._strategy = RoundRobin()

    def register(self, server_id, weight):
        with self._lock:
            require(bool(server_id) and server_id not in self._servers, "Invalid/duplicate server")
            require(
                type(weight) is int and 1 <= weight <= 1000 and len(self._servers) < 1000,
                "Invalid capacity/weight",
            )
            self._servers[server_id] = Server(server_id, weight)

    def remove(self, server_id):
        with self._lock:
            require(server_id in self._servers, "Unknown server")
            require(self._servers[server_id].active == 0, "Server is busy")
            del self._servers[server_id]

    def on_health(self, server_id, healthy):
        with self._lock:
            require(server_id in self._servers, "Unknown server")
            self._servers[server_id].healthy = healthy

    def set_strategy(self, strategy):
        with self._lock:
            require(strategy is not None, "Missing strategy")
            self._strategy = strategy

    def acquire(self, request_id):
        with self._lock:
            require(
                bool(request_id) and request_id not in self._leases, "Invalid/duplicate request"
            )
            servers = [server for server in self._servers.values() if server.healthy]
            require(bool(servers), "No healthy servers")
            index = self._strategy.select(servers)
            require(type(index) is int and 0 <= index < len(servers), "Invalid strategy result")
            selected = servers[index]
            require(selected.active < 1_000_000, "Server connection limit")
            selected.active += 1
            self._leases[request_id] = selected
            return selected.id

    def release(self, request_id):
        with self._lock:
            require(request_id in self._leases, "Unknown/completed request")
            self._leases.pop(request_id).active -= 1


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
    balancer = LoadBalancer()
    health = HealthService(balancer)
    rejected(lambda: balancer.acquire("empty"))
    rejected(lambda: balancer.register("bad", 0))
    balancer.register("A", 2)
    balancer.register("B", 1)
    rejected(lambda: balancer.register("A", 1))
    check(balancer.acquire("r1") == "A")
    check(balancer.acquire("r2") == "B")
    rejected(lambda: balancer.acquire("r1"))
    rejected(lambda: balancer.remove("A"))
    balancer.release("r1")
    balancer.set_strategy(LeastConnections())
    check(balancer.acquire("r3") == "A")
    health.report("A", False)
    check(balancer.acquire("r4") == "B")
    health.report("B", False)
    rejected(lambda: balancer.acquire("none"))
    for key in ["r2", "r3", "r4"]:
        balancer.release(key)
    rejected(lambda: balancer.release("r4"))
    health.report("A", True)
    health.report("B", True)
    balancer.set_strategy(WeightedRoundRobin())
    count = 0
    for _ in range(6):
        count += balancer.acquire("weighted") == "A"
        balancer.release("weighted")
    check(count == 4)
    balancer.set_strategy(RandomStrategy())
    check(balancer.acquire("random") in ["A", "B"])
    balancer.release("random")
    balancer.remove("A")
    check(balancer.acquire("last") == "B")
    balancer.release("last")
    print("Round robin: A,B")
    print("Least connections: A")
    print("Healthy fallback: B")
    print("Weighted A:B: 4:2")
    print("Edge cases: passed")


if __name__ == "__main__":
    main()
