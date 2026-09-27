package InterviewQuestions;

import java.util.*;

public class ParkingLotDemo {
    enum VehicleType { BIKE, CAR, TRUCK }
    record Vehicle(String plate, VehicleType type) {
        Vehicle {
            if (plate == null || plate.isBlank() || type == null) {
                throw new IllegalArgumentException("Invalid vehicle");
            }
        }
    }

    static class Slot {
        final int level;
        final int id;
        final VehicleType type;
        final int exitDistance;
        boolean occupied;

        Slot(int level, int id, VehicleType type, int exitDistance) {
            this.level = level;
            this.id = id;
            this.type = type;
            this.exitDistance = exitDistance;
        }
    }

    record Ticket(int id, Vehicle vehicle, Slot slot, long enteredAt) {
        // Ticket identity is immutable; the manager owns slot occupancy.
    }
    interface SlotAssignmentStrategy {
        Slot select(List<Slot> slots, Vehicle vehicle);
    }

    static class FirstFit implements SlotAssignmentStrategy {
        public Slot select(List<Slot> slots, Vehicle vehicle) {
            return slots.stream()
                .filter(slot -> !slot.occupied && slot.type == vehicle.type())
                .findFirst()
                .orElse(null);
        }
    }

    static class NearestExit implements SlotAssignmentStrategy {
        public Slot select(List<Slot> slots, Vehicle vehicle) {
            return slots.stream()
                .filter(slot -> !slot.occupied && slot.type == vehicle.type())
                .min(Comparator.comparingInt(slot -> slot.exitDistance))
                .orElse(null);
        }
    }

    interface FeesCalculationStrategy {
        long fee(long minutes);
    }

    static class HourlyFee implements FeesCalculationStrategy {
        public long fee(long minutes) {
            if (minutes < 0) {
                throw new IllegalArgumentException("Exit precedes entry");
            }

            return Math.multiplyExact(Math.max(1, minutes / 60 + (minutes % 60 == 0 ? 0 : 1)), 50);
        }
    }

    interface Payment {
        boolean pay(int ticketId, long amount);
    }

    static class ParkingLotManager {
        private final List<Slot> slots;
        private final Map<Integer, Ticket> tickets = new HashMap<>();
        private final Set<String> plates = new HashSet<>();
        private final FeesCalculationStrategy fees;
        private SlotAssignmentStrategy strategy = new FirstFit();
        private int nextId = 1;

        ParkingLotManager(List<Slot> slots, FeesCalculationStrategy fees) {
            this.slots = new ArrayList<>(slots);
            this.fees = fees;
        }

        synchronized void setStrategy(SlotAssignmentStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy);
        }

        synchronized Ticket park(Vehicle vehicle, long minute) {
            if (minute < 0) {
                throw new IllegalArgumentException("Invalid entry time");
            }

            if (plates.contains(vehicle.plate())) {
                throw new IllegalStateException("Vehicle already parked");
            }

            Slot slot = strategy.select(slots, vehicle);
            if (slot == null) {
                throw new IllegalStateException("No compatible slot");
            }

            Ticket ticket = new Ticket(nextId++, vehicle, slot, minute);
            slot.occupied = true;
            plates.add(vehicle.plate());
            tickets.put(ticket.id(), ticket);
            return ticket;
        }

        synchronized long exit(int ticketId, long minute, Payment payment) {
            Ticket ticket = tickets.get(ticketId);
            if (ticket == null) {
                throw new IllegalArgumentException("Unknown or closed ticket");
            }

            long amount = fees.fee(Math.subtractExact(minute, ticket.enteredAt()));
            // Teaching payment is synchronous and in memory. On failure keep the slot/ticket.
            if (!payment.pay(ticketId, amount)) {
                throw new IllegalStateException("Payment failed; vehicle remains parked");
            }

            ticket.slot().occupied = false;
            plates.remove(ticket.vehicle().plate());
            tickets.remove(ticketId);
            return amount;
        }
    }

    record EntryGate(int id, ParkingLotManager manager) {
        Ticket enter(Vehicle vehicle, long minute) {
            return manager.park(vehicle, minute);
        }
    }

    record ExitGate(int id, ParkingLotManager manager) {
        long leave(int ticket, long minute, Payment payment) {
            return manager.exit(ticket, minute, payment);
        }
    }

    static void reject(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected rejection");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            System.out.println(expected.getMessage());
        }
    }

    public static void main(String[] args) {
        ParkingLotManager lot = new ParkingLotManager(
            List.of(new Slot(0, 1, VehicleType.CAR, 8), new Slot(0, 2, VehicleType.BIKE, 1),
                    new Slot(1, 3, VehicleType.TRUCK, 2), new Slot(1, 4, VehicleType.CAR, 3)),
            new HourlyFee());
        EntryGate entryA = new EntryGate(1, lot);
        EntryGate entryB = new EntryGate(2, lot);
        ExitGate exit = new ExitGate(1, lot);
        Ticket first = entryA.enter(new Vehicle("CAR-1", VehicleType.CAR), 0);
        System.out.println("Ticket " + first.id() + ": " + first.slot().level + "/" +
                           first.slot().id);
        lot.setStrategy(new NearestExit());
        Ticket second = entryB.enter(new Vehicle("CAR-2", VehicleType.CAR), 0);
        System.out.println("Ticket " + second.id() + ": " + second.slot().level + "/" +
                           second.slot().id);
        reject(() -> entryB.enter(new Vehicle("CAR-1", VehicleType.CAR), 0));
        reject(() -> entryA.enter(new Vehicle("CAR-3", VehicleType.CAR), 0));
        reject(() -> exit.leave(first.id(), -1, (id, amount) -> true));
        reject(() -> exit.leave(first.id(), 61, (id, amount) -> false));
        reject(() -> entryA.enter(new Vehicle("CAR-3", VehicleType.CAR), 61));
        System.out.println("Paid: " + exit.leave(first.id(), 61, (id, amount) -> true));
        reject(() -> exit.leave(first.id(), 61, (id, amount) -> true));
        Ticket reused = entryA.enter(new Vehicle("CAR-3", VehicleType.CAR), 62);
        System.out.println("Reused: " + reused.slot().level + "/" + reused.slot().id);
        System.out.println("Bike slot: " +
                           entryA.enter(new Vehicle("BIKE-1", VehicleType.BIKE), 0).slot().id);
        System.out.println("Truck slot: " +
                           entryB.enter(new Vehicle("TRUCK-1", VehicleType.TRUCK), 0).slot().id);
    }
}
