package main

import (
	"sync"
	"sync/atomic"
	"testing"
)

func TestConcurrentAllowance(t *testing.T) {
	strategies := []Strategy{value(NewTokenBucket(17, 1000, 1)), value(NewSlidingWindow(17, 1000, 1))}
	for _, strategy := range strategies {
		var group sync.WaitGroup
		var allowed atomic.Int64
		for i := 0; i < 200; i++ {
			group.Add(1)
			go func() {
				defer group.Done()
				accepted, err := strategy.Allow("same", 0)
				if err != nil {
					t.Error(err)
				}
				if accepted {
					allowed.Add(1)
				}
			}()
		}
		group.Wait()
		if allowed.Load() != 17 {
			t.Fatalf("allowed %d", allowed.Load())
		}
		if _, err := strategy.Allow("other", 0); err == nil {
			t.Fatal("expected key-capacity rejection")
		}
	}
}

func TestFrequentRefill(t *testing.T) {
	bucket := value(NewTokenBucket(1, 1000, 1))
	if !value(bucket.Allow("x", 0)) {
		t.Fatal("initial token missing")
	}
	for now := int64(1); now < 1000; now++ {
		if value(bucket.Allow("x", now)) {
			t.Fatal("early refill")
		}
	}
	if !value(bucket.Allow("x", 1000)) {
		t.Fatal("lost fractional refill time")
	}
}
