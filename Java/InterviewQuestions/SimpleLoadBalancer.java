/*
 * SIMPLE JAVA - Load balancer
 *
 * Functional requirements (MVP): Register servers, update health, route in round-robin order among
 * healthy servers. Non-functional requirements: readable, in-memory, safe access within one JVM.
 * Core entities: Server holds ID/health. LoadBalancer owns the list and next-server cursor.
 * Operations and flow: route scans at most one full round, skips unhealthy servers and returns an
 * ID. Design choices and concurrency: synchronized keeps health checks and cursor advancement
 * consistent across callers. Assumptions / omitted features: No network forwarding, health probes,
 * removal, weights or connection tracking. Edge cases to discuss/test: Empty/all-unhealthy fleet,
 * duplicates, repeated round-robin calls, health changes. Run from repository root (Java 17+ and
 * Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleLoadBalancer Read
 * SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.List;

public class SimpleLoadBalancer {
    static class Server {
        final String id;
        boolean healthy = true;

        Server(String id) {
            this.id = id;
        }
    }

    static class LoadBalancer {
        private final List<Server> servers = new ArrayList<>();
        private int next;

        synchronized void register(String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Server ID is required");
            }
            for (Server server : servers) {
                if (server.id.equals(id)) {
                    throw new IllegalArgumentException("Duplicate server");
                }
            }
            servers.add(new Server(id));
        }

        synchronized void setHealthy(String id, boolean healthy) {
            for (Server server : servers) {
                if (server.id.equals(id)) {
                    server.healthy = healthy;
                    return;
                }
            }
            throw new IllegalArgumentException("Unknown server");
        }

        // Keep checking health and moving the shared cursor in one critical section.
        synchronized String route() {
            for (int checked = 0; checked < servers.size(); checked++) {
                Server server = servers.get(next);
                next = (next + 1) % servers.size();
                if (server.healthy) {
                    return server.id;
                }
            }
            throw new IllegalStateException("No healthy server");
        }
    }

    public static void main(String[] args) {
        LoadBalancer balancer = new LoadBalancer();
        balancer.register("A");
        balancer.register("B");
        System.out.println("Routes: " + balancer.route() + "," + balancer.route());
        balancer.setHealthy("A", false);
        System.out.println("After A fails: " + balancer.route());
    }
}
