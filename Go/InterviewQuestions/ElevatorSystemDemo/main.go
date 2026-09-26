package main

import (
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
}

func (elevator *Elevator) serve() {
	if elevator.stops[elevator.floor] {
		delete(elevator.stops, elevator.floor)
		elevator.display(elevator.id, elevator.floor)
	}
}

func (elevator *Elevator) tick() {
	elevator.serve()
	if len(elevator.stops) == 0 {
		elevator.direction = "IDLE"
		return
	}
	above := []int{}
	below := []int{}
	for stop := range elevator.stops {
		if stop > elevator.floor {
			above = append(above, stop)
		} else {
			below = append(below, stop)
		}
	}
	sort.Ints(above)
	sort.Sort(sort.Reverse(sort.IntSlice(below)))
	var target int
	if elevator.direction == "DOWN" && len(below) > 0 {
		target = below[0]
	} else if len(above) > 0 {
		target = above[0]
	} else {
		target = below[0]
	}
	if target > elevator.floor {
		elevator.direction = "UP"
		elevator.floor++
	} else {
		elevator.direction = "DOWN"
		elevator.floor--
	}
	elevator.serve()
	if len(elevator.stops) == 0 {
		elevator.direction = "IDLE"
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
		if distance(elevator.floor, floor) < distance(selected.floor, floor) {
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
}

func NewSystem(count, topFloor int, display func(int, int)) (*ElevatorSystem, error) {
	if count <= 0 || topFloor < 1 {
		return nil, fmt.Errorf("invalid building")
	}
	system := &ElevatorSystem{topFloor: topFloor, strategy: NearestElevatorStrategy{}}
	for id := 0; id < count; id++ {
		system.elevators = append(system.elevators, &Elevator{id: id, direction: "IDLE", stops: make(map[int]bool), display: display})
	}
	return system, nil
}

func (system *ElevatorSystem) SetStrategy(strategy SelectionStrategy) {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	system.strategy = strategy
}

func (system *ElevatorSystem) ExternalRequest(floor int, direction string) (int, error) {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	if floor < 0 || floor > system.topFloor {
		return 0, fmt.Errorf("invalid floor")
	}
	if (direction != "UP" && direction != "DOWN") || (floor == 0 && direction == "DOWN") || (floor == system.topFloor && direction == "UP") {
		return 0, fmt.Errorf("invalid hall direction")
	}
	selected := system.strategy.Select(system.elevators, floor)
	selected.stops[floor] = true
	return selected.id, nil
}

func (system *ElevatorSystem) InternalRequest(id, floor int) error {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	if floor < 0 || floor > system.topFloor || id < 0 || id >= len(system.elevators) {
		return fmt.Errorf("invalid floor or elevator")
	}
	system.elevators[id].stops[floor] = true
	return nil
}

func (system *ElevatorSystem) RunUntilIdle() {
	system.mutex.Lock()
	defer system.mutex.Unlock()
	for {
		pending := false
		for _, elevator := range system.elevators {
			if len(elevator.stops) > 0 {
				pending = true
			}
			elevator.tick()
		}
		if !pending {
			return
		}
	}
}

func main() {
	system, err := NewSystem(2, 10, func(id, floor int) {
		fmt.Printf("Elevator %d arrived: %d\n", id, floor)
	})
	if err != nil {
		panic(err)
	}
	request := func(floor int, direction string) int {
		id, err := system.ExternalRequest(floor, direction)
		if err != nil {
			panic(err)
		}
		return id
	}
	fmt.Println("Selected:", request(3, "UP"))
	system.InternalRequest(0, 5)
	system.InternalRequest(0, 5)
	system.RunUntilIdle()
	fmt.Println("Selected:", request(4, "DOWN"))
	system.InternalRequest(0, 1)
	system.RunUntilIdle()
	system.SetStrategy(&RoundRobinStrategy{})
	fmt.Println("Round robin:", request(0, "UP"))
	fmt.Println("Round robin:", request(0, "UP"))
	system.RunUntilIdle()
	system.RunUntilIdle()
	if system.InternalRequest(0, 11) == nil {
		panic("invalid floor accepted")
	}
	fmt.Println("Invalid floor rejected")
	if _, err := system.ExternalRequest(0, "DOWN"); err == nil {
		panic("invalid direction accepted")
	}
	fmt.Println("Invalid direction rejected")
}
