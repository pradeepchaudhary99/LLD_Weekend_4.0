"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ExitGate =
    exports.EntryGate =
    exports.ParkingLotManager =
    exports.HourlyFee =
    exports.NearestExit =
    exports.FirstFit =
    exports.Slot =
    exports.Vehicle =
        void 0;

class Vehicle {
    plate;
    kind;

    constructor(plate, kind) {
        this.plate = plate;
        this.kind = kind;
        if (!plate.trim() || !["BIKE", "CAR", "TRUCK"].includes(kind)) {
            throw new Error("Invalid vehicle");
        }
    }
}
exports.Vehicle = Vehicle;

class Slot {
    level;
    id;
    kind;
    exitDistance;
    occupied = false;

    constructor(level, id, kind, exitDistance) {
        this.level = level;
        this.id = id;
        this.kind = kind;
        this.exitDistance = exitDistance;
        // Slots belong to one manager; mutate occupancy only through that manager.
    }
}
exports.Slot = Slot;

class FirstFit {
    select(slots, vehicle) {
        return slots.find((slot) => !slot.occupied && slot.kind === vehicle.kind);
    }
}
exports.FirstFit = FirstFit;

class NearestExit {
    select(slots, vehicle) {
        return slots
            .filter((slot) => !slot.occupied && slot.kind === vehicle.kind)
            .sort((left, right) => left.exitDistance - right.exitDistance)[0];
    }
}
exports.NearestExit = NearestExit;

class HourlyFee {
    fee(minutes) {
        if (minutes < 0) {
            throw new Error("Exit precedes entry");
        }
        const amount = Math.max(1, Math.ceil(minutes / 60)) * 50;
        if (!Number.isSafeInteger(amount)) {
            throw new Error("Fee exceeds safe range");
        }
        return amount;
    }
}
exports.HourlyFee = HourlyFee;
// Atomic synchronous operations on one event loop; no awaits or remote payments.

class ParkingLotManager {
    slots;
    fees;
    strategy = new FirstFit();
    tickets = new Map();
    plates = new Set();
    nextId = 1;

    constructor(slots, fees) {
        this.slots = slots;
        this.fees = fees;
        // The configuration is owned by this manager for the duration of the demo.
    }

    setStrategy(strategy) {
        this.strategy = strategy;
    }

    park(vehicle, minute) {
        if (!Number.isSafeInteger(minute) || minute < 0) {
            throw new Error("Invalid entry time");
        }
        if (this.plates.has(vehicle.plate)) {
            throw new Error("Vehicle already parked");
        }
        const slot = this.strategy.select(this.slots, vehicle);
        if (!slot) {
            throw new Error("No compatible slot");
        }
        const ticket = { id: this.nextId++, vehicle, slot, enteredAt: minute };
        slot.occupied = true;
        this.plates.add(vehicle.plate);
        this.tickets.set(ticket.id, ticket);
        return ticket;
    }

    exit(ticketId, minute, payment) {
        const ticket = this.tickets.get(ticketId);
        if (!ticket) {
            throw new Error("Unknown or closed ticket");
        }
        if (!Number.isSafeInteger(minute)) {
            throw new Error("Invalid exit time");
        }
        const amount = this.fees.fee(minute - ticket.enteredAt);
        if (!payment(ticketId, amount)) {
            throw new Error("Payment failed; vehicle remains parked");
        }
        ticket.slot.occupied = false;
        this.plates.delete(ticket.vehicle.plate);
        this.tickets.delete(ticketId);
        return amount;
    }
}
exports.ParkingLotManager = ParkingLotManager;

class EntryGate {
    id;
    manager;

    constructor(id, manager) {
        this.id = id;
        this.manager = manager;
        // Multiple gates share one manager.
    }

    enter(vehicle, minute) {
        return this.manager.park(vehicle, minute);
    }
}
exports.EntryGate = EntryGate;

class ExitGate {
    id;
    manager;

    constructor(id, manager) {
        this.id = id;
        this.manager = manager;
        // The manager owns payment and release ordering.
    }

    leave(ticket, minute, payment) {
        return this.manager.exit(ticket, minute, payment);
    }
}
exports.ExitGate = ExitGate;

function reject(action) {
    let rejected = false;
    try {
        action();
    } catch (error) {
        if (!(error instanceof Error)) {
            throw error;
        }
        rejected = true;
        console.log(error.message);
    }
    if (!rejected) {
        throw new Error("Expected rejection");
    }
}

function main() {
    const lot = new ParkingLotManager(
        [
            new Slot(0, 1, "CAR", 8),
            new Slot(0, 2, "BIKE", 1),
            new Slot(1, 3, "TRUCK", 2),
            new Slot(1, 4, "CAR", 3),
        ],
        new HourlyFee(),
    );
    const entryA = new EntryGate(1, lot);
    const entryB = new EntryGate(2, lot);
    const exit = new ExitGate(1, lot);
    const first = entryA.enter(new Vehicle("CAR-1", "CAR"), 0);
    console.log(`Ticket ${first.id}: ${first.slot.level}/${first.slot.id}`);
    lot.setStrategy(new NearestExit());
    const second = entryB.enter(new Vehicle("CAR-2", "CAR"), 0);
    console.log(`Ticket ${second.id}: ${second.slot.level}/${second.slot.id}`);
    reject(() => entryB.enter(new Vehicle("CAR-1", "CAR"), 0));
    reject(() => entryA.enter(new Vehicle("CAR-3", "CAR"), 0));
    reject(() => exit.leave(first.id, -1, () => true));
    reject(() => exit.leave(first.id, 61, () => false));
    reject(() => entryA.enter(new Vehicle("CAR-3", "CAR"), 61));
    console.log(`Paid: ${exit.leave(first.id, 61, () => true)}`);
    reject(() => exit.leave(first.id, 61, () => true));
    const reused = entryA.enter(new Vehicle("CAR-3", "CAR"), 62);
    console.log(`Reused: ${reused.slot.level}/${reused.slot.id}`);
    console.log(`Bike slot: ${entryA.enter(new Vehicle("BIKE-1", "BIKE"), 0).slot.id}`);
    console.log(`Truck slot: ${entryB.enter(new Vehicle("TRUCK-1", "TRUCK"), 0).slot.id}`);
}
if (require.main === module) {
    main();
}
