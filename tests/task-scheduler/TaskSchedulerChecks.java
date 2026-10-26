package InterviewQuestions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Run commands are in TASK_SCHEDULER_GUIDE.md. */
public class TaskSchedulerChecks {
    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Scheduler check failed");
        }
    }

    public static void main(String[] args) throws Exception {
        var scheduler = new TaskSchedular_demo.Scheduler(2);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Runnable blocked = () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Owner did not release action");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        };
        try {
            scheduler.schedule("a", 0, 1, 1, blocked);
            scheduler.schedule("b", 0, 1, 1, blocked);
            scheduler.start();
            check(entered.await(3, TimeUnit.SECONDS));
            check(scheduler.cancel("a"));
            check(scheduler.cancel("b"));
        } finally {
            release.countDown();
            scheduler.close();
        }
        check(scheduler.snapshot("a").runs() == 1);
        check(scheduler.snapshot("b").runs() == 1);
        check(scheduler.dispatches().size() == 2);

        try (var timed = new TaskSchedular_demo.Scheduler(1)) {
            timed.schedule("future", 60000, 0, 0, () -> {});
            timed.start();
            long due = timed.now() + 30;
            long[] observed = {-1};
            timed.schedule("early", due, 1, 0, () -> { observed[0] = timed.now(); });
            check(timed.awaitRuns("early", 1, 3000));
            check(observed[0] >= due);
            check(timed.dispatches().equals(java.util.List.of("early")));
        }
        System.out.println("Java scheduler concurrency checks passed");
    }
}
