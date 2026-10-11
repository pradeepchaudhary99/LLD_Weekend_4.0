package main

import (
	"sync"
	"testing"
)

func TestConcurrentSubmissionAndReservation(t *testing.T) {
	gateway := &PaymentGateway{acceptCharge: true, acceptRefund: true}
	service := NewFoodDelivery(gateway)
	request := Request{"alice", "r1", "Home", "pizza", 2}
	var group sync.WaitGroup
	for i := 0; i < 50; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			status, err := service.Place("same", request)
			if err != nil || status != "PLACED" {
				t.Errorf("place: %s, %v", status, err)
			}
		}()
	}
	group.Wait()
	if gateway.charges != 1 {
		t.Fatalf("charged %d times", gateway.charges)
	}
	for _, id := range []string{"same", "other"} {
		if _, err := service.Place(id, request); err != nil {
			t.Fatal(err)
		}
		for _, state := range []string{"ACCEPTED", "PREPARING", "READY"} {
			if err := service.Advance(id, state); err != nil {
				t.Fatal(err)
			}
		}
	}
	results := make(chan bool, 2)
	for _, id := range []string{"same", "other"} {
		group.Add(1)
		go func(id string) {
			defer group.Done()
			assigned, err := service.Assign(id)
			if err != nil {
				t.Error(err)
			}
			results <- assigned
		}(id)
	}
	group.Wait()
	close(results)
	count := 0
	for assigned := range results {
		if assigned {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("reserved rider %d times", count)
	}
}
