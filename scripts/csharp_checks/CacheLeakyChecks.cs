using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using LLDWeekend4.InterviewQuestions;

public class CacheLeakyChecks
{
    static void Check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Native check failed");
        }
    }

    public static void Main()
    {
        var cache = new LRU_CacheDemo.LRUCache(8);
        Parallel.For(0, 1000,
                     index =>
                     {
                         cache.Put(index % 16, index);
                         cache.Get((index + 1) % 16);
                     });
        Check(cache.Keys().Count == 8 && cache.Keys().Distinct().Count() == 8);
        var times = new List<long>();
        using var arrived = new ManualResetEventSlim();
        var bucket =
            new LeakyBucketPseduo.LeakyBucket(4, 20,
                                              job =>
                                              {
                                                  times.Add(Stopwatch.GetTimestamp());
                                                  arrived.Set();
                                                  if (job == 1)
                                                  {
                                                      throw new Exception("Expected failure");
                                                  }
                                              });
        try
        {
            bucket.Start();
            Check(bucket.Submit(1));
            Check(arrived.Wait(TimeSpan.FromSeconds(5)));
            Check(bucket.Submit(2));
            Check(bucket.Submit(3));
        }
        finally
        {
            bucket.Dispose();
        }
        bucket.Dispose();
        Check(times.Count == 3 && bucket.Failures() == 1 && !bucket.Submit(4));
        for (int index = 1; index < times.Count; index++)
        {
            Check(Stopwatch.GetElapsedTime(times[index - 1], times[index]).TotalMilliseconds >= 18);
        }
        Console.WriteLine("C# cache/leaky-bucket concurrency and cadence checks passed");
    }
}
