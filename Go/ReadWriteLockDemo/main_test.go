package main

import (
	"sync"
	"testing"
)

func TestConcurrentReadAndWrite(t *testing.T) {
	cache := &Cache{values: make(map[int]int)}
	var workers sync.WaitGroup
	for key := 0; key < 8; key++ {
		workers.Add(1)
		go func(key int) {
			defer workers.Done()
			for value := 0; value < 100; value++ {
				cache.Write(key, value)
				found, exists := cache.Read(key)
				if !exists || found != value {
					t.Error("lost value")
				}
				cache.Read((key + 1) % 8)
			}
		}(key)
	}
	workers.Wait()
}
