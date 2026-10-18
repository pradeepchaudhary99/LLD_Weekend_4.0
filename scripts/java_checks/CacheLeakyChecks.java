package InterviewQuestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CacheLeakyChecks {
    public static void main(String[] args) throws Exception {
        var cache = new LRU_CacheDemo.LRUCache(8);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < 1000; index++) {
                final int value = index;
                tasks.add(() -> {
                    cache.put(value % 16, value);
                    cache.get((value + 1) % 16);
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
            LRU_CacheDemo.check(cache.keys().size() == 8 &&
                                new HashSet<>(cache.keys()).size() == 8);
            List<Integer> jobs = new ArrayList<>();
            var queued = new LeakyBucketPseduo.LeakyBucket(10, 1, jobs::add);
            List<Callable<Boolean>> producers = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                final int job = index;
                producers.add(() -> queued.submit(job));
            }
            int accepted = 0;
            for (var future : pool.invokeAll(producers)) {
                if (future.get()) {
                    accepted++;
                }
            }
            LeakyBucketPseduo.check(accepted == 10);
            queued.close();
            LeakyBucketPseduo.check(jobs.size() == 10 && new HashSet<>(jobs).size() == 10);
        } finally {
            pool.shutdownNow();
        }
        List<Long> times = new ArrayList<>();
        CountDownLatch arrived = new CountDownLatch(1);
        var bucket = new LeakyBucketPseduo.LeakyBucket(4, 20, job -> {
            times.add(System.nanoTime());
            arrived.countDown();
            if (job == 1) {
                throw new IllegalStateException("Expected failure");
            }
        });
        try {
            bucket.start();
            LeakyBucketPseduo.check(bucket.submit(1));
            LeakyBucketPseduo.check(arrived.await(5, TimeUnit.SECONDS));
            LeakyBucketPseduo.check(bucket.submit(2));
            LeakyBucketPseduo.check(bucket.submit(3));
        } finally {
            bucket.close();
        }
        LeakyBucketPseduo.check(times.size() == 3 && bucket.failures() == 1);
        for (int index = 1; index < times.size(); index++) {
            LeakyBucketPseduo.check(times.get(index) - times.get(index - 1) >= 18_000_000);
        }
        System.out.println("Java cache/leaky-bucket concurrency and cadence checks passed");
    }
}
