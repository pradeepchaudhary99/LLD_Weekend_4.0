# Amazon Locker — runnable in-memory package pickup model.
# Functional requirements: choose a location; deposit into the smallest available
# locker that fits; issue a pickup token and notification; collect exactly once;
# reject wrong/expired tokens; let staff return expired parcels and reuse lockers.
# Sizes: 0 small, 1 medium, 2 large. Equal-size ties use configuration order.
# Non-functional requirements: atomic assignment/pickup, consistent lifecycle,
# testable expiry, and detached response snapshots. Operations scan one location.
# Core entities: Locker(size, occupancy), Assignment(package/location/locker/token/
# expiry), StoredPackage(status), LockerService(locations, packages, outbox).
# Flow: deposit -> validate -> best-fit scan -> occupy -> save assignment + outbox;
# pickup -> validate package/token/time -> release -> mark PICKED_UP.
# Concurrency: one service lock protects compound state changes in threaded versions.
# Expiry boundary is now >= expiry. Expired parcels remain physically stored until
# returnExpired confirms staff removal; expiry alone must never free the locker.
# Time is injected, nondecreasing, in arbitrary milliseconds; use one clock source.
# now <= 10^12, 0 < TTL <= 10^9; package IDs are never reused within this service.
# Notifications are an inspectable in-memory outbox, not actual SMS/email delivery.
# Scope: trusted callers; existing order/customer/location selection is upstream.
# Deposit and door opening are modeled atomically; no real hardware or database.
# Authentication, staff roles, rate limiting, durable outbox delivery, maintenance
# mode and crash recovery are production extensions, not implemented guarantees.
# Edge cases: bad size/location, duplicate package, no capacity, bad/replayed token,
# exact expiry, premature staff return, expired occupancy, and concurrent last-slot
# allocation. Returned/collected assignments remain in history; memory is unbounded.
# Run from repository root: python3 scripts/run.py "Python" InterviewQuestions/AmazonLockerDemo
# See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.

from dataclasses import dataclass
from secrets import token_hex
from threading import RLock


@dataclass(frozen=True)
class Assignment:
    package_id: str
    location: str
    locker: int
    code: str
    expiry: int


class LockerService:
    def __init__(self):
        self.lock = RLock()
        self.locations = {}
        self.packages = {}
        self.outbox = []
        self.last_time = 0

    def time(self, now):
        require(type(now) is int and self.last_time <= now <= 10**12)
        self.last_time = now

    def add_location(self, location, sizes):
        with self.lock:
            require(
                isinstance(location, str)
                and location.strip()
                and location not in self.locations
                and sizes
            )
            require(all(type(size) is int and 0 <= size <= 2 for size in sizes))
            self.locations[location] = [[size, None] for size in sizes]

    def deposit(self, package_id, location, size, now, ttl):
        with self.lock:
            self.time(now)
            require(
                isinstance(package_id, str)
                and package_id.strip()
                and package_id not in self.packages
            )
            require(type(size) is int and 0 <= size <= 2 and type(ttl) is int and 0 < ttl <= 10**9)
            require(location in self.locations)
            lockers = self.locations[location]
            choices = [
                (locker[0], index)
                for index, locker in enumerate(lockers)
                if locker[1] is None and locker[0] >= size
            ]
            require(choices)
            index = min(choices)[1]
            assignment = Assignment(package_id, location, index, token_hex(16), now + ttl)
            lockers[index][1] = package_id
            self.packages[package_id] = [assignment, "STORED"]
            self.outbox.append(assignment)
            return assignment

    def pickup(self, package_id, code, now):
        with self.lock:
            self.time(now)
            stored = self.packages.get(package_id)
            if stored is None or stored[1] != "STORED":
                return False
            if now >= stored[0].expiry:
                stored[1] = "EXPIRED"
                return False
            if stored[0].code != code:
                return False
            self.release(stored[0])
            stored[1] = "PICKED_UP"
            return True

    def return_expired(self, package_id, now):
        with self.lock:
            self.time(now)
            stored = self.packages.get(package_id)
            if stored is None or stored[1] not in ("STORED", "EXPIRED") or now < stored[0].expiry:
                return False
            self.release(stored[0])
            stored[1] = "RETURNED"
            return True

    def release(self, assignment):
        self.locations[assignment.location][assignment.locker][1] = None

    def notifications(self):
        with self.lock:
            return tuple(self.outbox)


def require(condition):
    if not condition:
        raise ValueError("Invalid request or no suitable locker")


def check(condition):
    if not condition:
        raise AssertionError("Locker contract failed")


def main():
    service = LockerService()
    service.add_location("A", [2, 0, 1])
    first = service.deposit("p1", "A", 0, 0, 10)
    check(first.locker == 1)
    check(not service.pickup("p1", "wrong", 1))
    check(service.pickup("p1", first.code, 2))
    check(not service.pickup("p1", first.code, 2))
    second = service.deposit("p2", "A", 0, 2, 5)
    check(second.locker == 1)
    check(not service.pickup("p2", second.code, 7))
    third = service.deposit("p3", "A", 0, 7, 5)
    check(third.locker == 2)
    check(service.return_expired("p2", 7))
    check(service.deposit("p4", "A", 0, 7, 5).locker == 1)
    check(len(service.notifications()) == 4)
    print(
        "Best fit: 1\nPickup once: true\nExpired locker retained: true\nReturned locker reused: true\nNotifications: 4"
    )


if __name__ == "__main__":
    main()
