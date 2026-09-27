using System;
using System.Collections.Generic;
using System.Linq;

namespace LLDWeekend4.InterviewQuestions;

public static class ParkingLotDemo
{
    private enum VehicleType
    {
        Bike,
        Car,
        Truck
    }

    private sealed record Vehicle(string Plate, VehicleType Type);

    private sealed class Slot
    {
        public readonly int Level;
        public readonly int Id;
        public readonly VehicleType Type;
        public readonly int ExitDistance;
        public bool Occupied;

        public Slot(int level, int id, VehicleType type, int exitDistance)
        {
            Level = level;
            Id = id;
            Type = type;
            ExitDistance = exitDistance;
        }
    }

    private sealed record Ticket(int Id, Vehicle Vehicle, Slot Slot, long EnteredAt);
    private interface ISlotAssignmentStrategy
    {
        Slot? Select(List<Slot> slots, Vehicle vehicle);
    }

    private sealed class FirstFit : ISlotAssignmentStrategy
    {
        public Slot? Select(List<Slot> slots, Vehicle vehicle)
        {
            return slots.FirstOrDefault(slot => !slot.Occupied && slot.Type == vehicle.Type);
        }
    }

    private sealed class NearestExit : ISlotAssignmentStrategy
    {
        public Slot? Select(List<Slot> slots, Vehicle vehicle)
        {
            return slots.Where(slot => !slot.Occupied && slot.Type == vehicle.Type)
                .OrderBy(slot => slot.ExitDistance)
                .FirstOrDefault();
        }
    }

    private interface IFeesCalculationStrategy
    {
        long Fee(long minutes);
    }

    private sealed class HourlyFee : IFeesCalculationStrategy
    {
        public long Fee(long minutes)
        {
            if (minutes < 0)
            {
                throw new ArgumentException("Exit precedes entry");
            }

            return checked(Math.Max(1, minutes / 60 + (minutes % 60 == 0 ? 0 : 1)) * 50);
        }
    }

    private sealed class ParkingLotManager
    {
        private readonly List<Slot> slots;
        private readonly IFeesCalculationStrategy fees;
        private ISlotAssignmentStrategy strategy = new FirstFit();
        private readonly Dictionary<int, Ticket> tickets = new();
        private readonly HashSet<string> plates = new();
        private readonly object gate = new();
        private int nextId = 1;

        public ParkingLotManager(List<Slot> slots, IFeesCalculationStrategy fees)
        {
            this.slots = new List<Slot>(slots);
            this.fees = fees;
        }

        public void SetStrategy(ISlotAssignmentStrategy replacement)
        {
            lock (gate)
            {
                strategy = replacement;
            }
        }

        public Ticket Park(Vehicle vehicle, long minute)
        {
            lock (gate)
            {
                if (string.IsNullOrWhiteSpace(vehicle.Plate) || !Enum.IsDefined(vehicle.Type))
                {
                    throw new ArgumentException("Invalid vehicle");
                }

                if (minute < 0)
                {
                    throw new ArgumentException("Invalid entry time");
                }

                if (plates.Contains(vehicle.Plate))
                {
                    throw new InvalidOperationException("Vehicle already parked");
                }

                Slot slot = strategy.Select(slots, vehicle) ??
                            throw new InvalidOperationException("No compatible slot");
                Ticket ticket = new(nextId++, vehicle, slot, minute);
                slot.Occupied = true;
                plates.Add(vehicle.Plate);
                tickets.Add(ticket.Id, ticket);
                return ticket;
            }
        }

        public long Exit(int ticketId, long minute, Func<int, long, bool> payment)
        {
            lock (gate)
            {
                if (!tickets.TryGetValue(ticketId, out Ticket? ticket))
                {
                    throw new ArgumentException("Unknown or closed ticket");
                }

                long amount = fees.Fee(checked(minute - ticket.EnteredAt));
                if (!payment(ticketId, amount))
                {
                    throw new InvalidOperationException("Payment failed; vehicle remains parked");
                }

                ticket.Slot.Occupied = false;
                plates.Remove(ticket.Vehicle.Plate);
                tickets.Remove(ticketId);
                return amount;
            }
        }
    }

    private sealed record EntryGate(int Id, ParkingLotManager Manager)
    {
        public Ticket Enter(Vehicle vehicle, long minute)
        {
            return Manager.Park(vehicle, minute);
        }
    }

    private sealed record ExitGate(int Id, ParkingLotManager Manager)
    {
        public long Leave(int ticket, long minute, Func<int, long, bool> payment)
        {
            return Manager.Exit(ticket, minute, payment);
        }
    }

    private static void Reject(Action action)
    {
        try
        {
            action();
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException)
        {
            Console.WriteLine(error.Message);
            return;
        }

        throw new Exception("Expected rejection");
    }

    public static void Main()
    {
        ParkingLotManager lot =
            new(new List<Slot> { new(0, 1, VehicleType.Car, 8), new(0, 2, VehicleType.Bike, 1),
                                 new(1, 3, VehicleType.Truck, 2), new(1, 4, VehicleType.Car, 3) },
                new HourlyFee());
        EntryGate entryA = new(1, lot);
        EntryGate entryB = new(2, lot);
        ExitGate exit = new(1, lot);
        Ticket first = entryA.Enter(new Vehicle("CAR-1", VehicleType.Car), 0);
        Console.WriteLine($"Ticket {first.Id}: {first.Slot.Level}/{first.Slot.Id}");
        lot.SetStrategy(new NearestExit());
        Ticket second = entryB.Enter(new Vehicle("CAR-2", VehicleType.Car), 0);
        Console.WriteLine($"Ticket {second.Id}: {second.Slot.Level}/{second.Slot.Id}");
        Reject(() => entryB.Enter(new Vehicle("CAR-1", VehicleType.Car), 0));
        Reject(() => entryA.Enter(new Vehicle("CAR-3", VehicleType.Car), 0));
        Reject(() => exit.Leave(first.Id, -1, (id, amount) => true));
        Reject(() => exit.Leave(first.Id, 61, (id, amount) => false));
        Reject(() => entryA.Enter(new Vehicle("CAR-3", VehicleType.Car), 61));
        Console.WriteLine($"Paid: {exit.Leave(first.Id, 61, (id, amount) => true)}");
        Reject(() => exit.Leave(first.Id, 61, (id, amount) => true));
        Ticket reused = entryA.Enter(new Vehicle("CAR-3", VehicleType.Car), 62);
        Console.WriteLine($"Reused: {reused.Slot.Level}/{reused.Slot.Id}");
        Console.WriteLine(
            $"Bike slot: {entryA.Enter(new Vehicle("BIKE-1", VehicleType.Bike), 0).Slot.Id}");
        Console.WriteLine(
            $"Truck slot: {entryB.Enter(new Vehicle("TRUCK-1", VehicleType.Truck), 0).Slot.Id}");
    }
}
