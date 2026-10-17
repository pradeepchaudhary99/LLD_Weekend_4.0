package InterviewQuestions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class BalancerRateChecks {
    public static void main(String[] args) throws Exception {
        var pool = Executors.newFixedThreadPool(16);
        try {
            for (var strategy : List.<RateLimitingDemo.Strategy>of(
                     new RateLimitingDemo.TokenBucket(17, 1000, 1),
                     new RateLimitingDemo.SlidingWindow(17, 1000, 1))) {
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (int index = 0; index < 200; index++) {
                    tasks.add(() -> strategy.allow("same", 0));
                }
                int allowed = 0;
                for (var future : pool.invokeAll(tasks)) {
                    if (future.get()) {
                        allowed++;
                    }
                }
                RateLimitingDemo.check(allowed == 17);
            }
            var balancer = new LoadBalancerDemo.LoadBalancer();
            balancer.register("A", 1);
            balancer.register("B", 1);
            balancer.setStrategy(new LoadBalancerDemo.LeastConnections());
            List<Callable<String>> routes = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                String id = Integer.toString(index);
                routes.add(() -> balancer.acquire(id));
            }
            int countA = 0;
            for (var future : pool.invokeAll(routes)) {
                if (future.get().equals("A")) {
                    countA++;
                }
            }
            LoadBalancerDemo.check(countA == 100);
            balancer.onHealth("A", false);
            for (int index = 0; index < 200; index++) {
                balancer.release(Integer.toString(index));
            }
            balancer.remove("A");
            balancer.remove("B");
        } finally {
            pool.shutdownNow();
        }
        System.out.println("Java balancer/rate-limit concurrency checks passed");
    }
}
