/*
 * INTERVIEW DESIGN NOTES - Food Delivery
 *
 * Implementation status: runnable, deliberately small in-memory teaching example.
 * The original classroom sketches are preserved in classroom-drafts/2026-10-11/.
 *
 * Functional requirements:
 * - Browse two fixed restaurant menus; submit a one-line cart (item and quantity).
 * - Charge an order, accept/reject it, prepare it and mark it ready for collection.
 * - Reserve one available rider, record pickup and delivery, then release the rider.
 * - Cancel before pickup; refund before changing state or releasing the rider.
 * - Record transition events for notification consumers and status tracking.
 *
 * Non-functional requirements:
 * - Reuse an order ID only for identical input; never charge it twice in this process.
 * - Use integer minor currency units; validate quantity 1..100 before charging.
 * - Serialize service operations; never assign one rider to two active deliveries.
 * - Return menu/event snapshots; retain copied order inputs and the charged amount.
 *
 * Core entities and responsibilities:
 * - Request: customer ID, address, restaurant and a single cart item/quantity.
 * - Order: immutable request/amount snapshot plus status and assigned rider.
 * - Restaurant/MenuItem: represented by fixed ID-to-price maps; no editable catalog.
 * - DeliveryPartner: ID-to-availability map; delivery state lives in Order.
 * - PaymentGateway: deterministic charge/refund fake; counters expose duplicate calls.
 * - FoodDelivery: owns order transitions, rider reservation and notification events.
 *
 * Key operations and flow:
 * - menu -> place -> ACCEPTED -> PREPARING -> READY -> assign -> PICKED_UP -> DELIVERED.
 * - place returns PLACED or PAYMENT_FAILED; repeated input returns current status.
 * - cancel(reject=true) rejects only PLACED orders; normal cancellation is pre-pickup.
 * - If no rider exists, assign returns false and the READY order can be retried.
 *
 * Design choices and invariants:
 * - Explicit transition table instead of a class for every state keeps the MVP small.
 * - One service owns coupled order/rider invariants; private helpers assume its lock.
 * - Failed refund preserves state/reservation; successful repeated cancel is a no-op.
 * - Failed charges are remembered; a new order ID is needed for a new attempt.
 * - Every committed transition appends an event; callers cannot mutate stored orders.
 *
 * Assumptions and out of scope:
 * - One currency; menu prices are minor units. No taxes, coupons or partial refunds.
 * - One item type per cart, fixed available menus and one rider keep the demo focused.
 * - Customer IDs/addresses are caller-provided; authentication/ownership is upstream.
 * - The fake gateway returns immediately without exceptions; configure it only when idle.
 * - No network payment ambiguity, durable idempotency, outbox, live GPS or real SMS.
 * - Real providers must not run under this lock: add durable intents, idempotency keys,
 *   reconciliation and an outbox before introducing remote calls or multiple processes.
 *
 * Interview discussion and checks:
 * - Demo checks duplicates/conflicts, wrong-restaurant items, invalid transitions,
 *   rider contention, refund retry, duplicate cancellation and cancellation after pickup.
 * - Discuss multiple cart lines, restaurant closure, rider rejection, payment timeouts,
 *   delivery timeouts, bounded event retention and restart-safe recovery.
 *
 * Concurrency in this edition:
 * - Mutex protects all service operations; errors report invalid input/state.
 *
 * Run / validation:
 * - From repository root: python3 scripts/run.py "Go" InterviewQuestions/Zomato_Food_Delivery
 * - Prerequisites, lifecycle and edge checks: see FOOD_DELIVERY.md.
 */

package main

import (
	"errors"
	"fmt"
	"sync"
)

type Request struct {
	Customer   string
	Restaurant string
	Address    string
	Item       string
	Quantity   int
}

type Order struct {
	request Request
	amount  int
	status  string
	partner string
}

type PaymentGateway struct {
	acceptCharge bool
	acceptRefund bool
	charges      int
	refunds      int
}

func (g *PaymentGateway) charge(amount int) bool {
	g.charges++
	return g.acceptCharge
}

func (g *PaymentGateway) refund(amount int) bool {
	g.refunds++
	return g.acceptRefund
}

type FoodDelivery struct {
	mu       sync.Mutex
	gateway  *PaymentGateway
	menus    map[string]map[string]int
	orders   map[string]*Order
	partners map[string]bool
	audit    []string
}

func NewFoodDelivery(gateway *PaymentGateway) *FoodDelivery {
	return &FoodDelivery{
		gateway:  gateway,
		menus:    map[string]map[string]int{"r1": {"pizza": 12000}, "r2": {"rice": 8000}},
		orders:   make(map[string]*Order),
		partners: map[string]bool{"rider1": true},
	}
}

func (s *FoodDelivery) Menu(restaurant string) (map[string]int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	items, ok := s.menus[restaurant]
	if !ok {
		return nil, errors.New("unknown restaurant")
	}
	result := make(map[string]int)
	for item, price := range items {
		result[item] = price
	}
	return result, nil
}

func (s *FoodDelivery) Place(id string, request Request) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if order, ok := s.orders[id]; ok {
		if order.request != request {
			return "", errors.New("conflicting order ID")
		}
		return order.status, nil
	}
	if id == "" || request.Customer == "" || request.Address == "" {
		return "", errors.New("required identity/address")
	}
	price, ok := s.menus[request.Restaurant][request.Item]
	if !ok || request.Quantity < 1 || request.Quantity > 100 {
		return "", errors.New("invalid cart")
	}
	amount := price * request.Quantity
	status := "PAYMENT_FAILED"
	if s.gateway.charge(amount) {
		status = "PLACED"
	}
	s.orders[id] = &Order{request: request, amount: amount, status: status}
	s.audit = append(s.audit, id+":"+status)
	return status, nil
}

// Caller holds mu. No pointer to an internal Order is exposed to callers.
func (s *FoodDelivery) change(id string, order *Order, status string) {
	order.status = status
	s.audit = append(s.audit, id+":"+status)
}

func (s *FoodDelivery) Advance(id string, target string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	order, ok := s.orders[id]
	if !ok {
		return errors.New("unknown order")
	}
	next := map[string]string{"PLACED": "ACCEPTED", "ACCEPTED": "PREPARING", "PREPARING": "READY", "ASSIGNED": "PICKED_UP", "PICKED_UP": "DELIVERED"}
	expected, ok := next[order.status]
	if !ok || expected != target {
		return errors.New("invalid transition")
	}
	s.change(id, order, target)
	if target == "DELIVERED" {
		s.partners[order.partner] = true
	}
	return nil
}

func (s *FoodDelivery) Assign(id string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	order, ok := s.orders[id]
	if !ok || order.status != "READY" {
		return false, errors.New("order not ready")
	}
	for partner, available := range s.partners {
		if available {
			s.partners[partner] = false
			order.partner = partner
			s.change(id, order, "ASSIGNED")
			return true, nil
		}
	}
	return false, nil
}

func (s *FoodDelivery) Cancel(id string, reject bool) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	order, ok := s.orders[id]
	if !ok {
		return false, errors.New("unknown order")
	}
	target := "CANCELLED"
	if reject {
		target = "REJECTED"
	}
	if order.status == target {
		return true, nil
	}
	allowed := order.status == "PLACED" || (!reject && (order.status == "ACCEPTED" || order.status == "PREPARING" || order.status == "READY" || order.status == "ASSIGNED"))
	if !allowed {
		return false, errors.New("cannot cancel")
	}
	if !s.gateway.refund(order.amount) {
		return false, nil
	}
	if order.partner != "" {
		s.partners[order.partner] = true
	}
	s.change(id, order, target)
	return true, nil
}

func (s *FoodDelivery) Status(id string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	order, ok := s.orders[id]
	if !ok {
		return "", errors.New("unknown order")
	}
	return order.status, nil
}

func (s *FoodDelivery) Events() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.audit...)
}

func check(condition bool) {
	if !condition {
		panic("contract failed")
	}
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func value[T any](result T, err error) T {
	must(err)
	return result
}

func main() {
	gateway := &PaymentGateway{acceptCharge: true, acceptRefund: true}
	service := NewFoodDelivery(gateway)
	request := Request{"alice", "r1", "Home", "pizza", 2}
	check(value(service.Menu("r1"))["pizza"] == 12000)
	check(value(service.Place("o1", request)) == "PLACED")
	check(value(service.Place("o1", request)) == "PLACED" && gateway.charges == 1)
	_, err := service.Place("o1", Request{"bob", "r1", "Home", "pizza", 2})
	check(err != nil)
	_, err = service.Place("bad", Request{"alice", "r1", "Home", "rice", 1})
	check(err != nil)
	check(service.Advance("o1", "DELIVERED") != nil)
	for _, id := range []string{"o1", "o2"} {
		value(service.Place(id, request))
		for _, state := range []string{"ACCEPTED", "PREPARING", "READY"} {
			must(service.Advance(id, state))
		}
	}
	check(value(service.Assign("o1")))
	check(!value(service.Assign("o2")))
	gateway.acceptRefund = false
	check(!value(service.Cancel("o1", false)) && value(service.Status("o1")) == "ASSIGNED")
	gateway.acceptRefund = true
	check(value(service.Cancel("o1", false)))
	refunds := gateway.refunds
	check(value(service.Cancel("o1", false)) && gateway.refunds == refunds)
	check(value(service.Assign("o2")))
	must(service.Advance("o2", "PICKED_UP"))
	_, err = service.Cancel("o2", false)
	check(err != nil)
	must(service.Advance("o2", "DELIVERED"))
	check(service.Advance("o2", "PREPARING") != nil)
	value(service.Place("o3", request))
	check(value(service.Cancel("o3", true)))
	gateway.acceptCharge = false
	check(value(service.Place("o4", request)) == "PAYMENT_FAILED")
	charges := gateway.charges
	check(value(service.Place("o4", request)) == "PAYMENT_FAILED" && gateway.charges == charges)
	_, err = service.Place("bad", Request{"alice", "r1", "Home", "pizza", 0})
	check(err != nil)
	_, err = service.Status("missing")
	check(err != nil)
	events := service.Events()
	events[0] = "changed"
	check(service.Events()[0] != "changed")
	fmt.Println("Delivered: " + value(service.Status("o2")))
	fmt.Println("Cancelled: " + value(service.Status("o1")))
	fmt.Println("Rejected: " + value(service.Status("o3")))
	fmt.Println("Failed payment: " + value(service.Status("o4")))
	fmt.Println("Edge cases: passed")
}
