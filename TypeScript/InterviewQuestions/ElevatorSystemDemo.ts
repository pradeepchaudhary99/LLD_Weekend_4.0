// A synchronous event-loop simulation. No method suspends between state updates.
export type Direction = "UP" | "DOWN" | "IDLE";

export interface Observer {
    arrived(elevatorId: number, floor: number): void;
}

class Display implements Observer {
    arrived(elevatorId: number, floor: number): void {
        console.log(`Elevator ${elevatorId} arrived: ${floor}`);
    }
}

export class Elevator {
    floor = 0;
    direction: Direction = "IDLE";
    readonly stops = new Set<number>();

    constructor(
        readonly id: number,
        private readonly display: Observer,
    ) {
        // Controller owns and mutates elevator state.
    }

    private serve(): void {
        if (this.stops.delete(this.floor)) {
            this.display.arrived(this.id, this.floor);
        }
    }

    tick(): void {
        this.serve();
        if (this.stops.size === 0) {
            this.direction = "IDLE";
            return;
        }
        const above = [...this.stops].filter((stop) => stop > this.floor).sort((a, b) => a - b);
        const below = [...this.stops].filter((stop) => stop < this.floor).sort((a, b) => b - a);
        const preferred = this.direction === "DOWN" ? below : above;
        const fallback = this.direction === "DOWN" ? above : below;
        const target = (preferred.length ? preferred : fallback)[0];
        this.direction = target > this.floor ? "UP" : "DOWN";
        this.floor += this.direction === "UP" ? 1 : -1;
        this.serve();
        if (this.stops.size === 0) {
            this.direction = "IDLE";
        }
    }
}

export interface SelectionStrategy {
    select(elevators: Elevator[], floor: number): Elevator;
}

export class NearestElevatorStrategy implements SelectionStrategy {
    select(elevators: Elevator[], floor: number): Elevator {
        return [...elevators].sort(
            (a, b) => Math.abs(a.floor - floor) - Math.abs(b.floor - floor) || a.id - b.id,
        )[0];
    }
}

export class RoundRobinStrategy implements SelectionStrategy {
    private next = 0;

    select(elevators: Elevator[], floor: number): Elevator {
        const selected = elevators[this.next];
        this.next = (this.next + 1) % elevators.length;
        return selected;
    }
}

export class ElevatorSystem {
    private readonly elevators: Elevator[];
    private strategy: SelectionStrategy = new NearestElevatorStrategy();

    constructor(
        count: number,
        private readonly topFloor: number,
        display: Observer,
    ) {
        if (!Number.isInteger(count) || count <= 0 || !Number.isInteger(topFloor) || topFloor < 1) {
            throw new RangeError("Invalid building");
        }
        this.elevators = Array.from({ length: count }, (_, id) => new Elevator(id, display));
    }

    private validate(floor: number): void {
        if (!Number.isInteger(floor) || floor < 0 || floor > this.topFloor) {
            throw new RangeError("Invalid floor");
        }
    }

    setStrategy(strategy: SelectionStrategy): void {
        this.strategy = strategy;
    }

    externalRequest(floor: number, direction: Direction): number {
        this.validate(floor);
        if (
            (direction !== "UP" && direction !== "DOWN") ||
            (floor === 0 && direction === "DOWN") ||
            (floor === this.topFloor && direction === "UP")
        ) {
            throw new RangeError("Invalid hall direction");
        }
        const selected = this.strategy.select(this.elevators, floor);
        selected.stops.add(floor);
        return selected.id;
    }

    internalRequest(elevatorId: number, floor: number): void {
        this.validate(floor);
        if (
            !Number.isInteger(elevatorId) ||
            elevatorId < 0 ||
            elevatorId >= this.elevators.length
        ) {
            throw new RangeError("Invalid elevator");
        }
        this.elevators[elevatorId].stops.add(floor);
    }

    runUntilIdle(): void {
        while (this.elevators.some((elevator) => elevator.stops.size > 0)) {
            for (const elevator of this.elevators) {
                elevator.tick();
            }
        }
    }
}

function main(): void {
    const system = new ElevatorSystem(2, 10, new Display());
    console.log(`Selected: ${system.externalRequest(3, "UP")}`);
    system.internalRequest(0, 5);
    system.internalRequest(0, 5);
    system.runUntilIdle();
    console.log(`Selected: ${system.externalRequest(4, "DOWN")}`);
    system.internalRequest(0, 1);
    system.runUntilIdle();
    system.setStrategy(new RoundRobinStrategy());
    console.log(`Round robin: ${system.externalRequest(0, "UP")}`);
    console.log(`Round robin: ${system.externalRequest(0, "UP")}`);
    system.runUntilIdle();
    system.runUntilIdle();
    try {
        system.internalRequest(0, 11);
        throw new Error("Invalid floor accepted");
    } catch (error) {
        if (!(error instanceof RangeError)) {
            throw error;
        }
        console.log("Invalid floor rejected");
    }
    try {
        system.externalRequest(0, "DOWN");
        throw new Error("Invalid direction accepted");
    } catch (error) {
        if (!(error instanceof RangeError)) {
            throw error;
        }
        console.log("Invalid direction rejected");
    }
}

if (require.main === module) {
    main();
}
