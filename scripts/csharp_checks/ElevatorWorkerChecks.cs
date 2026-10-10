using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using static LLDWeekend4.InterviewQuestions.ElevatorSystemDemo;

public static class ElevatorWorkerChecks
{
    private sealed class Display : IObserver
    {
        public readonly ManualResetEventSlim First = new();
        public readonly ManualResetEventSlim Release = new();
        public readonly ManualResetEventSlim Second = new();
        public readonly List<int> Stops = new();

        public void Arrived(int id, int floor)
        {
            if (id == 0)
            {
                Stops.Add(floor);
                if (floor == 3)
                {
                    First.Set();
                    if (!Release.Wait(TimeSpan.FromSeconds(5)))
                    {
                        throw new InvalidOperationException("Release timed out");
                    }
                }
            }
            else
            {
                Second.Set();
            }
        }
    }

    public static void Main()
    {
        var display = new Display();
        using var system = new ElevatorSystem(2, 10, display);
        try
        {
            system.InternalRequest(0, 3);
            system.InternalRequest(0, 5);
            system.Start();
            if (!display.First.Wait(TimeSpan.FromSeconds(2)))
            {
                throw new Exception("First car did not start");
            }
            system.InternalRequest(0, 4);
            system.InternalRequest(0, 1);
            system.InternalRequest(1, 2);
            if (!display.Second.Wait(TimeSpan.FromSeconds(2)))
            {
                throw new Exception("Second car blocked behind first");
            }
        }
        finally
        {
            display.Release.Set();
        }
        system.RunUntilIdle();
        if (!display.Stops.SequenceEqual(new[] { 3, 4, 5, 1 }))
        {
            throw new Exception("Incorrect sweep/reversal");
        }
        system.Dispose();
        bool rejected = false;
        try
        {
            system.InternalRequest(0, 1);
        }
        catch (InvalidOperationException)
        {
            rejected = true;
        }
        if (!rejected)
        {
            throw new Exception("Accepted work after shutdown");
        }
        Console.WriteLine("C# independent elevator checks passed");
    }
}
