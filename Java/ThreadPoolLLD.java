import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class ThreadPoolLLD {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int value = 1; value <= 10; value++) {
                final int number = value;
                results.add(pool.submit(() -> number));
            }

            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get();
            }

            if (total != 55) {
                throw new AssertionError("Missing results");
            }

            System.out.println("Executor sum: " + total);
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
    }
}
