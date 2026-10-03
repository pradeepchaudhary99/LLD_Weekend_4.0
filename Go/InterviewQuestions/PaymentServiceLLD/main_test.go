package main

import (
	"sync"
	"testing"
)

type unknownGateway struct {
	attempts    int
	allowRefund bool
}

func (g *unknownGateway) Charge(id string, request Request) string {
	g.attempts++
	return "UNKNOWN"
}

func (g *unknownGateway) Refund(id string) bool {
	return g.allowRefund
}

func expectStatus(t *testing.T, expected string, payment Payment, err error) {
	t.Helper()
	if err != nil || payment.Status != expected {
		t.Fatalf("expected %s; got %s, %v", expected, payment.Status, err)
	}
}

func TestConcurrentIdempotency(t *testing.T) {
	request := Request{Order: "order", MinorUnits: 100, Method: "CARD", Gateway: "stripe"}
	gateway := NewFakeGateway(true, false)
	service := NewPaymentService(map[string]Gateway{"stripe": gateway})
	var workers sync.WaitGroup
	for index := 0; index < 100; index++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			payment, err := service.Pay("same-key", request)
			if err != nil || payment.Status != "SUCCESS" {
				t.Error("concurrent payment failed", err)
			}
		}()
	}
	workers.Wait()
	if gateway.Charges() != 1 {
		t.Fatal("duplicate charge")
	}
	different := request
	different.MinorUnits++
	if _, err := service.Pay("same-key", different); err == nil {
		t.Fatal("idempotency conflict accepted")
	}
	if _, err := service.Pay(" ", request); err == nil {
		t.Fatal("blank key accepted")
	}
	invalid := request
	invalid.MinorUnits = 0
	if _, err := service.Pay("invalid", invalid); err == nil {
		t.Fatal("zero amount accepted")
	}
	invalid = request
	invalid.Gateway = "missing"
	if _, err := service.Pay("unknown", invalid); err == nil {
		t.Fatal("unknown gateway accepted")
	}
	snapshot, _ := service.Pay("same-key", request)
	payment, err := service.Refund("same-key")
	expectStatus(t, "REFUNDED", payment, err)
	payment, err = service.Refund("same-key")
	expectStatus(t, "REFUNDED", payment, err)
	if snapshot.Status != "SUCCESS" {
		t.Fatal("snapshot mutated")
	}
	payment, err = service.Webhook("late", "stripe", "same-key", "FAILED")
	expectStatus(t, "REFUNDED", payment, err)
	if _, err := service.Webhook("wrong", "paypal", "same-key", "SUCCESS"); err == nil {
		t.Fatal("wrong provider accepted")
	}
	if _, err := service.Refund("missing"); err == nil {
		t.Fatal("unknown payment accepted")
	}
	for _, method := range []string{"CARD", "UPI", "NET_BANKING", "WALLET"} {
		request.Method = method
		payment, err = service.Pay(method, request)
		expectStatus(t, "SUCCESS", payment, err)
	}
}

func TestUnknownDeclinedAndRefundRetry(t *testing.T) {
	request := Request{Order: "order", MinorUnits: 100, Method: "CARD", Gateway: "stripe"}
	unknown := &unknownGateway{}
	pending := NewPaymentService(map[string]Gateway{"stripe": unknown})
	payment, err := pending.Pay("pending", request)
	expectStatus(t, "PROCESSING", payment, err)
	if unknown.attempts != 3 {
		t.Fatal("retry bound")
	}
	payment, err = pending.Pay("pending", request)
	expectStatus(t, "PROCESSING", payment, err)
	if unknown.attempts != 3 {
		t.Fatal("duplicate request retried")
	}
	if _, err := pending.Refund("pending"); err == nil {
		t.Fatal("refunded unresolved payment")
	}
	payment, err = pending.Webhook("event", "stripe", "pending", "SUCCESS")
	expectStatus(t, "SUCCESS", payment, err)
	payment, err = pending.Webhook("event", "stripe", "pending", "FAILED")
	expectStatus(t, "SUCCESS", payment, err)
	if _, err := pending.Refund("pending"); err == nil {
		t.Fatal("failed refund accepted")
	}
	payment, err = pending.Pay("pending", request)
	expectStatus(t, "SUCCESS", payment, err)
	unknown.allowRefund = true
	payment, err = pending.Refund("pending")
	expectStatus(t, "REFUNDED", payment, err)
	declined := NewPaymentService(map[string]Gateway{"stripe": NewFakeGateway(false, true)})
	payment, err = declined.Pay("declined", request)
	expectStatus(t, "FAILED", payment, err)
	if _, err := declined.Refund("declined"); err == nil {
		t.Fatal("refunded declined payment")
	}
}
