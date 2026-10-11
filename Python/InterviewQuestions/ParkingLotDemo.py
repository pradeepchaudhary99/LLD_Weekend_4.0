# INTERVIEW DESIGN NOTES - Parking Lot
#
# Implementation status: runnable in-memory teaching example. Requirements
# below describe this edition; production extensions are explicitly excluded.
#
# Functional requirements:
# - Park bikes, cars and trucks in matching slots across multiple levels.
# - Issue tickets recording vehicle, slot and entry time.
# - Support first-fit and nearest-exit assignment strategies.
# - Calculate fees on exit, collect payment and release the slot only on success.
# - Reject duplicate parked vehicles, full lots and unknown/closed tickets.
#
# Non-functional requirements:
# - Keep allocation, ticket and occupancy updates consistent across gates.
# - Keep pricing, allocation and payment dependencies replaceable.
# - Preserve the ticket and occupied slot when payment fails.
# - Use explicit times and integer fees for deterministic teaching scenarios.
#
# Core entities and responsibilities:
# - Vehicle/VehicleType: identity and required slot category.
# - Slot: level, ID, compatible type, distance to exit and occupancy.
# - Ticket: links a vehicle and allocated slot to its entry time.
# - ParkingLotManager: owns active tickets, parked plates and allocation state.
# - EntryGate/ExitGate: delegate to the shared manager.
# - Assignment strategy, fee strategy and Payment: interchangeable policies.
#
# Key operations and flow:
# - Entry -> validate -> find compatible free slot -> mark occupied -> issue ticket.
# - Exit -> find ticket -> validate elapsed time -> compute fee -> attempt payment.
# - Payment success removes ticket/plate and frees the slot; failure changes none.
#
# Design choices and invariants:
# - Composition and Strategy keep gate code independent of allocation and pricing.
# - Exact vehicle-type matching; nearest-exit ties preserve source slot order.
# - HourlyFee charges 50 units per started hour, with at least one hour.
# - The manager owns slot mutation; callers must not mutate returned slot objects.
#
# Assumptions and out of scope:
# - Synchronous fake payment; no remote charge reconciliation or durable storage.
# - No reservations, EV charging, oversized-slot fallback or physical gate control.
# - Payment callbacks are trusted and quick; avoid re-entering the manager.
#
# Interview discussion and checks:
# - Test duplicate entry, exhausted capacity, failed payment, negative duration,
# - repeat exit and reuse of a released slot.
# - Selection scans the slots; discuss indexed availability for larger lots.
#
# Concurrency in this edition:
# - Instance-level synchronization protects service/game mutations.
#
# Run / validation:
# - From repository root:
#   python3 scripts/run.py Python InterviewQuestions/ParkingLotDemo
# - Prerequisites and focused checks: see README.md and the topic guide.

from dataclasses import dataclass
from threading import Lock


@dataclass(frozen=True)
class Vehicle:
    plate: str
    kind: str

    def __post_init__(self):
        if not self.plate.strip() or self.kind not in ("BIKE", "CAR", "TRUCK"):
            raise ValueError("Invalid vehicle")


@dataclass
class Slot:
    level: int
    id: int
    kind: str
    exit_distance: int
    occupied: bool = False


@dataclass(frozen=True)
class Ticket:
    id: int
    vehicle: Vehicle
    slot: Slot
    entered_at: int


class FirstFit:
    def select(self, slots, vehicle):
        return next(
            (slot for slot in slots if not slot.occupied and slot.kind == vehicle.kind), None
        )


class NearestExit:
    def select(self, slots, vehicle):
        candidates = [slot for slot in slots if not slot.occupied and slot.kind == vehicle.kind]
        return min(candidates, key=lambda slot: slot.exit_distance, default=None)


class HourlyFee:
    def fee(self, minutes):
        if minutes < 0:
            raise ValueError("Exit precedes entry")
        return max(1, (minutes + 59) // 60) * 50


class ParkingLotManager:
    def __init__(self, slots, fees):
        self.slots = list(slots)
        self.fees = fees
        self.strategy = FirstFit()
        self.tickets = {}
        self.plates = set()
        self.next_id = 1
        self.lock = Lock()

    def set_strategy(self, strategy):
        with self.lock:
            self.strategy = strategy

    def park(self, vehicle, minute):
        with self.lock:
            if minute < 0:
                raise ValueError("Invalid entry time")
            if vehicle.plate in self.plates:
                raise ValueError("Vehicle already parked")
            slot = self.strategy.select(self.slots, vehicle)
            if slot is None:
                raise ValueError("No compatible slot")
            ticket = Ticket(self.next_id, vehicle, slot, minute)
            self.next_id += 1
            slot.occupied = True
            self.plates.add(vehicle.plate)
            self.tickets[ticket.id] = ticket
            return ticket

    def exit(self, ticket_id, minute, payment):
        with self.lock:
            ticket = self.tickets.get(ticket_id)
            if ticket is None:
                raise ValueError("Unknown or closed ticket")
            amount = self.fees.fee(minute - ticket.entered_at)
            if not payment(ticket_id, amount):
                raise ValueError("Payment failed; vehicle remains parked")
            ticket.slot.occupied = False
            self.plates.remove(ticket.vehicle.plate)
            del self.tickets[ticket_id]
            return amount


@dataclass
class EntryGate:
    id: int
    manager: ParkingLotManager

    def enter(self, vehicle, minute):
        return self.manager.park(vehicle, minute)


@dataclass
class ExitGate:
    id: int
    manager: ParkingLotManager

    def leave(self, ticket, minute, payment):
        return self.manager.exit(ticket, minute, payment)


def reject(action):
    try:
        action()
        raise AssertionError("Expected rejection")
    except ValueError as error:
        print(error)


def main():
    lot = ParkingLotManager(
        [Slot(0, 1, "CAR", 8), Slot(0, 2, "BIKE", 1), Slot(1, 3, "TRUCK", 2), Slot(1, 4, "CAR", 3)],
        HourlyFee(),
    )
    entry_a = EntryGate(1, lot)
    entry_b = EntryGate(2, lot)
    exit_gate = ExitGate(1, lot)
    first = entry_a.enter(Vehicle("CAR-1", "CAR"), 0)
    print(f"Ticket {first.id}: {first.slot.level}/{first.slot.id}")
    lot.set_strategy(NearestExit())
    second = entry_b.enter(Vehicle("CAR-2", "CAR"), 0)
    print(f"Ticket {second.id}: {second.slot.level}/{second.slot.id}")
    reject(lambda: entry_b.enter(Vehicle("CAR-1", "CAR"), 0))
    reject(lambda: entry_a.enter(Vehicle("CAR-3", "CAR"), 0))
    reject(lambda: exit_gate.leave(first.id, -1, lambda ticket, amount: True))
    reject(lambda: exit_gate.leave(first.id, 61, lambda ticket, amount: False))
    reject(lambda: entry_a.enter(Vehicle("CAR-3", "CAR"), 61))
    print(f"Paid: {exit_gate.leave(first.id, 61, lambda ticket, amount: True)}")
    reject(lambda: exit_gate.leave(first.id, 61, lambda ticket, amount: True))
    reused = entry_a.enter(Vehicle("CAR-3", "CAR"), 62)
    print(f"Reused: {reused.slot.level}/{reused.slot.id}")
    print(f"Bike slot: {entry_a.enter(Vehicle('BIKE-1', 'BIKE'), 0).slot.id}")
    print(f"Truck slot: {entry_b.enter(Vehicle('TRUCK-1', 'TRUCK'), 0).slot.id}")


if __name__ == "__main__":
    main()
