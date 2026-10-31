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
Run from repository root: python3 scripts/run.py "C++" InterviewQuestions/KafkaLLDDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

#include <algorithm>
#include <iostream>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
using namespace std;
struct Message {
    string key, value;
    int offset;
};
struct Batch {
    string topic, group, member;
    int generation, partition, start;
    vector<Message> messages;
};
struct Topic {
    vector<vector<Message>> partitions;
    int next = 0;
    explicit Topic(int count) : partitions(count) {
    }
};
struct Group {
    vector<string> members;
    vector<int> offsets;
    int generation = 0;
    explicit Group(int count) : offsets(count) {
    }
};

void require(bool condition) {
    if (!condition)
        throw invalid_argument("Invalid broker request");
}

void check(bool condition) {
    if (!condition)
        throw runtime_error("Broker contract failed");
}
class Broker {
    mutex gate;
    map<string, Topic> topics;
    map<pair<string, string>, Group> groups;
    Topic &topic(const string &name) {
        require(topics.count(name));
        return topics.at(name);
    }
    Group &group(const string &name, const string &id) {
        require(groups.count({name, id}));
        return groups.at({name, id});
    }

  public:
    void createTopic(const string &name, int count) {
        lock_guard<mutex> lock(gate);
        require(!name.empty() && !topics.count(name) && count > 0 && count <= 100);
        topics.emplace(name, Topic(count));
    }

    int publish(const string &name, const string &key, const string &value) {
        lock_guard<mutex> lock(gate);
        auto &state = topic(name);
        int partition = 0;
        if (key.empty()) {
            partition = state.next;
            state.next = (state.next + 1) % state.partitions.size();
        } else {
            for (unsigned char character : key) {
                require(character <= 127);
                partition = (partition * 31 + character) % state.partitions.size();
            }
        }
        auto &log = state.partitions[partition];
        log.push_back({key, value, static_cast<int>(log.size())});
        return partition;
    }

    void join(const string &name, const string &id, const string &member) {
        lock_guard<mutex> lock(gate);
        auto &state = topic(name);
        require(!id.empty() && !member.empty());
        groups.try_emplace({name, id}, static_cast<int>(state.partitions.size()));
        auto &consumers = group(name, id);
        require(find(consumers.members.begin(), consumers.members.end(), member) ==
                consumers.members.end());
        consumers.members.push_back(member);
        sort(consumers.members.begin(), consumers.members.end());
        ++consumers.generation;
    }

    void leave(const string &name, const string &id, const string &member) {
        lock_guard<mutex> lock(gate);
        auto &consumers = group(name, id);
        auto found = find(consumers.members.begin(), consumers.members.end(), member);
        require(found != consumers.members.end());
        consumers.members.erase(found);
        ++consumers.generation;
    }

    vector<Batch> poll(const string &name, const string &id, const string &member, int limit) {
        lock_guard<mutex> lock(gate);
        auto &state = topic(name);
        auto &consumers = group(name, id);
        require(find(consumers.members.begin(), consumers.members.end(), member) !=
                    consumers.members.end() &&
                limit > 0 && limit <= 1000);
        vector<Batch> batches;
        for (int partition = 0; partition < static_cast<int>(state.partitions.size());
             ++partition) {
            if (consumers.members[partition % consumers.members.size()] != member)
                continue;
            const auto &log = state.partitions[partition];
            int start = consumers.offsets[partition];
            int end = min(static_cast<int>(log.size()), start + limit);
            if (end > start)
                batches.push_back({name, id, member, consumers.generation, partition, start,
                                   vector<Message>(log.begin() + start, log.begin() + end)});
        }
        return batches;
    }

    bool commit(const Batch &batch) {
        lock_guard<mutex> lock(gate);
        auto &consumers = group(batch.topic, batch.group);
        if (consumers.generation != batch.generation || consumers.members.empty() ||
            batch.partition < 0 || batch.partition >= static_cast<int>(consumers.offsets.size()))
            return false;
        if (consumers.members[batch.partition % consumers.members.size()] != batch.member ||
            consumers.offsets[batch.partition] != batch.start || batch.messages.empty())
            return false;
        auto next = batch.start + batch.messages.size();
        if (next > topic(batch.topic).partitions[batch.partition].size())
            return false;
        consumers.offsets[batch.partition] = static_cast<int>(next);
        return true;
    }
};

int main() {
    Broker broker;
    broker.createTopic("orders", 2);
    check(broker.publish("orders", "a", "one") == 1);
    broker.publish("orders", "a", "two");
    broker.publish("orders", "", "other");
    broker.join("orders", "g", "c1");
    auto initial = broker.poll("orders", "g", "c1", 10);
    check(initial.size() == 2 && initial[1].messages[1].offset == 1);
    auto replay = broker.poll("orders", "g", "c1", 10);
    check(replay.size() == 2 && replay[1].start == 0 && replay[1].messages.size() == 2);
    broker.join("orders", "g", "c2");
    check(!broker.commit(initial[0]));
    auto left = broker.poll("orders", "g", "c1", 10), right = broker.poll("orders", "g", "c2", 10);
    check(left.size() == 1 && right.size() == 1);
    check(broker.commit(left[0]) && broker.commit(right[0]));
    check(!broker.commit(right[0]) && broker.poll("orders", "g", "c2", 10).empty());
    broker.join("orders", "independent", "reader");
    check(broker.poll("orders", "independent", "reader", 10).size() == 2);
    broker.leave("orders", "g", "c2");
    cout << "Key ordering: 0,1\nUncommitted replay: true\nStale generation rejected: "
            "true\nPartition owners: c1,c2\nIndependent group replay: true\n";
}
