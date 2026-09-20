using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace LLDWeekend4.Concurrency;

public sealed class CustomThreadPool : IDisposable
{
    private readonly BlockingCollection<Action> tasks = new();
    private readonly List<Thread> workers = new();
    private readonly object gate = new();
    private bool closed;
    private int failures;
    public int Failures => failures;

    public CustomThreadPool(int size)
    {
        if (size <= 0)
        {
            throw new ArgumentException("Pool size must be positive");
        }

        for (int index = 0; index < size; index++)
        {
            Thread worker = new(Work);
            workers.Add(worker);
            worker.Start();
        }
    }

    private void Work()
    {
        foreach (Action task in tasks.GetConsumingEnumerable())
        {
            try
            {
                task();
            }
            catch (Exception)
            {
                Interlocked.Increment(ref failures);
            }
        }
    }

    public void Submit(Action task)
    {
        lock (gate)
        {
            if (closed)
            {
                throw new InvalidOperationException("Pool is closed");
            }

            ArgumentNullException.ThrowIfNull(task);
            tasks.Add(task);
        }
    }

    // Lifecycle belongs to the owner, never a worker of this pool.
    public void Dispose()
    {
        lock (gate)
        {
            if (!closed)
            {
                closed = true;
                tasks.CompleteAdding();
            }
        }

        foreach (Thread worker in workers)
        {
            worker.Join();
        }
    }
}

public static class Lessons
{
    public static void Fundamentals()
    {
        int count = 0;
        List<Thread> threads = new();
        for (int worker = 0; worker < 4; worker++)
        {
            Thread thread = new(() =>
                                {
                                    for (int iteration = 0; iteration < 1000; iteration++)
                                    {
                                        Interlocked.Increment(ref count);
                                    }
                                });
            threads.Add(thread);
            thread.Start();
        }

        foreach (Thread thread in threads)
        {
            thread.Join();
        }

        if (count != 4000)
        {
            throw new Exception("Lost updates");
        }

        Console.WriteLine($"Counter: {count}");
    }

    public static void ProducerConsumer()
    {
        using BlockingCollection<int> buffer = new(1);
        int total = 0;
        Thread consumer = new(() =>
                              {
                                  foreach (int value in buffer.GetConsumingEnumerable())
                                  {
                                      total += value;
                                  }
                              });
        consumer.Start();
        for (int value = 1; value <= 10; value++)
        {
            buffer.Add(value);
        }

        buffer.CompleteAdding();
        consumer.Join();
        if (total != 55)
        {
            throw new Exception("Missing items");
        }

        Console.WriteLine($"Consumed sum: {total}");
        try
        {
            using BlockingCollection<int> invalid = new(0);
            throw new Exception("Accepted invalid capacity");
        }
        catch (ArgumentOutOfRangeException)
        {
            Console.WriteLine("Invalid capacity rejected");
        }
    }

    public static void CustomPool()
    {
        using CustomThreadPool pool = new(3);
        int total = 0;
        pool.Submit(() =>
                    {
                        // Deliberately fail one task to demonstrate worker survival.
                        throw new Exception("Expected teaching failure");
                    });
        for (int value = 1; value <= 10; value++)
        {
            int number = value;
            pool.Submit(() => Interlocked.Add(ref total, number));
        }

        pool.Dispose();
        pool.Dispose();
        if (total != 55 || pool.Failures != 1)
        {
            throw new Exception("Tasks lost or failures hidden");
        }

        Console.WriteLine($"Completed sum: {total}");
        Console.WriteLine($"Task failures: {pool.Failures}");
        try
        {
            pool.Submit(() =>
                        {
                            // This task must never be accepted.
                        });
            throw new Exception("Closed pool accepted work");
        }
        catch (InvalidOperationException)
        {
            Console.WriteLine("Submission after shutdown rejected");
        }

        try
        {
            using CustomThreadPool invalid = new(0);
            throw new Exception("Accepted invalid pool size");
        }
        catch (ArgumentException)
        {
            Console.WriteLine("Invalid pool size rejected");
        }
    }

    public static void ExecutorPool()
    {
        // TPL owns its threads. Bound the loop to at most three concurrent operations.
        int total = 0;
        Parallel.ForEach(Enumerable.Range(1, 10),
                         new ParallelOptions { MaxDegreeOfParallelism = 3 },
                         value => Interlocked.Add(ref total, value));
        if (total != 55)
        {
            throw new Exception("Missing results");
        }

        Console.WriteLine($"Executor sum: {total}");
    }
}
