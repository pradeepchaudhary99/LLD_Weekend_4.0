# Amazon Locker and Kafka LLD

Both lessons are runnable in Java, Python, C++, Go, C#, JavaScript and TypeScript.
The original unfinished Java sketches are preserved verbatim under
`classroom-drafts/2026-10-31/`. The Simple Java MVP editions remain Java-only.

## Amazon Locker

**Requirements.** Support multiple locations and locker sizes; choose the smallest
available locker that fits a package; issue a pickup code and notification; allow
one successful pickup; expire codes; make lockers reusable after physical removal.
Atomic allocation and pickup prevent two customers from claiming the same slot.

**Entities.** Locker stores size and occupancy. Assignment contains the package,
location, locker index, pickup token and expiry. StoredPackage tracks lifecycle.
LockerService owns the locations, packages and notification outbox.

**Flow.** Configure a location, then deposit using a unique package ID, size,
current time and TTL. Sizes are 0 small, 1 medium and 2 large. Best-fit allocation
scans the location; equal sizes use configuration order. Deposit occupies the
locker and creates an outbox entry in the same protected operation. The demo
inspects this outbox; it does not send real messages.

Pickup validates both package ID and token. Wrong tokens leave the assignment
unchanged. At `now >= expiry`, pickup fails and records EXPIRED. **Expiry does not
remove the parcel.** The locker stays occupied until an authorized staff workflow
calls `returnExpired` after physically retrieving it. That method also recognizes
an expired STORED parcel even if the customer never attempted pickup. Successful
pickup sets PICKED_UP; staff removal sets RETURNED. Replays return false.

**Concurrency and time.** Threaded implementations use a coarse service lock around
allocation, occupancy and status changes. JavaScript/TypeScript methods are
synchronous on one event loop. There are no external callbacks or I/O within
these operations. Supply nondecreasing time from one source in milliseconds;
acceptance bounds are `0 <= now <= 10^12` and `0 < TTL <= 10^9`. Calls, including
failed requests, can advance the service's observed clock. Injected time makes
expiry tests deterministic; real deployment needs a server-owned clock.

**Scope and trade-offs.** This is the locker subsystem; order placement, customer
accounts, agent authentication and location selection are upstream. Door opening
and deposit are modeled as atomic actions, not actual hardware acknowledgements.
Production needs a reservation/deposit protocol, durable state, recovery after
partial hardware failure, role checks, token-attempt limits and durable outbox
delivery. Maintenance mode is an extension. Random pickup tokens are 128-bit hex
strings; C++ uses `random_device`, whose security depends on the implementation,
so replace it with an OS-backed cryptographic generator for real credentials.
Package IDs and history are retained indefinitely in this teaching service.

**Demo output.** Checks best-fit selection, bad and repeated pickup codes, expiry
at the exact boundary, retained occupancy, staff return and locker reuse:

```text
Best fit: 1
Pickup once: true
Expired locker retained: true
Returned locker reused: true
Notifications: 4
```

## Kafka LLD

**Requirements.** Create partitioned topics, append keyed messages, manage consumer
groups, assign partitions, poll records and explicitly commit processed offsets.
Preserve ordering within each partition, independent progress between groups, and
atomic updates under concurrent operations.

**Entities.** Topic holds partition logs and a round-robin cursor. Message contains
key, value and offset. Group holds sorted members, committed next-offsets and a
generation number. Batch is a detached snapshot with ownership and offset
metadata. Broker coordinates these. Producer and consumer operations from the
sketch are exposed through publish and poll/commit, keeping the runnable model
small enough to explain in an interview.

**Flow.** Nonempty ASCII keys use a documented rolling hash modulo partition count.
The same key stays in the same partition; empty keys use round robin. Each append
gets that partition's next contiguous offset. This teaching hash is not Apache
Kafka's partitioner, and this program does not connect to a Kafka cluster.

Groups are scoped by `(topic, group ID)`, using tuple/structured keys to avoid
separator collisions. Sorted members own partition `i` through
`members[i % memberCount]`. Each join or leave increments the generation, including
changes that leave a partition with its previous owner. Progress survives the last
member leaving. Extra members can own no partitions. There are no background
heartbeats: membership changes are explicit.

Poll reads from the **committed next-offset** without advancing it. The limit is
per partition. Process the entire batch successfully, then commit it. Commit checks
its generation, partition owner and starting offset before advancing to the end of
the batch. Old-generation or duplicate commits return false. Repeated polls before
commit replay records, allowing a caller to retry after processing failure.

**Concurrency and delivery.** A coarse broker lock makes appends, rebalances and
commits atomic; application processing happens outside it. JS/TS use synchronous
event-loop operations. Rebalancing invalidates old commits but cannot undo external
side effects. Serialize processing for each member/partition and make external
side effects idempotent. The model supports at-least-once processing within a live
process, not exactly-once delivery or crash durability. Committing before processing
can skip unprocessed records.

**Scope and trade-offs.** Clients are trusted and must commit unchanged broker-issued
batches. Detached snapshots protect the log from accidental list changes, but batch
metadata is not a security capability or proof of processing. No network, disk,
replication, retention, transactions, authentication or automatic failure detection
is included. Partition counts are fixed at creation (1–100); poll limits are
1–1,000 records per partition. Memory is unbounded; restart loses all data. Coarse
locking keeps the correctness story clear; per-partition locks and a separate
coordinator would increase throughput but require careful rebalance coordination.

**Demo output.** Checks key ordering, replay, stale and duplicate commit rejection,
partition ownership and independent group offsets:

```text
Key ordering: 0,1
Uncommitted replay: true
Stale generation rejected: true
Partition owners: c1,c2
Independent group replay: true
```

## Run the lessons

From the repository root, install Python 3.10+ for the runner and your selected
language toolchain: JDK 17+, C++17 compiler, Go 1.22+, .NET SDK 8, or Node.js 18+
(20+ recommended). Run `npm ci` before running JavaScript/TypeScript.

```sh
python3 scripts/run.py Java InterviewQuestions/AmazonLockerDemo
python3 scripts/run.py Python InterviewQuestions/AmazonLockerDemo
python3 scripts/run.py 'C++' InterviewQuestions/AmazonLockerDemo
python3 scripts/run.py Go InterviewQuestions/AmazonLockerDemo
python3 scripts/run.py 'C#' InterviewQuestions/AmazonLockerDemo
python3 scripts/run.py JavaScript InterviewQuestions/AmazonLockerDemo
python3 scripts/run.py TypeScript InterviewQuestions/AmazonLockerDemo

python3 scripts/run.py Java InterviewQuestions/KafkaLLDDemo
python3 scripts/run.py Python InterviewQuestions/KafkaLLDDemo
python3 scripts/run.py 'C++' InterviewQuestions/KafkaLLDDemo
python3 scripts/run.py Go InterviewQuestions/KafkaLLDDemo
python3 scripts/run.py 'C#' InterviewQuestions/KafkaLLDDemo
python3 scripts/run.py JavaScript InterviewQuestions/KafkaLLDDemo
python3 scripts/run.py TypeScript InterviewQuestions/KafkaLLDDemo
```

## Focused validation

```sh
python3 scripts/test_locker_kafka.py
mkdir -p /tmp/lld-locker-kafka-checks
javac -d /tmp/lld-locker-kafka-checks Java/InterviewQuestions/AmazonLockerDemo.java Java/InterviewQuestions/KafkaLLDDemo.java tests/locker-kafka/LockerKafkaChecks.java
java -cp /tmp/lld-locker-kafka-checks InterviewQuestions.LockerKafkaChecks
(cd Go && go test -race ./InterviewQuestions/AmazonLockerDemo ./InterviewQuestions/KafkaLLDDemo)
python3 scripts/validate.py
npm run build:typescript
```

The focused tests cover competing last-slot deposits, simultaneous pickup attempts,
expiry and retained occupancy, invalid inputs, concurrent appends with sequential
unique offsets, snapshot independence, rebalance fencing, surplus members, empty
group recovery and group-key collisions. The seven-language demos cover the common
contract; deeper concurrency tests run in Java, Python and Go. Tests cannot prove
every possible interleaving or any of the out-of-scope distributed guarantees.
