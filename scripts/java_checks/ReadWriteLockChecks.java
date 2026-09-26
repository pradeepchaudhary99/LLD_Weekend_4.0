import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ReadWriteLockChecks {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        ReadWriteLockDemo.MyReadWriteLock lock = new ReadWriteLockDemo.MyReadWriteLock();
        CountDownLatch readerEntered = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        CountDownLatch writerAttempted = new CountDownLatch(1);
        CountDownLatch writerEntered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        lock.readLock();
        Thread reader = new Thread(() -> {
            try {
                lock.readLock();
                try {
                    readerEntered.countDown();
                    require(releaseReader.await(3, TimeUnit.SECONDS), "Reader release timed out");
                } finally {
                    lock.unlockReadLock();
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        Thread writer = new Thread(() -> {
            try {
                writerAttempted.countDown();
                lock.writeLock();
                try {
                    writerEntered.countDown();
                } finally {
                    lock.unlockWriteLock();
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        try {
            require(readerEntered.await(2, TimeUnit.SECONDS), "Readers did not overlap");
            writer.start();
            require(writerAttempted.await(2, TimeUnit.SECONDS), "Writer did not start");
            require(!writerEntered.await(50, TimeUnit.MILLISECONDS), "Writer overlapped a reader");
        } finally {
            releaseReader.countDown();
            lock.unlockReadLock();
        }
        reader.join(2000);
        writer.join(2000);
        require(!reader.isAlive() && !writer.isAlive(), "Lock failed to drain");
        require(writerEntered.getCount() == 0 && failure.get() == null, "Worker failed");

        // Cancelling a waiting writer must not leave waitingWriters permanently positive.
        lock.readLock();
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread cancelledWriter = new Thread(() -> {
            try {
                lock.writeLock();
                lock.unlockWriteLock();
                failure.set(new AssertionError("Writer should have been interrupted"));
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        });
        cancelledWriter.setDaemon(true);
        cancelledWriter.start();
        cancelledWriter.interrupt();
        require(interrupted.await(2, TimeUnit.SECONDS), "Cancellation did not finish");
        CountDownLatch anotherReader = new CountDownLatch(1);
        Thread probe = new Thread(() -> {
            try {
                lock.readLock();
                try {
                    anotherReader.countDown();
                } finally {
                    lock.unlockReadLock();
                }
            } catch (InterruptedException error) {
                failure.set(error);
            }
        });
        probe.setDaemon(true);
        probe.start();
        try {
            require(anotherReader.await(2, TimeUnit.SECONDS), "Cancelled writer blocked readers");
        } finally {
            lock.unlockReadLock();
        }
        probe.join(2000);
        cancelledWriter.join(2000);
        require(failure.get() == null, "Unexpected worker failure");
        System.out.println("Java lock overlap, exclusion and interruption checks passed");
    }
}
