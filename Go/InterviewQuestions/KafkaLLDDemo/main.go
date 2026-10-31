/*
Kafka LLD — a small in-memory partitioned log, not an Apache Kafka client.
Functional requirements: create topics, publish records, join/leave groups,
assign partitions, poll batches, and explicitly commit processed offsets.
Non-functional requirements: atomic appends/offset updates, per-partition order,
independent group progress, and rejection of stale commits after rebalancing.
Core entities: Message(key/value/offset), Topic(partition logs), Group(members/
committed offsets/generation), Batch(detached records and ownership metadata),
Broker(registry and coordination). Producers call publish; consumers poll/process/
commit outside the broker. Broker methods cover the original draft's helpers.
Flow: key -> partition -> append offset; member -> assigned partitions -> read
from committed next-offset -> process all records -> commit whole batch.
Nonempty ASCII keys use rolling hash (hash*31+character) modulo partition count;
empty keys round-robin. This is a teaching hash, not Kafka's wire-compatible hash.
Groups are scoped by (topic, group ID). Sorted member IDs own partition i via
members[i % memberCount]. Each join/leave increments the generation; progress
survives an empty group. Duplicate joins and unknown leaves are rejected.
Poll does not advance offsets. Commit requires current generation, ownership,
and matching starting offset; next offset is start + batch size. No rewind.
Concurrency: a coarse broker lock protects all operations in threaded versions;
processing is outside that lock. Rebalance fences commits, not external effects.
Repeated polls before commit replay records. Process before commit; failures can
retry. Side effects need idempotency: this is at-least-once within a live process,
not exactly-once processing. Serialize processing for each member/partition.
Scope: trusted consumers commit unchanged broker-issued batches only. Batch
metadata is not an authorization token. No durability, replication, retention,
network, heartbeat-driven failure detection, transactions or background threads.
Topic partitions are fixed (1..100); poll limit (1..1000) is per partition.
Edge cases: bad topic/key/count, duplicate members, no members, surplus members,
independent groups, retry without commit, stale generation, duplicate commits,
and concurrent publishes. Memory is unbounded and process restart loses all data.
Run from repository root: python3 scripts/run.py "Go" InterviewQuestions/KafkaLLDDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

package main

import (
	"errors"
	"fmt"
	"reflect"
	"sort"
	"sync"
)

type Message struct {
	Key, Value string
	Offset     int
}

type Batch struct {
	Topic, Group, Member         string
	Generation, Partition, Start int
	Messages                     []Message
}

type Topic struct {
	Partitions [][]Message
	Next       int
}

type Group struct {
	Members    []string
	Offsets    []int
	Generation int
}

type GroupKey struct {
	Topic, Group string
}

type Broker struct {
	mu     sync.Mutex
	topics map[string]*Topic
	groups map[GroupKey]*Group
}

func NewBroker() *Broker {
	return &Broker{topics: make(map[string]*Topic), groups: make(map[GroupKey]*Group)}
}

func (b *Broker) CreateTopic(name string, count int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if name == "" || b.topics[name] != nil || count <= 0 || count > 100 {
		return errors.New("invalid topic")
	}
	b.topics[name] = &Topic{Partitions: make([][]Message, count)}
	return nil
}

func (b *Broker) Publish(name, key, value string) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	topic := b.topics[name]
	if topic == nil {
		return 0, errors.New("unknown topic")
	}
	partition := 0
	if key == "" {
		partition = topic.Next
		topic.Next = (topic.Next + 1) % len(topic.Partitions)
	} else {
		for _, character := range key {
			if character > 127 {
				return 0, errors.New("ASCII keys only")
			}
			partition = (partition*31 + int(character)) % len(topic.Partitions)
		}
	}
	log := topic.Partitions[partition]
	topic.Partitions[partition] = append(log, Message{key, value, len(log)})
	return partition, nil
}

func (b *Broker) Join(name, id, member string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	topic := b.topics[name]
	if topic == nil || id == "" || member == "" {
		return errors.New("invalid join")
	}
	key := GroupKey{name, id}
	group := b.groups[key]
	if group == nil {
		group = &Group{Offsets: make([]int, len(topic.Partitions))}
		b.groups[key] = group
	}
	for _, existing := range group.Members {
		if existing == member {
			return errors.New("duplicate member")
		}
	}
	group.Members = append(group.Members, member)
	sort.Strings(group.Members)
	group.Generation++
	return nil
}

func (b *Broker) Leave(name, id, member string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	group := b.groups[GroupKey{name, id}]
	if group == nil {
		return errors.New("unknown group")
	}
	for index, existing := range group.Members {
		if existing == member {
			group.Members = append(group.Members[:index], group.Members[index+1:]...)
			group.Generation++
			return nil
		}
	}
	return errors.New("unknown member")
}

func (b *Broker) Poll(name, id, member string, limit int) ([]Batch, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	topic := b.topics[name]
	group := b.groups[GroupKey{name, id}]
	if topic == nil || group == nil || limit <= 0 || limit > 1000 {
		return nil, errors.New("invalid poll")
	}
	found := false
	for _, existing := range group.Members {
		if existing == member {
			found = true
		}
	}
	if !found {
		return nil, errors.New("unknown member")
	}
	batches := []Batch{}
	for partition, log := range topic.Partitions {
		if group.Members[partition%len(group.Members)] != member {
			continue
		}
		start := group.Offsets[partition]
		end := start + limit
		if end > len(log) {
			end = len(log)
		}
		if end > start {
			messages := append([]Message{}, log[start:end]...)
			batches = append(batches, Batch{name, id, member, group.Generation, partition, start, messages})
		}
	}
	return batches, nil
}

func (b *Broker) Commit(batch Batch) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	group := b.groups[GroupKey{batch.Topic, batch.Group}]
	if group == nil || group.Generation != batch.Generation || len(group.Members) == 0 || batch.Partition < 0 || batch.Partition >= len(group.Offsets) {
		return false
	}
	if group.Members[batch.Partition%len(group.Members)] != batch.Member || group.Offsets[batch.Partition] != batch.Start || len(batch.Messages) == 0 {
		return false
	}
	next := batch.Start + len(batch.Messages)
	if next > len(b.topics[batch.Topic].Partitions[batch.Partition]) {
		return false
	}
	group.Offsets[batch.Partition] = next
	return true
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func check(ok bool) {
	if !ok {
		panic("broker contract failed")
	}
}

func main() {
	broker := NewBroker()
	must(broker.CreateTopic("orders", 2))
	partition, err := broker.Publish("orders", "a", "one")
	must(err)
	check(partition == 1)
	_, err = broker.Publish("orders", "a", "two")
	must(err)
	_, err = broker.Publish("orders", "", "other")
	must(err)
	must(broker.Join("orders", "g", "c1"))
	initial, err := broker.Poll("orders", "g", "c1", 10)
	must(err)
	check(len(initial) == 2 && initial[1].Messages[1].Offset == 1)
	replay, err := broker.Poll("orders", "g", "c1", 10)
	must(err)
	check(reflect.DeepEqual(initial, replay))
	must(broker.Join("orders", "g", "c2"))
	check(!broker.Commit(initial[0]))
	left, err := broker.Poll("orders", "g", "c1", 10)
	must(err)
	right, err := broker.Poll("orders", "g", "c2", 10)
	must(err)
	check(len(left) == 1 && len(right) == 1)
	check(broker.Commit(left[0]) && broker.Commit(right[0]))
	check(!broker.Commit(right[0]))
	empty, err := broker.Poll("orders", "g", "c2", 10)
	must(err)
	check(len(empty) == 0)
	must(broker.Join("orders", "independent", "reader"))
	independent, err := broker.Poll("orders", "independent", "reader", 10)
	must(err)
	check(len(independent) == 2)
	must(broker.Leave("orders", "g", "c2"))
	fmt.Println("Key ordering: 0,1\nUncommitted replay: true\nStale generation rejected: true\nPartition owners: c1,c2\nIndependent group replay: true")
}
