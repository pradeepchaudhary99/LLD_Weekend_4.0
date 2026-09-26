"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ElevatorSystem =
    exports.RoundRobinStrategy =
    exports.NearestElevatorStrategy =
    exports.Elevator =
        void 0;

class Display {
    arrived(elevatorId, floor) {
        console.log(`Elevator ${elevatorId} arrived: ${floor}`);
    }
}

class Elevator {
    id;
    display;
    floor = 0;
    direction = "IDLE";
    stops = new Set();

    constructor(id, display) {
        this.id = id;
        this.display = display;
        // Controller owns and mutates elevator state.
    }

    serve() {
        if (this.stops.delete(this.floor)) {
            this.display.arrived(this.id, this.floor);
        }
    }

    tick() {
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
exports.Elevator = Elevator;

class NearestElevatorStrategy {
    select(elevators, floor) {
        return [...elevators].sort(
            (a, b) => Math.abs(a.floor - floor) - Math.abs(b.floor - floor) || a.id - b.id,
        )[0];
    }
}
exports.NearestElevatorStrategy = NearestElevatorStrategy;

class RoundRobinStrategy {
    next = 0;

    select(elevators, floor) {
        const selected = elevators[this.next];
        this.next = (this.next + 1) % elevators.length;
        return selected;
    }
}
exports.RoundRobinStrategy = RoundRobinStrategy;

class ElevatorSystem {
    topFloor;
    elevators;
    strategy = new NearestElevatorStrategy();

    constructor(count, topFloor, display) {
        this.topFloor = topFloor;
        if (!Number.isInteger(count) || count <= 0 || !Number.isInteger(topFloor) || topFloor < 1) {
            throw new RangeError("Invalid building");
        }
        this.elevators = Array.from({ length: count }, (_, id) => new Elevator(id, display));
    }

    validate(floor) {
        if (!Number.isInteger(floor) || floor < 0 || floor > this.topFloor) {
            throw new RangeError("Invalid floor");
        }
    }

    setStrategy(strategy) {
        this.strategy = strategy;
    }

    externalRequest(floor, direction) {
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

    internalRequest(elevatorId, floor) {
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

    runUntilIdle() {
        while (this.elevators.some((elevator) => elevator.stops.size > 0)) {
            for (const elevator of this.elevators) {
                elevator.tick();
            }
        }
    }
}
exports.ElevatorSystem = ElevatorSystem;

function main() {
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
