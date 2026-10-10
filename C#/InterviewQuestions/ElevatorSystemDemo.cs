using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;

namespace LLDWeekend4.InterviewQuestions;

public static class ElevatorSystemDemo
{
    public enum Direction
    {
        Up,
        Down,
        Idle
    }

    public interface IObserver
    {
        void Arrived(int id, int floor);
    }

    public sealed class Display : IObserver
    {
        private readonly object gate = new();
        private readonly List<(int Id, int Floor)> arrivals = new();

        public void Arrived(int id, int floor)
        {
            lock (gate)
            {
                arrivals.Add((id, floor));
            }
        }

        public void PrintArrivals()
        {
            lock (gate)
            {
                foreach (var arrival in arrivals.OrderBy(arrival => arrival.Id))
                {
                    Console.WriteLine($"Elevator {arrival.Id} arrived: {arrival.Floor}");
                }
                arrivals.Clear();
            }
        }
    }

    public sealed class Elevator
    {
        public readonly int Id;
        private int floor;
        private Direction direction = Direction.Idle;
        private readonly SortedSet<int> stops = new();
        private readonly IObserver display;
        private readonly object gate = new();
        private bool shutdown;
        private bool notifying;
        private Exception? failure;

        public Elevator(int id, IObserver display)
        {
            Id = id;
            this.display = display ?? throw new ArgumentNullException(nameof(display));
        }

        public int Floor
        {
            get {
                lock (gate)
                {
                    return floor;
                }
            }
        }

        private void CheckFailure()
        {
            if (failure != null)
            {
                throw new InvalidOperationException($"Elevator {Id} failed", failure);
            }
        }

        public void AddStop(int requestedFloor)
        {
            lock (gate)
            {
                CheckFailure();
                if (shutdown)
                {
                    throw new InvalidOperationException("Elevator shutting down");
                }
                stops.Add(requestedFloor);
                Monitor.PulseAll(gate);
            }
        }

        public bool IsIdle()
        {
            lock (gate)
            {
                CheckFailure();
                return stops.Count == 0 && !notifying;
            }
        }

        public void AwaitIdle()
        {
            lock (gate)
            {
                while (!IsIdle())
                {
                    Monitor.Wait(gate);
                }
            }
        }

        public void Shutdown()
        {
            lock (gate)
            {
                shutdown = true;
                Monitor.PulseAll(gate);
            }
        }

        public void Run()
        {
            try
            {
                while (true)
                {
                    int? arrived = null;
                    lock (gate)
                    {
                        while (stops.Count == 0 && !shutdown)
                        {
                            Monitor.Wait(gate);
                        }
                        if (stops.Count == 0)
                        {
                            return;
                        }
                        if (stops.Remove(floor))
                        {
                            arrived = floor;
                        }
                        else
                        {
                            var above = stops.Where(stop => stop > floor).ToList();
                            var below = stops.Where(stop => stop < floor).Reverse().ToList();
                            int target = direction == Direction.Down && below.Count > 0 ? below[0]
                                         : above.Count > 0                              ? above[0]
                                                                                        : below[0];
                            direction = target > floor ? Direction.Up : Direction.Down;
                            floor += direction == Direction.Up ? 1 : -1;
                            if (stops.Remove(floor))
                            {
                                arrived = floor;
                            }
                        }
                        if (stops.Count == 0)
                        {
                            direction = Direction.Idle;
                        }
                        notifying = arrived.HasValue;
                    }
                    if (arrived.HasValue)
                    {
                        display.Arrived(Id, arrived.Value);
                    }
                    lock (gate)
                    {
                        notifying = false;
                        Monitor.PulseAll(gate);
                    }
                }
            }
            catch (Exception error)
            {
                lock (gate)
                {
                    failure = error;
                }
            }
            finally
            {
                lock (gate)
                {
                    shutdown = true;
                    notifying = false;
                    Monitor.PulseAll(gate);
                }
            }
        }
    }

    public interface ISelectionStrategy
    {
        Elevator Select(List<Elevator> elevators, int floor);
    }

    public sealed class NearestElevatorStrategy : ISelectionStrategy
    {
        public Elevator Select(List<Elevator> elevators, int floor)
        {
            return elevators.OrderBy(elevator => Math.Abs(elevator.Floor - floor))
                .ThenBy(elevator => elevator.Id)
                .First();
        }
    }

    public sealed class RoundRobinStrategy : ISelectionStrategy
    {
        private int next;

        public Elevator Select(List<Elevator> elevators, int floor)
        {
            Elevator selected = elevators[next];
            next = (next + 1) % elevators.Count;
            return selected;
        }
    }

    public sealed class ElevatorSystem : IDisposable
    {
        private readonly int topFloor;
        private readonly List<Elevator> elevators = new();
        private readonly List<Thread> workers = new();
        private bool started;
        private bool closed;
        private ISelectionStrategy strategy = new NearestElevatorStrategy();
        private readonly object gate = new();

        public ElevatorSystem(int count, int topFloor, IObserver display)
        {
            if (count <= 0 || topFloor < 1)
            {
                throw new ArgumentException("Invalid building");
            }
            this.topFloor = topFloor;
            for (int id = 0; id < count; id++)
            {
                var elevator = new Elevator(id, display);
                elevators.Add(elevator);
                workers.Add(new Thread(elevator.Run) { Name = $"elevator-{id}" });
            }
        }

        private void Validate(int floor)
        {
            if (floor < 0 || floor > topFloor)
            {
                throw new ArgumentException("Invalid floor");
            }
        }

        public void SetStrategy(ISelectionStrategy replacement)
        {
            lock (gate)
            {
                EnsureOpen();
                strategy = replacement ?? throw new ArgumentNullException(nameof(replacement));
            }
        }

        public int ExternalRequest(int floor, Direction direction)
        {
            lock (gate)
            {
                EnsureOpen();
                Validate(floor);
                if (direction == Direction.Idle || (floor == 0 && direction == Direction.Down) ||
                    (floor == topFloor && direction == Direction.Up))
                {
                    throw new ArgumentException("Invalid hall direction");
                }
                Elevator selected = strategy.Select(elevators, floor);
                selected.AddStop(floor);
                return selected.Id;
            }
        }

        public void InternalRequest(int id, int floor)
        {
            lock (gate)
            {
                EnsureOpen();
                Validate(floor);
                if (id < 0 || id >= elevators.Count)
                {
                    throw new ArgumentException("Invalid elevator");
                }
                elevators[id].AddStop(floor);
            }
        }

        private void EnsureOpen()
        {
            if (closed)
            {
                throw new InvalidOperationException("System closed");
            }
        }

        private void StartWorkers()
        {
            if (!started)
            {
                started = true;
                foreach (var worker in workers)
                {
                    worker.Start();
                }
            }
        }

        public void Start()
        {
            lock (gate)
            {
                EnsureOpen();
                StartWorkers();
            }
        }

        private void RejectWorkerWait()
        {
            if (workers.Contains(Thread.CurrentThread))
            {
                throw new InvalidOperationException("Worker cannot wait for itself");
            }
        }

        public void RunUntilIdle()
        {
            RejectWorkerWait();
            Start();
            while (true)
            {
                foreach (var elevator in elevators)
                {
                    elevator.AwaitIdle();
                }
                lock (gate)
                {
                    if (elevators.All(elevator => elevator.IsIdle()))
                    {
                        return;
                    }
                }
            }
        }

        public void Dispose()
        {
            RejectWorkerWait();
            lock (gate)
            {
                closed = true;
                StartWorkers();
                foreach (var elevator in elevators)
                {
                    elevator.Shutdown();
                }
            }
            foreach (var worker in workers)
            {
                worker.Join();
            }
            foreach (var elevator in elevators)
            {
                elevator.IsIdle();
            }
        }
    }

    public static void Main()
    {
        var display = new Display();
        using ElevatorSystem system = new(2, 10, display);
        Console.WriteLine($"Selected: {system.ExternalRequest(3, Direction.Up)}");
        system.InternalRequest(0, 5);
        system.InternalRequest(0, 5);
        system.RunUntilIdle();
        display.PrintArrivals();
        Console.WriteLine($"Selected: {system.ExternalRequest(4, Direction.Down)}");
        system.InternalRequest(0, 1);
        system.RunUntilIdle();
        display.PrintArrivals();
        system.SetStrategy(new RoundRobinStrategy());
        Console.WriteLine($"Round robin: {system.ExternalRequest(0, Direction.Up)}");
        Console.WriteLine($"Round robin: {system.ExternalRequest(0, Direction.Up)}");
        system.RunUntilIdle();
        display.PrintArrivals();
        system.RunUntilIdle();
        display.PrintArrivals();
        try
        {
            system.InternalRequest(0, 11);
            throw new Exception("Invalid floor accepted");
        }
        catch (ArgumentException)
        {
            Console.WriteLine("Invalid floor rejected");
        }
        try
        {
            system.ExternalRequest(0, Direction.Down);
            throw new Exception("Invalid direction accepted");
        }
        catch (ArgumentException)
        {
            Console.WriteLine("Invalid direction rejected");
        }
    }
}
