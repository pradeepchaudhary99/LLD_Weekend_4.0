/*
 * INTERVIEW DESIGN NOTES - Parking Lot
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Park bikes, cars and trucks in matching slots across multiple levels.
 * - Issue tickets recording vehicle, slot and entry time.
 * - Support first-fit and nearest-exit assignment strategies.
 * - Calculate fees on exit, collect payment and release the slot only on success.
 * - Reject duplicate parked vehicles, full lots and unknown/closed tickets.
 *
 * Non-functional requirements:
 * - Keep allocation, ticket and occupancy updates consistent across gates.
 * - Keep pricing, allocation and payment dependencies replaceable.
 * - Preserve the ticket and occupied slot when payment fails.
 * - Use explicit times and integer fees for deterministic teaching scenarios.
 *
 * Core entities and responsibilities:
 * - Vehicle/VehicleType: identity and required slot category.
 * - Slot: level, ID, compatible type, distance to exit and occupancy.
 * - Ticket: links a vehicle and allocated slot to its entry time.
 * - ParkingLotManager: owns active tickets, parked plates and allocation state.
 * - EntryGate/ExitGate: delegate to the shared manager.
 * - Assignment strategy, fee strategy and Payment: interchangeable policies.
 *
 * Key operations and flow:
 * - Entry -> validate -> find compatible free slot -> mark occupied -> issue ticket.
 * - Exit -> find ticket -> validate elapsed time -> compute fee -> attempt payment.
 * - Payment success removes ticket/plate and frees the slot; failure changes none.
 *
 * Design choices and invariants:
 * - Composition and Strategy keep gate code independent of allocation and pricing.
 * - Exact vehicle-type matching; nearest-exit ties preserve source slot order.
 * - HourlyFee charges 50 units per started hour, with at least one hour.
 * - The manager owns slot mutation; callers must not mutate returned slot objects.
 *
 * Assumptions and out of scope:
 * - Synchronous fake payment; no remote charge reconciliation or durable storage.
 * - No reservations, EV charging, oversized-slot fallback or physical gate control.
 * - Payment callbacks are trusted and quick; avoid re-entering the manager.
 *
 * Interview discussion and checks:
 * - Test duplicate entry, exhausted capacity, failed payment, negative duration,
 * - repeat exit and reuse of a released slot.
 * - Selection scans the slots; discuss indexed availability for larger lots.
 *
 * Concurrency in this edition:
 * - Instance-level synchronization protects service/game mutations.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py Java InterviewQuestions/ParkingLotDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

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
