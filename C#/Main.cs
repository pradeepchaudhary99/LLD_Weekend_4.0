// ONE FILE - OOP Concepts in C#
//
// Covers: Class & Object, Encapsulation, Abstraction, Inheritance,
// Polymorphism (overloading + overriding), Interface, Composition,
// Association, Aggregation, static, readonly (final-like), this / base.

using System;
using LLDWeekend4.Solid;
using LLDWeekend4.Relationships;

namespace LLDWeekend4.Oop
{
    // ------------------------------------------------------------
    // Abstraction
    // ------------------------------------------------------------
    public abstract class Employee
    {
        protected string Name;
        protected double Salary;

        protected Employee(string name, double salary)
        {
            Name = name;
            Salary = salary;
        }

        public abstract void Work();          // abstract method

        public void Display()                 // concrete method
            => Console.WriteLine($"{Name} earns {Salary}");
    }

    // ------------------------------------------------------------
    // Interface
    // ------------------------------------------------------------
    public interface IPayable
    {
        void PaySalary();
    }

    // ------------------------------------------------------------
    // Inheritance + Method Overriding
    // ------------------------------------------------------------
    public class Developer : Employee, IPayable
    {
        public Developer(string name, double salary) : base(name, salary) { }

        public override void Work()
            => Console.WriteLine($"{Name} is writing C# code.");

        public void PaySalary()
            => Console.WriteLine($"Salary credited to {Name}");
    }

    // ------------------------------------------------------------
    // Encapsulation
    // ------------------------------------------------------------
    public class BankAccount
    {
        private double _balance;   // hidden data

        public BankAccount(double balance) => _balance = balance;

        public void Deposit(double amount) => _balance += amount;

        public void Withdraw(double amount)
        {
            if (amount <= _balance) _balance -= amount;
        }

        public double GetBalance() => _balance;
    }

    // ------------------------------------------------------------
    // Composition (strong Has-A)
    // ------------------------------------------------------------
    public class Engine
    {
        public void Start() => Console.WriteLine("Engine Started");
    }

    public class Car
    {
        private readonly Engine _engine = new();  // owned by Car

        public void StartCar()
        {
            _engine.Start();
            Console.WriteLine("Car Started");
        }
    }

    // ------------------------------------------------------------
    // Aggregation (weak Has-A)
    // ------------------------------------------------------------
    public class Department
    {
        public string Name { get; }
        public Department(string name) => Name = name;
    }

    public class Professor
    {
        private readonly string _name;
        private readonly Department _department;   // shared, not owned

        public Professor(string name, Department department)
        {
            _name = name;
            _department = department;
        }

        public void ShowDepartment()
            => Console.WriteLine($"{_name} belongs to {_department.Name}");
    }

    // ------------------------------------------------------------
    // Association
    // ------------------------------------------------------------
    public class Course
    {
        public string Title { get; }
        public Course(string title) => Title = title;
    }

    public class Student
    {
        private readonly string _name;
        public Student(string name) => _name = name;

        public void Attend(Course course)
            => Console.WriteLine($"{_name} attends {course.Title}");
    }

    // ------------------------------------------------------------
    // Polymorphism (Method Overloading)
    // ------------------------------------------------------------
    public class Calculator
    {
        public int Add(int a, int b) => a + b;
        public double Add(double a, double b) => a + b;
        public int Add(int a, int b, int c) => a + b + c;
    }

    // ------------------------------------------------------------
    // static & readonly (final-like)
    // ------------------------------------------------------------
    public class Company
    {
        public static string CompanyName = "SYS Titans";
        public readonly int CompanyId;

        public Company(int id) => CompanyId = id;

        public static void PrintCompany() => Console.WriteLine(CompanyName);
    }

    // ------------------------------------------------------------
    // this Keyword
    // ------------------------------------------------------------
    public class Person
    {
        private readonly string name;

        public Person(string name) => this.name = name;

        public void Print() => Console.WriteLine(this.name);
    }

    // ------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------
    public static class Program
    {
        public static void Main()
        {
            // Class & Object
            var person = new Person("Pradeep");
            person.Print();

            // Encapsulation
            var account = new BankAccount(1000);
            account.Deposit(500);
            account.Withdraw(200);
            Console.WriteLine($"Balance = {account.GetBalance()}");

            // Inheritance + Abstraction + Runtime Polymorphism
            Employee emp = new Developer("Rahul", 120000);
            emp.Display();
            emp.Work();

            ((IPayable)emp).PaySalary();

            // Method Overloading
            var calculator = new Calculator();
            Console.WriteLine(calculator.Add(2, 3));
            Console.WriteLine(calculator.Add(2.5, 3.5));
            Console.WriteLine(calculator.Add(1, 2, 3));

            // Composition
            new Car().StartCar();

            // Aggregation
            var department = new Department("Computer Science");
            new Professor("Amit", department).ShowDepartment();

            // Association
            new Student("Neha").Attend(new Course("Low Level Design"));

            // Static
            Company.PrintCompany();

            // readonly member
            var company = new Company(101);
            Console.WriteLine($"Company ID = {company.CompanyId}");

            Console.WriteLine("\n--- SOLID ---");
            SolidDemo.Run();

            Console.WriteLine("\n--- Relationships ---");
            RelationshipDemo.Run();
        }
    }
}
