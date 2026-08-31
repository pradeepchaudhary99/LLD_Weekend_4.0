// SOLID Principles Demonstration in ONE FILE (C#).

using System;

namespace LLDWeekend4.Solid
{
    // =======================
    // 1. SINGLE RESPONSIBILITY PRINCIPLE (SRP)
    // =======================

    // BAD: one class doing multiple things
    public class UserBad
    {
        public string Name = "";
        public void SaveToDb() => Console.WriteLine("Saving user to DB");
        public void SendEmail() => Console.WriteLine("Sending email");
    }

    // GOOD: separate responsibilities
    public class User
    {
        public string Name = "";
    }

    public class UserRepository
    {
        public void Save(User user) => Console.WriteLine("Saving user to DB");
    }

    public class EmailService
    {
        public void SendEmail(User user) => Console.WriteLine("Sending email");
    }

    // =======================
    // 2. OPEN CLOSED PRINCIPLE (OCP)
    // =======================

    // BAD: must modify the class for every new type
    public class DiscountCalculatorBad
    {
        public double Calculate(string type) => type switch
        {
            "NEW" => 10,
            "PREMIUM" => 20,
            "DIWALI" => 30,
            _ => 0,
        };
    }

    // GOOD: extend without modifying
    public interface IDiscountStrategy
    {
        double Calculate();
    }

    public class NewCustomerDiscount : IDiscountStrategy
    {
        public double Calculate() => 10;
    }

    public class PremiumCustomerDiscount : IDiscountStrategy
    {
        public double Calculate() => 20;
    }

    public class DiscountCalculator
    {
        public double Calculate(IDiscountStrategy strategy) => strategy.Calculate();
    }

    // =======================
    // 3. LISKOV SUBSTITUTION PRINCIPLE (LSP)
    // =======================

    // BAD: violates substitution
    public class BirdBad
    {
        public virtual void Fly() => Console.WriteLine("Flying");
    }

    public class PenguinBad : BirdBad
    {
        public override void Fly() => throw new NotSupportedException("Can't fly");
    }

    // GOOD: only flying birds expose Fly()
    public interface IBird
    {
        void Eat();
    }

    public interface IFlyingBird : IBird
    {
        void Fly();
    }

    public class Sparrow : IFlyingBird
    {
        public void Eat() => Console.WriteLine("Sparrow eating");
        public void Fly() => Console.WriteLine("Flying");
    }

    // =======================
    // 4. INTERFACE SEGREGATION PRINCIPLE (ISP)
    // =======================

    // BAD: fat interface
    public interface IWorkerBad
    {
        void Work();
        void Eat();
    }

    public class RobotBad : IWorkerBad
    {
        public void Work() => Console.WriteLine("Working");
        public void Eat() => throw new NotSupportedException("Robot doesn't eat");
    }

    // GOOD: split interfaces
    public interface IWorkable
    {
        void Work();
    }

    public interface IEatable
    {
        void Eat();
    }

    public class Human : IWorkable, IEatable
    {
        public void Work() => Console.WriteLine("Working");
        public void Eat() => Console.WriteLine("Eating");
    }

    public class Robot : IWorkable
    {
        public void Work() => Console.WriteLine("Working");
    }

    // =======================
    // 5. DEPENDENCY INVERSION PRINCIPLE (DIP)
    // =======================

    // BAD: high-level depends on low-level
    public class MySqlDatabase
    {
        public void Connect() => Console.WriteLine("Connecting to MySQL");
    }

    public class ApplicationBad
    {
        private readonly MySqlDatabase _db = new();
        public void Start() => _db.Connect();
    }

    // GOOD: depend on abstraction
    public interface IDatabase
    {
        void Connect();
    }

    public class MySql : IDatabase
    {
        public void Connect() => Console.WriteLine("Connecting to MySQL");
    }

    public class PostgreSql : IDatabase
    {
        public void Connect() => Console.WriteLine("Connecting to PostgreSQL");
    }

    public class NoSql : IDatabase
    {
        public void Connect() => Console.WriteLine("Connecting to NoSQL");
    }

    public class Application
    {
        private IDatabase _db;

        public Application(IDatabase db) => _db = db;

        public void SetDb(IDatabase db) => _db = db;

        public void Start() => _db.Connect();
    }

    // =======================
    // RUNNER
    // =======================
    public static class SolidDemo
    {
        public static void Run()
        {
            // SRP
            var user = new User();
            new UserRepository().Save(user);
            new EmailService().SendEmail(user);

            // OCP
            var calc = new DiscountCalculator();
            Console.WriteLine(calc.Calculate(new PremiumCustomerDiscount()));

            // LSP
            IFlyingBird bird = new Sparrow();
            bird.Fly();

            // ISP
            IWorkable robot = new Robot();
            robot.Work();

            // DIP
            var app = new Application(new MySql());
            app.SetDb(new NoSql());
            app.Start();
        }
    }
}
