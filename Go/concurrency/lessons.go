// Package concurrency demonstrates goroutines, channels and owner-managed worker lifecycles.
package concurrency

import (
	"fmt"
	"sync"
	"sync/atomic"
)

type Pool struct {
	jobs     chan func()
	workers  sync.WaitGroup
	mutex    sync.Mutex
	closed   bool
	failures atomic.Int64
}

func NewPool(size int) (*Pool, error) {
	if size <= 0 {
		return nil, fmt.Errorf("pool size must be positive")
	}
	pool := &Pool{jobs: make(chan func(), 16)}
	for index := 0; index < size; index++ {
		pool.workers.Add(1)
		go func() {
			defer pool.workers.Done()
			for task := range pool.jobs {
				pool.execute(task)
			}
		}()
	}
	return pool, nil
}

func (pool *Pool) execute(task func()) {
	defer func() {
		if recover() != nil {
			pool.failures.Add(1)
		}
	}()
	task()
}

func (pool *Pool) Submit(task func()) error {
	pool.mutex.Lock()
	defer pool.mutex.Unlock()
	if pool.closed {
		return fmt.Errorf("pool is closed")
	}
	if task == nil {
		return fmt.Errorf("task must not be nil")
	}
	// This bounded queue applies backpressure. Tasks must not recursively submit to this pool.
	pool.jobs <- task
	return nil
}

// Close is called by the owner, outside workers, and waits for accepted tasks.
func (pool *Pool) Close() {
	pool.mutex.Lock()
	if !pool.closed {
		pool.closed = true
		close(pool.jobs)
	}
	pool.mutex.Unlock()
	pool.workers.Wait()
}

func Fundamentals() {
	var count atomic.Int64
	var threads sync.WaitGroup
	for worker := 0; worker < 4; worker++ {
		threads.Add(1)
		go func() {
			defer threads.Done()
			for iteration := 0; iteration < 1000; iteration++ {
				count.Add(1)
			}
		}()
	}
	threads.Wait()
	if count.Load() != 4000 {
		panic("lost updates")
	}
	fmt.Println("Counter:", count.Load())
}

func newBuffer(capacity int) (chan int, error) {
	if capacity <= 0 {
		return nil, fmt.Errorf("capacity must be positive")
	}
	return make(chan int, capacity), nil
}

func ProducerConsumer() {
	buffer, _ := newBuffer(1)
	done := make(chan int)
	go func() {
		total := 0
		for value := range buffer {
			total += value
		}
		done <- total
	}()
	for value := 1; value <= 10; value++ {
		buffer <- value
	}
	close(buffer)
	total := <-done
	if total != 55 {
		panic("missing items")
	}
	fmt.Println("Consumed sum:", total)
	if _, err := newBuffer(0); err == nil {
		panic("invalid capacity accepted")
	}
	fmt.Println("Invalid capacity rejected")
}

func CustomPool() {
	pool, _ := NewPool(3)
	var total atomic.Int64
	pool.Submit(func() {
		panic("expected teaching failure")
	})
	for value := 1; value <= 10; value++ {
		number := value
		pool.Submit(func() {
			total.Add(int64(number))
		})
	}
	pool.Close()
	pool.Close()
	if total.Load() != 55 || pool.failures.Load() != 1 {
		panic("tasks lost or failures hidden")
	}
	fmt.Println("Completed sum:", total.Load())
	fmt.Println("Task failures:", pool.failures.Load())
	if pool.Submit(func() {}) == nil {
		panic("closed pool accepted work")
	}
	fmt.Println("Submission after shutdown rejected")
	if _, err := NewPool(0); err == nil {
		panic("invalid pool size accepted")
	}
	fmt.Println("Invalid pool size rejected")
}

func ExecutorPool() {
	// Go has no ExecutorService: goroutines consume jobs and send results on channels.
	jobs := make(chan int)
	results := make(chan int, 10)
	var workers sync.WaitGroup
	for index := 0; index < 3; index++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for value := range jobs {
				results <- value
			}
		}()
	}
	for value := 1; value <= 10; value++ {
		jobs <- value
	}
	close(jobs)
	workers.Wait()
	close(results)
	total := 0
	for value := range results {
		total += value
	}
	if total != 55 {
		panic("missing results")
	}
	fmt.Println("Executor sum:", total)
}
