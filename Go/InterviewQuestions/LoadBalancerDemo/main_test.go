package main

import (
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
)

func TestConcurrentLeases(t *testing.T) {
	balancer := NewLoadBalancer()
	must(balancer.Register("A", 1))
	must(balancer.Register("B", 1))
	must(balancer.SetStrategy(&LeastConnections{}))
	var group sync.WaitGroup
	var countA atomic.Int64
	for i := 0; i < 200; i++ {
		group.Add(1)
		go func(index int) {
			defer group.Done()
			selected, err := balancer.Acquire(fmt.Sprint(index))
			if err != nil {
				t.Error(err)
			}
			if selected == "A" {
				countA.Add(1)
			}
		}(i)
	}
	group.Wait()
	if countA.Load() != 100 {
		t.Fatalf("unbalanced reservations: %d", countA.Load())
	}
	must(balancer.OnHealth("A", false))
	for i := 0; i < 200; i++ {
		must(balancer.Release(fmt.Sprint(i)))
	}
	must(balancer.Remove("A"))
	must(balancer.Remove("B"))
}
