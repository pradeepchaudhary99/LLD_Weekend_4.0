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
 * - Round robin wraps its cursor every selection; least connections breaks ties by registration
 * order.
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
 * - python3 scripts/run.py "Java" InterviewQuestions/LoadBalancerDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class LoadBalancerDemo {
    static class Server {
        final String id;
        final int weight;
        boolean healthy = true;
        int active;

        Server(String id, int weight) {
            this.id = id;
            this.weight = weight;
        }
    }

    interface Strategy {
        int select(List<Server> servers);
    }

    static class RoundRobin implements Strategy {
        int cursor;

        public int select(List<Server> servers) {
            int index = cursor % servers.size();
            cursor = (index + 1) % servers.size();
            return index;
        }
    }

    static class LeastConnections implements Strategy {
        public int select(List<Server> servers) {
            int best = 0;
            for (int index = 1; index < servers.size(); index++) {
                if (servers.get(index).active < servers.get(best).active) {
                    best = index;
                }
            }
            return best;
        }
    }

    static class WeightedRoundRobin implements Strategy {
        int cursor;

        public int select(List<Server> servers) {
            int total = 0;
            for (Server server : servers) {
                total += server.weight;
            }
            int ticket = cursor % total;
            cursor = (ticket + 1) % total;
            for (int index = 0; index < servers.size(); index++) {
                ticket -= servers.get(index).weight;
                if (ticket < 0) {
                    return index;
                }
            }
            throw new IllegalStateException("Invalid weights");
        }
    }

    static class RandomStrategy implements Strategy {
        final Random random = new Random(7);

        public int select(List<Server> servers) {
            return random.nextInt(servers.size());
        }
    }

    interface HealthObserver {
        void onHealth(String id, boolean healthy);
    }

    static class HealthService {
        private final HealthObserver observer;

        HealthService(HealthObserver observer) {
            this.observer = observer;
        }

        void report(String id, boolean healthy) {
            observer.onHealth(id, healthy);
        }
    }

    static class LoadBalancer implements HealthObserver {
        private final Map<String, Server> servers = new LinkedHashMap<>();
        private final Map<String, Server> leases = new LinkedHashMap<>();
        private Strategy strategy = new RoundRobin();

        synchronized void register(String id, int weight) {
            require(id != null && !id.isEmpty() && !servers.containsKey(id),
                    "Invalid/duplicate server");
            require(weight >= 1 && weight <= 1000 && servers.size() < 1000,
                    "Invalid capacity/weight");
            servers.put(id, new Server(id, weight));
        }

        synchronized void remove(String id) {
            require(servers.containsKey(id), "Unknown server");
            require(servers.get(id).active == 0, "Server is busy");
            servers.remove(id);
        }

        public synchronized void onHealth(String id, boolean healthy) {
            require(servers.containsKey(id), "Unknown server");
            servers.get(id).healthy = healthy;
        }

        synchronized void setStrategy(Strategy strategy) {
            require(strategy != null, "Missing strategy");
            this.strategy = strategy;
        }

        synchronized String acquire(String requestId) {
            require(requestId != null && !requestId.isEmpty() && !leases.containsKey(requestId),
                    "Invalid/duplicate request");
            List<Server> healthy = new ArrayList<>();
            for (Server server : servers.values()) {
                if (server.healthy) {
                    healthy.add(server);
                }
            }
            require(!healthy.isEmpty(), "No healthy servers");
            int index = strategy.select(healthy);
            require(index >= 0 && index < healthy.size(), "Invalid strategy result");
            Server selected = healthy.get(index);
            require(selected.active < 1_000_000, "Server connection limit");
            selected.active++;
            leases.put(requestId, selected);
            return selected.id;
        }

        synchronized void release(String requestId) {
            require(leases.containsKey(requestId), "Unknown/completed request");
            Server server = leases.remove(requestId);
            server.active--;
        }
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
        LoadBalancer balancer = new LoadBalancer();
        HealthService health = new HealthService(balancer);
        rejected(() -> balancer.acquire("empty"));
        rejected(() -> balancer.register("bad", 0));
        balancer.register("A", 2);
        balancer.register("B", 1);
        rejected(() -> balancer.register("A", 1));
        check(balancer.acquire("r1").equals("A"));
        check(balancer.acquire("r2").equals("B"));
        rejected(() -> balancer.acquire("r1"));
        rejected(() -> balancer.remove("A"));
        balancer.release("r1");
        balancer.setStrategy(new LeastConnections());
        check(balancer.acquire("r3").equals("A"));
        health.report("A", false);
        check(balancer.acquire("r4").equals("B"));
        health.report("B", false);
        rejected(() -> balancer.acquire("none"));
        for (String id : List.of("r2", "r3", "r4")) {
            balancer.release(id);
        }
        rejected(() -> balancer.release("r4"));
        health.report("A", true);
        health.report("B", true);
        balancer.setStrategy(new WeightedRoundRobin());
        int countA = 0;
        for (int index = 0; index < 6; index++) {
            if (balancer.acquire("weighted").equals("A")) {
                countA++;
            }
            balancer.release("weighted");
        }
        check(countA == 4);
        balancer.setStrategy(new RandomStrategy());
        check(List.of("A", "B").contains(balancer.acquire("random")));
        balancer.release("random");
        balancer.remove("A");
        check(balancer.acquire("last").equals("B"));
        balancer.release("last");
        System.out.println("Round robin: A,B");
        System.out.println("Least connections: A");
        System.out.println("Healthy fallback: B");
        System.out.println("Weighted A:B: 4:2");
        System.out.println("Edge cases: passed");
    }
}
