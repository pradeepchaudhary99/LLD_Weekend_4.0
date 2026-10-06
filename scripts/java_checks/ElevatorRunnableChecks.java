package InterviewQuestions;

import InterviewQuestions.ElevatorSystemDemo.Direction;
import InterviewQuestions.ElevatorSystemDemo.ElevatorSystem;
import InterviewQuestions.ElevatorSystemDemo.RoundRobinStrategy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ElevatorRunnableChecks {
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Callback release timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Invalid action accepted");
    }

    private static void sweepAndWakeup() throws Exception {
        List<Integer> arrivals = Collections.synchronizedList(new ArrayList<>());
        try (ElevatorSystem system =
                 new ElevatorSystem(1, 10, (id, floor) -> { arrivals.add(floor); })) {
            system.internalRequest(0, 5);
            system.internalRequest(0, 3);
            system.internalRequest(0, 5);
            system.runUntilIdle();
            require(arrivals.equals(List.of(3, 5)), "Sweep or deduplication failed");
            arrivals.clear();
            system.internalRequest(0, 1);
            system.runUntilIdle();
            system.internalRequest(0, 1);
            system.runUntilIdle();
            require(arrivals.equals(List.of(1, 1)), "Idle wakeup or current-floor request failed");
            system.runUntilIdle();
            rejects(() -> system.internalRequest(1, 1));
            rejects(() -> system.internalRequest(0, 11));
            rejects(() -> system.externalRequest(0, Direction.DOWN));
            rejects(() -> system.externalRequest(10, Direction.UP));
            rejects(() -> system.externalRequest(2, null));
        }
    }

    private static void independentWorkersAndLiveRequests() throws Exception {
        CountDownLatch firstCarArrived = new CountDownLatch(1);
        CountDownLatch releaseFirstCar = new CountDownLatch(1);
        CountDownLatch secondCarArrived = new CountDownLatch(1);
        List<String> arrivals = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        CountDownLatch waiterInterrupted = new CountDownLatch(1);
        try (ElevatorSystem system = new ElevatorSystem(2, 10, (id, floor) -> {
                 arrivals.add(Thread.currentThread().getName() + ":" + floor);
                 if (id == 0 && floor == 1) {
                     firstCarArrived.countDown();
                     await(releaseFirstCar);
                 }
                 if (id == 1 && floor == 4) {
                     secondCarArrived.countDown();
                 }
             })) {
            try {
                system.internalRequest(0, 1);
                system.start();
                require(firstCarArrived.await(2, TimeUnit.SECONDS), "First car never started");
                // Both requests must be accepted while elevator 0's callback is still running.
                system.internalRequest(0, 3);
                system.internalRequest(1, 4);
                require(secondCarArrived.await(2, TimeUnit.SECONDS),
                        "Second car blocked behind first");
                Thread waiter = new Thread(() -> {
                    try {
                        system.runUntilIdle();
                        waiterFailure.set(new AssertionError("Returned before callback completed"));
                    } catch (InterruptedException expected) {
                        waiterInterrupted.countDown();
                    } catch (Throwable error) {
                        waiterFailure.set(error);
                    }
                });
                waiter.start();
                waiter.interrupt();
                waiter.join(2000);
                require(!waiter.isAlive() && waiterInterrupted.getCount() == 0,
                        "Waiting caller did not respond to interruption");
                require(waiterFailure.get() == null, "Waiter failed");
            } finally {
                releaseFirstCar.countDown();
            }
            system.runUntilIdle();
            require(arrivals.containsAll(List.of("elevator-0:1", "elevator-0:3", "elevator-1:4")),
                    "Requests were lost or executed on the wrong worker");
        }
    }

    private static void reversalWithNewStops() throws Exception {
        CountDownLatch atThree = new CountDownLatch(1);
        CountDownLatch continueTravel = new CountDownLatch(1);
        List<Integer> arrivals = Collections.synchronizedList(new ArrayList<>());
        try (ElevatorSystem system = new ElevatorSystem(1, 10, (id, floor) -> {
                 arrivals.add(floor);
                 if (floor == 3) {
                     atThree.countDown();
                     await(continueTravel);
                 }
             })) {
            try {
                system.internalRequest(0, 3);
                system.internalRequest(0, 5);
                system.start();
                require(atThree.await(2, TimeUnit.SECONDS), "Car never reached 3");
                system.internalRequest(0, 1);
                system.internalRequest(0, 4);
            } finally {
                continueTravel.countDown();
            }
            system.runUntilIdle();
            require(arrivals.equals(List.of(3, 4, 5, 1)), "Car did not continue then reverse");
        }
    }

    private static void shutdownAndFailure() throws Exception {
        List<String> arrivals = Collections.synchronizedList(new ArrayList<>());
        ElevatorSystem system =
            new ElevatorSystem(2, 10, (id, floor) -> { arrivals.add(id + ":" + floor); });
        system.setStrategy(new RoundRobinStrategy());
        require(system.externalRequest(2, Direction.UP) == 0, "Round robin first car");
        require(system.externalRequest(3, Direction.UP) == 1, "Round robin second car");
        // Cleanup drains queued stops, joins every worker and preserves interruption.
        Thread.currentThread().interrupt();
        system.close();
        require(Thread.interrupted(), "Close lost caller's interrupt status");
        require(arrivals.size() == 2, "Close lost accepted work");
        system.close();
        rejects(() -> system.internalRequest(0, 1));
        rejects(system::start);
        ElevatorSystem failed = new ElevatorSystem(
            1, 10, (id, floor) -> { throw new IllegalStateException("Broken display"); });
        failed.internalRequest(0, 1);
        try {
            failed.runUntilIdle();
            throw new AssertionError("Worker failure was hidden");
        } catch (IllegalStateException expected) {
            require(expected.getCause() != null, "Original failure not retained");
        } finally {
            rejects(failed::close);
        }
        require(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> {
            return thread.isAlive() && thread.getName().startsWith("elevator-");
        }),
                "An elevator thread leaked");
    }

    public static void main(String[] args) throws Exception {
        sweepAndWakeup();
        independentWorkersAndLiveRequests();
        reversalWithNewStops();
        shutdownAndFailure();
        System.out.println("Elevator Runnable checks passed: independent workers, live requests, "
                           + "deduplication, reversal, interruption, failure and shutdown");
    }
}
