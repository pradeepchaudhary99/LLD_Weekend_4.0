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
Run from repository root: python3 scripts/run.py "Java" InterviewQuestions/KafkaLLDDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

package InterviewQuestions;
import java.util.*;

public class KafkaLLDDemo {
    record Message(String key, String value, int offset) {}
    record Batch(String topic, String group, String member, int generation, int partition,
                 int start, List<Message> messages) {}
    static class Topic {
        final List<List<Message>> partitions = new ArrayList<>();
        int next;
        Topic(int count) {
            for (int i = 0; i < count; i++) {
                partitions.add(new ArrayList<>());
            }
        }
    }

    static class Group {
        final List<String> members = new ArrayList<>();
        final int[] offsets;
        int generation;
        Group(int count) {
            offsets = new int[count];
        }
    }

    static class Broker {
        private final Map<String, Topic> topics = new HashMap<>();
        private final Map<List<String>, Group> groups = new HashMap<>();
        synchronized void createTopic(String name, int count) {
            require(name != null && !name.isBlank() && !topics.containsKey(name) && count > 0 &&
                    count <= 100);
            topics.put(name, new Topic(count));
        }

        private Topic topic(String name) {
            Topic topic = topics.get(name);
            require(topic != null);
            return topic;
        }

        private Group group(String topic, String group) {
            Group state = groups.get(List.of(topic, group));
            require(state != null);
            return state;
        }

        synchronized int publish(String name, String key, String value) {
            Topic topic = topic(name);
            require(key != null && value != null);
            int partition = 0;
            if (key.isEmpty()) {
                partition = topic.next;
                topic.next = (topic.next + 1) % topic.partitions.size();
            } else {
                for (char character : key.toCharArray()) {
                    require(character <= 127);
                    partition = (partition * 31 + character) % topic.partitions.size();
                }
            }
            List<Message> log = topic.partitions.get(partition);
            log.add(new Message(key, value, log.size()));
            return partition;
        }

        synchronized void join(String name, String groupId, String member) {
            Topic topic = topic(name);
            require(groupId != null && !groupId.isBlank() && member != null && !member.isBlank());
            Group group = groups.computeIfAbsent(List.of(name, groupId),
                                                 ignored -> new Group(topic.partitions.size()));
            require(!group.members.contains(member));
            group.members.add(member);
            Collections.sort(group.members);
            group.generation++;
        }

        synchronized void leave(String name, String groupId, String member) {
            Group group = group(name, groupId);
            require(group.members.remove(member));
            group.generation++;
        }

        synchronized List<Batch> poll(String name, String groupId, String member, int limit) {
            Topic topic = topic(name);
            Group group = group(name, groupId);
            require(group.members.contains(member) && limit > 0 && limit <= 1000);
            List<Batch> batches = new ArrayList<>();
            for (int partition = 0; partition < topic.partitions.size(); partition++) {
                if (!group.members.get(partition % group.members.size()).equals(member)) {
                    continue;
                }
                List<Message> log = topic.partitions.get(partition);
                int start = group.offsets[partition];
                List<Message> messages =
                    List.copyOf(log.subList(start, Math.min(log.size(), start + limit)));
                if (!messages.isEmpty()) {
                    batches.add(new Batch(name, groupId, member, group.generation, partition, start,
                                          messages));
                }
            }
            return List.copyOf(batches);
        }

        synchronized boolean commit(Batch batch) {
            Group group = group(batch.topic(), batch.group());
            if (group.generation != batch.generation() || group.members.isEmpty() ||
                batch.partition() < 0 || batch.partition() >= group.offsets.length) {
                return false;
            }
            if (!group.members.get(batch.partition() % group.members.size())
                     .equals(batch.member()) ||
                group.offsets[batch.partition()] != batch.start() || batch.messages().isEmpty()) {
                return false;
            }
            int next = batch.start() + batch.messages().size();
            if (next > topic(batch.topic()).partitions.get(batch.partition()).size()) {
                return false;
            }
            group.offsets[batch.partition()] = next;
            return true;
        }
    }

    static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid broker request");
        }
    }

    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Broker contract failed");
        }
    }

    public static void main(String[] args) {
        Broker broker = new Broker();
        broker.createTopic("orders", 2);
        check(broker.publish("orders", "a", "one") == 1);
        broker.publish("orders", "a", "two");
        broker.publish("orders", "", "other");
        broker.join("orders", "g", "c1");
        List<Batch> initial = broker.poll("orders", "g", "c1", 10);
        check(initial.size() == 2 && initial.get(1).messages().get(1).offset() == 1);
        check(broker.poll("orders", "g", "c1", 10).equals(initial)); // No commit: replay.
        broker.join("orders", "g", "c2");
        check(!broker.commit(initial.get(0)));
        List<Batch> left = broker.poll("orders", "g", "c1", 10),
                    right = broker.poll("orders", "g", "c2", 10);
        check(left.size() == 1 && right.size() == 1);
        check(broker.commit(left.get(0)) && broker.commit(right.get(0)));
        check(!broker.commit(right.get(0)) && broker.poll("orders", "g", "c2", 10).isEmpty());
        broker.join("orders", "independent", "reader");
        check(broker.poll("orders", "independent", "reader", 10).size() == 2);
        broker.leave("orders", "g", "c2");
        System.out.println(
            "Key ordering: 0,1\nUncommitted replay: true\nStale generation rejected: " +
            "true\nPartition owners: c1,c2\nIndependent group replay: true");
    }
}
