package main

import (
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
)

func TestCompetingGatesAndDuplicateExit(t *testing.T) {
	lot := NewLot([]*Slot{{ID: 1, Kind: "CAR"}}, HourlyFee{})
	var workers sync.WaitGroup
	var successes atomic.Int64
	tickets := make(chan Ticket, 8)
	for index := 0; index < 8; index++ {
		workers.Add(1)
		go func(index int) {
			defer workers.Done()
			gate := EntryGate{index, lot}
			ticket, err := gate.Enter(Vehicle{fmt.Sprint(index), "CAR"}, 0)
			if err == nil {
				successes.Add(1)
				tickets <- ticket
			}
		}(index)
	}
	workers.Wait()
	if successes.Load() != 1 {
		t.Fatal("slot assigned more or less than once")
	}
	ticket := <-tickets
	var charges atomic.Int64
	for index := 0; index < 2; index++ {
		workers.Add(1)
		go func(index int) {
			defer workers.Done()
			gate := ExitGate{index, lot}
			_, _ = gate.Leave(ticket.ID, 61, func(id int, amount int64) bool {
				if amount != 100 {
					t.Error("wrong fee")
				}
				charges.Add(1)
				return true
			})
		}(index)
	}
	workers.Wait()
	if charges.Load() != 1 {
		t.Fatal("duplicate charge")
	}
}

func TestFeeBoundariesAndFailureRetention(t *testing.T) {
	fee := HourlyFee{}
	for minutes, expected := range map[int64]int64{0: 50, 59: 50, 60: 50, 61: 100, 120: 100, 121: 150} {
		actual, err := fee.Fee(minutes)
		if err != nil || actual != expected {
			t.Fatal("incorrect fee rounding")
		}
	}
	lot := NewLot([]*Slot{{ID: 1, Kind: "CAR"}}, fee)
	ticket, _ := lot.Park(Vehicle{"A", "CAR"}, 0)
	if _, err := lot.Exit(ticket.ID, 1, func(int, int64) bool {
		return false
	}); err == nil {
		t.Fatal("payment failure ignored")
	}
	if _, err := lot.Park(Vehicle{"B", "CAR"}, 1); err == nil {
		t.Fatal("slot released before payment")
	}
}
