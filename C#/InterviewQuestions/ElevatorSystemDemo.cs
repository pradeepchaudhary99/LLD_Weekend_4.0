using System;
using System.Collections.Generic;
using System.Linq;

namespace LLDWeekend4.InterviewQuestions;

public static class ElevatorSystemDemo
{
    private enum Direction
    {
        Up,
        Down,
        Idle
    }

    private interface IObserver
    {
        void Arrived(int id, int floor);
    }

    private sealed class Display : IObserver
    {
        public void Arrived(int id, int floor)
        {
            Console.WriteLine($"Elevator {id} arrived: {floor}");
        }
    }

    private sealed class Elevator
    {
        public readonly int Id;
        public int Floor;
        private Direction direction = Direction.Idle;
        public readonly SortedSet<int> Stops = new();
        private readonly IObserver display;

        public Elevator(int id, IObserver display)
        {
            Id = id;
            this.display = display;
        }

        private void Serve()
        {
            if (Stops.Remove(Floor))
            {
                display.Arrived(Id, Floor);
            }
        }

        public void Tick()
        {
            Serve();
            if (Stops.Count == 0)
            {
                direction = Direction.Idle;
                return;
            }
            List<int> above = Stops.Where(stop => stop > Floor).ToList();
            List<int> below = Stops.Where(stop => stop < Floor).Reverse().ToList();
            int target;
            if (direction == Direction.Down && below.Count > 0)
            {
                target = below[0];
            }
            else if (above.Count > 0)
            {
                target = above[0];
            }
            else
            {
                target = below[0];
            }
            direction = target > Floor ? Direction.Up : Direction.Down;
            Floor += direction == Direction.Up ? 1 : -1;
            Serve();
            if (Stops.Count == 0)
            {
                direction = Direction.Idle;
            }
        }
    }

    private interface ISelectionStrategy
    {
        Elevator Select(List<Elevator> elevators, int floor);
    }

    private sealed class NearestElevatorStrategy : ISelectionStrategy
    {
        public Elevator Select(List<Elevator> elevators, int floor)
        {
            return elevators.OrderBy(elevator => Math.Abs(elevator.Floor - floor))
                .ThenBy(elevator => elevator.Id)
                .First();
        }
    }

    private sealed class RoundRobinStrategy : ISelectionStrategy
    {
        private int next;

        public Elevator Select(List<Elevator> elevators, int floor)
        {
            Elevator selected = elevators[next];
            next = (next + 1) % elevators.Count;
            return selected;
        }
    }

    private sealed class ElevatorSystem
    {
        private readonly int topFloor;
        private readonly List<Elevator> elevators = new();
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
                elevators.Add(new Elevator(id, display));
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
                strategy = replacement ?? throw new ArgumentNullException(nameof(replacement));
            }
        }

        public int ExternalRequest(int floor, Direction direction)
        {
            lock (gate)
            {
                Validate(floor);
                if (direction == Direction.Idle || (floor == 0 && direction == Direction.Down) ||
                    (floor == topFloor && direction == Direction.Up))
                {
                    throw new ArgumentException("Invalid hall direction");
                }
                Elevator selected = strategy.Select(elevators, floor);
                selected.Stops.Add(floor);
                return selected.Id;
            }
        }

        public void InternalRequest(int id, int floor)
        {
            lock (gate)
            {
                Validate(floor);
                if (id < 0 || id >= elevators.Count)
                {
                    throw new ArgumentException("Invalid elevator");
                }
                elevators[id].Stops.Add(floor);
            }
        }

        public void RunUntilIdle()
        {
            lock (gate)
            {
                while (elevators.Any(elevator => elevator.Stops.Count > 0))
                {
                    foreach (Elevator elevator in elevators)
                    {
                        elevator.Tick();
                    }
                }
            }
        }
    }

    public static void Main()
    {
        ElevatorSystem system = new(2, 10, new Display());
        Console.WriteLine($"Selected: {system.ExternalRequest(3, Direction.Up)}");
        system.InternalRequest(0, 5);
        system.InternalRequest(0, 5);
        system.RunUntilIdle();
        Console.WriteLine($"Selected: {system.ExternalRequest(4, Direction.Down)}");
        system.InternalRequest(0, 1);
        system.RunUntilIdle();
        system.SetStrategy(new RoundRobinStrategy());
        Console.WriteLine($"Round robin: {system.ExternalRequest(0, Direction.Up)}");
        Console.WriteLine($"Round robin: {system.ExternalRequest(0, Direction.Up)}");
        system.RunUntilIdle();
        system.RunUntilIdle();
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
