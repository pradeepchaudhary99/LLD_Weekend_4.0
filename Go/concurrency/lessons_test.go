package concurrency

import (
	"reflect"
	"sync"
	"testing"
)

func TestSingleWorkerRecoversAndDrains(t *testing.T) {
	pool, err := NewPool(1)
	if err != nil {
		t.Fatal(err)
	}
	values := []int{}
	pool.Submit(func() {
		panic("expected")
	})
	for value := 0; value < 100; value++ {
		number := value
		if err := pool.Submit(func() {
			values = append(values, number)
		}); err != nil {
			t.Fatal(err)
		}
	}
	pool.Close()
	pool.Close()
	expected := make([]int, 100)
	for index := range expected {
		expected[index] = index
	}
	if !reflect.DeepEqual(values, expected) || pool.failures.Load() != 1 {
		t.Fatal("tasks lost, reordered or failure not recorded")
	}
	if pool.Submit(func() {}) == nil {
		t.Fatal("closed pool accepted work")
	}
}

func TestConcurrentSubmissionAndClose(t *testing.T) {
	pool, _ := NewPool(3)
	var submitters sync.WaitGroup
	for index := 0; index < 20; index++ {
		submitters.Add(1)
		go func() {
			defer submitters.Done()
			// Either accepted and drained, or explicitly rejected; never send on a closed channel.
			_ = pool.Submit(func() {})
		}()
	}
	pool.Close()
	submitters.Wait()
}

func TestInvalidAndEmptyPool(t *testing.T) {
	for _, size := range []int{0, -1} {
		if _, err := NewPool(size); err == nil {
			t.Fatal("invalid pool size accepted")
		}
		if _, err := newBuffer(size); err == nil {
			t.Fatal("invalid capacity accepted")
		}
	}
	pool, _ := NewPool(1)
	if pool.Submit(nil) == nil {
		t.Fatal("nil task accepted")
	}
	pool.Close()
	pool.Close()
}
