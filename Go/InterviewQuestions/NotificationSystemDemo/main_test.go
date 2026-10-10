package main

import (
	"slices"
	"sync"
	"testing"
)

func TestNotifications(t *testing.T) {
	email := &FakeChannel{}
	prefs := map[string][]string{"u": {"EMAIL", "SMS"}, "bad": {"PUSH"}, "off": {}}
	service := NewNotificationService(map[string]NotificationChannel{"EMAIL": email, "SMS": &FakeChannel{failures: 2}, "PUSH": &FakeChannel{failures: 10}}, map[string]string{"t": "Hello {name}"}, prefs)
	defer service.Close()
	prefs["u"][0] = "PUSH"
	low := Request{"low", "u", "t", "Ada", 2}
	for _, request := range []Request{low, {"high", "u", "t", "Ada", 0}, low, {"fail", "bad", "t", "Ada", 1}, {"skip", "off", "t", "Ada", 1}} {
		if err := service.Submit(request); err != nil {
			t.Fatal(err)
		}
	}
	status := func(id, expected string) {
		t.Helper()
		actual, err := service.Status(id)
		if err != nil || actual != expected {
			t.Fatalf("status %s: %s, %v", id, actual, err)
		}
	}
	attempts := func(id, channel string, expected int) {
		t.Helper()
		actual, err := service.Attempts(id, channel)
		if err != nil || actual != expected {
			t.Fatalf("attempts: %d, %v", actual, err)
		}
	}
	status("low", "QUEUED")
	status("skip", "SKIPPED")
	for _, request := range []Request{{"low", "u", "t", "changed", 2}, {"missing", "u", "missing", "Ada", 1}, {"invalid", "u", "t", "Ada", 3}} {
		if service.Submit(request) == nil {
			t.Fatal("invalid request accepted")
		}
	}
	if _, err := service.Status("missing"); err == nil {
		t.Fatal("unknown ID accepted")
	}
	if err := service.AwaitIdle(); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(service.SentOrder(), []string{"high/EMAIL", "high/SMS", "low/EMAIL", "low/SMS"}) {
		t.Fatal("priority or FIFO ordering")
	}
	attempts("high", "SMS", 3)
	attempts("low", "EMAIL", 1)
	attempts("fail", "PUSH", 3)
	status("fail", "FAILED")
	if email.delivered[0] != "high/EMAIL:Hello Ada" {
		t.Fatal("template rendering")
	}
	var workers sync.WaitGroup
	for index := 0; index < 100; index++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if err := service.Submit(Request{"same", "u", "t", "Ada", 1}); err != nil {
				t.Error(err)
			}
		}()
	}
	workers.Wait()
	if err := service.AwaitIdle(); err != nil {
		t.Fatal(err)
	}
	attempts("same", "EMAIL", 1)
	if len(service.SentOrder()) != 6 {
		t.Fatal("duplicate delivery")
	}
	service.Close()
	if service.Submit(Request{"late", "u", "t", "Ada", 1}) == nil || service.Start() == nil {
		t.Fatal("accepted work after close")
	}
	drain := NewNotificationService(map[string]NotificationChannel{"EMAIL": email}, map[string]string{"t": "{name}"}, map[string][]string{"u": {"EMAIL"}})
	if err := drain.Submit(Request{"drain", "u", "t", "Ada", 1}); err != nil {
		t.Fatal(err)
	}
	drain.Close()
	actual, err := drain.Status("drain")
	if err != nil || actual != "SENT" {
		t.Fatal("close did not drain work")
	}
}
