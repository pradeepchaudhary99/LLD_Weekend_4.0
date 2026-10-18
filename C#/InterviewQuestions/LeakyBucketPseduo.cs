/*
 * INTERVIEW DESIGN NOTES - Queued Leaky Bucket
 *
 * Functional requirements:
 * - Accept requests into a bounded FIFO queue; reject overflow and submissions after close.
 * - One worker attempts jobs with a minimum interval between scheduled attempt starts.
 * - Wake an idle worker on submission; count ordinary handler failures and continue.
 * - Close rejects new work, drains accepted work, and waits for the worker to finish.
 *
 * Non-functional requirements:
 * - Protect admission and queue state; execute callbacks outside queue locks.
 * - Use monotonic time, bounded waiting capacity and no idle busy-spinning.
 * - Do not accumulate catch-up credits after slow callbacks or scheduler delays.
 *
 * Core entities and responsibilities:
 * - LeakyBucket: capacity, waiting queue, worker lifecycle and cumulative failure count.
 * - Request: integer job ID; handler processes it synchronously (awaitable in JS/TS).
 * - Worker: thread/goroutine in native editions; async task on the Node event loop.
 * - Monotonic clock: determines remaining delay since the previous attempt started.
 *
 * Operations and flow:
 * - submit -> atomic closed/capacity check -> enqueue -> signal -> return acceptance.
 * - start -> wait for work -> wait remaining interval -> dequeue -> attempt handler.
 * - close before start starts a draining worker; repeated close waits safely again.
 * - Ordinary handler failure increments failures; the job is not retried or requeued.
 *
 * Design choices and invariants:
 * - Capacity counts waiting jobs, excluding the one executing; total outstanding <= capacity+1.
 * - First attempt can start immediately. Slow work lowers throughput rather than causing bursts.
 * - Callbacks are serial; start twice is invalid; submissions may be queued before start.
 * - Close is owner-only: handlers must not call/await close on their own bucket.
 * - Handlers must finish; no forced cancellation or shutdown timeout is promised.
 *
 * Assumptions and scope:
 * - Interval is an integer 1..60000 milliseconds. This is a queue/shaper, not the
 *   token-bucket or sliding-window admission algorithms in RateLimitingDemo.
 * - Native threads and Node timers are not real-time; OS scheduling can delay attempts.
 * - No durable queue, retry policy, distributed ordering, priorities or delivery guarantee
 *   across process crashes. Fatal runtime/process failures are outside the contract.
 * - C# uses Dispose for close; C++ also drains in its destructor; Go returns handler errors
 *   and counts recovered panics; JS/TS await rejected promises and count them as failures.
 * - Native FIFO operations are O(1); JS/TS Array.shift is O(queue length) in this small demo.
 *
 * Edge cases and discussion:
 * - Full queue before start, close before start, idle wakeup, failure followed by later work,
 *   repeated close, invalid configuration, cadence and concurrent producers during shutdown.
 * - Discuss deadlines, backpressure, cancellation, durable delivery and overload policies.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "C#" InterviewQuestions/LeakyBucketPseduo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;

namespace LLDWeekend4.InterviewQuestions;

public class LeakyBucketPseduo
{
    internal class LeakyBucket : IDisposable
    {
        private readonly object gate = new();
        private readonly int capacity;
        private readonly int intervalMs;
        private readonly Action<int> handler;
        private readonly Queue<int> queue = new();
        private Thread? worker;
        private bool closed;
        private int failures;

        public LeakyBucket(int capacity, int intervalMs, Action<int> handler)
        {
            if (capacity <= 0 || intervalMs <= 0 || intervalMs > 60000 || handler == null)
            {
                throw new ArgumentException("Invalid configuration");
            }
            this.capacity = capacity;
            this.intervalMs = intervalMs;
            this.handler = handler;
        }

        public bool Submit(int request)
        {
            lock (gate)
            {
                if (closed || queue.Count >= capacity)
                {
                    return false;
                }
                queue.Enqueue(request);
                Monitor.PulseAll(gate);
                return true;
            }
        }

        private void StartWorker()
        {
            worker = new Thread(Run);
            worker.Start();
        }

        public void Start()
        {
            lock (gate)
            {
                if (worker != null || closed)
                {
                    throw new InvalidOperationException("Already started/closed");
                }
                StartWorker();
            }
        }

        private void Run()
        {
            long? lastStart = null;
            while (true)
            {
                int request;
                lock (gate)
                {
                    while (queue.Count == 0 && !closed)
                    {
                        Monitor.Wait(gate);
                    }
                    if (queue.Count == 0)
                    {
                        return;
                    }
                    if (lastStart.HasValue)
                    {
                        double remaining =
                            intervalMs -
                            Stopwatch.GetElapsedTime(lastStart.Value).TotalMilliseconds;
                        while (remaining > 0)
                        {
                            Monitor.Wait(gate, (int)Math.Ceiling(remaining));
                            remaining = intervalMs -
                                        Stopwatch.GetElapsedTime(lastStart.Value).TotalMilliseconds;
                        }
                    }
                    request = queue.Dequeue();
                }
                lastStart = Stopwatch.GetTimestamp();
                try
                {
                    handler(request);
                }
                catch (Exception)
                {
                    lock (gate)
                    {
                        failures++;
                    }
                }
            }
        }

        public int Failures()
        {
            lock (gate)
            {
                return failures;
            }
        }

        public void Dispose()
        {
            Thread thread;
            lock (gate)
            {
                if (Thread.CurrentThread == worker)
                {
                    throw new InvalidOperationException("Handler cannot close its worker");
                }
                closed = true;
                if (worker == null)
                {
                    StartWorker();
                }
                thread = worker!;
                Monitor.PulseAll(gate);
            }
            thread.Join();
        }
    }

    static void Check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Queue contract failed");
        }
    }

    public static void Main()
    {
        var attempted = new List<int>();
        var bucket = new LeakyBucket(2, 1,
                                     request =>
                                     {
                                         attempted.Add(request);
                                         if (request == 1)
                                         {
                                             throw new InvalidOperationException(
                                                 "Simulated handler failure");
                                         }
                                     });
        Check(bucket.Submit(1));
        Check(bucket.Submit(2));
        Check(!bucket.Submit(3));
        bucket.Start();
        bucket.Dispose();
        bucket.Dispose();
        Check(!bucket.Submit(4));
        Check(attempted.SequenceEqual(new[] { 1, 2 }) && bucket.Failures() == 1);
        var beforeStart = new LeakyBucket(1, 1,
                                          request =>
                                          {});
        Check(beforeStart.Submit(7));
        beforeStart.Dispose();
        bool rejected = false;
        try
        {
            new LeakyBucket(0, 1,
                            request =>
                            {});
        }
        catch (ArgumentException)
        {
            rejected = true;
        }
        Check(rejected);
        Console.WriteLine("Overflow rejected: true");
        Console.WriteLine("FIFO attempts: 1,2");
        Console.WriteLine("Handler failures: 1");
        Console.WriteLine("Shutdown drained: true");
    }
}
