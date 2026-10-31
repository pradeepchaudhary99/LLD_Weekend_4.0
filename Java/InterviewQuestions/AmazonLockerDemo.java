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
Run from repository root: python3 scripts/run.py "Java" InterviewQuestions/AmazonLockerDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

package InterviewQuestions;

import java.util.*;
import java.security.SecureRandom;

public class AmazonLockerDemo {
    record Assignment(String packageId, String location, int locker, String code, long expiry) {}
    static class Locker {
        final int size;
        String packageId;
        Locker(int size) {
            this.size = size;
        }
    }

    static class StoredPackage {
        final Assignment assignment;
        String status = "STORED";
        StoredPackage(Assignment assignment) {
            this.assignment = assignment;
        }
    }

    static class LockerService {
        private final Map<String, List<Locker>> locations = new HashMap<>();
        private final Map<String, StoredPackage> packages = new HashMap<>();
        private final List<Assignment> notifications = new ArrayList<>();
        private final SecureRandom random = new SecureRandom();
        private long lastTime;

        synchronized void addLocation(String id, int... sizes) {
            require(id != null && !id.isBlank() && !locations.containsKey(id) && sizes.length > 0);
            List<Locker> lockers = new ArrayList<>();
            for (int size : sizes) {
                require(size >= 0 && size <= 2);
                lockers.add(new Locker(size));
            }
            locations.put(id, lockers);
        }

        private void time(long now) {
            require(now >= lastTime && now <= 1_000_000_000_000L);
            lastTime = now;
        }

        synchronized Assignment deposit(String id, String location, int size, long now, long ttl) {
            time(now);
            require(id != null && !id.isBlank() && !packages.containsKey(id));
            require(size >= 0 && size <= 2 && ttl > 0 && ttl <= 1_000_000_000L);
            List<Locker> lockers = locations.get(location);
            require(lockers != null);
            int selected = -1;
            for (int i = 0; i < lockers.size(); i++) {
                Locker candidate = lockers.get(i);
                if (candidate.packageId == null && candidate.size >= size &&
                    (selected < 0 || candidate.size < lockers.get(selected).size)) {
                    selected = i;
                }
            }
            require(selected >= 0);
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            Assignment assignment =
                new Assignment(id, location, selected, HexFormat.of().formatHex(bytes), now + ttl);
            lockers.get(selected).packageId = id;
            packages.put(id, new StoredPackage(assignment));
            notifications.add(assignment); // In-memory notification outbox, committed with storage.
            return assignment;
        }

        synchronized boolean pickup(String id, String code, long now) {
            time(now);
            StoredPackage stored = packages.get(id);
            if (stored == null || !stored.status.equals("STORED")) {
                return false;
            }
            if (now >= stored.assignment.expiry()) {
                stored.status = "EXPIRED";
                return false;
            }
            if (!stored.assignment.code().equals(code)) {
                return false;
            }
            release(stored);
            stored.status = "PICKED_UP";
            return true;
        }

        synchronized boolean returnExpired(String id, long now) {
            time(now);
            StoredPackage stored = packages.get(id);
            if (stored == null ||
                !(stored.status.equals("STORED") || stored.status.equals("EXPIRED")) ||
                now < stored.assignment.expiry()) {
                return false;
            }
            release(stored);
            stored.status = "RETURNED";
            return true;
        }

        private void release(StoredPackage stored) {
            Assignment assignment = stored.assignment;
            locations.get(assignment.location()).get(assignment.locker()).packageId = null;
        }

        synchronized List<Assignment> notifications() {
            return List.copyOf(notifications);
        }
    }

    static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid request or no suitable locker");
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Locker contract failed");
        }
    }

    public static void main(String[] args) {
        LockerService service = new LockerService();
        service.addLocation("A", 2, 0, 1);
        Assignment first = service.deposit("p1", "A", 0, 0, 10);
        check(first.locker() == 1);
        check(!service.pickup("p1", "wrong", 1));
        check(service.pickup("p1", first.code(), 2));
        check(!service.pickup("p1", first.code(), 2));
        Assignment second = service.deposit("p2", "A", 0, 2, 5);
        check(second.locker() == 1);
        check(!service.pickup("p2", second.code(), 7));
        Assignment third = service.deposit("p3", "A", 0, 7, 5);
        check(third.locker() == 2); // Expired parcel still physically occupies small locker.
        check(service.returnExpired("p2", 7));
        check(service.deposit("p4", "A", 0, 7, 5).locker() == 1);
        check(service.notifications().size() == 4);
        System.out.println("Best fit: 1\nPickup once: true\nExpired locker retained: " +
                           "true\nReturned locker reused: true\nNotifications: 4");
    }
}
