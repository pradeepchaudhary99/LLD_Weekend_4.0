using System;
using System.Collections.Generic;
using System.Threading;

namespace LLDWeekend4.Concurrency;

public static class ReadWriteLockDemo
{
    private sealed class Cache : IDisposable
    {
        private readonly Dictionary<int, int> values = new();
        private readonly ReaderWriterLockSlim gate = new();

        public int? Read(int key)
        {
            gate.EnterReadLock();
            try
            {
                return values.TryGetValue(key, out int value) ? value : null;
            }
            finally
            {
                gate.ExitReadLock();
            }
        }

        public void Write(int key, int value)
        {
            gate.EnterWriteLock();
            try
            {
                values[key] = value;
            }
            finally
            {
                gate.ExitWriteLock();
            }
        }

        public void Dispose()
        {
            gate.Dispose();
        }
    }

    public static void Main()
    {
        using Cache cache = new();
        List<Thread> writers = new();
        for (int key = 0; key < 4; key++)
        {
            int capturedKey = key;
            Thread writer = new(() => cache.Write(capturedKey, capturedKey * 10));
            writers.Add(writer);
            writer.Start();
        }
        foreach (Thread writer in writers)
        {
            writer.Join();
        }
        int total = 0;
        for (int key = 0; key < 4; key++)
        {
            total += cache.Read(key) ?? throw new Exception("Missing key");
        }
        if (total != 60 || cache.Read(99) != null)
        {
            throw new Exception("Cache contents incorrect");
        }
        Console.WriteLine($"Cache sum: {total}");
        Console.WriteLine("Missing: true");
        cache.Write(0, -1);
        Console.WriteLine($"Stored negative: {cache.Read(0)}");
        cache.Write(0, 7);
        Console.WriteLine($"Updated: {cache.Read(0)}");
    }
}
