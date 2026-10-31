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
Run from repository root: python3 scripts/run.py "Go" InterviewQuestions/AmazonLockerDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
)

type Assignment struct {
	PackageID, Location string
	Locker              int
	Code                string
	Expiry              int64
}

type Locker struct {
	Size      int
	PackageID string
}

type StoredPackage struct {
	Assignment Assignment
	Status     string
}

type LockerService struct {
	mu        sync.Mutex
	locations map[string][]Locker
	packages  map[string]*StoredPackage
	outbox    []Assignment
	lastTime  int64
}

func NewLockerService() *LockerService {
	return &LockerService{locations: make(map[string][]Locker), packages: make(map[string]*StoredPackage)}
}

func (s *LockerService) time(now int64) error {
	if now < s.lastTime || now > 1000000000000 {
		return errors.New("invalid time")
	}
	s.lastTime = now
	return nil
}

func (s *LockerService) AddLocation(id string, sizes []int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if id == "" || s.locations[id] != nil || len(sizes) == 0 {
		return errors.New("invalid location")
	}
	lockers := make([]Locker, len(sizes))
	for index, size := range sizes {
		if size < 0 || size > 2 {
			return errors.New("invalid size")
		}
		lockers[index] = Locker{Size: size}
	}
	s.locations[id] = lockers
	return nil
}

func (s *LockerService) Deposit(id, location string, size int, now, ttl int64) (Assignment, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.time(now); err != nil {
		return Assignment{}, err
	}
	lockers := s.locations[location]
	if id == "" || s.packages[id] != nil || size < 0 || size > 2 || ttl <= 0 || ttl > 1000000000 || lockers == nil {
		return Assignment{}, errors.New("invalid deposit")
	}
	selected := -1
	for index, candidate := range lockers {
		if candidate.PackageID == "" && candidate.Size >= size && (selected < 0 || candidate.Size < lockers[selected].Size) {
			selected = index
		}
	}
	if selected < 0 {
		return Assignment{}, errors.New("no suitable locker")
	}
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return Assignment{}, err
	}
	assignment := Assignment{id, location, selected, hex.EncodeToString(bytes), now + ttl}
	lockers[selected].PackageID = id
	s.packages[id] = &StoredPackage{assignment, "STORED"}
	s.outbox = append(s.outbox, assignment)
	return assignment, nil
}

func (s *LockerService) release(assignment Assignment) {
	s.locations[assignment.Location][assignment.Locker].PackageID = ""
}

func (s *LockerService) Pickup(id, code string, now int64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.time(now); err != nil {
		return false, err
	}
	stored := s.packages[id]
	if stored == nil || stored.Status != "STORED" {
		return false, nil
	}
	if now >= stored.Assignment.Expiry {
		stored.Status = "EXPIRED"
		return false, nil
	}
	if code != stored.Assignment.Code {
		return false, nil
	}
	s.release(stored.Assignment)
	stored.Status = "PICKED_UP"
	return true, nil
}

func (s *LockerService) ReturnExpired(id string, now int64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.time(now); err != nil {
		return false, err
	}
	stored := s.packages[id]
	if stored == nil || (stored.Status != "STORED" && stored.Status != "EXPIRED") || now < stored.Assignment.Expiry {
		return false, nil
	}
	s.release(stored.Assignment)
	stored.Status = "RETURNED"
	return true, nil
}

func (s *LockerService) Notifications() []Assignment {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Assignment{}, s.outbox...)
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func check(ok bool) {
	if !ok {
		panic("locker contract failed")
	}
}

func result(ok bool, err error) bool {
	must(err)
	return ok
}

func main() {
	service := NewLockerService()
	must(service.AddLocation("A", []int{2, 0, 1}))
	first, err := service.Deposit("p1", "A", 0, 0, 10)
	must(err)
	check(first.Locker == 1)
	check(!result(service.Pickup("p1", "wrong", 1)))
	check(result(service.Pickup("p1", first.Code, 2)))
	check(!result(service.Pickup("p1", first.Code, 2)))
	second, err := service.Deposit("p2", "A", 0, 2, 5)
	must(err)
	check(second.Locker == 1)
	check(!result(service.Pickup("p2", second.Code, 7)))
	third, err := service.Deposit("p3", "A", 0, 7, 5)
	must(err)
	check(third.Locker == 2)
	check(result(service.ReturnExpired("p2", 7)))
	fourth, err := service.Deposit("p4", "A", 0, 7, 5)
	must(err)
	check(fourth.Locker == 1)
	check(len(service.Notifications()) == 4)
	fmt.Println("Best fit: 1\nPickup once: true\nExpired locker retained: true\nReturned locker reused: true\nNotifications: 4")
}
