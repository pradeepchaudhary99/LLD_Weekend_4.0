/*
 * INTERVIEW DESIGN NOTES - Elevator System
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Support multiple elevators, hall requests and in-car floor requests.
 * - Select cars using nearest-distance or round-robin strategies.
 * - Deduplicate pending stops; continue in direction before reversing.
 * - Notify arrivals, accept requests while cars work and wait for idle completion.
 * - Reject invalid floors/directions; drain accepted work and shut down cleanly.
 *
 * Non-functional requirements:
 * - Each elevator processes its own work independently.
 * - Protect mutable car state; never hold a car lock during an observer callback.
 * - Idle workers wait rather than spin; lifecycle cleanup must wait for workers.
 * - Make worker failures observable and keep the printed demo reproducible.
 *
 * Core entities and responsibilities:
 * - Elevator: owns position, direction, pending stops and the worker loop.
 * - ElevatorSystem: validates and assigns requests, starts and closes workers.
 * - SelectionStrategy: chooses a car; nearest and round-robin are implementations.
 * - Observer/Display: receives arrivals and buffers stable per-car output.
 *
 * Key operations and flow:
 * - external request -> validate -> select car -> enqueue stop -> wake car.
 * - Car run loop -> choose direction -> move one floor -> notify an arrival.
 * - run-until-idle waits for empty queues and completed callbacks; close drains.
 *
 * Design choices and invariants:
 * - Strategy separates assignment policy; Observer separates arrival presentation.
 * - A pending stop is deduplicated; a request after service is new work.
 * - Nearest selection uses live snapshots, not an atomic fleet-wide position.
 * - Owner code manages lifecycle; callbacks must not wait for their own system.
 *
 * Assumptions and out of scope:
 * - Floors are 0 through topFloor; hall direction does not affect assignment.
 * - No real motor timing, doors, capacity, emergency controls or persistence.
 * - Callbacks must finish promptly; no hard shutdown deadline or crash recovery.
 *
 * Interview discussion and checks:
 * - Test independent cars with a blocked observer, requests during travel and reversal.
 * - Test idle wakeup, duplicate stops, invalid requests and cleanup without leaks.
 * - Discuss direction-aware dispatch, starvation, capacity and emergency states.
 *
 * Concurrency in this edition:
 * - Async task per car on one Node event loop; await idle/close promises.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py JavaScript InterviewQuestions/ElevatorSystemDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ElevatorSystem =
    exports.RoundRobinStrategy =
    exports.NearestElevatorStrategy =
    exports.Elevator =
        void 0;
// One asynchronous task per elevator on a single Node event loop.
const promises_1 = require("node:timers/promises");

class Display {
    arrivals = [];

    arrived(elevatorId, floor) {
        this.arrivals.push({ id: elevatorId, floor });
    }

    printArrivals() {
        this.arrivals.sort((a, b) => a.id - b.id);
        for (const arrival of this.arrivals) {
            console.log(`Elevator ${arrival.id} arrived: ${arrival.floor}`);
        }
        this.arrivals = [];
    }
}

class Elevator {
    id;
    display;
    currentFloor = 0;
    direction = "IDLE";
    stops = new Set();
    started = false;
    shutdown = false;
    failure;
    task;

    constructor(id, display) {
        this.id = id;
        this.display = display;
        // Each car owns its asynchronous task and pending stops.
    }
    get floor() {
        return this.currentFloor;
    }

    checkFailure() {
        if (this.failure !== undefined) {
            throw new Error(`Elevator ${this.id} failed`, { cause: this.failure });
        }
    }

    addStop(floor) {
        this.checkFailure();
        if (this.shutdown) {
            throw new Error("Elevator shutting down");
        }
        this.stops.add(floor);
        if (this.started) {
            this.wake();
        }
    }

    start() {
        this.started = true;
        this.wake();
    }

    wake() {
        if (!this.task && this.stops.size && this.failure === undefined) {
            this.task = this.run()
                .catch((error) => {
                    this.failure = error;
                    this.shutdown = true;
                })
                .finally(() => {
                    this.task = undefined;
                    this.wake();
                });
        }
    }

    async run() {
        // Yield between floors so other cars and request producers can progress.
        await (0, promises_1.setImmediate)();
        while (this.stops.size) {
            let arrived;
            if (this.stops.delete(this.currentFloor)) {
                arrived = this.currentFloor;
            } else {
                const above = [...this.stops]
                    .filter((stop) => stop > this.currentFloor)
                    .sort((a, b) => a - b);
                const below = [...this.stops]
                    .filter((stop) => stop < this.currentFloor)
                    .sort((a, b) => b - a);
                const preferred = this.direction === "DOWN" ? below : above;
                const fallback = this.direction === "DOWN" ? above : below;
                const target = (preferred.length ? preferred : fallback)[0];
                this.direction = target > this.currentFloor ? "UP" : "DOWN";
                this.currentFloor += this.direction === "UP" ? 1 : -1;
                if (this.stops.delete(this.currentFloor)) {
                    arrived = this.currentFloor;
                }
            }
            if (!this.stops.size) {
                this.direction = "IDLE";
            }
            if (arrived !== undefined) {
                await this.display.arrived(this.id, arrived);
            }
            await (0, promises_1.setImmediate)();
        }
    }

    isIdle() {
        this.checkFailure();
        return !this.stops.size && !this.task;
    }

    async awaitIdle() {
        while (this.task) {
            await this.task;
        }
        this.checkFailure();
    }

    shutdownAfterDrain() {
        this.shutdown = true;
        this.start();
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
    closed = false;

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
        this.ensureOpen();
        if (!strategy) {
            throw new Error("Missing strategy");
        }
        this.strategy = strategy;
    }

    externalRequest(floor, direction) {
        this.ensureOpen();
        this.validate(floor);
        if (
            (direction !== "UP" && direction !== "DOWN") ||
            (floor === 0 && direction === "DOWN") ||
            (floor === this.topFloor && direction === "UP")
        ) {
            throw new RangeError("Invalid hall direction");
        }
        const selected = this.strategy.select(this.elevators, floor);
        selected.addStop(floor);
        return selected.id;
    }

    internalRequest(elevatorId, floor) {
        this.ensureOpen();
        this.validate(floor);
        if (
            !Number.isInteger(elevatorId) ||
            elevatorId < 0 ||
            elevatorId >= this.elevators.length
        ) {
            throw new RangeError("Invalid elevator");
        }
        this.elevators[elevatorId].addStop(floor);
    }

    ensureOpen() {
        if (this.closed) {
            throw new Error("System closed");
        }
    }

    start() {
        this.ensureOpen();
        this.elevators.forEach((elevator) => elevator.start());
    }

    async runUntilIdle() {
        this.start();
        while (true) {
            await Promise.all(this.elevators.map((elevator) => elevator.awaitIdle()));
            if (this.elevators.every((elevator) => elevator.isIdle())) {
                return;
            }
        }
    }

    async close() {
        this.closed = true;
        this.elevators.forEach((elevator) => elevator.shutdownAfterDrain());
        const results = await Promise.allSettled(
            this.elevators.map((elevator) => elevator.awaitIdle()),
        );
        for (const result of results) {
            if (result.status === "rejected") {
                throw result.reason;
            }
        }
    }
}
exports.ElevatorSystem = ElevatorSystem;

async function main() {
    const display = new Display();
    const system = new ElevatorSystem(2, 10, display);
    try {
        console.log(`Selected: ${system.externalRequest(3, "UP")}`);
        system.internalRequest(0, 5);
        system.internalRequest(0, 5);
        await system.runUntilIdle();
        display.printArrivals();
        console.log(`Selected: ${system.externalRequest(4, "DOWN")}`);
        system.internalRequest(0, 1);
        await system.runUntilIdle();
        display.printArrivals();
        system.setStrategy(new RoundRobinStrategy());
        console.log(`Round robin: ${system.externalRequest(0, "UP")}`);
        console.log(`Round robin: ${system.externalRequest(0, "UP")}`);
        await system.runUntilIdle();
        display.printArrivals();
        await system.runUntilIdle();
        display.printArrivals();
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
    } finally {
        await system.close();
    }
}
if (require.main === module) {
    main().catch((error) => {
        console.error(error);
        process.exitCode = 1;
    });
}
