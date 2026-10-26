/*
Task scheduler: one process, in-memory teaching example.
Functional requirements: schedule one-time or fixed-delay recurring actions;
order dispatch by deadline, priority (0 high, 2 low), then insertion order;
cancel tasks, inspect status/runs, isolate action failures, and close workers.
Non-functional requirements: safe shared state, no polling loop, bounded task
registry (10,000 IDs), and no overlapping executions of the same recurring task.
Core entities: Task holds identity/deadline/action/state; Scheduler owns the
pending queue, registry and workers; Snapshot exposes a detached status/run count.
Flow: schedule -> start -> wait for earliest deadline -> mark RUNNING -> execute
outside the state lock -> record result -> optionally requeue after fixed delay.
Concurrency: queue selection and state transitions are atomic. Cancellation can
win before dispatch; after dispatch the action may finish but never recur.
Priorities break equal-deadline ties; completion order with several workers is
not guaranteed. Failures terminate that task, not the worker. Runs count attempts.
Close cancels pending work and running recurrence, waits for active actions, and
is owner-only. Actions must finish; there is no forced interruption or timeout.
Scope: monotonic milliseconds since construction, interval 0 means one-time;
IDs are retained for this scheduler lifetime. No persistence, retries, cron,
distributed ownership, eviction, or production-grade admission control.
Edge cases: duplicate/unknown IDs, invalid times, cancellation before/during run,
action failure, earlier insertion waking sleepers, and close before start.
A shared lock and condition protect state; native workers execute actions outside it.
The pending min-heap gives O(log n) insertion/removal.
Run from repository root: python3 scripts/run.py "C++" InterviewQuestions/TaskSchedular_demo
See TASK_SCHEDULER_GUIDE.md for prerequisites and contracts.
*/

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <queue>
#include <stdexcept>
#include <string>
#include <thread>
#include <tuple>
#include <vector>
using namespace std;
class Scheduler {
    struct Task {
        string id;
        long long due, interval;
        int priority, sequence, runs = 0;
        function<void()> action;
        string status = "WAITING";
    };
    struct Later {
        bool operator()(const shared_ptr<Task> &a, const shared_ptr<Task> &b) const {
            return tie(a->due, a->priority, a->sequence) > tie(b->due, b->priority, b->sequence);
        }
    };
    mutex gate, joinGate;
    condition_variable changed;
    map<string, shared_ptr<Task>> tasks;
    priority_queue<shared_ptr<Task>, vector<shared_ptr<Task>>, Later> pending;
    vector<thread> workers;
    vector<thread::id> workerIds;
    vector<string> dispatched;
    chrono::steady_clock::time_point epoch = chrono::steady_clock::now();
    int workerCount;
    bool started = false, closed = false;
    shared_ptr<Task> task(const string &id) {
        auto found = tasks.find(id);
        if (found == tasks.end())
            throw invalid_argument("Unknown task");
        return found->second;
    }
    void work() {
        for (;;) {
            shared_ptr<Task> next;
            {
                unique_lock<mutex> lock(gate);
                for (;;) {
                    if (closed)
                        return;
                    while (!pending.empty() && pending.top()->status == "CANCELLED")
                        pending.pop();
                    if (pending.empty()) {
                        changed.wait(lock);
                        continue;
                    }
                    next = pending.top();
                    auto remaining = next->due - now();
                    if (remaining > 0) {
                        changed.wait_for(lock, chrono::milliseconds(remaining));
                        continue;
                    }
                    pending.pop();
                    next->status = "RUNNING";
                    dispatched.push_back(next->id);
                    break;
                }
            }
            bool failed = false;
            try {
                next->action();
            } catch (...) {
                failed = true;
            }
            lock_guard<mutex> lock(gate);
            ++next->runs;
            if (next->status != "CANCELLED") {
                if (failed)
                    next->status = "FAILED";
                else if (next->interval > 0 && !closed) {
                    next->due = now() + next->interval;
                    next->status = "WAITING";
                    pending.push(next);
                } else
                    next->status = "COMPLETED";
            }
            changed.notify_all();
        }
    }

  public:
    explicit Scheduler(int count) : workerCount(count) {
        if (count < 1 || count > 32)
            throw invalid_argument("Invalid worker count");
    }
    ~Scheduler() {
        close();
    }
    long long now() const {
        return chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now() - epoch)
            .count();
    }
    void schedule(string id, long long due, int priority, long long interval,
                  function<void()> action) {
        lock_guard<mutex> lock(gate);
        if (closed || id.empty() || tasks.count(id) || tasks.size() >= 10000 || due < 0 ||
            due > 1000000000000LL || priority < 0 || priority > 2 || interval < 0 ||
            interval > 1000000000 || !action)
            throw invalid_argument("Invalid task");
        auto next = make_shared<Task>();
        next->id = id;
        next->due = due;
        next->priority = priority;
        next->interval = interval;
        next->sequence = static_cast<int>(tasks.size());
        next->action = std::move(action);
        tasks[id] = next;
        pending.push(next);
        changed.notify_all();
    }
    void start() {
        lock_guard<mutex> lock(gate);
        if (started || closed)
            throw invalid_argument("Already started/closed");
        started = true;
        for (int i = 0; i < workerCount; ++i) {
            workers.emplace_back([this] {
                work();
            });
            workerIds.push_back(workers.back().get_id());
        }
    }
    bool cancel(const string &id) {
        lock_guard<mutex> lock(gate);
        auto next = task(id);
        if (next->status != "WAITING" && next->status != "RUNNING")
            return false;
        next->status = "CANCELLED";
        changed.notify_all();
        return true;
    }
    pair<string, int> snapshot(const string &id) {
        lock_guard<mutex> lock(gate);
        auto next = task(id);
        return {next->status, next->runs};
    }
    vector<string> dispatches() {
        lock_guard<mutex> lock(gate);
        return dispatched;
    }
    bool awaitRuns(const string &id, int count, int timeout) {
        if (count < 1 || timeout < 0 || timeout > 60000)
            throw invalid_argument("Invalid wait");
        unique_lock<mutex> lock(gate);
        auto next = task(id);
        auto deadline = now() + timeout;
        while (next->runs < count) {
            if (next->status == "CANCELLED" || next->status == "FAILED" ||
                next->status == "COMPLETED")
                return false;
            auto remaining = deadline - now();
            if (remaining <= 0)
                return false;
            changed.wait_for(lock, chrono::milliseconds(remaining));
        }
        return true;
    }
    void close() {
        {
            lock_guard<mutex> lock(gate);
            if (find(workerIds.begin(), workerIds.end(), this_thread::get_id()) != workerIds.end())
                throw logic_error("Owner must close scheduler");
            closed = true;
            for (auto &entry : tasks) {
                auto next = entry.second;
                if (next->status == "WAITING" || (next->status == "RUNNING" && next->interval > 0))
                    next->status = "CANCELLED";
            }
            pending = {};
            changed.notify_all();
        }
        lock_guard<mutex> lock(joinGate);
        for (auto &worker : workers)
            if (worker.joinable())
                worker.join();
    }
};
void check(bool condition) {
    if (!condition)
        throw runtime_error("Scheduler contract failed");
}
int main() {
    Scheduler scheduler(1);
    auto noop = [] {
    };
    auto fail = [] {
        throw runtime_error("Expected failure");
    };
    scheduler.schedule("low", 0, 2, 0, noop);
    scheduler.schedule("high", 0, 0, 0, noop);
    scheduler.schedule("cancel", 0, 1, 0, fail);
    check(scheduler.cancel("cancel"));
    scheduler.start();
    check(scheduler.awaitRuns("low", 1, 5000));
    check(scheduler.dispatches() == vector<string>({"high", "low"}));
    scheduler.schedule("failure", scheduler.now(), 1, 0, fail);
    check(scheduler.awaitRuns("failure", 1, 5000));
    check(scheduler.snapshot("failure").first == "FAILED");
    scheduler.schedule("repeat", scheduler.now(), 1, 5, noop);
    check(scheduler.awaitRuns("repeat", 2, 5000));
    check(scheduler.cancel("repeat"));
    scheduler.close();
    check(scheduler.snapshot("cancel").second == 0);
    check(scheduler.snapshot("repeat").first == "CANCELLED");
    cout << "Priority: high,low\nCancelled before dispatch: true\nFailure isolated: "
            "true\nRecurring stopped: true\nShutdown: closed\n";
}
