/*
Task scheduler: one process, in-memory teaching example.
Functional requirements: schedule one-time or fixed-delay recurring actions;
order dispatch by deadline, priority (0 high, 2 low), then insertion order;
cancel tasks, inspect status/runs, isolate action failures, and close workers.
Non-functional requirements: safe shared state, no polling loop, bounded task
registry (10,000 IDs), and no overlapping executions of the same recurring task.
Core entities: Task holds identity/deadline/action/state; Scheduler owns the
pending queue, registry and workers; Snapshot exposes a detached status/run count.
Flow: schedule -> start -> wait for earliest deadline -> mark RUNNING -> execute
outside the state lock -> record result -> optionally requeue after fixed delay.
Concurrency: queue selection and state transitions are atomic. Cancellation can
win before dispatch; after dispatch the action may finish but never recur.
Priorities break equal-deadline ties; completion order with several workers is
not guaranteed. Failures terminate that task, not the worker. Runs count attempts.
Close cancels pending work and running recurrence, waits for active actions, and
is owner-only. Actions must finish; there is no forced interruption or timeout.
Scope: monotonic milliseconds since construction, interval 0 means one-time;
IDs are retained for this scheduler lifetime. No persistence, retries, cron,
distributed ownership, eviction, or production-grade admission control.
Edge cases: duplicate/unknown IDs, invalid times, cancellation before/during run,
action failure, earlier insertion waking sleepers, and close before start.
Mutex + broadcast channel generations wake workers; timers wait for deadlines.
Actions return errors; panics are isolated. Close must not run inside an action.
Run: python3 scripts/run.py Go InterviewQuestions/TaskSchedular_demo
*/

package main

import (
	"container/heap"
	"errors"
	"fmt"
	"sync"
	"time"
)

type Task struct {
	id                       string
	due, interval            int64
	priority, sequence, runs int
	action                   func() error
	status                   string
}

type taskHeap []*Task

func (h taskHeap) Len() int {
	return len(h)
}

func (h taskHeap) Less(i, j int) bool {
	if h[i].due != h[j].due {
		return h[i].due < h[j].due
	}
	if h[i].priority != h[j].priority {
		return h[i].priority < h[j].priority
	}
	return h[i].sequence < h[j].sequence
}

func (h taskHeap) Swap(i, j int) {
	h[i], h[j] = h[j], h[i]
}

func (h *taskHeap) Push(value any) {
	*h = append(*h, value.(*Task))
}

func (h *taskHeap) Pop() any {
	old := *h
	value := old[len(old)-1]
	old[len(old)-1] = nil
	*h = old[:len(old)-1]
	return value
}

type Snapshot struct {
	Status string
	Runs   int
}

type Scheduler struct {
	mu              sync.Mutex
	changed         chan struct{}
	workers         sync.WaitGroup
	epoch           time.Time
	count           int
	started, closed bool
	tasks           map[string]*Task
	pending         taskHeap
	dispatched      []string
}

func NewScheduler(count int) (*Scheduler, error) {
	if count < 1 || count > 32 {
		return nil, errors.New("invalid worker count")
	}
	return &Scheduler{count: count, epoch: time.Now(), changed: make(chan struct{}), tasks: make(map[string]*Task)}, nil
}

func (s *Scheduler) Now() int64 {
	return time.Since(s.epoch).Milliseconds()
}

// signal is called with mu held. Every waiter sees the closed generation.
func (s *Scheduler) signal() {
	close(s.changed)
	s.changed = make(chan struct{})
}

func (s *Scheduler) Schedule(id string, due int64, priority int, interval int64, action func() error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || id == "" || s.tasks[id] != nil || len(s.tasks) >= 10000 || due < 0 || due > 1000000000000 || priority < 0 || priority > 2 || interval < 0 || interval > 1000000000 || action == nil {
		return errors.New("invalid task")
	}
	task := &Task{id: id, due: due, priority: priority, interval: interval, sequence: len(s.tasks), action: action, status: "WAITING"}
	s.tasks[id] = task
	heap.Push(&s.pending, task)
	s.signal()
	return nil
}

func (s *Scheduler) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.started || s.closed {
		return errors.New("already started/closed")
	}
	s.started = true
	s.workers.Add(s.count)
	for i := 0; i < s.count; i++ {
		go s.work()
	}
	return nil
}

func wait(ch <-chan struct{}, milliseconds int64) {
	timer := time.NewTimer(time.Duration(milliseconds) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-ch:
	case <-timer.C:
	}
}

func (s *Scheduler) take() *Task {
	s.mu.Lock()
	defer s.mu.Unlock()
	for !s.closed {
		for len(s.pending) > 0 && s.pending[0].status == "CANCELLED" {
			heap.Pop(&s.pending)
		}
		if len(s.pending) == 0 {
			ch := s.changed
			s.mu.Unlock()
			<-ch
			s.mu.Lock()
			continue
		}
		task := s.pending[0]
		remaining := task.due - s.Now()
		if remaining > 0 {
			ch := s.changed
			s.mu.Unlock()
			wait(ch, remaining)
			s.mu.Lock()
			continue
		}
		heap.Pop(&s.pending)
		task.status = "RUNNING"
		s.dispatched = append(s.dispatched, task.id)
		return task
	}
	return nil
}

func invoke(action func() error) (failed bool) {
	defer func() {
		if recover() != nil {
			failed = true
		}
	}()
	return action() != nil
}

func (s *Scheduler) work() {
	defer s.workers.Done()
	for {
		task := s.take()
		if task == nil {
			return
		}
		failed := invoke(task.action)
		s.mu.Lock()
		task.runs++
		if task.status != "CANCELLED" {
			if failed {
				task.status = "FAILED"
			} else if task.interval > 0 && !s.closed {
				task.due = s.Now() + task.interval
				task.status = "WAITING"
				heap.Push(&s.pending, task)
			} else {
				task.status = "COMPLETED"
			}
		}
		s.signal()
		s.mu.Unlock()
	}
}

func (s *Scheduler) Cancel(id string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := s.tasks[id]
	if task == nil {
		return false, errors.New("unknown task")
	}
	if task.status != "WAITING" && task.status != "RUNNING" {
		return false, nil
	}
	task.status = "CANCELLED"
	s.signal()
	return true, nil
}

func (s *Scheduler) Snapshot(id string) (Snapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := s.tasks[id]
	if task == nil {
		return Snapshot{}, errors.New("unknown task")
	}
	return Snapshot{task.status, task.runs}, nil
}

func (s *Scheduler) Dispatches() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string{}, s.dispatched...)
}

func (s *Scheduler) AwaitRuns(id string, count int, timeout int64) (bool, error) {
	if count < 1 || timeout < 0 || timeout > 60000 {
		return false, errors.New("invalid wait")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	task := s.tasks[id]
	if task == nil {
		return false, errors.New("unknown task")
	}
	deadline := s.Now() + timeout
	for task.runs < count {
		if task.status == "CANCELLED" || task.status == "FAILED" || task.status == "COMPLETED" {
			return false, nil
		}
		remaining := deadline - s.Now()
		if remaining <= 0 {
			return false, nil
		}
		ch := s.changed
		s.mu.Unlock()
		wait(ch, remaining)
		s.mu.Lock()
	}
	return true, nil
}

// Close belongs to the owner: calling it inside an action waits for itself.
func (s *Scheduler) Close() {
	s.mu.Lock()
	s.closed = true
	for _, task := range s.tasks {
		if task.status == "WAITING" || (task.status == "RUNNING" && task.interval > 0) {
			task.status = "CANCELLED"
		}
	}
	s.pending = nil
	s.signal()
	s.mu.Unlock()
	s.workers.Wait()
}

func require(ok bool) {
	if !ok {
		panic("scheduler contract failed")
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	scheduler, err := NewScheduler(1)
	must(err)
	defer scheduler.Close()
	noop := func() error {
		return nil
	}
	fail := func() error {
		return errors.New("expected failure")
	}
	must(scheduler.Schedule("low", 0, 2, 0, noop))
	must(scheduler.Schedule("high", 0, 0, 0, noop))
	must(scheduler.Schedule("cancel", 0, 1, 0, fail))
	ok, err := scheduler.Cancel("cancel")
	must(err)
	require(ok)
	must(scheduler.Start())
	ok, err = scheduler.AwaitRuns("low", 1, 5000)
	must(err)
	require(ok)
	order := scheduler.Dispatches()
	require(len(order) == 2 && order[0] == "high" && order[1] == "low")
	must(scheduler.Schedule("failure", scheduler.Now(), 1, 0, fail))
	ok, err = scheduler.AwaitRuns("failure", 1, 5000)
	must(err)
	require(ok)
	state, err := scheduler.Snapshot("failure")
	must(err)
	require(state.Status == "FAILED")
	must(scheduler.Schedule("repeat", scheduler.Now(), 1, 5, noop))
	ok, err = scheduler.AwaitRuns("repeat", 2, 5000)
	must(err)
	require(ok)
	ok, err = scheduler.Cancel("repeat")
	must(err)
	require(ok)
	scheduler.Close()
	state, err = scheduler.Snapshot("cancel")
	must(err)
	require(state.Runs == 0)
	state, err = scheduler.Snapshot("repeat")
	must(err)
	require(state.Status == "CANCELLED")
	fmt.Println("Priority: high,low\nCancelled before dispatch: true\nFailure isolated: true\nRecurring stopped: true\nShutdown: closed")
}
