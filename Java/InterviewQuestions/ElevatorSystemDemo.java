package InterviewQuestions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

// Deterministic, single-controller simulation: one tick is one floor of travel.
// No background threads, physical doors, emergencies, or durable request storage.
public class ElevatorSystemDemo {
    enum Direction { UP, DOWN, IDLE }

    interface Observer {
        void arrived(int elevatorId, int floor);
    }

    static class Display implements Observer {
        public void arrived(int elevatorId, int floor) {
            System.out.println("Elevator " + elevatorId + " arrived: " + floor);
        }
    }

    static class Elevator {
        final int id;
        int floor;
        Direction direction = Direction.IDLE;
        final TreeSet<Integer> stops = new TreeSet<>();
        final Observer display;

        Elevator(int id, Observer display) {
            this.id = id;
            this.display = display;
        }

        void tick() {
            if (stops.remove(floor)) {
                display.arrived(id, floor);
            }
            if (stops.isEmpty()) {
                direction = Direction.IDLE;
                return;
            }
            Integer target = direction == Direction.DOWN ? stops.lower(floor) : stops.higher(floor);
            if (target == null) {
                target = direction == Direction.DOWN ? stops.higher(floor) : stops.lower(floor);
            }
            direction = target > floor ? Direction.UP : Direction.DOWN;
            floor += direction == Direction.UP ? 1 : -1;
            if (stops.remove(floor)) {
                display.arrived(id, floor);
            }
            if (stops.isEmpty()) {
                direction = Direction.IDLE;
            }
        }
    }

    interface ElevatorSelectionStrategy {
        Elevator select(List<Elevator> elevators, int floor);
    }

    static class NearestElevatorStrategy implements ElevatorSelectionStrategy {
        public Elevator select(List<Elevator> elevators, int floor) {
            return elevators.stream()
                .min(
                    Comparator.comparingInt((Elevator elevator) -> Math.abs(elevator.floor - floor))
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

    static class ElevatorSystem {
        private final int topFloor;
        private final List<Elevator> elevators = new ArrayList<>();
        private ElevatorSelectionStrategy strategy = new NearestElevatorStrategy();

        ElevatorSystem(int count, int topFloor, Observer display) {
            if (count <= 0 || topFloor < 1) {
                throw new IllegalArgumentException("Invalid building");
            }
            this.topFloor = topFloor;
            for (int id = 0; id < count; id++) {
                elevators.add(new Elevator(id, display));
            }
        }

        private void validate(int floor) {
            if (floor < 0 || floor > topFloor) {
                throw new IllegalArgumentException("Invalid floor");
            }
        }

        synchronized void setStrategy(ElevatorSelectionStrategy strategy) {
            this.strategy = java.util.Objects.requireNonNull(strategy);
        }

        synchronized int externalRequest(int floor, Direction direction) {
            validate(floor);
            if (direction == Direction.IDLE || direction == null ||
                (floor == 0 && direction == Direction.DOWN) ||
                (floor == topFloor && direction == Direction.UP)) {
                throw new IllegalArgumentException("Invalid hall direction");
            }
            // Simplification: hall direction is validated, but selection uses distance only.
            Elevator selected = strategy.select(elevators, floor);
            selected.stops.add(floor);
            return selected.id;
        }

        synchronized void internalRequest(int elevatorId, int floor) {
            validate(floor);
            if (elevatorId < 0 || elevatorId >= elevators.size()) {
                throw new IllegalArgumentException("Invalid elevator");
            }
            elevators.get(elevatorId).stops.add(floor);
        }

        synchronized void runUntilIdle() {
            while (elevators.stream().anyMatch(elevator -> !elevator.stops.isEmpty())) {
                for (Elevator elevator : elevators) {
                    elevator.tick();
                }
            }
        }
    }

    public static void main(String[] args) {
        ElevatorSystem system = new ElevatorSystem(2, 10, new Display());
        System.out.println("Selected: " + system.externalRequest(3, Direction.UP));
        system.internalRequest(0, 5);
        system.internalRequest(0, 5);
        system.runUntilIdle();
        System.out.println("Selected: " + system.externalRequest(4, Direction.DOWN));
        system.internalRequest(0, 1);
        system.runUntilIdle();
        system.setStrategy(new RoundRobinStrategy());
        System.out.println("Round robin: " + system.externalRequest(0, Direction.UP));
        System.out.println("Round robin: " + system.externalRequest(0, Direction.UP));
        system.runUntilIdle();
        system.runUntilIdle();
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
