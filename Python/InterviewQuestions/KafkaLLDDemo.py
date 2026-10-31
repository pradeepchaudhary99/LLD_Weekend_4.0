# Kafka LLD — a small in-memory partitioned log, not an Apache Kafka client.
# Functional requirements: create topics, publish records, join/leave groups,
# assign partitions, poll batches, and explicitly commit processed offsets.
# Non-functional requirements: atomic appends/offset updates, per-partition order,
# independent group progress, and rejection of stale commits after rebalancing.
# Core entities: Message(key/value/offset), Topic(partition logs), Group(members/
# committed offsets/generation), Batch(detached records and ownership metadata),
# Broker(registry and coordination). Producers call publish; consumers poll/process/
# commit outside the broker. Broker methods cover the original draft's helpers.
# Flow: key -> partition -> append offset; member -> assigned partitions -> read
# from committed next-offset -> process all records -> commit whole batch.
# Nonempty ASCII keys use rolling hash (hash*31+character) modulo partition count;
# empty keys round-robin. This is a teaching hash, not Kafka's wire-compatible hash.
# Groups are scoped by (topic, group ID). Sorted member IDs own partition i via
# members[i % memberCount]. Each join/leave increments the generation; progress
# survives an empty group. Duplicate joins and unknown leaves are rejected.
# Poll does not advance offsets. Commit requires current generation, ownership,
# and matching starting offset; next offset is start + batch size. No rewind.
# Concurrency: a coarse broker lock protects all operations in threaded versions;
# processing is outside that lock. Rebalance fences commits, not external effects.
# Repeated polls before commit replay records. Process before commit; failures can
# retry. Side effects need idempotency: this is at-least-once within a live process,
# not exactly-once processing. Serialize processing for each member/partition.
# Scope: trusted consumers commit unchanged broker-issued batches only. Batch
# metadata is not an authorization token. No durability, replication, retention,
# network, heartbeat-driven failure detection, transactions or background threads.
# Topic partitions are fixed (1..100); poll limit (1..1000) is per partition.
# Edge cases: bad topic/key/count, duplicate members, no members, surplus members,
# independent groups, retry without commit, stale generation, duplicate commits,
# and concurrent publishes. Memory is unbounded and process restart loses all data.
# Run from repository root: python3 scripts/run.py "Python" InterviewQuestions/KafkaLLDDemo
# See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.

from dataclasses import dataclass
from threading import RLock


@dataclass(frozen=True)
class Message:
    key: str
    value: str
    offset: int


@dataclass(frozen=True)
class Batch:
    topic: str
    group: str
    member: str
    generation: int
    partition: int
    start: int
    messages: tuple


class Broker:
    def __init__(self):
        self.lock = RLock()
        self.topics = {}
        self.groups = {}

    def create_topic(self, name, count):
        with self.lock:
            require(
                isinstance(name, str)
                and name.strip()
                and name not in self.topics
                and type(count) is int
                and 0 < count <= 100
            )
            self.topics[name] = {"partitions": [[] for _ in range(count)], "next": 0}

    def topic(self, name):
        require(name in self.topics)
        return self.topics[name]

    def group(self, name, group_id):
        require((name, group_id) in self.groups)
        return self.groups[name, group_id]

    def publish(self, name, key, value):
        with self.lock:
            topic = self.topic(name)
            require(isinstance(key, str) and isinstance(value, str) and key.isascii())
            count = len(topic["partitions"])
            partition = 0
            if not key:
                partition = topic["next"]
                topic["next"] = (partition + 1) % count
            else:
                for character in key:
                    partition = (partition * 31 + ord(character)) % count
            log = topic["partitions"][partition]
            log.append(Message(key, value, len(log)))
            return partition

    def join(self, name, group_id, member):
        with self.lock:
            topic = self.topic(name)
            require(
                isinstance(group_id, str)
                and group_id.strip()
                and isinstance(member, str)
                and member.strip()
            )
            group = self.groups.setdefault(
                (name, group_id),
                {"members": [], "offsets": [0] * len(topic["partitions"]), "generation": 0},
            )
            require(member not in group["members"])
            group["members"].append(member)
            group["members"].sort()
            group["generation"] += 1

    def leave(self, name, group_id, member):
        with self.lock:
            group = self.group(name, group_id)
            require(member in group["members"])
            group["members"].remove(member)
            group["generation"] += 1

    def poll(self, name, group_id, member, limit):
        with self.lock:
            topic, group = self.topic(name), self.group(name, group_id)
            require(member in group["members"] and type(limit) is int and 0 < limit <= 1000)
            batches = []
            for partition, log in enumerate(topic["partitions"]):
                if group["members"][partition % len(group["members"])] != member:
                    continue
                start = group["offsets"][partition]
                messages = tuple(log[start : start + limit])
                if messages:
                    batches.append(
                        Batch(
                            name, group_id, member, group["generation"], partition, start, messages
                        )
                    )
            return tuple(batches)

    def commit(self, batch):
        with self.lock:
            group = self.group(batch.topic, batch.group)
            if (
                group["generation"] != batch.generation
                or not group["members"]
                or not 0 <= batch.partition < len(group["offsets"])
            ):
                return False
            if (
                group["members"][batch.partition % len(group["members"])] != batch.member
                or group["offsets"][batch.partition] != batch.start
                or not batch.messages
            ):
                return False
            next_offset = batch.start + len(batch.messages)
            if next_offset > len(self.topic(batch.topic)["partitions"][batch.partition]):
                return False
            group["offsets"][batch.partition] = next_offset
            return True


def require(condition):
    if not condition:
        raise ValueError("Invalid broker request")


def check(condition):
    if not condition:
        raise AssertionError("Broker contract failed")


def main():
    broker = Broker()
    broker.create_topic("orders", 2)
    check(broker.publish("orders", "a", "one") == 1)
    broker.publish("orders", "a", "two")
    broker.publish("orders", "", "other")
    broker.join("orders", "g", "c1")
    initial = broker.poll("orders", "g", "c1", 10)
    check(len(initial) == 2 and initial[1].messages[1].offset == 1)
    check(broker.poll("orders", "g", "c1", 10) == initial)
    broker.join("orders", "g", "c2")
    check(not broker.commit(initial[0]))
    left, right = broker.poll("orders", "g", "c1", 10), broker.poll("orders", "g", "c2", 10)
    check(len(left) == len(right) == 1)
    check(broker.commit(left[0]) and broker.commit(right[0]))
    check(not broker.commit(right[0]) and not broker.poll("orders", "g", "c2", 10))
    broker.join("orders", "independent", "reader")
    check(len(broker.poll("orders", "independent", "reader", 10)) == 2)
    broker.leave("orders", "g", "c2")
    print(
        "Key ordering: 0,1\nUncommitted replay: true\nStale generation rejected: true\nPartition owners: c1,c2\nIndependent group replay: true"
    )


if __name__ == "__main__":
    main()
