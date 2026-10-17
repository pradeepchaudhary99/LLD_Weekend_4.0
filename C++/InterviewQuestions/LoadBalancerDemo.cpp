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
 * - python3 scripts/run.py "C++" InterviewQuestions/LoadBalancerDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

#include <algorithm>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

struct Server {
    std::string id;
    int weight;
    bool healthy = true;
    int active = 0;
};

struct Strategy {
    virtual int select(const std::vector<std::shared_ptr<Server>> &servers) = 0;
    virtual ~Strategy() = default;
};

struct RoundRobin : Strategy {
    size_t cursor = 0;
    int select(const std::vector<std::shared_ptr<Server>> &servers) override {
        size_t index = cursor % servers.size();
        cursor = (index + 1) % servers.size();
        return static_cast<int>(index);
    }
};

struct LeastConnections : Strategy {
    int select(const std::vector<std::shared_ptr<Server>> &servers) override {
        size_t best = 0;
        for (size_t index = 1; index < servers.size(); index++) {
            if (servers[index]->active < servers[best]->active) {
                best = index;
            }
        }
        return static_cast<int>(best);
    }
};

struct WeightedRoundRobin : Strategy {
    int cursor = 0;
    int select(const std::vector<std::shared_ptr<Server>> &servers) override {
        int total = 0;
        for (const auto &server : servers) {
            total += server->weight;
        }
        int ticket = cursor % total;
        cursor = (ticket + 1) % total;
        for (size_t index = 0; index < servers.size(); index++) {
            ticket -= servers[index]->weight;
            if (ticket < 0) {
                return static_cast<int>(index);
            }
        }
        throw std::logic_error("Invalid weights");
    }
};

struct RandomStrategy : Strategy {
    std::mt19937 random{7};
    int select(const std::vector<std::shared_ptr<Server>> &servers) override {
        std::uniform_int_distribution<int> distribution(0, static_cast<int>(servers.size()) - 1);
        return distribution(random);
    }
};

struct HealthObserver {
    virtual void onHealth(const std::string &id, bool healthy) = 0;
    virtual ~HealthObserver() = default;
};

class HealthService {
    HealthObserver &observer;

  public:
    explicit HealthService(HealthObserver &observer) : observer(observer) {
    }
    void report(const std::string &id, bool healthy) {
        observer.onHealth(id, healthy);
    }
};

void require(bool condition, const std::string &message) {
    if (!condition) {
        throw std::invalid_argument(message);
    }
}

class LoadBalancer : public HealthObserver {
    std::mutex mutex;
    std::map<std::string, std::shared_ptr<Server>> servers;
    std::vector<std::shared_ptr<Server>> registrationOrder;
    std::map<std::string, std::shared_ptr<Server>> leases;
    std::unique_ptr<Strategy> strategy = std::make_unique<RoundRobin>();

  public:
    void registerServer(const std::string &id, int weight) {
        std::lock_guard<std::mutex> lock(mutex);
        require(!id.empty() && !servers.count(id), "Invalid/duplicate server");
        require(weight >= 1 && weight <= 1000 && servers.size() < 1000, "Invalid capacity/weight");
        auto server = std::make_shared<Server>(Server{id, weight});
        servers.emplace(id, server);
        registrationOrder.push_back(server);
    }

    void remove(const std::string &id) {
        std::lock_guard<std::mutex> lock(mutex);
        require(servers.count(id) != 0, "Unknown server");
        auto server = servers.at(id);
        require(server->active == 0, "Server is busy");
        registrationOrder.erase(
            std::remove(registrationOrder.begin(), registrationOrder.end(), server),
            registrationOrder.end());
        servers.erase(id);
    }

    void onHealth(const std::string &id, bool healthy) override {
        std::lock_guard<std::mutex> lock(mutex);
        require(servers.count(id) != 0, "Unknown server");
        servers.at(id)->healthy = healthy;
    }

    void setStrategy(std::unique_ptr<Strategy> next) {
        std::lock_guard<std::mutex> lock(mutex);
        require(next != nullptr, "Missing strategy");
        strategy = std::move(next);
    }

    std::string acquire(const std::string &requestId) {
        std::lock_guard<std::mutex> lock(mutex);
        require(!requestId.empty() && !leases.count(requestId), "Invalid/duplicate request");
        std::vector<std::shared_ptr<Server>> healthy;
        for (const auto &server : registrationOrder) {
            if (server->healthy) {
                healthy.push_back(server);
            }
        }
        require(!healthy.empty(), "No healthy servers");
        int index = strategy->select(healthy);
        require(index >= 0 && static_cast<size_t>(index) < healthy.size(),
                "Invalid strategy result");
        auto selected = healthy.at(index);
        require(selected->active < 1000000, "Server connection limit");
        selected->active++;
        leases.emplace(requestId, selected);
        return selected->id;
    }

    void release(const std::string &requestId) {
        std::lock_guard<std::mutex> lock(mutex);
        require(leases.count(requestId) != 0, "Unknown/completed request");
        leases.at(requestId)->active--;
        leases.erase(requestId);
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
    LoadBalancer balancer;
    HealthService health(balancer);
    rejected([&] {
        balancer.acquire("empty");
    });
    rejected([&] {
        balancer.registerServer("bad", 0);
    });
    balancer.registerServer("A", 2);
    balancer.registerServer("B", 1);
    rejected([&] {
        balancer.registerServer("A", 1);
    });
    check(balancer.acquire("r1") == "A");
    check(balancer.acquire("r2") == "B");
    rejected([&] {
        balancer.acquire("r1");
    });
    rejected([&] {
        balancer.remove("A");
    });
    balancer.release("r1");
    balancer.setStrategy(std::make_unique<LeastConnections>());
    check(balancer.acquire("r3") == "A");
    health.report("A", false);
    check(balancer.acquire("r4") == "B");
    health.report("B", false);
    rejected([&] {
        balancer.acquire("none");
    });
    for (const auto &id : {"r2", "r3", "r4"}) {
        balancer.release(id);
    }
    rejected([&] {
        balancer.release("r4");
    });
    health.report("A", true);
    health.report("B", true);
    balancer.setStrategy(std::make_unique<WeightedRoundRobin>());
    int countA = 0;
    for (int index = 0; index < 6; index++) {
        if (balancer.acquire("weighted") == "A") {
            countA++;
        }
        balancer.release("weighted");
    }
    check(countA == 4);
    balancer.setStrategy(std::make_unique<RandomStrategy>());
    check(balancer.acquire("random") != "");
    balancer.release("random");
    balancer.remove("A");
    check(balancer.acquire("last") == "B");
    balancer.release("last");
    std::cout << "Round robin: A,B" << "\n";
    std::cout << "Least connections: A" << "\n";
    std::cout << "Healthy fallback: B" << "\n";
    std::cout << "Weighted A:B: 4:2" << "\n";
    std::cout << "Edge cases: passed" << "\n";
}
