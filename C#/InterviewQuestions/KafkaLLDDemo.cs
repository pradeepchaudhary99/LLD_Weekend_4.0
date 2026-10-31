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
Run from repository root: python3 scripts/run.py "C#" InterviewQuestions/KafkaLLDDemo
See LOCKER_KAFKA_GUIDE.md for prerequisites and validation commands.
*/

using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
namespace LLDWeekend4.InterviewQuestions;
public class KafkaLLDDemo
{
    public record Message(string key, string value, int offset);
    public record Batch(string topic, string group, string member, int generation, int partition,
                        int start, List<Message> messages);
    public class Topic
    {
        public readonly List<List<Message>> partitions = new();
        public int next;
        public Topic(int count)
        {
            for (int i = 0; i < count; i++)
            {
                partitions.Add(new());
            }
        }
    }

    public class Group
    {
        public readonly List<string> members = new();
        public readonly int[] offsets;
        public int generation;
        public Group(int count)
        {
            offsets = new int[count];
        }
    }

    public class Broker
    {
        private readonly object gate = new();
        private readonly Dictionary<string, Topic> topics = new();
        private readonly Dictionary<(string, string), Group> groups = new();
        public void CreateTopic(string name, int count)
        {
            lock (gate)
            {
                require(name != null && name.Trim().Length > 0 && !topics.ContainsKey(name) &&
                        count > 0 && count <= 100);
                topics.Add(name, new Topic(count));
            }
        }

        private Topic GetTopic(string name)
        {
            Topic topic = topics.GetValueOrDefault(name)!;
            require(topic != null);
            return topic;
        }

        private Group GetGroup(string topic, string group)
        {
            Group state = groups.GetValueOrDefault((topic, group))!;
            require(state != null);
            return state;
        }

        public int Publish(string name, string key, string value)
        {
            lock (gate)
            {
                Topic topic = GetTopic(name);
                require(key != null && value != null);
                int partition = 0;
                if (key.Length == 0)
                {
                    partition = topic.next;
                    topic.next = (topic.next + 1) % topic.partitions.Count;
                }
                else
                {
                    foreach (char character in key)
                    {
                        require(character <= 127);
                        partition = (partition * 31 + character) % topic.partitions.Count;
                    }
                }
                List<Message> log = topic.partitions[partition];
                log.Add(new Message(key, value, log.Count));
                return partition;
            }
        }

        private Group getOrCreateGroup(string name, string id, int count)
        {
            if (!groups.TryGetValue((name,id),out Group? value))
            {
                value = new Group(count);
                groups.Add((name, id), value);
            }
            return value;
        }

        public void Join(string name, string groupId, string member)
        {
            lock (gate)
            {
                Topic topic = GetTopic(name);
                require(groupId != null && groupId.Trim().Length > 0 && member != null &&
                        member.Trim().Length > 0);
                Group group = getOrCreateGroup(name, groupId, topic.partitions.Count);
                require(!group.members.Contains(member));
                group.members.Add(member);
                group.members.Sort(StringComparer.Ordinal);
                group.generation++;
            }
        }

        public void Leave(string name, string groupId, string member)
        {
            lock (gate)
            {
                Group group = GetGroup(name, groupId);
                require(group.members.Remove(member));
                group.generation++;
            }
        }

        public List<Batch> Poll(string name, string groupId, string member, int limit)
        {
            lock (gate)
            {
                Topic topic = GetTopic(name);
                Group group = GetGroup(name, groupId);
                require(group.members.Contains(member) && limit > 0 && limit <= 1000);
                List<Batch> batches = new();
                for (int partition = 0; partition < topic.partitions.Count; partition++)
                {
                    if (!group.members[partition % group.members.Count].Equals(member))
                    {
                        continue;
                    }
                    List<Message> log = topic.partitions[partition];
                    int start = group.offsets[partition];
                    List<Message> messages = log.Skip(start).Take(limit).ToList();
                    if (messages.Count > 0)
                    {
                        batches.Add(new Batch(name, groupId, member, group.generation, partition,
                                              start, messages));
                    }
                }
                return batches.ToList();
            }
        }

        public bool Commit(Batch batch)
        {
            lock (gate)
            {
                Group group = GetGroup(batch.topic, batch.group);
                if (group.generation != batch.generation || group.members.Count == 0 ||
                    batch.partition < 0 || batch.partition >= group.offsets.Length)
                {
                    return false;
                }
                if (!group.members[batch.partition % group.members.Count].Equals(batch.member) ||
                    group.offsets[batch.partition] != batch.start || batch.messages.Count == 0)
                {
                    return false;
                }
                int next = batch.start + batch.messages.Count;
                if (next > GetTopic(batch.topic).partitions[batch.partition].Count)
                {
                    return false;
                }
                group.offsets[batch.partition] = next;
                return true;
            }
        }
    }

    static void require([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition)
    {
        if (!condition)
        {
            throw new ArgumentException("Invalid broker request");
        }
    }

    static void check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Broker contract failed");
        }
    }

    public static void Main(string[] args)
    {
        Broker broker = new Broker();
        broker.CreateTopic("orders", 2);
        check(broker.Publish("orders", "a", "one") == 1);
        broker.Publish("orders", "a", "two");
        broker.Publish("orders", "", "other");
        broker.Join("orders", "g", "c1");
        List<Batch> initial = broker.Poll("orders", "g", "c1", 10);
        check(initial.Count == 2 && initial[1].messages[1].offset == 1);
        check(broker.Poll("orders", "g", "c1", 10)
                  .SelectMany(b => b.messages)
                  .SequenceEqual(initial.SelectMany(b => b.messages))); // No commit: replay.
        broker.Join("orders", "g", "c2");
        check(!broker.Commit(initial[0]));
        List<Batch> left = broker.Poll("orders", "g", "c1", 10),
                    right = broker.Poll("orders", "g", "c2", 10);
        check(left.Count == 1 && right.Count == 1);
        check(broker.Commit(left[0]) && broker.Commit(right[0]));
        check(!broker.Commit(right[0]) && broker.Poll("orders", "g", "c2", 10).Count == 0);
        broker.Join("orders", "independent", "reader");
        check(broker.Poll("orders", "independent", "reader", 10).Count == 2);
        broker.Leave("orders", "g", "c2");
        Console.WriteLine(
            "Key ordering: 0,1\nUncommitted replay: true\nStale generation rejected: " +
            "true\nPartition owners: c1,c2\nIndependent group replay: true");
    }
}
