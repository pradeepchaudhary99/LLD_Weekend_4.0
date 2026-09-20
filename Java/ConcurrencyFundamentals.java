import java.util.ArrayList;
import java.util.List;

// Threads share the heap. Each has its own stack; volatile alone does not make count++ atomic.
public class ConcurrencyFundamentals {
    static class Counter {
        private int count;
        synchronized void increment() {
            count++;
        }

        synchronized int getCount() {
            return count;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Counter counter = new Counter();
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            Thread thread = new Thread(() -> {
                for (int iteration = 0; iteration < 1000; iteration++) {
                    counter.increment();
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Joining is separate from protecting the increment: we need both.
        for (Thread thread : threads) {
            thread.join();
        }

        if (counter.getCount() != 4000) {
            throw new AssertionError("Lost updates");
        }

        System.out.println("Counter: " + counter.getCount());
    }
}
