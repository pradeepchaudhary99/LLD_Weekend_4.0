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
Run from repository root: python3 scripts/run.py "C#" InterviewQuestions/AmazonLockerDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
namespace LLDWeekend4.InterviewQuestions;
public class AmazonLockerDemo
{
    public record Assignment(string packageId, string location, int locker, string code,
                             long expiry);
    public class Locker
    {
        public readonly int size;
        public string? packageId;
        public Locker(int size)
        {
            this.size = size;
        }
    }

    public class StoredPackage
    {
        public readonly Assignment assignment;
        public string status = "STORED";
        public StoredPackage(Assignment assignment)
        {
            this.assignment = assignment;
        }
    }

    public class LockerService
    {
        private readonly object gate = new();
        private readonly Dictionary<string, List<Locker>> locations = new();
        private readonly Dictionary<string, StoredPackage> packages = new();
        private readonly List<Assignment> outbox = new();

        private long lastTime;

        public void AddLocation(string id, params int[] sizes)
        {
            lock (gate)
            {
                require(id != null && id.Trim().Length > 0 && !locations.ContainsKey(id) &&
                        sizes.Length > 0);
                List<Locker> lockers = new();
                foreach (int size in sizes)
                {
                    require(size >= 0 && size <= 2);
                    lockers.Add(new Locker(size));
                }
                locations.Add(id, lockers);
            }
        }

        private void time(long now)
        {
            require(now >= lastTime && now <= 1_000_000_000_000L);
            lastTime = now;
        }

        public Assignment Deposit(string id, string location, int size, long now, long ttl)
        {
            lock (gate)
            {
                time(now);
                require(id != null && id.Trim().Length > 0 && !packages.ContainsKey(id));
                require(size >= 0 && size <= 2 && ttl > 0 && ttl <= 1_000_000_000L);
                List<Locker> lockers = locations.GetValueOrDefault(location)!;
                require(lockers != null);
                int selected = -1;
                for (int i = 0; i < lockers.Count; i++)
                {
                    Locker candidate = lockers[i];
                    if (candidate.packageId == null && candidate.size >= size &&
                        (selected < 0 || candidate.size < lockers[selected].size))
                    {
                        selected = i;
                    }
                }
                require(selected >= 0);
                byte[] bytes = RandomNumberGenerator.GetBytes(16);
                Assignment assignment =
                    new Assignment(id, location, selected, Convert.ToHexString(bytes), now + ttl);
                lockers[selected].packageId = id;
                packages.Add(id, new StoredPackage(assignment));
                outbox.Add(assignment); // In-memory notification outbox, committed with storage.
                return assignment;
            }
        }

        public bool Pickup(string id, string code, long now)
        {
            lock (gate)
            {
                time(now);
                StoredPackage? stored = packages.GetValueOrDefault(id);
                if (stored == null || !stored.status.Equals("STORED"))
                {
                    return false;
                }
                if (now >= stored.assignment.expiry)
                {
                    stored.status = "EXPIRED";
                    return false;
                }
                if (!stored.assignment.code.Equals(code))
                {
                    return false;
                }
                release(stored);
                stored.status = "PICKED_UP";
                return true;
            }
        }

        public bool ReturnExpired(string id, long now)
        {
            lock (gate)
            {
                time(now);
                StoredPackage? stored = packages.GetValueOrDefault(id);
                if (stored == null ||
                    !(stored.status.Equals("STORED") || stored.status.Equals("EXPIRED")) ||
                    now < stored.assignment.expiry)
                {
                    return false;
                }
                release(stored);
                stored.status = "RETURNED";
                return true;
            }
        }

        private void release(StoredPackage stored)
        {
            Assignment assignment = stored.assignment;
            locations[assignment.location][assignment.locker].packageId = null;
        }

        public List<Assignment> Notifications()
        {
            lock (gate)
            {
                return outbox.ToList();
            }
        }
    }

    static void require([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition)
    {
        if (!condition)
        {
            throw new ArgumentException("Invalid request or no suitable locker");
        }
    }

    static void check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Locker contract failed");
        }
    }

    public static void Main(string[] args)
    {
        LockerService service = new LockerService();
        service.AddLocation("A", 2, 0, 1);
        Assignment first = service.Deposit("p1", "A", 0, 0, 10);
        check(first.locker == 1);
        check(!service.Pickup("p1", "wrong", 1));
        check(service.Pickup("p1", first.code, 2));
        check(!service.Pickup("p1", first.code, 2));
        Assignment second = service.Deposit("p2", "A", 0, 2, 5);
        check(second.locker == 1);
        check(!service.Pickup("p2", second.code, 7));
        Assignment third = service.Deposit("p3", "A", 0, 7, 5);
        check(third.locker == 2); // Expired parcel still physically occupies small locker.
        check(service.ReturnExpired("p2", 7));
        check(service.Deposit("p4", "A", 0, 7, 5).locker == 1);
        check(service.Notifications().Count == 4);
        Console.WriteLine("Best fit: 1\nPickup once: true\nExpired locker retained: " +
                          "true\nReturned locker reused: true\nNotifications: 4");
    }
}
