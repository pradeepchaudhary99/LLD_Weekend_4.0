/*
 * INTERVIEW DESIGN NOTES - Queued Leaky Bucket
 *
 * Functional requirements:
 * - Accept requests into a bounded FIFO queue; reject overflow and submissions after close.
 * - One worker attempts jobs with a minimum interval between scheduled attempt starts.
 * - Wake an idle worker on submission; count ordinary handler failures and continue.
 * - Close rejects new work, drains accepted work, and waits for the worker to finish.
 *
 * Non-functional requirements:
 * - Protect admission and queue state; execute callbacks outside queue locks.
 * - Use monotonic time, bounded waiting capacity and no idle busy-spinning.
 * - Do not accumulate catch-up credits after slow callbacks or scheduler delays.
 *
 * Core entities and responsibilities:
 * - LeakyBucket: capacity, waiting queue, worker lifecycle and cumulative failure count.
 * - Request: integer job ID; handler processes it synchronously (awaitable in JS/TS).
 * - Worker: thread/goroutine in native editions; async task on the Node event loop.
 * - Monotonic clock: determines remaining delay since the previous attempt started.
 *
 * Operations and flow:
 * - submit -> atomic closed/capacity check -> enqueue -> signal -> return acceptance.
 * - start -> wait for work -> wait remaining interval -> dequeue -> attempt handler.
 * - close before start starts a draining worker; repeated close waits safely again.
 * - Ordinary handler failure increments failures; the job is not retried or requeued.
 *
 * Design choices and invariants:
 * - Capacity counts waiting jobs, excluding the one executing; total outstanding <= capacity+1.
 * - First attempt can start immediately. Slow work lowers throughput rather than causing bursts.
 * - Callbacks are serial; start twice is invalid; submissions may be queued before start.
 * - Close is owner-only: handlers must not call/await close on their own bucket.
 * - Handlers must finish; no forced cancellation or shutdown timeout is promised.
 *
 * Assumptions and scope:
 * - Interval is an integer 1..60000 milliseconds. This is a queue/shaper, not the
 *   token-bucket or sliding-window admission algorithms in RateLimitingDemo.
 * - Native threads and Node timers are not real-time; OS scheduling can delay attempts.
 * - No durable queue, retry policy, distributed ordering, priorities or delivery guarantee
 *   across process crashes. Fatal runtime/process failures are outside the contract.
 * - C# uses Dispose for close; C++ also drains in its destructor; Go returns handler errors
 *   and counts recovered panics; JS/TS await rejected promises and count them as failures.
 * - Native FIFO operations are O(1); JS/TS Array.shift is O(queue length) in this small demo.
 *
 * Edge cases and discussion:
 * - Full queue before start, close before start, idle wakeup, failure followed by later work,
 *   repeated close, invalid configuration, cadence and concurrent producers during shutdown.
 * - Discuss deadlines, backpressure, cancellation, durable delivery and overload policies.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "Go" InterviewQuestions/LeakyBucketPseduo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

package main

import (
	"container/list"
	"errors"
	"fmt"
	"reflect"
	"sync"
	"time"
)

type LeakyBucket struct {
	mu       sync.Mutex
	changed  *sync.Cond
	capacity int
	interval time.Duration
	handler  func(int) error
	queue    *list.List
	started  bool
	closed   bool
	failures int
	done     chan struct{}
}

func NewLeakyBucket(capacity int, intervalMs int, handler func(int) error) (*LeakyBucket, error) {
	if capacity <= 0 || intervalMs <= 0 || intervalMs > 60000 || handler == nil {
		return nil, errors.New("invalid configuration")
	}
	bucket := &LeakyBucket{capacity: capacity, interval: time.Duration(intervalMs) * time.Millisecond,
		handler: handler, queue: list.New(), done: make(chan struct{})}
	bucket.changed = sync.NewCond(&bucket.mu)
	return bucket, nil
}

func (b *LeakyBucket) Submit(request int) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed || b.queue.Len() >= b.capacity {
		return false
	}
	b.queue.PushBack(request)
	b.changed.Broadcast()
	return true
}

func (b *LeakyBucket) startWorker() {
	b.started = true
	go b.run()
}

func (b *LeakyBucket) Start() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.started || b.closed {
		return errors.New("already started/closed")
	}
	b.startWorker()
	return nil
}

func (b *LeakyBucket) invoke(request int) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("handler panic: %v", recovered)
		}
	}()
	return b.handler(request)
}

func (b *LeakyBucket) run() {
	defer close(b.done)
	var lastStart time.Time
	for {
		b.mu.Lock()
		for b.queue.Len() == 0 && !b.closed {
			b.changed.Wait()
		}
		if b.queue.Len() == 0 {
			b.mu.Unlock()
			return
		}
		b.mu.Unlock()
		if !lastStart.IsZero() {
			time.Sleep(time.Until(lastStart.Add(b.interval)))
		}
		// One worker owns removals; producers can only append during the wait.
		b.mu.Lock()
		request := b.queue.Remove(b.queue.Front()).(int)
		b.mu.Unlock()
		lastStart = time.Now()
		if b.invoke(request) != nil {
			b.mu.Lock()
			b.failures++
			b.mu.Unlock()
		}
	}
}

func (b *LeakyBucket) Failures() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.failures
}

// Close is an owner operation; calling it from the handler would wait for itself.
func (b *LeakyBucket) Close() {
	b.mu.Lock()
	b.closed = true
	if !b.started {
		b.startWorker()
	}
	b.changed.Broadcast()
	b.mu.Unlock()
	<-b.done
}

func check(condition bool) {
	if !condition {
		panic("queue contract failed")
	}
}

func main() {
	attempted := make([]int, 0)
	bucket, err := NewLeakyBucket(2, 1, func(request int) error {
		attempted = append(attempted, request)
		if request == 1 {
			return errors.New("simulated handler failure")
		}
		return nil
	})
	check(err == nil)
	check(bucket.Submit(1))
	check(bucket.Submit(2))
	check(!bucket.Submit(3))
	check(bucket.Start() == nil)
	bucket.Close()
	bucket.Close()
	check(!bucket.Submit(4))
	check(reflect.DeepEqual(attempted, []int{1, 2}) && bucket.Failures() == 1)
	beforeStart, err := NewLeakyBucket(1, 1, func(request int) error {
		return nil
	})
	check(err == nil)
	check(beforeStart.Submit(7))
	beforeStart.Close()
	_, err = NewLeakyBucket(0, 1, func(request int) error {
		return nil
	})
	check(err != nil)
	fmt.Println("Overflow rejected: true")
	fmt.Println("FIFO attempts: 1,2")
	fmt.Println("Handler failures: 1")
	fmt.Println("Shutdown drained: true")
}
