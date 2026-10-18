package main

import (
	"sync"
	"testing"
)

func TestConcurrentCache(t *testing.T) {
	cache := newCache(8)
	var group sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		group.Add(1)
		go func(worker int) {
			defer group.Done()
			for index := 0; index < 500; index++ {
				cache.Put((index+worker)%16, index)
				cache.Get(index % 16)
			}
		}(worker)
	}
	group.Wait()
	keys := cache.Keys()
	found := make(map[int]bool)
	for _, key := range keys {
		if found[key] {
			t.Fatal("duplicate key")
		}
		found[key] = true
		if _, ok := cache.Get(key); !ok {
			t.Fatal("missing mapped key")
		}
	}
	if len(keys) != 8 {
		t.Fatalf("size %d", len(keys))
	}
}
