export type VehicleType = "BIKE" | "CAR" | "TRUCK";

export class Vehicle {
    constructor(
        readonly plate: string,
        readonly kind: VehicleType,
    ) {
        if (!plate.trim() || !["BIKE", "CAR", "TRUCK"].includes(kind)) {
            throw new Error("Invalid vehicle");
        }
    }
}

export class Slot {
    occupied = false;

    constructor(
        readonly level: number,
        readonly id: number,
        readonly kind: VehicleType,
        readonly exitDistance: number,
    ) {
        // Slots belong to one manager; mutate occupancy only through that manager.
    }
}

export interface Ticket {
    readonly id: number;
    readonly vehicle: Vehicle;
    readonly slot: Slot;
    readonly enteredAt: number;
}

export interface SlotAssignmentStrategy {
    select(slots: Slot[], vehicle: Vehicle): Slot | undefined;
}

export class FirstFit implements SlotAssignmentStrategy {
    select(slots: Slot[], vehicle: Vehicle): Slot | undefined {
        return slots.find((slot) => !slot.occupied && slot.kind === vehicle.kind);
    }
}

export class NearestExit implements SlotAssignmentStrategy {
    select(slots: Slot[], vehicle: Vehicle): Slot | undefined {
        return slots
            .filter((slot) => !slot.occupied && slot.kind === vehicle.kind)
            .sort((left, right) => left.exitDistance - right.exitDistance)[0];
    }
}

export interface FeesCalculationStrategy {
    fee(minutes: number): number;
}

export class HourlyFee implements FeesCalculationStrategy {
    fee(minutes: number): number {
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

export type Payment = (ticketId: number, amount: number) => boolean;

// Atomic synchronous operations on one event loop; no awaits or remote payments.
export class ParkingLotManager {
    private strategy: SlotAssignmentStrategy = new FirstFit();
    private readonly tickets = new Map<number, Ticket>();
    private readonly plates = new Set<string>();
    private nextId = 1;

    constructor(
        private readonly slots: Slot[],
        private readonly fees: FeesCalculationStrategy,
    ) {
        // The configuration is owned by this manager for the duration of the demo.
    }

    setStrategy(strategy: SlotAssignmentStrategy): void {
        this.strategy = strategy;
    }

    park(vehicle: Vehicle, minute: number): Ticket {
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
        const ticket: Ticket = { id: this.nextId++, vehicle, slot, enteredAt: minute };
        slot.occupied = true;
        this.plates.add(vehicle.plate);
        this.tickets.set(ticket.id, ticket);
        return ticket;
    }

    exit(ticketId: number, minute: number, payment: Payment): number {
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

export class EntryGate {
    constructor(
        readonly id: number,
        private readonly manager: ParkingLotManager,
    ) {
        // Multiple gates share one manager.
    }

    enter(vehicle: Vehicle, minute: number): Ticket {
        return this.manager.park(vehicle, minute);
    }
}

export class ExitGate {
    constructor(
        readonly id: number,
        private readonly manager: ParkingLotManager,
    ) {
        // The manager owns payment and release ordering.
    }

    leave(ticket: number, minute: number, payment: Payment): number {
        return this.manager.exit(ticket, minute, payment);
    }
}

function reject(action: () => unknown): void {
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

function main(): void {
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
