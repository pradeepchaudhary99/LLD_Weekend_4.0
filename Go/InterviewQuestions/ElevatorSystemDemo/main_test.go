package main

import (
	"slices"
	"sync"
	"testing"
	"time"
)

func TestIndependentWorkers(t *testing.T) {
	first, release, second := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var once sync.Once
	firstCarStops := []int{}
	system, err := NewSystem(2, 10, func(id, floor int) {
		if id == 0 {
			firstCarStops = append(firstCarStops, floor)
			if floor == 3 {
				close(first)
				select {
				case <-release:
				case <-time.After(5 * time.Second):
					panic("release timed out")
				}
			}
		} else {
			close(second)
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	defer system.Close()
	defer once.Do(func() {
		close(release)
	})
	must(system.InternalRequest(0, 3))
	must(system.InternalRequest(0, 5))
	must(system.Start())
	select {
	case <-first:
	case <-time.After(2 * time.Second):
		t.Fatal("first car did not start")
	}
	must(system.InternalRequest(0, 4))
	must(system.InternalRequest(0, 1))
	must(system.InternalRequest(1, 2))
	select {
	case <-second:
	case <-time.After(2 * time.Second):
		t.Fatal("second car blocked behind first")
	}
	once.Do(func() {
		close(release)
	})
	if err := system.RunUntilIdle(); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(firstCarStops, []int{3, 4, 5, 1}) {
		t.Fatal("sweep or reversal", firstCarStops)
	}
	if err := system.Close(); err != nil {
		t.Fatal(err)
	}
	if system.InternalRequest(0, 1) == nil {
		t.Fatal("accepted work after shutdown")
	}
}

func TestObserverFailure(t *testing.T) {
	system, err := NewSystem(1, 10, func(id, floor int) {
		panic("broken display")
	})
	if err != nil {
		t.Fatal(err)
	}
	must(system.InternalRequest(0, 1))
	if system.RunUntilIdle() == nil {
		t.Fatal("observer failure hidden")
	}
	if system.Close() == nil {
		t.Fatal("close did not report failure")
	}
}
