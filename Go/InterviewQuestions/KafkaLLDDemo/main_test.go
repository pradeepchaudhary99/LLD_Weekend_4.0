package main

import (
	"strconv"
	"sync"
	"testing"
)

func TestConcurrentAppendsAndRebalance(t *testing.T) {
	broker := NewBroker()
	if err := broker.CreateTopic("t", 1); err != nil {
		t.Fatal(err)
	}
	var workers sync.WaitGroup
	for index := 0; index < 500; index++ {
		workers.Add(1)
		go func(index int) {
			defer workers.Done()
			if _, err := broker.Publish("t", "key", strconv.Itoa(index)); err != nil {
				t.Error(err)
			}
		}(index)
	}
	workers.Wait()
	if err := broker.Join("t", "g", "a"); err != nil {
		t.Fatal(err)
	}
	batches, err := broker.Poll("t", "g", "a", 1000)
	if err != nil || len(batches) != 1 {
		t.Fatal("poll failed")
	}
	batch := batches[0]
	if len(batch.Messages) != 500 {
		t.Fatal("lost appends")
	}
	values := map[string]bool{}
	for index, message := range batch.Messages {
		if message.Offset != index {
			t.Fatal("nonsequential offset")
		}
		values[message.Value] = true
	}
	if len(values) != 500 {
		t.Fatal("duplicate or lost values")
	}
	if err := broker.Join("t", "g", "b"); err != nil {
		t.Fatal(err)
	}
	if broker.Commit(batch) {
		t.Fatal("stale commit accepted")
	}
	batches, err = broker.Poll("t", "g", "a", 1000)
	if err != nil {
		t.Fatal(err)
	}
	if !broker.Commit(batches[0]) || broker.Commit(batches[0]) {
		t.Fatal("commit contract failed")
	}
	empty, err := broker.Poll("t", "g", "b", 1000)
	if err != nil || len(empty) != 0 {
		t.Fatal("surplus member owns partition")
	}
}
