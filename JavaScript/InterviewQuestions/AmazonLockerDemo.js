"use strict";
/*
Amazon Locker — runnable in-memory package pickup model.
Functional requirements: choose a location; deposit into the smallest available
locker that fits; issue a pickup token and notification; collect exactly once;
reject wrong/expired tokens; let staff return expired parcels and reuse lockers.
Sizes: 0 small, 1 medium, 2 large. Equal-size ties use configuration order.
Non-functional requirements: atomic assignment/pickup, consistent lifecycle,
testable expiry, and detached response snapshots. Operations scan one location.
Core entities: Locker(size, occupancy), Assignment(package/location/locker/token/
expiry), StoredPackage(status), LockerService(locations, packages, outbox).
Flow: deposit -> validate -> best-fit scan -> occupy -> save assignment + outbox;
pickup -> validate package/token/time -> release -> mark PICKED_UP.
Concurrency: one service lock protects compound state changes in threaded versions.
Expiry boundary is now >= expiry. Expired parcels remain physically stored until
returnExpired confirms staff removal; expiry alone must never free the locker.
Time is injected, nondecreasing, in arbitrary milliseconds; use one clock source.
now <= 10^12, 0 < TTL <= 10^9; package IDs are never reused within this service.
Notifications are an inspectable in-memory outbox, not actual SMS/email delivery.
Scope: trusted callers; existing order/customer/location selection is upstream.
Deposit and door opening are modeled atomically; no real hardware or database.
Authentication, staff roles, rate limiting, durable outbox delivery, maintenance
mode and crash recovery are production extensions, not implemented guarantees.
Edge cases: bad size/location, duplicate package, no capacity, bad/replayed token,
exact expiry, premature staff return, expired occupancy, and concurrent last-slot
allocation. Returned/collected assignments remain in history; memory is unbounded.
JavaScript/TypeScript operations are synchronous on one event loop (no await);
this is atomic per process, not synchronization across workers or servers.
Run from repository root: python3 scripts/run.py "TypeScript" InterviewQuestions/AmazonLockerDemo
Generated JS: python3 scripts/run.py JavaScript InterviewQuestions/AmazonLockerDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/
Object.defineProperty(exports, "__esModule", { value: true });
const node_crypto_1 = require("node:crypto");

class LockerService {
    locations = new Map();
    packages = new Map();
    outbox = [];
    lastTime = 0;

    time(now) {
        requireCondition(Number.isSafeInteger(now) && now >= this.lastTime && now <= 1e12);
        this.lastTime = now;
    }

    addLocation(id, sizes) {
        requireCondition(id.trim().length > 0 && !this.locations.has(id) && sizes.length > 0);
        requireCondition(sizes.every((size) => Number.isInteger(size) && size >= 0 && size <= 2));
        this.locations.set(
            id,
            sizes.map((size) => ({ size })),
        );
    }

    deposit(id, location, size, now, ttl) {
        this.time(now);
        requireCondition(id.trim().length > 0 && !this.packages.has(id));
        requireCondition(
            Number.isInteger(size) &&
                size >= 0 &&
                size <= 2 &&
                Number.isSafeInteger(ttl) &&
                ttl > 0 &&
                ttl <= 1e9,
        );
        const lockers = this.locations.get(location);
        requireCondition(lockers !== undefined);
        let selected = -1;
        for (let index = 0; index < lockers.length; index++) {
            const candidate = lockers[index];
            if (
                candidate.packageId === undefined &&
                candidate.size >= size &&
                (selected < 0 || candidate.size < lockers[selected].size)
            ) {
                selected = index;
            }
        }
        requireCondition(selected >= 0);
        const assignment = Object.freeze({
            packageId: id,
            location,
            locker: selected,
            code: (0, node_crypto_1.randomBytes)(16).toString("hex"),
            expiry: now + ttl,
        });
        lockers[selected].packageId = id;
        this.packages.set(id, { assignment, status: "STORED" });
        this.outbox.push(assignment);
        return assignment;
    }

    pickup(id, code, now) {
        this.time(now);
        const stored = this.packages.get(id);
        if (!stored || stored.status !== "STORED") return false;
        if (now >= stored.assignment.expiry) {
            stored.status = "EXPIRED";
            return false;
        }
        if (stored.assignment.code !== code) return false;
        this.release(stored.assignment);
        stored.status = "PICKED_UP";
        return true;
    }

    returnExpired(id, now) {
        this.time(now);
        const stored = this.packages.get(id);
        if (
            !stored ||
            !["STORED", "EXPIRED"].includes(stored.status) ||
            now < stored.assignment.expiry
        )
            return false;
        this.release(stored.assignment);
        stored.status = "RETURNED";
        return true;
    }

    release(assignment) {
        this.locations.get(assignment.location)[assignment.locker].packageId = undefined;
    }

    notifications() {
        return this.outbox.slice();
    }
}

function requireCondition(condition) {
    if (!condition) throw new Error("Invalid request or no suitable locker");
}

function check(condition) {
    if (!condition) throw new Error("Locker contract failed");
}

function main() {
    const service = new LockerService();
    service.addLocation("A", [2, 0, 1]);
    const first = service.deposit("p1", "A", 0, 0, 10);
    check(first.locker === 1);
    check(!service.pickup("p1", "wrong", 1));
    check(service.pickup("p1", first.code, 2));
    check(!service.pickup("p1", first.code, 2));
    const second = service.deposit("p2", "A", 0, 2, 5);
    check(second.locker === 1);
    check(!service.pickup("p2", second.code, 7));
    const third = service.deposit("p3", "A", 0, 7, 5);
    check(third.locker === 2);
    check(service.returnExpired("p2", 7));
    check(service.deposit("p4", "A", 0, 7, 5).locker === 1);
    check(service.notifications().length === 4);
    console.log(
        "Best fit: 1\nPickup once: true\nExpired locker retained: true\nReturned locker reused: true\nNotifications: 4",
    );
}
main();
