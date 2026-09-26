import java.util.HashMap;
import java.util.Map;

public class ReadWriteLockDemo {
    // Non-reentrant, writer-preferring teaching lock. No read-to-write upgrades.
    static class MyReadWriteLock {
        private int readers;
        private int waitingWriters;
        private boolean activeWriter;

        synchronized void readLock() throws InterruptedException {
            while (activeWriter || waitingWriters > 0) {
                wait();
            }
            readers++;
        }

        synchronized void unlockReadLock() {
            if (readers == 0) {
                throw new IllegalStateException("No reader to release");
            }
            readers--;
            notifyAll();
        }

        synchronized void writeLock() throws InterruptedException {
            waitingWriters++;
            try {
                while (readers > 0 || activeWriter) {
                    wait();
                }
                activeWriter = true;
            } finally {
                waitingWriters--;
                // Also wake readers if the last waiting writer was interrupted.
                notifyAll();
            }
        }

        synchronized void unlockWriteLock() {
            if (!activeWriter) {
                throw new IllegalStateException("No writer to release");
            }
            activeWriter = false;
            notifyAll();
        }
    }

    static class Cache {
        private final Map<Integer, Integer> values = new HashMap<>();
        private final MyReadWriteLock lock = new MyReadWriteLock();

        Integer readValue(int key) throws InterruptedException {
            lock.readLock();
            try {
                return values.get(key);
            } finally {
                lock.unlockReadLock();
            }
        }

        void writeValue(int key, int value) throws InterruptedException {
            lock.writeLock();
            try {
                values.put(key, value);
            } finally {
                lock.unlockWriteLock();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Cache cache = new Cache();
        Thread[] writers = new Thread[4];
        for (int index = 0; index < writers.length; index++) {
            final int key = index;
            writers[index] = new Thread(() -> {
                try {
                    cache.writeValue(key, key * 10);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            writers[index].start();
        }
        for (Thread writer : writers) {
            writer.join();
        }
        int sum = 0;
        for (int key = 0; key < 4; key++) {
            sum += cache.readValue(key);
        }
        if (sum != 60 || cache.readValue(99) != null) {
            throw new AssertionError("Cache contents incorrect");
        }
        System.out.println("Cache sum: " + sum);
        System.out.println("Missing: true");
        cache.writeValue(0, -1);
        System.out.println("Stored negative: " + cache.readValue(0));
        cache.writeValue(0, 7);
        System.out.println("Updated: " + cache.readValue(0));
    }
}
