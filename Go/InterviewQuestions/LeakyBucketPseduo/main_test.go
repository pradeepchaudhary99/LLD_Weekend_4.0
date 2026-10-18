package main

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestAdmissionAndDrain(t *testing.T) {
	var attempted atomic.Int64
	bucket, err := NewLeakyBucket(10, 1, func(job int) error {
		attempted.Add(1)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	var group sync.WaitGroup
	var accepted atomic.Int64
	for index := 0; index < 100; index++ {
		group.Add(1)
		go func(job int) {
			defer group.Done()
			if bucket.Submit(job) {
				accepted.Add(1)
			}
		}(index)
	}
	group.Wait()
	if accepted.Load() != 10 {
		t.Fatal("capacity not enforced")
	}
	for index := 0; index < 2; index++ {
		group.Add(1)
		go func() {
			defer group.Done()
			bucket.Close()
		}()
	}
	group.Wait()
	if attempted.Load() != 10 || bucket.Submit(999) {
		t.Fatal("drain/close failure")
	}
}

func TestWakeupCadenceAndFailure(t *testing.T) {
	arrived := make(chan struct{}, 1)
	times := make([]time.Time, 0)
	bucket, err := NewLeakyBucket(4, 20, func(job int) error {
		times = append(times, time.Now())
		if job == 1 {
			arrived <- struct{}{}
			return errors.New("expected failure")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer bucket.Close()
	if bucket.Start() != nil || !bucket.Submit(1) {
		t.Fatal("startup failure")
	}
	select {
	case <-arrived:
	case <-time.After(5 * time.Second):
		t.Fatal("worker did not wake")
	}
	if !bucket.Submit(2) || !bucket.Submit(3) {
		t.Fatal("submission failed")
	}
	bucket.Close()
	if len(times) != 3 || bucket.Failures() != 1 {
		t.Fatal("failure stopped worker")
	}
	for index := 1; index < len(times); index++ {
		if times[index].Sub(times[index-1]) < 18*time.Millisecond {
			t.Fatal("attempts too close")
		}
	}
}
