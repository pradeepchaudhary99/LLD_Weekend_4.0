package InterviewQuestions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

// Each elevator owns a worker thread; one loop iteration travels at most one floor.
// This is a simulation, without physical doors, travel delays, or emergency handling.
public class ElevatorSystemDemo {
    enum Direction { UP, DOWN, IDLE }

    interface Observer {
        void arrived(int elevatorId, int floor);
    }

    static class Display implements Observer {
        private record Arrival(int elevatorId, int floor) {}

        private final List<Arrival> arrivals = new ArrayList<>();

        public synchronized void arrived(int elevatorId, int floor) {
            arrivals.add(new Arrival(elevatorId, floor));
        }

        synchronized void printArrivals() {
            // Stable output for students, even when different cars arrive concurrently.
            // A stable sort preserves each car's actual arrival sequence.
            arrivals.sort(Comparator.comparingInt(Arrival::elevatorId));
            for (Arrival arrival : arrivals) {
                System.out.println("Elevator " + arrival.elevatorId() +
                                   " arrived: " + arrival.floor());
            }
            arrivals.clear();
        }
    }

    static class Elevator implements Runnable {
        final int id;
        private int floor;
        private Direction direction = Direction.IDLE;
        private final TreeSet<Integer> stops = new TreeSet<>();
        private final Observer display;
        private boolean shutdown;
        private boolean notifyingArrival;
        private RuntimeException failure;

        Elevator(int id, Observer display) {
            this.id = id;
            this.display = Objects.requireNonNull(display);
        }

        synchronized int currentFloor() {
            return floor;
        }

        synchronized void addStop(int requestedFloor) {
            checkFailure();
            if (shutdown) {
                throw new IllegalStateException("Elevator is shutting down");
            }
            stops.add(requestedFloor);
            notifyAll();
        }

        private void checkFailure() {
            if (failure != null) {
                throw new IllegalStateException("Elevator " + id + " failed", failure);
            }
        }

        synchronized boolean isIdle() {
            checkFailure();
            return stops.isEmpty() && !notifyingArrival;
        }

        synchronized void awaitIdle() throws InterruptedException {
            while (!isIdle()) {
                wait();
            }
        }

        synchronized void shutdown() {
            shutdown = true;
            notifyAll();
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Integer arrivedAt = null;
                    synchronized (this) {
                        // wait() releases this elevator's monitor; requests can wake it up.
                        while (stops.isEmpty() && !shutdown) {
                            wait();
                        }
                        if (stops.isEmpty()) {
                            return;
                        }
                        if (stops.remove(floor)) {
                            arrivedAt = floor;
                        } else {
                            Integer target = direction == Direction.DOWN ? stops.lower(floor)
                                                                         : stops.higher(floor);
                            if (target == null) {
                                target = direction == Direction.DOWN ? stops.higher(floor)
                                                                     : stops.lower(floor);
                            }
                            direction = target > floor ? Direction.UP : Direction.DOWN;
                            floor += direction == Direction.UP ? 1 : -1;
                            if (stops.remove(floor)) {
                                arrivedAt = floor;
                            }
                        }
                        if (stops.isEmpty()) {
                            direction = Direction.IDLE;
                        }
                        notifyingArrival = arrivedAt != null;
                    }

                    // Do not hold the elevator monitor while invoking user code.
                    if (arrivedAt != null) {
                        display.arrived(id, arrivedAt);
                    }
                    synchronized (this) {
                        notifyingArrival = false;
                        notifyAll();
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                synchronized (this) {
                    failure = new IllegalStateException("Worker interrupted", error);
                }
            } catch (RuntimeException error) {
                synchronized (this) {
                    failure = error;
                }
            } finally {
                synchronized (this) {
                    shutdown = true;
                    notifyingArrival = false;
                    notifyAll();
                }
            }
        }
    }

    interface ElevatorSelectionStrategy {
        Elevator select(List<Elevator> elevators, int floor);
    }

    static class NearestElevatorStrategy implements ElevatorSelectionStrategy {
        public Elevator select(List<Elevator> elevators, int floor) {
            return elevators.stream()
                .min(Comparator
                         .comparingInt(
                             (Elevator elevator) -> Math.abs(elevator.currentFloor() - floor))
                         .thenComparingInt(elevator -> elevator.id))
                .orElseThrow();
        }
    }

    static class RoundRobinStrategy implements ElevatorSelectionStrategy {
        private int next;

        public Elevator select(List<Elevator> elevators, int floor) {
            Elevator selected = elevators.get(next);
            next = (next + 1) % elevators.size();
            return selected;
        }
    }

    static class ElevatorSystem implements AutoCloseable {
        private final int topFloor;
        private final List<Elevator> elevators = new ArrayList<>();
        private final List<Thread> workers = new ArrayList<>();
        private boolean started;
        private boolean closed;
        private ElevatorSelectionStrategy strategy = new NearestElevatorStrategy();

        ElevatorSystem(int count, int topFloor, Observer display) {
            if (count <= 0 || topFloor < 1) {
                throw new IllegalArgumentException("Invalid building");
            }
            Objects.requireNonNull(display);
            this.topFloor = topFloor;
            for (int id = 0; id < count; id++) {
                Elevator elevator = new Elevator(id, display);
                elevators.add(elevator);
                workers.add(new Thread(elevator, "elevator-" + id));
            }
        }

        private void validate(int floor) {
            if (floor < 0 || floor > topFloor) {
                throw new IllegalArgumentException("Invalid floor");
            }
        }

        synchronized void setStrategy(ElevatorSelectionStrategy strategy) {
            ensureOpen();
            this.strategy = Objects.requireNonNull(strategy);
        }

        synchronized int externalRequest(int floor, Direction direction) {
            ensureOpen();
            validate(floor);
            if (direction == Direction.IDLE || direction == null ||
                (floor == 0 && direction == Direction.DOWN) ||
                (floor == topFloor && direction == Direction.UP)) {
                throw new IllegalArgumentException("Invalid hall direction");
            }
            // Simplification: hall direction is validated, but selection uses distance only.
            Elevator selected = strategy.select(elevators, floor);
            selected.addStop(floor);
            return selected.id;
        }

        synchronized void internalRequest(int elevatorId, int floor) {
            ensureOpen();
            validate(floor);
            if (elevatorId < 0 || elevatorId >= elevators.size()) {
                throw new IllegalArgumentException("Invalid elevator");
            }
            elevators.get(elevatorId).addStop(floor);
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("System is closed");
            }
        }

        synchronized void start() {
            ensureOpen();
            startWorkers();
        }

        private void startWorkers() {
            if (!started) {
                started = true;
                for (Thread worker : workers) {
                    worker.start();
                }
            }
        }

        private void rejectWorkerWait() {
            if (workers.contains(Thread.currentThread())) {
                throw new IllegalStateException("An elevator worker cannot wait for itself");
            }
        }

        void runUntilIdle() throws InterruptedException {
            rejectWorkerWait();
            start();
            while (true) {
                for (Elevator elevator : elevators) {
                    // Never hold the system monitor while a worker is completing a stop.
                    elevator.awaitIdle();
                }
                synchronized (this) {
                    // New requests could have reached an earlier car while waiting for another.
                    if (elevators.stream().allMatch(Elevator::isIdle)) {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() {
            rejectWorkerWait();
            synchronized (this) {
                closed = true;
                // Closing before start still drains already accepted requests.
                startWorkers();
                for (Elevator elevator : elevators) {
                    elevator.shutdown();
                }
            }
            boolean interrupted = false;
            for (Thread worker : workers) {
                boolean joined = false;
                while (!joined) {
                    try {
                        worker.join();
                        joined = true;
                    } catch (InterruptedException error) {
                        // Finish cleanup, then restore the caller's interrupt status.
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            for (Elevator elevator : elevators) {
                synchronized (elevator) {
                    elevator.checkFailure();
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Display display = new Display();
        try (ElevatorSystem system = new ElevatorSystem(2, 10, display)) {
            System.out.println("Selected: " + system.externalRequest(3, Direction.UP));
            system.internalRequest(0, 5);
            system.internalRequest(0, 5);
            system.runUntilIdle();
            display.printArrivals();
            System.out.println("Selected: " + system.externalRequest(4, Direction.DOWN));
            system.internalRequest(0, 1);
            system.runUntilIdle();
            display.printArrivals();
            system.setStrategy(new RoundRobinStrategy());
            System.out.println("Round robin: " + system.externalRequest(0, Direction.UP));
            System.out.println("Round robin: " + system.externalRequest(0, Direction.UP));
            system.runUntilIdle();
            display.printArrivals();
            system.runUntilIdle();
            display.printArrivals();
            try {
                system.internalRequest(0, 11);
                throw new AssertionError("Invalid floor accepted");
            } catch (IllegalArgumentException expected) {
                System.out.println("Invalid floor rejected");
            }
            try {
                system.externalRequest(0, Direction.DOWN);
                throw new AssertionError("Invalid direction accepted");
            } catch (IllegalArgumentException expected) {
                System.out.println("Invalid direction rejected");
            }
        }
    }
}
