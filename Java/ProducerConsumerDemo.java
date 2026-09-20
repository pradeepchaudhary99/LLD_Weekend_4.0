import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class ProducerConsumerDemo {
    static class BoundedBuffer {
        private final Queue<Integer> items = new ArrayDeque<>();
        private final int capacity;
        BoundedBuffer(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be positive");
            }

            this.capacity = capacity;
        }

        synchronized void produce(int value) throws InterruptedException {
            // Use while: another producer may fill the queue before we reacquire the monitor.
            while (items.size() == capacity) {
                wait();
            }

            items.add(value);
            notifyAll();
        }

        synchronized int consume() throws InterruptedException {
            while (items.isEmpty()) {
                wait();
            }

            int value = items.remove();
            notifyAll();
            return value;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        BoundedBuffer buffer = new BoundedBuffer(1);
        AtomicInteger total = new AtomicInteger();
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    // Process outside the buffer's monitor.
                    total.addAndGet(buffer.consume());
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        for (int value = 1; value <= 10; value++) {
            buffer.produce(value);
        }

        consumer.join();
        if (total.get() != 55) {
            throw new AssertionError("Missing items");
        }

        System.out.println("Consumed sum: " + total.get());
        try {
            new BoundedBuffer(0);
            throw new AssertionError("Accepted invalid capacity");
        } catch (IllegalArgumentException expected) {
            System.out.println("Invalid capacity rejected");
        }
    }
}
