package main

import (
	"errors"
	"fmt"
	"sort"
	"sync"
)

type Elevator struct {
	id        int
	floor     int
	direction string
	stops     map[int]bool
	display   func(int, int)
	gate      sync.Mutex
	changed   *sync.Cond
	shutdown  bool
	notifying bool
	failure   error
	done      chan struct{}
}

func newElevator(id int, display func(int, int)) *Elevator {
	elevator := &Elevator{id: id, direction: "IDLE", stops: map[int]bool{}, display: display, done: make(chan struct{})}
	elevator.changed = sync.NewCond(&elevator.gate)
	return elevator
}

func (e *Elevator) currentFloor() int {
	e.gate.Lock()
	defer e.gate.Unlock()
	return e.floor
}

func (e *Elevator) addStop(floor int) error {
	e.gate.Lock()
	defer e.gate.Unlock()
	if e.failure != nil {
		return e.failure
	}
	if e.shutdown {
		return errors.New("elevator shutting down")
	}
	e.stops[floor] = true
	e.changed.Broadcast()
	return nil
}

func (e *Elevator) isIdle() (bool, error) {
	e.gate.Lock()
	defer e.gate.Unlock()
	return len(e.stops) == 0 && !e.notifying, e.failure
}

func (e *Elevator) awaitIdle() error {
	e.gate.Lock()
	defer e.gate.Unlock()
	for e.failure == nil && (len(e.stops) != 0 || e.notifying) {
		e.changed.Wait()
	}
	return e.failure
}

func (e *Elevator) stop() {
	e.gate.Lock()
	defer e.gate.Unlock()
	e.shutdown = true
	e.changed.Broadcast()
}

func (e *Elevator) run() {
	defer close(e.done)
	for {
		e.gate.Lock()
		for len(e.stops) == 0 && !e.shutdown {
			e.changed.Wait()
		}
		if len(e.stops) == 0 {
			e.gate.Unlock()
			return
		}
		arrived, hasArrival := 0, false
		if e.stops[e.floor] {
			delete(e.stops, e.floor)
			arrived, hasArrival = e.floor, true
		} else {
			above, below := []int{}, []int{}
			for stop := range e.stops {
				if stop > e.floor {
					above = append(above, stop)
				} else {
					below = append(below, stop)
				}
			}
			sort.Ints(above)
			sort.Sort(sort.Reverse(sort.IntSlice(below)))
			var target int
			if e.direction == "DOWN" && len(below) > 0 {
				target = below[0]
			} else if len(above) > 0 {
				target = above[0]
			} else {
				target = below[0]
			}
			if target > e.floor {
				e.direction = "UP"
				e.floor++
			} else {
				e.direction = "DOWN"
				e.floor--
			}
			if e.stops[e.floor] {
				delete(e.stops, e.floor)
				arrived, hasArrival = e.floor, true
			}
		}
		if len(e.stops) == 0 {
			e.direction = "IDLE"
		}
		e.notifying = hasArrival
		e.gate.Unlock()
		var failure error
		if hasArrival {
			// Recover a teaching observer panic so waiters receive an explicit failure.
			func() {
				defer func() {
					if value := recover(); value != nil {
						failure = fmt.Errorf("observer failed: %v", value)
					}
				}()
				e.display(e.id, arrived)
			}()
		}
		e.gate.Lock()
		e.notifying = false
		e.failure = failure
		if failure != nil {
			e.shutdown = true
		}
		e.changed.Broadcast()
		e.gate.Unlock()
		if failure != nil {
			return
		}
	}
}

type SelectionStrategy interface {
	Select([]*Elevator, int) *Elevator
}

type NearestElevatorStrategy struct{}

func distance(a, b int) int {
	if a > b {
		return a - b
	}
	return b - a
}

func (NearestElevatorStrategy) Select(elevators []*Elevator, floor int) *Elevator {
	selected := elevators[0]
	for _, elevator := range elevators[1:] {
		if distance(elevator.currentFloor(), floor) < distance(selected.currentFloor(), floor) {
			selected = elevator
		}
	}
	return selected
}

type RoundRobinStrategy struct {
	next int
}

func (strategy *RoundRobinStrategy) Select(elevators []*Elevator, floor int) *Elevator {
	selected := elevators[strategy.next]
	strategy.next = (strategy.next + 1) % len(elevators)
	return selected
}

type ElevatorSystem struct {
	topFloor  int
	elevators []*Elevator
	strategy  SelectionStrategy
	mutex     sync.Mutex
	started   bool
	closed    bool
}

func NewSystem(count, topFloor int, display func(int, int)) (*ElevatorSystem, error) {
	if count <= 0 || topFloor < 1 || display == nil {
		return nil, fmt.Errorf("invalid building")
	}
	system := &ElevatorSystem{topFloor: topFloor, strategy: NearestElevatorStrategy{}}
	for id := 0; id < count; id++ {
		system.elevators = append(system.elevators, newElevator(id, display))
	}
	return system, nil
}

func (system *ElevatorSystem) SetStrategy(strategy SelectionStrategy) error {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	if system.closed || strategy == nil {
		return errors.New("closed system or missing strategy")
	}
	system.strategy = strategy
	return nil
}

func (system *ElevatorSystem) ExternalRequest(floor int, direction string) (int, error) {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	if system.closed || floor < 0 || floor > system.topFloor {
		return 0, fmt.Errorf("invalid floor")
	}
	if (direction != "UP" && direction != "DOWN") || (floor == 0 && direction == "DOWN") || (floor == system.topFloor && direction == "UP") {
		return 0, fmt.Errorf("invalid hall direction")
	}
	selected := system.strategy.Select(system.elevators, floor)
	return selected.id, selected.addStop(floor)
}

func (system *ElevatorSystem) InternalRequest(id, floor int) error {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	if system.closed || floor < 0 || floor > system.topFloor || id < 0 || id >= len(system.elevators) {
		return fmt.Errorf("invalid floor or elevator")
	}
	return system.elevators[id].addStop(floor)
}

func (system *ElevatorSystem) startWorkers() {
	if !system.started {
		system.started = true
		for _, elevator := range system.elevators {
			go elevator.run()
		}
	}
}

func (system *ElevatorSystem) Start() error {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	if system.closed {
		return errors.New("system closed")
	}
	system.startWorkers()
	return nil
}

func (system *ElevatorSystem) RunUntilIdle() error {
	if err := system.Start(); err != nil {
		return err
	}
	for {
		for _, elevator := range system.elevators {
			if err := elevator.awaitIdle(); err != nil {
				return err
			}
		}
		system.mutex.Lock()
		allIdle := true
		var failure error
		for _, elevator := range system.elevators {
			idle, err := elevator.isIdle()
			allIdle = allIdle && idle
			if err != nil {
				failure = err
			}
		}
		system.mutex.Unlock()
		if failure != nil || allIdle {
			return failure
		}
	}
}

func (system *ElevatorSystem) Close() error {
	// Lifecycle methods belong to the owner, not an observer running on a car.
	system.mutex.Lock()
	system.closed = true
	system.startWorkers()
	for _, elevator := range system.elevators {
		elevator.stop()
	}
	system.mutex.Unlock()
	var failure error
	for _, elevator := range system.elevators {
		<-elevator.done
		if _, err := elevator.isIdle(); err != nil {
			failure = err
		}
	}
	return failure
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	var displayGate sync.Mutex
	arrivals := [][2]int{}
	system, err := NewSystem(2, 10, func(id, floor int) {
		displayGate.Lock()
		defer displayGate.Unlock()
		arrivals = append(arrivals, [2]int{id, floor})
	})
	if err != nil {
		panic(err)
	}
	defer func() {
		must(system.Close())
	}()
	printArrivals := func() {
		displayGate.Lock()
		defer displayGate.Unlock()
		sort.SliceStable(arrivals, func(a, b int) bool {
			return arrivals[a][0] < arrivals[b][0]
		})
		for _, arrival := range arrivals {
			fmt.Printf("Elevator %d arrived: %d\n", arrival[0], arrival[1])
		}
		arrivals = nil
	}
	request := func(floor int, direction string) int {
		id, err := system.ExternalRequest(floor, direction)
		if err != nil {
			panic(err)
		}
		return id
	}
	fmt.Println("Selected:", request(3, "UP"))
	must(system.InternalRequest(0, 5))
	must(system.InternalRequest(0, 5))
	must(system.RunUntilIdle())
	printArrivals()
	fmt.Println("Selected:", request(4, "DOWN"))
	must(system.InternalRequest(0, 1))
	must(system.RunUntilIdle())
	printArrivals()
	must(system.SetStrategy(&RoundRobinStrategy{}))
	fmt.Println("Round robin:", request(0, "UP"))
	fmt.Println("Round robin:", request(0, "UP"))
	must(system.RunUntilIdle())
	printArrivals()
	must(system.RunUntilIdle())
	printArrivals()
	if system.InternalRequest(0, 11) == nil {
		panic("invalid floor accepted")
	}
	fmt.Println("Invalid floor rejected")
	if _, err := system.ExternalRequest(0, "DOWN"); err == nil {
		panic("invalid direction accepted")
	}
	fmt.Println("Invalid direction rejected")
}
