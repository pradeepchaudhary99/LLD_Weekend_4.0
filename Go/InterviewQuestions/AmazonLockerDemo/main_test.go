package main

import (
	"sync"
	"testing"
)

func TestLastSlotAndPickup(t *testing.T) {
	service := NewLockerService()
	if err := service.AddLocation("A", []int{0}); err != nil {
		t.Fatal(err)
	}
	var workers sync.WaitGroup
	results := make(chan Assignment, 8)
	start := make(chan struct{})
	for index := 0; index < 8; index++ {
		workers.Add(1)
		go func(index int) {
			defer workers.Done()
			<-start
			assignment, err := service.Deposit(string(rune('a'+index)), "A", 0, 0, 10)
			if err == nil {
				results <- assignment
			}
		}(index)
	}
	close(start)
	workers.Wait()
	close(results)
	if len(results) != 1 {
		t.Fatalf("expected one winner, got %d", len(results))
	}
	winner := <-results
	picked := make(chan bool, 8)
	for index := 0; index < 8; index++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			ok, err := service.Pickup(winner.PackageID, winner.Code, 1)
			if err != nil {
				t.Error(err)
			}
			picked <- ok
		}()
	}
	workers.Wait()
	close(picked)
	count := 0
	for ok := range picked {
		if ok {
			count++
		}
	}
	if count != 1 || len(service.Notifications()) != 1 {
		t.Fatal("pickup/outbox invariant failed")
	}
}

func TestExpiry(t *testing.T) {
	service := NewLockerService()
	if err := service.AddLocation("A", []int{0}); err != nil {
		t.Fatal(err)
	}
	parcel, err := service.Deposit("p", "A", 0, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	ok, err := service.Pickup("p", parcel.Code, 10)
	if err != nil || ok {
		t.Fatal("accepted expired code")
	}
	if _, err := service.Deposit("q", "A", 0, 10, 10); err == nil {
		t.Fatal("freed an occupied expired locker")
	}
	ok, err = service.ReturnExpired("p", 10)
	if err != nil || !ok {
		t.Fatal("staff return failed")
	}
	if _, err := service.Deposit("q", "A", 0, 10, 10); err != nil {
		t.Fatal(err)
	}
}
