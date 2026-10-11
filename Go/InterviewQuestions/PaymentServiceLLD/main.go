/*
 * INTERVIEW DESIGN NOTES - Payment Service
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Create payments for CARD, UPI, NET_BANKING and WALLET methods.
 * - Choose a registered gateway and track PROCESSING, SUCCESS, FAILED or REFUNDED.
 * - Deduplicate identical keys and reject changed payloads under the same key.
 * - Retry ambiguous gateway outcomes up to three total attempts.
 * - Support full refunds and trusted, deduplicated reconciliation webhooks.
 *
 * Non-functional requirements:
 * - Serialize state transitions within a service instance.
 * - Use integer minor units and immutable/copied payment snapshots.
 * - Retain stable gateway payment identity across retries.
 * - Keep provider integration replaceable and failure scenarios testable locally.
 *
 * Core entities and responsibilities:
 * - Request: order, amount, method and gateway identity.
 * - Payment: ID, request and status snapshot.
 * - PaymentService: owns payment records and idempotency/event indexes.
 * - Gateway: charge/refund contract; FakeGateway simulates provider outcomes.
 *
 * Key operations and flow:
 * - pay -> validate -> check key -> store PROCESSING -> retry the same gateway ID.
 * - Definitive success/decline becomes SUCCESS/FAILED; exhaustion stays PROCESSING.
 * - refund requires SUCCESS; trusted webhook reconciles a PROCESSING payment.
 *
 * Design choices and invariants:
 * - Gateway interface isolates provider behavior from orchestration.
 * - An uncertain response is not proof of failure; do not invent a new payment ID.
 * - Full refund retries reuse the ID; late events cannot revert terminal states.
 * - Webhook event identity is scoped by provider; validate payment/provider match.
 *
 * Assumptions and out of scope:
 * - One assumed currency (INR), fake providers and in-memory records only.
 * - Webhook authentication happens before this trusted method; no HTTP endpoint.
 * - No partial refunds, persistent transactions, multi-process coordination,
 * - real payment credentials, retry backoff or conflicting-final-event recovery.
 *
 * Interview discussion and checks:
 * - Test concurrent duplicate keys, payload conflicts, lost responses and exhaustion.
 * - Test decline, failed/refunded retries, stale events and snapshot independence.
 * - Discuss durable unique constraints, provider idempotency and reconciliation.
 *
 * Concurrency in this edition:
 * - Instance-level synchronization protects service/game mutations.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py Go InterviewQuestions/PaymentServiceLLD
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

// In-memory orchestration with simulated gateways, never real money.
package main

import (
	"errors"
	"fmt"
	"strings"
	"sync"
)

type Request struct {
	Order      string
	MinorUnits int64
	Method     string
	Gateway    string
}

func (r Request) validate() error {
	if strings.TrimSpace(r.Order) == "" || r.MinorUnits <= 0 || strings.TrimSpace(r.Gateway) == "" {
		return errors.New("invalid payment request")
	}
	switch r.Method {
	case "CARD", "UPI", "NET_BANKING", "WALLET":
		return nil
	default:
		return errors.New("invalid payment method")
	}
}

type Payment struct {
	ID      string
	Request Request
	Status  string
}

type Gateway interface {
	Charge(id string, request Request) string
	Refund(id string) bool
}

type FakeGateway struct {
	charged           map[string]bool
	refunded          map[string]bool
	loseFirstResponse bool
	decline           bool
	gate              sync.Mutex
}

func NewFakeGateway(loseFirstResponse, decline bool) *FakeGateway {
	return &FakeGateway{charged: map[string]bool{}, refunded: map[string]bool{}, loseFirstResponse: loseFirstResponse, decline: decline}
}

func (g *FakeGateway) Charge(id string, request Request) string {
	g.gate.Lock()
	defer g.gate.Unlock()
	if g.decline {
		return "DECLINED"
	}
	g.charged[id] = true
	if g.loseFirstResponse {
		g.loseFirstResponse = false
		return "UNKNOWN"
	}
	return "SUCCESS"
}

func (g *FakeGateway) Refund(id string) bool {
	g.gate.Lock()
	defer g.gate.Unlock()
	if !g.charged[id] {
		return false
	}
	g.refunded[id] = true
	return true
}

func (g *FakeGateway) Charges() int {
	g.gate.Lock()
	defer g.gate.Unlock()
	return len(g.charged)
}

type PaymentService struct {
	gateways map[string]Gateway
	payments map[string]Payment
	events   map[[2]string]bool
	gate     sync.Mutex
}

func NewPaymentService(gateways map[string]Gateway) *PaymentService {
	copied := map[string]Gateway{}
	for name, gateway := range gateways {
		copied[name] = gateway
	}
	return &PaymentService{gateways: copied, payments: map[string]Payment{}, events: map[[2]string]bool{}}
}

func (s *PaymentService) Pay(key string, request Request) (Payment, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	if err := request.validate(); err != nil {
		return Payment{}, err
	}
	if strings.TrimSpace(key) == "" || s.gateways[request.Gateway] == nil {
		return Payment{}, errors.New("invalid key or gateway")
	}
	if existing, ok := s.payments[key]; ok {
		if existing.Request != request {
			return Payment{}, errors.New("idempotency conflict")
		}
		return existing, nil
	}
	payment := Payment{ID: key, Request: request, Status: "PROCESSING"}
	s.payments[key] = payment
	for attempt := 0; attempt < 3; attempt++ {
		outcome := s.gateways[request.Gateway].Charge(key, request)
		if outcome != "UNKNOWN" {
			payment.Status = "FAILED"
			if outcome == "SUCCESS" {
				payment.Status = "SUCCESS"
			}
			s.payments[key] = payment
			break
		}
	}
	return payment, nil
}

func (s *PaymentService) Refund(key string) (Payment, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	payment, ok := s.payments[key]
	if !ok {
		return Payment{}, errors.New("unknown payment")
	}
	if payment.Status == "REFUNDED" {
		return payment, nil
	}
	if payment.Status != "SUCCESS" {
		return Payment{}, errors.New("only successful payments can be refunded")
	}
	if !s.gateways[payment.Request.Gateway].Refund(key) {
		return Payment{}, errors.New("refund pending; retry with the same payment ID")
	}
	payment.Status = "REFUNDED"
	s.payments[key] = payment
	return payment, nil
}

// Webhook is a trusted boundary; authenticate the provider before calling it.
func (s *PaymentService) Webhook(event, gateway, key, status string) (Payment, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	payment, ok := s.payments[key]
	if !ok {
		return Payment{}, errors.New("unknown payment")
	}
	if strings.TrimSpace(event) == "" || payment.Request.Gateway != gateway || (status != "SUCCESS" && status != "FAILED") {
		return Payment{}, errors.New("invalid webhook")
	}
	eventKey := [2]string{gateway, event}
	if s.events[eventKey] {
		return payment, nil
	}
	s.events[eventKey] = true
	if payment.Status == "PROCESSING" {
		payment.Status = status
		s.payments[key] = payment
	}
	return payment, nil
}

func main() {
	gateway := NewFakeGateway(true, false)
	service := NewPaymentService(map[string]Gateway{"stripe": gateway})
	request := Request{Order: "order-1", MinorUnits: 1699900, Method: "CARD", Gateway: "stripe"}
	payment, err := service.Pay("key-1", request)
	if err != nil {
		panic(err)
	}
	fmt.Println("Payment:", payment.Status)
	if _, err := service.Pay("key-1", request); err != nil {
		panic(err)
	}
	fmt.Println("Gateway charges:", gateway.Charges())
	payment, err = service.Refund("key-1")
	if err != nil {
		panic(err)
	}
	fmt.Println("Refund:", payment.Status)
	payment, err = service.Webhook("event-1", "stripe", "key-1", "SUCCESS")
	if err != nil {
		panic(err)
	}
	fmt.Println("Late webhook:", payment.Status)
}
