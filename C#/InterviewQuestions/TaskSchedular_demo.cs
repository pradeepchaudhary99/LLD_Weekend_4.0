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
Run from repository root: python3 scripts/run.py "C#" InterviewQuestions/TaskSchedular_demo
See TASK_SCHEDULER_GUIDE.md for prerequisites and contracts.
*/

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;

namespace LLDWeekend4.InterviewQuestions;

public class TaskSchedular_demo
{
    enum Status
    {
        WAITING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    class Task
    {
        public readonly string id;
        public readonly int priority;
        public readonly long sequence;
        public readonly long interval;
        public readonly Action action;
        public long due;
        public int runs;
        public Status status = Status.WAITING;

        public Task(string id, long due, int priority, long sequence, long interval, Action action)
        {
            this.id = id;
            this.due = due;
            this.priority = priority;
            this.sequence = sequence;
            this.interval = interval;
            this.action = action;
        }
    }

    record Snapshot(Status status, int runs);

    class Scheduler : IDisposable
    {
        private readonly object gate = new();
        private readonly long epoch = Stopwatch.GetTimestamp();
        private readonly Dictionary<string, Task> tasks = new();
        private readonly PriorityQueue<Task, (long Due, int Priority, long Sequence)> pending =
            new();
        private readonly List<Thread> workers = new();
        private readonly List<string> dispatched = new();
        private bool started;
        private bool closed;

        public Scheduler(int workerCount)
        {
            Require(workerCount >= 1 && workerCount <= 32, "Invalid worker count");
            for (int index = 0; index < workerCount; index++)
            {
                workers.Add(new Thread(Work) { Name = "scheduler-" + index });
            }
        }

        public long Now()
        {
            return (long)Stopwatch.GetElapsedTime(epoch).TotalMilliseconds;
        }

        public void Schedule(string id, long due, int priority, long interval, Action action)
        {
            lock (gate)
            {

                Require(!closed && id != null && !string.IsNullOrWhiteSpace(id) &&
                            !tasks.ContainsKey(id),
                        "Closed or duplicate/empty ID");
                Require(due >= 0 && due <= 1_000_000_000_000L && priority >= 0 && priority <= 2 &&
                            interval >= 0 && interval <= 1_000_000_000 && action != null &&
                            tasks.Count < 10000,
                        "Invalid task or registry full");
                Task task = new Task(id, due, priority, tasks.Count, interval, action);
                tasks.Add(id, task);
                pending.Enqueue(task, (task.due, task.priority, task.sequence));
                Monitor.PulseAll(gate); // Also wakes workers waiting for a later deadline.
            }
        }

        public void Start()
        {
            lock (gate)
            {

                Require(!started && !closed, "Already started/closed");
                started = true;
                foreach (Thread worker in workers)
                {
                    worker.Start();
                }
            }
        }

        public bool Cancel(string id)
        {
            lock (gate)
            {

                Task task = Task(id);
                if (task.status != Status.WAITING && task.status != Status.RUNNING)
                {
                    return false;
                }
                task.status = Status.CANCELLED;
                Monitor.PulseAll(gate);
                return true;
            }
        }

        private Task Task(string id)
        {
            Require(tasks.ContainsKey(id), "Unknown task");
            return tasks[id];
        }

        public Snapshot Snapshot(string id)
        {
            lock (gate)
            {

                Task task = Task(id);
                return new Snapshot(task.status, task.runs);
            }
        }

        public List<string> Dispatches()
        {
            lock (gate)
            {

                return new List<string>(dispatched);
            }
        }

        public bool AwaitRuns(string id, int count, long timeoutMillis)
        {
            lock (gate)
            {

                Require(count >= 1 && timeoutMillis >= 0 && timeoutMillis <= 60000, "Invalid wait");
                Task task = Task(id);
                long deadline = Now() + timeoutMillis;
                while (task.runs < count)
                {
                    if (task.status == Status.CANCELLED || task.status == Status.FAILED ||
                        task.status == Status.COMPLETED)
                    {
                        return false;
                    }
                    long remaining = deadline - Now();
                    if (remaining <= 0)
                    {
                        return false;
                    }
                    Monitor.Wait(gate, (int)Math.Min(remaining, int.MaxValue));
                }
                return true;
            }
        }

        private Task? Take()
        {
            lock (gate)
            {

                while (!closed)
                {
                    while (pending.Count > 0 && pending.Peek().status == Status.CANCELLED)
                    {
                        pending.Dequeue();
                    }
                    if (pending.Count == 0)
                    {
                        Monitor.Wait(gate);
                        continue;
                    }
                    Task task = pending.Peek();
                    long remaining = task.due - Now();
                    if (remaining > 0)
                    {
                        Monitor.Wait(gate, (int)Math.Min(remaining, int.MaxValue));
                        continue;
                    }
                    pending.Dequeue();
                    task.status = Status.RUNNING;
                    dispatched.Add(task.id);
                    return task;
                }
                return null;
            }
        }

        private void Work()
        {
            while (true)
            {
                Task? task;
                try
                {
                    task = Take();
                }
                catch (ThreadInterruptedException)
                {
                    continue; // Dispose(), not external interruption, owns worker shutdown.
                }
                if (task == null)
                {
                    return;
                }
                bool failed = false;
                try
                {
                    task.action(); // Never run application work under the scheduler lock.
                }
                catch (Exception)
                {
                    failed = true;
                }
                lock (gate)
                {
                    task.runs++;
                    if (task.status != Status.CANCELLED)
                    {
                        if (failed)
                        {
                            task.status = Status.FAILED;
                        }
                        else if (task.interval > 0 && !closed)
                        {
                            task.due =
                                Now() + task.interval; // Fixed delay after completion; no overlap.
                            task.status = Status.WAITING;
                            pending.Enqueue(task, (task.due, task.priority, task.sequence));
                        }
                        else
                        {
                            task.status = Status.COMPLETED;
                        }
                    }
                    Monitor.PulseAll(gate);
                }
            }
        }

        public void Dispose()
        {
            lock (gate)
            {
                Require(!workers.Contains(Thread.CurrentThread),
                        "Worker cannot close its scheduler");
                closed = true;
                foreach (Task task in tasks.Values)
                {
                    if (task.status == Status.WAITING ||
                        (task.status == Status.RUNNING && task.interval > 0))
                    {
                        task.status = Status.CANCELLED;
                    }
                }
                pending.Clear();
                Monitor.PulseAll(gate);
            }
            if (started)
            {
                foreach (Thread worker in workers)
                {
                    worker.Join();
                }
            }
        }
    }

    static void Require([System.Diagnostics.CodeAnalysis.DoesNotReturnIf(false)] bool condition,
                        string message)
    {
        if (!condition)
        {
            throw new ArgumentException(message);
        }
    }

    static void Check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Scheduler contract failed");
        }
    }

    public static void Main(string[] args)
    {
        Scheduler scheduler = new Scheduler(1);
        try
        {
            scheduler.Schedule("low", 0, 2, 0,
                               () =>
                               {});
            scheduler.Schedule("high", 0, 0, 0,
                               () =>
                               {});
            scheduler.Schedule("cancel", 0, 1, 0,
                               () =>
                               { throw new Exception("Cancelled task ran"); });
            Check(scheduler.Cancel("cancel"));
            scheduler.Start();
            Check(scheduler.AwaitRuns("low", 1, 5000));
            Check(scheduler.Dispatches().SequenceEqual(new[] { "high", "low" }));
            scheduler.Schedule("failure", scheduler.Now(), 1, 0,
                               () =>
                               { throw new InvalidOperationException("Expected"); });
            Check(scheduler.AwaitRuns("failure", 1, 5000));
            Check(scheduler.Snapshot("failure").status == Status.FAILED);
            scheduler.Schedule("repeat", scheduler.Now(), 1, 5,
                               () =>
                               {});
            Check(scheduler.AwaitRuns("repeat", 2, 5000));
            Check(scheduler.Cancel("repeat"));
        }
        finally
        {
            scheduler.Dispose();
        }
        Check(scheduler.Snapshot("cancel").runs == 0);
        Check(scheduler.Snapshot("repeat").status == Status.CANCELLED);
        Console.WriteLine("Priority: high,low");
        Console.WriteLine("Cancelled before dispatch: true");
        Console.WriteLine("Failure isolated: true");
        Console.WriteLine("Recurring stopped: true");
        Console.WriteLine("Shutdown: closed");
    }
}
