package main

import (
	"errors"
	"fmt"
	"slices"
	"sort"
	"strings"
	"sync"
)

type Request struct {
	ID       string
	User     string
	Template string
	Name     string
	Priority int
}

type NotificationChannel interface {
	Send(id, message string) bool
}

type FakeChannel struct {
	failures  int
	delivered []string
	gate      sync.Mutex
}

func (c *FakeChannel) Send(id, message string) bool {
	c.gate.Lock()
	defer c.gate.Unlock()
	if c.failures > 0 {
		c.failures--
		return false
	}
	c.delivered = append(c.delivered, id+":"+message)
	return true
}

type Delivery struct {
	request  Request
	channel  string
	message  string
	sequence int64
	status   string
	attempts int
}

type NotificationService struct {
	channels    map[string]NotificationChannel
	templates   map[string]string
	preferences map[string][]string
	requests    map[string]Request
	deliveries  map[string][]*Delivery
	order       []string
	queue       []*Delivery
	sequence    int64
	active      int
	started     bool
	closed      bool
	gate        sync.Mutex
	changed     *sync.Cond
	done        chan struct{}
}

func NewNotificationService(channels map[string]NotificationChannel, templates map[string]string, preferences map[string][]string) *NotificationService {
	service := &NotificationService{channels: map[string]NotificationChannel{}, templates: map[string]string{}, preferences: map[string][]string{}, requests: map[string]Request{}, deliveries: map[string][]*Delivery{}, done: make(chan struct{})}
	for name, channel := range channels {
		service.channels[name] = channel
	}
	for name, template := range templates {
		service.templates[name] = template
	}
	for user, values := range preferences {
		service.preferences[user] = slices.Clone(values)
	}
	service.changed = sync.NewCond(&service.gate)
	return service
}

func (s *NotificationService) Submit(request Request) error {
	s.gate.Lock()
	defer s.gate.Unlock()
	template, templateExists := s.templates[request.Template]
	selected, userExists := s.preferences[request.User]
	if s.closed || strings.TrimSpace(request.ID) == "" || request.Priority < 0 || request.Priority > 2 || !templateExists || !userExists {
		return errors.New("invalid or closed request")
	}
	if existing, exists := s.requests[request.ID]; exists {
		if existing != request {
			return errors.New("idempotency conflict")
		}
		return nil
	}
	seen := map[string]bool{}
	for _, channel := range selected {
		if seen[channel] || s.channels[channel] == nil {
			return errors.New("invalid channel preference")
		}
		seen[channel] = true
	}
	message := strings.ReplaceAll(template, "{name}", request.Name)
	fanout := []*Delivery{}
	for _, channel := range selected {
		fanout = append(fanout, &Delivery{request: request, channel: channel, message: message, sequence: s.sequence, status: "QUEUED"})
		s.sequence++
	}
	s.requests[request.ID] = request
	s.deliveries[request.ID] = fanout
	s.queue = append(s.queue, fanout...)
	s.changed.Broadcast()
	return nil
}

func (s *NotificationService) Status(id string) (string, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	fanout, exists := s.deliveries[id]
	if !exists {
		return "", errors.New("unknown notification")
	}
	if len(fanout) == 0 {
		return "SKIPPED", nil
	}
	failed := false
	for _, delivery := range fanout {
		if delivery.status == "QUEUED" || delivery.status == "PROCESSING" {
			return "QUEUED", nil
		}
		failed = failed || delivery.status == "FAILED"
	}
	if failed {
		return "FAILED", nil
	}
	return "SENT", nil
}

func (s *NotificationService) Attempts(id, channel string) (int, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	fanout, exists := s.deliveries[id]
	if !exists {
		return 0, errors.New("unknown notification")
	}
	total := 0
	for _, delivery := range fanout {
		if delivery.channel == channel {
			total += delivery.attempts
		}
	}
	return total, nil
}

func (s *NotificationService) SentOrder() []string {
	s.gate.Lock()
	defer s.gate.Unlock()
	return slices.Clone(s.order)
}

func (s *NotificationService) startWorker() {
	if !s.started {
		s.started = true
		go s.run()
	}
}

func (s *NotificationService) Start() error {
	s.gate.Lock()
	defer s.gate.Unlock()
	if s.closed {
		return errors.New("service closed")
	}
	s.startWorker()
	return nil
}

func (s *NotificationService) run() {
	defer close(s.done)
	for {
		s.gate.Lock()
		for len(s.queue) == 0 && !s.closed {
			s.changed.Wait()
		}
		if len(s.queue) == 0 {
			s.gate.Unlock()
			return
		}
		sort.Slice(s.queue, func(left, right int) bool {
			a, b := s.queue[left], s.queue[right]
			if a.request.Priority != b.request.Priority {
				return a.request.Priority < b.request.Priority
			}
			return a.sequence < b.sequence
		})
		delivery := s.queue[0]
		s.queue = s.queue[1:]
		delivery.status = "PROCESSING"
		s.active++
		s.gate.Unlock()
		sent, attempts := false, 0
		for !sent && attempts < 3 {
			attempts++
			// Providers report delivery failures as false, not a panic.
			sent = s.channels[delivery.channel].Send(delivery.request.ID+"/"+delivery.channel, delivery.message)
		}
		s.gate.Lock()
		delivery.attempts = attempts
		delivery.status = "FAILED"
		if sent {
			delivery.status = "SENT"
			s.order = append(s.order, delivery.request.ID+"/"+delivery.channel)
		}
		s.active--
		s.changed.Broadcast()
		s.gate.Unlock()
	}
}

func (s *NotificationService) AwaitIdle() error {
	s.gate.Lock()
	defer s.gate.Unlock()
	if s.closed {
		return errors.New("service closed")
	}
	s.startWorker()
	for len(s.queue) != 0 || s.active != 0 {
		s.changed.Wait()
	}
	return nil
}

func (s *NotificationService) Close() {
	// Lifecycle operations belong to the owner, never to a provider callback.
	s.gate.Lock()
	s.closed = true
	s.startWorker()
	s.changed.Broadcast()
	s.gate.Unlock()
	<-s.done
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	service := NewNotificationService(map[string]NotificationChannel{"EMAIL": &FakeChannel{}, "SMS": &FakeChannel{failures: 1}, "PUSH": &FakeChannel{failures: 9}}, map[string]string{"welcome": "Hello {name}"}, map[string][]string{"alice": {"EMAIL", "SMS"}, "bob": {"PUSH"}, "quiet": {}})
	defer service.Close()
	low := Request{"low", "alice", "welcome", "Alice", 2}
	must(service.Submit(low))
	must(service.Submit(Request{"high", "alice", "welcome", "Alice", 0}))
	must(service.Submit(low))
	must(service.Submit(Request{"fail", "bob", "welcome", "Bob", 1}))
	must(service.Submit(Request{"off", "quiet", "welcome", "Quiet", 1}))
	must(service.AwaitIdle())
	fmt.Println("Sent:", strings.Join(service.SentOrder(), ","))
	status, err := service.Status("high")
	must(err)
	fmt.Println("High:", status)
	attempts, err := service.Attempts("high", "SMS")
	must(err)
	fmt.Println("SMS attempts:", attempts)
	status, err = service.Status("fail")
	must(err)
	fmt.Println("Push:", status)
	status, err = service.Status("off")
	must(err)
	fmt.Println("Opt-out:", status)
}
