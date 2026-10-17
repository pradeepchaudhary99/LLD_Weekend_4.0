/*
 * INTERVIEW DESIGN NOTES - Load Balancer
 *
 * Functional requirements:
 * - Register/remove servers and route requests only to currently healthy servers.
 * - Support round robin, random, least connections and weighted round robin.
 * - Replace selection strategy at runtime; report health through an Observer boundary.
 * - Acquire a request lease and release it when work finishes, even after a health failure.
 *
 * Non-functional requirements:
 * - Serialize selection and active-count updates so least-connections decisions are atomic.
 * - Bound cursor arithmetic and reject invalid weights, duplicate IDs and double release.
 * - Keep routing separate from health observation and selection policy.
 *
 * Core entities and responsibilities:
 * - Server: ID, positive weight, health flag and active request count.
 * - Strategy: chooses an index from the nonempty healthy snapshot.
 * - LoadBalancer: owns registry, live request leases, counts and strategy replacement.
 * - HealthService/HealthObserver: reports simulated probe results to the balancer.
 * - Request: represented by its unique active request ID; no payload forwarding here.
 *
 * Operations and flow:
 * - register -> health report -> acquire -> filter healthy -> select -> increment count.
 * - Caller processes work and releases in finally/defer; release decrements exactly once.
 * - Marking unhealthy stops new selection but does not cancel existing requests.
 *
 * Design choices and invariants:
 * - Strategy and Observer keep the facade independent of policy/probe implementations.
 * - Round robin wraps its cursor every selection; least connections breaks ties by registration order.
 * - Weighted round robin uses consecutive tickets, not smooth weighting: 2:1 yields A,A,B.
 * - Selection and reservation share one lock in native editions. Strategies belong to one
 *   balancer and must return promptly without mutation, reentrancy or network calls.
 * - Registration order is stable; removing a busy server is rejected until leases drain.
 *
 * Assumptions and scope:
 * - In-memory single-process simulation; no sockets, HTTP forwarding, retries or persistence.
 * - HealthService accepts explicit simulated reports; periodic probe scheduling, failure
 *   thresholds and stale-probe generation checks are production extensions, not implemented.
 * - At most 1000 servers; weights 1..1000; at most 1000000 live leases per server.
 * - Strategy replacement does not migrate its cursor; use a fresh strategy to reset it.
 * - All choices filter in O(number of servers); random/round-robin selection is O(1)
 *   after filtering. C++ registry lookup is O(log n); hash-map editions average O(1).
 * - JavaScript/TypeScript run synchronously on one event loop; no cross-worker guarantee.
 *
 * Edge cases and interview discussion:
 * - Empty/unhealthy fleet, duplicate IDs, invalid weights, busy removal, double release,
 *   health changes with outstanding work, membership changes and weighted distribution.
 * - Discuss health-probe polling, connection leaks, passive failures and draining/removal.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "TypeScript" InterviewQuestions/LoadBalancerDemo
 * - python3 scripts/run.py JavaScript InterviewQuestions/LoadBalancerDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

export {};

class Server {
    healthy = true;
    active = 0;
    constructor(
        readonly id: string,
        readonly weight: number,
    ) {}
}

interface Strategy {
    select(servers: readonly Server[]): number;
}

class RoundRobin implements Strategy {
    private cursor = 0;
    select(servers: readonly Server[]): number {
        const index = this.cursor % servers.length;
        this.cursor = (index + 1) % servers.length;
        return index;
    }
}

class LeastConnections implements Strategy {
    select(servers: readonly Server[]): number {
        let best = 0;
        for (let index = 1; index < servers.length; index++) {
            if (servers[index].active < servers[best].active) {
                best = index;
            }
        }
        return best;
    }
}

class WeightedRoundRobin implements Strategy {
    private cursor = 0;
    select(servers: readonly Server[]): number {
        const total = servers.reduce((sum, server) => sum + server.weight, 0);
        let ticket = this.cursor % total;
        this.cursor = (ticket + 1) % total;
        for (let index = 0; index < servers.length; index++) {
            ticket -= servers[index].weight;
            if (ticket < 0) {
                return index;
            }
        }
        throw new Error("Invalid weights");
    }
}

class RandomStrategy implements Strategy {
    select(servers: readonly Server[]): number {
        return Math.floor(Math.random() * servers.length);
    }
}

interface HealthObserver {
    onHealth(id: string, healthy: boolean): void;
}

class HealthService {
    constructor(private readonly observer: HealthObserver) {}
    report(id: string, healthy: boolean): void {
        this.observer.onHealth(id, healthy);
    }
}

function requireCondition(condition: boolean, message: string): asserts condition {
    if (!condition) {
        throw new Error(message);
    }
}

class LoadBalancer implements HealthObserver {
    private readonly servers = new Map<string, Server>();
    private readonly leases = new Map<string, Server>();
    private strategy: Strategy = new RoundRobin();

    register(id: string, weight: number): void {
        requireCondition(id.length > 0 && !this.servers.has(id), "Invalid/duplicate server");
        requireCondition(
            Number.isInteger(weight) && weight >= 1 && weight <= 1000 && this.servers.size < 1000,
            "Invalid capacity/weight",
        );
        this.servers.set(id, new Server(id, weight));
    }

    remove(id: string): void {
        const server = this.servers.get(id);
        requireCondition(server !== undefined, "Unknown server");
        requireCondition(server.active === 0, "Server is busy");
        this.servers.delete(id);
    }

    onHealth(id: string, healthy: boolean): void {
        const server = this.servers.get(id);
        requireCondition(server !== undefined, "Unknown server");
        server.healthy = healthy;
    }

    setStrategy(strategy: Strategy): void {
        requireCondition(strategy !== null && strategy !== undefined, "Missing strategy");
        this.strategy = strategy;
    }

    acquire(requestId: string): string {
        requireCondition(
            requestId.length > 0 && !this.leases.has(requestId),
            "Invalid/duplicate request",
        );
        const healthy = [...this.servers.values()].filter((server) => server.healthy);
        requireCondition(healthy.length > 0, "No healthy servers");
        const index = this.strategy.select(healthy);
        requireCondition(
            Number.isInteger(index) && index >= 0 && index < healthy.length,
            "Invalid strategy result",
        );
        const selected = healthy[index];
        requireCondition(selected.active < 1_000_000, "Server connection limit");
        selected.active++;
        this.leases.set(requestId, selected);
        return selected.id;
    }

    release(requestId: string): void {
        const server = this.leases.get(requestId);
        requireCondition(server !== undefined, "Unknown/completed request");
        server.active--;
        this.leases.delete(requestId);
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

const balancer = new LoadBalancer();
const health = new HealthService(balancer);
rejected(() => balancer.acquire("empty"));
rejected(() => balancer.register("bad", 0));
balancer.register("A", 2);
balancer.register("B", 1);
rejected(() => balancer.register("A", 1));
check(balancer.acquire("r1") === "A");
check(balancer.acquire("r2") === "B");
rejected(() => balancer.acquire("r1"));
rejected(() => balancer.remove("A"));
balancer.release("r1");
balancer.setStrategy(new LeastConnections());
check(balancer.acquire("r3") === "A");
health.report("A", false);
check(balancer.acquire("r4") === "B");
health.report("B", false);
rejected(() => balancer.acquire("none"));
for (const id of ["r2", "r3", "r4"]) {
    balancer.release(id);
}
rejected(() => balancer.release("r4"));
health.report("A", true);
health.report("B", true);
balancer.setStrategy(new WeightedRoundRobin());
let countA = 0;
for (let index = 0; index < 6; index++) {
    if (balancer.acquire("weighted") === "A") {
        countA++;
    }
    balancer.release("weighted");
}
check(countA === 4);
balancer.setStrategy(new RandomStrategy());
check(["A", "B"].includes(balancer.acquire("random")));
balancer.release("random");
balancer.remove("A");
check(balancer.acquire("last") === "B");
balancer.release("last");
console.log("Round robin: A,B");
console.log("Least connections: A");
console.log("Healthy fallback: B");
console.log("Weighted A:B: 4:2");
console.log("Edge cases: passed");
