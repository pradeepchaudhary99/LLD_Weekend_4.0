import java.util.Arrays;
import java.util.List;
class Server {
    final String name; int connections;
    Server(String name, int connections) { this.name = name; this.connections = connections; }
}
interface LoadBalancingStrategy { Server select(List<Server> servers); }
class RoundRobinStrategy implements LoadBalancingStrategy {
    private int next;
    public Server select(List<Server> servers) { Server server = servers.get(next % servers.size()); next = (next + 1) % servers.size(); return server; }
}
class LeastConnectionStrategy implements LoadBalancingStrategy {
    public Server select(List<Server> servers) {
        Server best = servers.get(0);
        for (Server server : servers) if (server.connections < best.connections) best = server;
        return best;
    }
}
class LoadBalancer {
    private LoadBalancingStrategy strategy;
    private final List<Server> servers;
    LoadBalancer(List<Server> servers, LoadBalancingStrategy strategy) { this.servers = servers; setStrategy(strategy); }
    void setStrategy(LoadBalancingStrategy strategy) { this.strategy = java.util.Objects.requireNonNull(strategy); }
    Server sendRequest(String request) {
        if (servers.isEmpty()) throw new IllegalStateException("No servers available");
        Server server = strategy.select(servers); server.connections++;
        System.out.println(request + " -> " + server.name); return server;
    }
    void complete(Server server) { if (server.connections <= 0) throw new IllegalStateException("No active request"); server.connections--; }
}
public class StrategyDesign {
    public static void main(String[] args) {
        LoadBalancer lb = new LoadBalancer(Arrays.asList(new Server("A", 2), new Server("B", 0)), new RoundRobinStrategy());
        Server first = lb.sendRequest("r1"); lb.sendRequest("r2"); lb.complete(first);
        lb.setStrategy(new LeastConnectionStrategy()); lb.sendRequest("r3");
        try { new LoadBalancer(java.util.Collections.emptyList(), new RoundRobinStrategy()).sendRequest("r4"); }
        catch (IllegalStateException e) { System.out.println(e.getMessage()); }
    }
}
