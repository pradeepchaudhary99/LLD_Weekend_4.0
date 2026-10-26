package main

import (
	"testing"
	"time"
)

func TestParallelCancellation(t *testing.T) {
	scheduler, err := NewScheduler(2)
	if err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{}, 2)
	release := make(chan struct{})
	defer scheduler.Close()
	defer close(release)
	action := func() error { entered <- struct{}{}; <-release; return nil }
	for _, id := range []string{"a", "b"} {
		if err := scheduler.Schedule(id, 0, 1, 1, action); err != nil {
			t.Fatal(err)
		}
	}
	if err := scheduler.Start(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		select {
		case <-entered:
		case <-time.After(3 * time.Second):
			t.Fatal("workers did not run independently")
		}
	}
	for _, id := range []string{"a", "b"} {
		ok, err := scheduler.Cancel(id)
		if err != nil || !ok {
			t.Fatal("running cancellation failed")
		}
	}
	// The deferred release precedes Close, so no worker is left blocked.
}

func TestEarlierInsertionAndLifecycle(t *testing.T) {
	scheduler, _ := NewScheduler(1)
	defer scheduler.Close()
	noop := func() error { return nil }
	if err := scheduler.Schedule("future", 60000, 0, 0, noop); err != nil {
		t.Fatal(err)
	}
	if err := scheduler.Start(); err != nil {
		t.Fatal(err)
	}
	due := scheduler.Now() + 30
	observed := make(chan int64, 1)
	if err := scheduler.Schedule("early", due, 1, 0, func() error { observed <- scheduler.Now(); return nil }); err != nil {
		t.Fatal(err)
	}
	ok, err := scheduler.AwaitRuns("early", 1, 3000)
	if err != nil || !ok {
		t.Fatal("earlier insertion did not wake worker")
	}
	if <-observed < due {
		t.Fatal("task ran early")
	}
	scheduler.Close()
	state, _ := scheduler.Snapshot("future")
	if state.Status != "CANCELLED" || state.Runs != 0 {
		t.Fatal(state)
	}
	if scheduler.Schedule("late", 0, 1, 0, noop) == nil {
		t.Fatal("accepted after close")
	}
}
