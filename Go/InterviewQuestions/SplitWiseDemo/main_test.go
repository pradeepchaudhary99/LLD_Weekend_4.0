package main

import (
	"fmt"
	"slices"
	"sync"
	"testing"
)

func TestLedger(t *testing.T) {
	service := NewSplitWise()
	members := []string{"A", "B", "C"}
	for _, user := range []string{"A", "B", "C", "D"} {
		if err := service.AddUser(user); err != nil {
			t.Fatal(err)
		}
	}
	for _, group := range []string{"g", "other"} {
		if err := service.CreateGroup(group, members); err != nil {
			t.Fatal(err)
		}
	}
	expense := func(id, payer string, amount int64, participants []string, kind string, values, expected []int64) {
		t.Helper()
		shares, err := service.AddExpense("g", id, payer, amount, participants, kind, values)
		if err != nil || !slices.Equal(shares, expected) {
			t.Fatalf("split %s: %v, %v", id, shares, err)
		}
	}
	debt := func(group, from, to string, expected int64) {
		t.Helper()
		actual, err := service.Balance(group, from, to)
		if err != nil || actual != expected {
			t.Fatalf("balance: %d, %v", actual, err)
		}
	}
	expense("equal", "A", 2, members, "EQUAL", nil, []int64{1, 1, 0})
	expense("percent", "B", 7, members, "PERCENTAGE", []int64{5000, 2500, 2500}, []int64{3, 2, 2})
	expense("zero", "C", 1, members, "PERCENTAGE", []int64{0, 0, 10000}, []int64{0, 0, 1})
	debt("g", "A", "B", 2)
	debt("g", "B", "A", -2)
	debt("other", "A", "B", 0)
	invalid := []struct {
		id           string
		payer        string
		amount       int64
		participants []string
		kind         string
		values       []int64
	}{
		{"bad", "A", 10, members, "EXACT", []int64{2, 3, 4}},
		{"bad", "A", 10, members, "PERCENTAGE", []int64{5000, 2000, 2000}},
		{"bad", "A", 10, []string{"A", "A"}, "EQUAL", nil},
		{"bad", "D", 10, members, "EQUAL", nil},
		{"equal", "A", 10, members, "EQUAL", nil},
		{"bad", "A", 0, members, "EQUAL", nil},
		{"bad", "A", maxAmount + 1, members, "EQUAL", nil},
	}
	for _, request := range invalid {
		if _, err := service.AddExpense("g", request.id, request.payer, request.amount, request.participants, request.kind, request.values); err == nil {
			t.Fatal("invalid expense accepted")
		}
	}
	if service.Settle("g", "bad", "A", "B", 3) == nil || service.RemoveMember("g", "A") == nil {
		t.Fatal("oversettlement or leaving with debt allowed")
	}
	history, _ := service.History("g")
	if len(history) != 3 {
		t.Fatal("rejection mutated history")
	}
	debt("g", "A", "B", 2)
	if err := service.Settle("g", "settle", "A", "B", 2); err != nil {
		t.Fatal(err)
	}
	debt("g", "A", "B", 0)
	for _, err := range []error{service.RemoveMember("g", "A"), service.AddMember("g", "A"), service.AddMember("g", "D"), service.RemoveMember("g", "D")} {
		if err != nil {
			t.Fatal(err)
		}
	}
	if service.Settle("g", "settle", "C", "B", 1) == nil {
		t.Fatal("duplicate transaction accepted")
	}
	var workers sync.WaitGroup
	for index := 0; index < 100; index++ {
		workers.Add(1)
		go func(index int) {
			defer workers.Done()
			if _, err := service.AddExpense("other", fmt.Sprintf("parallel-%d", index), "A", 10, []string{"B"}, "EXACT", []int64{10}); err != nil {
				t.Error(err)
			}
		}(index)
	}
	workers.Wait()
	debt("other", "B", "A", 1000)
	history, _ = service.History("other")
	if len(history) != 100 {
		t.Fatal("concurrent updates lost")
	}
	for index := 0; index < 1000; index++ {
		if _, err := service.AddExpense("g", fmt.Sprintf("limit-%d", index), "A", maxAmount, []string{"B"}, "EXACT", []int64{maxAmount}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := service.AddExpense("g", "overflow", "A", 2, []string{"C", "B"}, "EXACT", []int64{1, 1}); err == nil {
		t.Fatal("balance overflow accepted")
	}
	debt("g", "C", "A", 0)
	history, _ = service.History("g")
	if len(history) != 1004 {
		t.Fatal("partial commit after overflow")
	}
	history[0] = "mutated"
	fresh, _ := service.History("g")
	if fresh[0] == "mutated" {
		t.Fatal("history is not a snapshot")
	}
}
