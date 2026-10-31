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
Token generation uses random_device; cryptographic quality is platform-dependent.
Use an OS-backed cryptographic generator for real pickup credentials.
Run from repository root: python3 scripts/run.py "C++" InterviewQuestions/AmazonLockerDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

#include <iostream>
#include <map>
#include <mutex>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>
using namespace std;
struct Assignment {
    string packageId, location;
    int locker;
    string code;
    long long expiry;
};
struct Locker {
    int size;
    string packageId;
};
struct StoredPackage {
    Assignment assignment;
    string status = "STORED";
};

void require(bool condition) {
    if (!condition)
        throw invalid_argument("Invalid request or no suitable locker");
}

void check(bool condition) {
    if (!condition)
        throw runtime_error("Locker contract failed");
}
class LockerService {
    mutex gate;
    map<string, vector<Locker>> locations;
    map<string, StoredPackage> packages;
    vector<Assignment> outbox;
    random_device random;
    long long lastTime = 0;
    void time(long long now) {
        require(now >= lastTime && now <= 1000000000000LL);
        lastTime = now;
    }

    void release(const Assignment &assignment) {
        locations.at(assignment.location).at(assignment.locker).packageId.clear();
    }

  public:
    void addLocation(const string &id, const vector<int> &sizes) {
        lock_guard<mutex> lock(gate);
        require(!id.empty() && !locations.count(id) && !sizes.empty());
        vector<Locker> lockers;
        for (int size : sizes) {
            require(size >= 0 && size <= 2);
            lockers.push_back({size, ""});
        }
        locations.emplace(id, lockers);
    }

    Assignment deposit(const string &id, const string &location, int size, long long now,
                       long long ttl) {
        lock_guard<mutex> lock(gate);
        time(now);
        require(!id.empty() && !packages.count(id) && size >= 0 && size <= 2 && ttl > 0 &&
                ttl <= 1000000000 && locations.count(location));
        auto &lockers = locations.at(location);
        int selected = -1;
        for (int index = 0; index < static_cast<int>(lockers.size()); ++index) {
            const auto &candidate = lockers[index];
            if (candidate.packageId.empty() && candidate.size >= size &&
                (selected < 0 || candidate.size < lockers[selected].size))
                selected = index;
        }
        require(selected >= 0);
        // random_device is a teaching source; its cryptographic quality is platform-dependent.
        const string hex = "0123456789abcdef";
        string code;
        uniform_int_distribution<int> digit(0, 15);
        for (int i = 0; i < 32; ++i)
            code += hex[digit(random)];
        Assignment assignment{id, location, selected, code, now + ttl};
        lockers[selected].packageId = id;
        packages.emplace(id, StoredPackage{assignment});
        outbox.push_back(assignment);
        return assignment;
    }

    bool pickup(const string &id, const string &code, long long now) {
        lock_guard<mutex> lock(gate);
        time(now);
        auto found = packages.find(id);
        if (found == packages.end() || found->second.status != "STORED")
            return false;
        auto &stored = found->second;
        if (now >= stored.assignment.expiry) {
            stored.status = "EXPIRED";
            return false;
        }
        if (stored.assignment.code != code)
            return false;
        release(stored.assignment);
        stored.status = "PICKED_UP";
        return true;
    }

    bool returnExpired(const string &id, long long now) {
        lock_guard<mutex> lock(gate);
        time(now);
        auto found = packages.find(id);
        if (found == packages.end())
            return false;
        auto &stored = found->second;
        if ((stored.status != "STORED" && stored.status != "EXPIRED") ||
            now < stored.assignment.expiry)
            return false;
        release(stored.assignment);
        stored.status = "RETURNED";
        return true;
    }

    vector<Assignment> notifications() {
        lock_guard<mutex> lock(gate);
        return outbox;
    }
};

int main() {
    LockerService service;
    service.addLocation("A", {2, 0, 1});
    auto first = service.deposit("p1", "A", 0, 0, 10);
    check(first.locker == 1);
    check(!service.pickup("p1", "wrong", 1));
    check(service.pickup("p1", first.code, 2));
    check(!service.pickup("p1", first.code, 2));
    auto second = service.deposit("p2", "A", 0, 2, 5);
    check(second.locker == 1);
    check(!service.pickup("p2", second.code, 7));
    auto third = service.deposit("p3", "A", 0, 7, 5);
    check(third.locker == 2);
    check(service.returnExpired("p2", 7));
    check(service.deposit("p4", "A", 0, 7, 5).locker == 1);
    check(service.notifications().size() == 4);
    cout << "Best fit: 1\nPickup once: true\nExpired locker retained: true\nReturned locker "
            "reused: true\nNotifications: 4\n";
}
