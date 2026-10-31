"use strict";
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
JavaScript/TypeScript operations are synchronous on one event loop (no await);
this is atomic per process, not synchronization across workers or servers.
Run from repository root: python3 scripts/run.py "TypeScript" InterviewQuestions/KafkaLLDDemo
Generated JS: python3 scripts/run.py JavaScript InterviewQuestions/KafkaLLDDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/
Object.defineProperty(exports, "__esModule", { value: true });

class Broker {
    topics = new Map();
    groups = new Map();

    createTopic(name, count) {
        requireCondition(
            name.trim().length > 0 &&
                !this.topics.has(name) &&
                Number.isInteger(count) &&
                count > 0 &&
                count <= 100,
        );
        this.topics.set(name, { partitions: Array.from({ length: count }, () => []), next: 0 });
    }

    topic(name) {
        const topic = this.topics.get(name);
        requireCondition(topic);
        return topic;
    }

    group(name, id) {
        const group = this.groups.get(JSON.stringify([name, id]));
        requireCondition(group);
        return group;
    }

    publish(name, key, value) {
        const topic = this.topic(name);
        requireCondition(typeof value === "string" && /^[\x00-\x7F]*$/.test(key));
        let partition = 0;
        if (!key) {
            partition = topic.next;
            topic.next = (topic.next + 1) % topic.partitions.length;
        } else {
            for (const character of key) {
                partition = (partition * 31 + character.charCodeAt(0)) % topic.partitions.length;
            }
        }
        const log = topic.partitions[partition];
        log.push(Object.freeze({ key, value, offset: log.length }));
        return partition;
    }

    join(name, id, member) {
        const topic = this.topic(name);
        requireCondition(id.trim().length > 0 && member.trim().length > 0);
        const key = JSON.stringify([name, id]);
        let group = this.groups.get(key);
        if (!group) {
            group = { members: [], offsets: topic.partitions.map(() => 0), generation: 0 };
            this.groups.set(key, group);
        }
        requireCondition(!group.members.includes(member));
        group.members.push(member);
        group.members.sort();
        group.generation++;
    }

    leave(name, id, member) {
        const group = this.group(name, id);
        const index = group.members.indexOf(member);
        requireCondition(index >= 0);
        group.members.splice(index, 1);
        group.generation++;
    }

    poll(name, id, member, limit) {
        const topic = this.topic(name);
        const group = this.group(name, id);
        requireCondition(
            group.members.includes(member) && Number.isInteger(limit) && limit > 0 && limit <= 1000,
        );
        const batches = [];
        for (let partition = 0; partition < topic.partitions.length; partition++) {
            if (group.members[partition % group.members.length] !== member) continue;
            const start = group.offsets[partition];
            const messages = Object.freeze(topic.partitions[partition].slice(start, start + limit));
            if (messages.length)
                batches.push(
                    Object.freeze({
                        topic: name,
                        group: id,
                        member,
                        generation: group.generation,
                        partition,
                        start,
                        messages,
                    }),
                );
        }
        return Object.freeze(batches);
    }

    commit(batch) {
        const group = this.group(batch.topic, batch.group);
        if (
            group.generation !== batch.generation ||
            !group.members.length ||
            !Number.isInteger(batch.partition) ||
            batch.partition < 0 ||
            batch.partition >= group.offsets.length
        )
            return false;
        if (
            group.members[batch.partition % group.members.length] !== batch.member ||
            group.offsets[batch.partition] !== batch.start ||
            !batch.messages.length
        )
            return false;
        const next = batch.start + batch.messages.length;
        if (next > this.topic(batch.topic).partitions[batch.partition].length) return false;
        group.offsets[batch.partition] = next;
        return true;
    }
}

function requireCondition(condition) {
    if (!condition) throw new Error("Invalid broker request");
}

function check(condition) {
    if (!condition) throw new Error("Broker contract failed");
}

function main() {
    const broker = new Broker();
    broker.createTopic("orders", 2);
    check(broker.publish("orders", "a", "one") === 1);
    broker.publish("orders", "a", "two");
    broker.publish("orders", "", "other");
    broker.join("orders", "g", "c1");
    const initial = broker.poll("orders", "g", "c1", 10);
    check(initial.length === 2 && initial[1].messages[1].offset === 1);
    check(JSON.stringify(broker.poll("orders", "g", "c1", 10)) === JSON.stringify(initial));
    broker.join("orders", "g", "c2");
    check(!broker.commit(initial[0]));
    const left = broker.poll("orders", "g", "c1", 10),
        right = broker.poll("orders", "g", "c2", 10);
    check(left.length === 1 && right.length === 1);
    check(broker.commit(left[0]) && broker.commit(right[0]));
    check(!broker.commit(right[0]) && broker.poll("orders", "g", "c2", 10).length === 0);
    broker.join("orders", "independent", "reader");
    check(broker.poll("orders", "independent", "reader", 10).length === 2);
    broker.leave("orders", "g", "c2");
    console.log(
        "Key ordering: 0,1\nUncommitted replay: true\nStale generation rejected: true\nPartition owners: c1,c2\nIndependent group replay: true",
    );
}
main();
