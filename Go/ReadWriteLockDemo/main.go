package main

import (
	"fmt"
	"sync"
)

type Cache struct {
	values map[int]int
	mutex  sync.RWMutex
}

func (cache *Cache) Read(key int) (int, bool) {
	cache.mutex.RLock()
	defer cache.mutex.RUnlock()
	value, found := cache.values[key]
	return value, found
}

func (cache *Cache) Write(key, value int) {
	cache.mutex.Lock()
	defer cache.mutex.Unlock()
	cache.values[key] = value
}

func main() {
	cache := &Cache{values: make(map[int]int)}
	var writers sync.WaitGroup
	for key := 0; key < 4; key++ {
		writers.Add(1)
		go func(key int) {
			defer writers.Done()
			cache.Write(key, key*10)
		}(key)
	}
	writers.Wait()
	total := 0
	for key := 0; key < 4; key++ {
		value, found := cache.Read(key)
		if !found {
			panic("missing key")
		}
		total += value
	}
	_, found := cache.Read(99)
	if total != 60 || found {
		panic("cache contents incorrect")
	}
	fmt.Println("Cache sum:", total)
	fmt.Println("Missing: true")
	cache.Write(0, -1)
	value, _ := cache.Read(0)
	fmt.Println("Stored negative:", value)
	cache.Write(0, 7)
	value, _ = cache.Read(0)
	fmt.Println("Updated:", value)
}
