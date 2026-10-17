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
 * - python3 scripts/run.py "Go" InterviewQuestions/LoadBalancerDemo
 * - Prerequisites and focused checks: see BALANCING_AND_RATE_LIMITING.md.
 */

package main

import (
	"errors"
	"fmt"
	"math/rand"
	"sync"
)

type Server struct {
	id      string
	weight  int
	healthy bool
	active  int
}

type Strategy interface {
	Select(servers []*Server) int
}

type RoundRobin struct {
	cursor int
}

func (s *RoundRobin) Select(servers []*Server) int {
	index := s.cursor % len(servers)
	s.cursor = (index + 1) % len(servers)
	return index
}

type LeastConnections struct{}

func (s *LeastConnections) Select(servers []*Server) int {
	best := 0
	for index := 1; index < len(servers); index++ {
		if servers[index].active < servers[best].active {
			best = index
		}
	}
	return best
}

type WeightedRoundRobin struct {
	cursor int
}

func (s *WeightedRoundRobin) Select(servers []*Server) int {
	total := 0
	for _, server := range servers {
		total += server.weight
	}
	ticket := s.cursor % total
	s.cursor = (ticket + 1) % total
	for index, server := range servers {
		ticket -= server.weight
		if ticket < 0 {
			return index
		}
	}
	return -1
}

type RandomStrategy struct {
	random *rand.Rand
}

func (s *RandomStrategy) Select(servers []*Server) int {
	return s.random.Intn(len(servers))
}

type HealthObserver interface {
	OnHealth(id string, healthy bool) error
}

type HealthService struct {
	observer HealthObserver
}

func (s *HealthService) Report(id string, healthy bool) error {
	return s.observer.OnHealth(id, healthy)
}

type LoadBalancer struct {
	mu                sync.Mutex
	servers           map[string]*Server
	registrationOrder []*Server
	leases            map[string]*Server
	strategy          Strategy
}

func NewLoadBalancer() *LoadBalancer {
	return &LoadBalancer{servers: make(map[string]*Server), leases: make(map[string]*Server), strategy: &RoundRobin{}}
}

func (b *LoadBalancer) Register(id string, weight int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if id == "" || b.servers[id] != nil {
		return errors.New("invalid/duplicate server")
	}
	if weight < 1 || weight > 1000 || len(b.servers) >= 1000 {
		return errors.New("invalid capacity/weight")
	}
	server := &Server{id: id, weight: weight, healthy: true}
	b.servers[id] = server
	b.registrationOrder = append(b.registrationOrder, server)
	return nil
}

func (b *LoadBalancer) Remove(id string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	server := b.servers[id]
	if server == nil {
		return errors.New("unknown server")
	}
	if server.active != 0 {
		return errors.New("server is busy")
	}
	for index, candidate := range b.registrationOrder {
		if candidate == server {
			b.registrationOrder = append(b.registrationOrder[:index], b.registrationOrder[index+1:]...)
			break
		}
	}
	delete(b.servers, id)
	return nil
}

func (b *LoadBalancer) OnHealth(id string, healthy bool) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	server := b.servers[id]
	if server == nil {
		return errors.New("unknown server")
	}
	server.healthy = healthy
	return nil
}

func (b *LoadBalancer) SetStrategy(strategy Strategy) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if strategy == nil {
		return errors.New("missing strategy")
	}
	b.strategy = strategy
	return nil
}

func (b *LoadBalancer) Acquire(requestID string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if requestID == "" || b.leases[requestID] != nil {
		return "", errors.New("invalid/duplicate request")
	}
	healthy := make([]*Server, 0)
	for _, server := range b.registrationOrder {
		if server.healthy {
			healthy = append(healthy, server)
		}
	}
	if len(healthy) == 0 {
		return "", errors.New("no healthy servers")
	}
	index := b.strategy.Select(healthy)
	if index < 0 || index >= len(healthy) {
		return "", errors.New("invalid strategy result")
	}
	selected := healthy[index]
	if selected.active >= 1000000 {
		return "", errors.New("server connection limit")
	}
	selected.active++
	b.leases[requestID] = selected
	return selected.id, nil
}

func (b *LoadBalancer) Release(requestID string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	server := b.leases[requestID]
	if server == nil {
		return errors.New("unknown/completed request")
	}
	server.active--
	delete(b.leases, requestID)
	return nil
}

func check(condition bool) {
	if !condition {
		panic("contract failed")
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func value[T any](result T, err error) T {
	must(err)
	return result
}

func main() {
	balancer := NewLoadBalancer()
	health := HealthService{observer: balancer}
	_, err := balancer.Acquire("empty")
	check(err != nil)
	check(balancer.Register("bad", 0) != nil)
	must(balancer.Register("A", 2))
	must(balancer.Register("B", 1))
	check(balancer.Register("A", 1) != nil)
	check(value(balancer.Acquire("r1")) == "A")
	check(value(balancer.Acquire("r2")) == "B")
	_, err = balancer.Acquire("r1")
	check(err != nil)
	check(balancer.Remove("A") != nil)
	must(balancer.Release("r1"))
	must(balancer.SetStrategy(&LeastConnections{}))
	check(value(balancer.Acquire("r3")) == "A")
	must(health.Report("A", false))
	check(value(balancer.Acquire("r4")) == "B")
	must(health.Report("B", false))
	_, err = balancer.Acquire("none")
	check(err != nil)
	for _, id := range []string{"r2", "r3", "r4"} {
		must(balancer.Release(id))
	}
	check(balancer.Release("r4") != nil)
	must(health.Report("A", true))
	must(health.Report("B", true))
	must(balancer.SetStrategy(&WeightedRoundRobin{}))
	countA := 0
	for index := 0; index < 6; index++ {
		if value(balancer.Acquire("weighted")) == "A" {
			countA++
		}
		must(balancer.Release("weighted"))
	}
	check(countA == 4)
	must(balancer.SetStrategy(&RandomStrategy{random: rand.New(rand.NewSource(7))}))
	selected := value(balancer.Acquire("random"))
	check(selected == "A" || selected == "B")
	must(balancer.Release("random"))
	must(balancer.Remove("A"))
	check(value(balancer.Acquire("last")) == "B")
	must(balancer.Release("last"))
	fmt.Println("Round robin: A,B")
	fmt.Println("Least connections: A")
	fmt.Println("Healthy fallback: B")
	fmt.Println("Weighted A:B: 4:2")
	fmt.Println("Edge cases: passed")
}
