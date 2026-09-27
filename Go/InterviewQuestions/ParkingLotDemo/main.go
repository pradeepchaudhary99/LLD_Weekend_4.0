package main

import (
	"fmt"
	"strings"
	"sync"
)

type Vehicle struct {
	Plate string
	Kind  string
}

type Slot struct {
	Level        int
	ID           int
	Kind         string
	ExitDistance int
	occupied     bool
}

type Ticket struct {
	ID        int
	Vehicle   Vehicle
	Slot      *Slot
	EnteredAt int64
}

type SlotAssignmentStrategy interface {
	Select([]*Slot, Vehicle) *Slot
}

type FirstFit struct{}

func (FirstFit) Select(slots []*Slot, vehicle Vehicle) *Slot {
	for _, slot := range slots {
		if !slot.occupied && slot.Kind == vehicle.Kind {
			return slot
		}
	}
	return nil
}

type NearestExit struct{}

func (NearestExit) Select(slots []*Slot, vehicle Vehicle) *Slot {
	var selected *Slot
	for _, slot := range slots {
		if !slot.occupied && slot.Kind == vehicle.Kind && (selected == nil || slot.ExitDistance < selected.ExitDistance) {
			selected = slot
		}
	}
	return selected
}

type FeesCalculationStrategy interface {
	Fee(int64) (int64, error)
}

type HourlyFee struct{}

func (HourlyFee) Fee(minutes int64) (int64, error) {
	if minutes < 0 {
		return 0, fmt.Errorf("Exit precedes entry")
	}
	hours := minutes / 60
	if minutes%60 != 0 {
		hours++
	}
	if hours == 0 {
		hours = 1
	}
	// minutes is nonnegative int64, so rounded hours * 50 remains in int64 range.
	return hours * 50, nil
}

type Payment func(int, int64) bool

type ParkingLotManager struct {
	slots    []*Slot
	fees     FeesCalculationStrategy
	strategy SlotAssignmentStrategy
	tickets  map[int]Ticket
	plates   map[string]bool
	nextID   int
	mutex    sync.Mutex
}

func NewLot(slots []*Slot, fees FeesCalculationStrategy) *ParkingLotManager {
	return &ParkingLotManager{slots: slots, fees: fees, strategy: FirstFit{}, tickets: make(map[int]Ticket), plates: make(map[string]bool), nextID: 1}
}

func (lot *ParkingLotManager) SetStrategy(strategy SlotAssignmentStrategy) {
	lot.mutex.Lock()
	defer lot.mutex.Unlock()
	lot.strategy = strategy
}

func (lot *ParkingLotManager) Park(vehicle Vehicle, minute int64) (Ticket, error) {
	lot.mutex.Lock()
	defer lot.mutex.Unlock()
	if strings.TrimSpace(vehicle.Plate) == "" || (vehicle.Kind != "BIKE" && vehicle.Kind != "CAR" && vehicle.Kind != "TRUCK") {
		return Ticket{}, fmt.Errorf("Invalid vehicle")
	}
	if minute < 0 {
		return Ticket{}, fmt.Errorf("Invalid entry time")
	}
	if lot.plates[vehicle.Plate] {
		return Ticket{}, fmt.Errorf("Vehicle already parked")
	}
	slot := lot.strategy.Select(lot.slots, vehicle)
	if slot == nil {
		return Ticket{}, fmt.Errorf("No compatible slot")
	}
	ticket := Ticket{lot.nextID, vehicle, slot, minute}
	lot.nextID++
	slot.occupied = true
	lot.plates[vehicle.Plate] = true
	lot.tickets[ticket.ID] = ticket
	return ticket, nil
}

func (lot *ParkingLotManager) Exit(ticketID int, minute int64, payment Payment) (int64, error) {
	lot.mutex.Lock()
	defer lot.mutex.Unlock()
	ticket, found := lot.tickets[ticketID]
	if !found {
		return 0, fmt.Errorf("Unknown or closed ticket")
	}
	if minute < ticket.EnteredAt {
		return 0, fmt.Errorf("Exit precedes entry")
	}
	amount, err := lot.fees.Fee(minute - ticket.EnteredAt)
	if err != nil {
		return 0, err
	}
	if !payment(ticketID, amount) {
		return 0, fmt.Errorf("Payment failed; vehicle remains parked")
	}
	ticket.Slot.occupied = false
	delete(lot.plates, ticket.Vehicle.Plate)
	delete(lot.tickets, ticketID)
	return amount, nil
}

type EntryGate struct {
	ID      int
	Manager *ParkingLotManager
}

func (gate EntryGate) Enter(vehicle Vehicle, minute int64) (Ticket, error) {
	return gate.Manager.Park(vehicle, minute)
}

type ExitGate struct {
	ID      int
	Manager *ParkingLotManager
}

func (gate ExitGate) Leave(ticket int, minute int64, payment Payment) (int64, error) {
	return gate.Manager.Exit(ticket, minute, payment)
}

func rejected(err error) {
	if err == nil {
		panic("expected rejection")
	}
	fmt.Println(err)
}

func main() {
	lot := NewLot([]*Slot{{Level: 0, ID: 1, Kind: "CAR", ExitDistance: 8}, {Level: 0, ID: 2, Kind: "BIKE", ExitDistance: 1}, {Level: 1, ID: 3, Kind: "TRUCK", ExitDistance: 2}, {Level: 1, ID: 4, Kind: "CAR", ExitDistance: 3}}, HourlyFee{})
	entryA := EntryGate{1, lot}
	entryB := EntryGate{2, lot}
	exit := ExitGate{1, lot}
	accept := func(int, int64) bool {
		return true
	}
	fail := func(int, int64) bool {
		return false
	}
	first, err := entryA.Enter(Vehicle{"CAR-1", "CAR"}, 0)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Ticket %d: %d/%d\n", first.ID, first.Slot.Level, first.Slot.ID)
	lot.SetStrategy(NearestExit{})
	second, err := entryB.Enter(Vehicle{"CAR-2", "CAR"}, 0)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Ticket %d: %d/%d\n", second.ID, second.Slot.Level, second.Slot.ID)
	_, err = entryB.Enter(Vehicle{"CAR-1", "CAR"}, 0)
	rejected(err)
	_, err = entryA.Enter(Vehicle{"CAR-3", "CAR"}, 0)
	rejected(err)
	_, err = exit.Leave(first.ID, -1, accept)
	rejected(err)
	_, err = exit.Leave(first.ID, 61, fail)
	rejected(err)
	_, err = entryA.Enter(Vehicle{"CAR-3", "CAR"}, 61)
	rejected(err)
	amount, err := exit.Leave(first.ID, 61, accept)
	if err != nil {
		panic(err)
	}
	fmt.Println("Paid:", amount)
	_, err = exit.Leave(first.ID, 61, accept)
	rejected(err)
	reused, err := entryA.Enter(Vehicle{"CAR-3", "CAR"}, 62)
	if err != nil {
		panic(err)
	}
	fmt.Printf("Reused: %d/%d\n", reused.Slot.Level, reused.Slot.ID)
	bike, err := entryA.Enter(Vehicle{"BIKE-1", "BIKE"}, 0)
	if err != nil {
		panic(err)
	}
	fmt.Println("Bike slot:", bike.Slot.ID)
	truck, err := entryB.Enter(Vehicle{"TRUCK-1", "TRUCK"}, 0)
	if err != nil {
		panic(err)
	}
	fmt.Println("Truck slot:", truck.Slot.ID)
}
