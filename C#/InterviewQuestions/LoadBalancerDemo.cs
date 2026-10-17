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
 * - python3 scripts/run.py "C#" InterviewQuestions/LoadBalancerDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

using System;
using System.Collections.Generic;

namespace LLDWeekend4.InterviewQuestions;

public class LoadBalancerDemo
{
    class Server
    {
        public readonly string id;
        public readonly int weight;
        public bool healthy = true;
        public int active;

        public Server(string id, int weight)
        {
            this.id = id;
            this.weight = weight;
        }
    }

    interface Strategy
    {
        int Select(List<Server> servers);
    }

    class RoundRobin : Strategy
    {
        public int cursor;

        public int Select(List<Server> servers)
        {
            int index = cursor % servers.Count;
            cursor = (index + 1) % servers.Count;
            return index;
        }
    }

    class LeastConnections : Strategy
    {
        public int Select(List<Server> servers)
        {
            int best = 0;
            for (int index = 1; index < servers.Count; index++)
            {
                if (servers[index].active < servers[best].active)
                {
                    best = index;
                }
            }
            return best;
        }
    }

    class WeightedRoundRobin : Strategy
    {
        public int cursor;

        public int Select(List<Server> servers)
        {
            int total = 0;
            foreach (Server server in servers)
            {
                total += server.weight;
            }
            int ticket = cursor % total;
            cursor = (ticket + 1) % total;
            for (int index = 0; index < servers.Count; index++)
            {
                ticket -= servers[index].weight;
                if (ticket < 0)
                {
                    return index;
                }
            }
            throw new InvalidOperationException("Invalid weights");
        }
    }

    class RandomStrategy : Strategy
    {
        public readonly Random random = new Random(7);

        public int Select(List<Server> servers)
        {
            return random.Next(servers.Count);
        }
    }

    interface HealthObserver
    {
        void OnHealth(string id, bool healthy);
    }

    class HealthService
    {
        private readonly HealthObserver observer;

        public HealthService(HealthObserver observer)
        {
            this.observer = observer;
        }

        public void Report(string id, bool healthy)
        {
            observer.OnHealth(id, healthy);
        }
    }

    class LoadBalancer : HealthObserver
    {
        private readonly object gate = new();
        private readonly Dictionary<string, Server> servers = new();
        private readonly Dictionary<string, Server> leases = new();
        private readonly List<Server> registrationOrder = new();
        private Strategy strategy = new RoundRobin();

        public void Register(string id, int weight)
        {
            lock (gate)
            {

                Require(id != null && id.Length > 0 && !servers.ContainsKey(id),
                        "Invalid/duplicate server");
                Require(weight >= 1 && weight <= 1000 && servers.Count < 1000,
                        "Invalid capacity/weight");
                servers[id] = new Server(id, weight);
                registrationOrder.Add(servers[id]);
            }
        }

        public void Remove(string id)
        {
            lock (gate)
            {

                Require(servers.ContainsKey(id), "Unknown server");
                Require(servers[id].active == 0, "Server is busy");
                registrationOrder.Remove(servers[id]);
                servers.Remove(id);
            }
        }

        public void OnHealth(string id, bool healthy)
        {
            lock (gate)
            {

                Require(servers.ContainsKey(id), "Unknown server");
                servers[id].healthy = healthy;
            }
        }

        public void SetStrategy(Strategy strategy)
        {
            lock (gate)
            {

                Require(strategy != null, "Missing strategy");
                this.strategy = strategy;
            }
        }

        public string Acquire(string requestId)
        {
            lock (gate)
            {

                Require(requestId != null && requestId.Length > 0 && !leases.ContainsKey(requestId),
                        "Invalid/duplicate request");
                List<Server> healthy = new();
                foreach (Server server in registrationOrder)
                {
                    if (server.healthy)
                    {
                        healthy.Add(server);
                    }
                }
                Require(healthy.Count > 0, "No healthy servers");
                int index = strategy.Select(healthy);
                Require(index >= 0 && index < healthy.Count, "Invalid strategy result");
                Server selected = healthy[index];
                Require(selected.active < 1_000_000, "Server connection limit");
                selected.active++;
                leases[requestId] = selected;
                return selected.id;
            }
        }

        public void Release(string requestId)
        {
            lock (gate)
            {

                Require(leases.ContainsKey(requestId), "Unknown/completed request");
                Server server = leases[requestId];
                leases.Remove(requestId);
                server.active--;
            }
        }
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
        LoadBalancer balancer = new LoadBalancer();
        HealthService health = new HealthService(balancer);
        Rejected(() => balancer.Acquire("empty"));
        Rejected(() => balancer.Register("bad", 0));
        balancer.Register("A", 2);
        balancer.Register("B", 1);
        Rejected(() => balancer.Register("A", 1));
        Check(balancer.Acquire("r1").Equals("A"));
        Check(balancer.Acquire("r2").Equals("B"));
        Rejected(() => balancer.Acquire("r1"));
        Rejected(() => balancer.Remove("A"));
        balancer.Release("r1");
        balancer.SetStrategy(new LeastConnections());
        Check(balancer.Acquire("r3").Equals("A"));
        health.Report("A", false);
        Check(balancer.Acquire("r4").Equals("B"));
        health.Report("B", false);
        Rejected(() => balancer.Acquire("none"));
        foreach (string id in new List<string> { "r2", "r3", "r4" })
        {
            balancer.Release(id);
        }
        Rejected(() => balancer.Release("r4"));
        health.Report("A", true);
        health.Report("B", true);
        balancer.SetStrategy(new WeightedRoundRobin());
        int countA = 0;
        for (int index = 0; index < 6; index++)
        {
            if (balancer.Acquire("weighted").Equals("A"))
            {
                countA++;
            }
            balancer.Release("weighted");
        }
        Check(countA == 4);
        balancer.SetStrategy(new RandomStrategy());
        Check(new List<string> { "A", "B" }.Contains(balancer.Acquire("random")));
        balancer.Release("random");
        balancer.Remove("A");
        Check(balancer.Acquire("last").Equals("B"));
        balancer.Release("last");
        Console.WriteLine("Round robin: A,B");
        Console.WriteLine("Least connections: A");
        Console.WriteLine("Healthy fallback: B");
        Console.WriteLine("Weighted A:B: 4:2");
        Console.WriteLine("Edge cases: passed");
    }
}
