// Class relationships in C#.
//
//   Association - "uses-a" (independent lifetimes)
//   Aggregation - "has-a"  (whole holds references to parts that outlive it)
//   Composition - "owns-a" (whole creates and owns its parts)

using System;
using System.Collections.Generic;
using System.Linq;

namespace LLDWeekend4.Relationships
{
    // ------------------------------------------------------------
    // Association
    // ------------------------------------------------------------
    public class Student
    {
        public string Name { get; }
        public Student(string name) => Name = name;
    }

    public class Payment
    {
        public double Amount { get; }
        public Payment(double amount) => Amount = amount;
    }

    public class Teacher
    {
        public void Teaches(IEnumerable<Student> students)
        {
            foreach (var student in students)
                Console.WriteLine($"Teaching {student.Name}");
        }

        public void GetSalary(Payment payment)
            => Console.WriteLine($"Teacher received {payment.Amount}");
    }

    // ------------------------------------------------------------
    // Aggregation (students exist outside the course)
    // ------------------------------------------------------------
    public class LldCourse
    {
        private readonly List<Student> _students = new();   // references, not owned

        public void Enroll(Student student) => _students.Add(student);

        public int Count => _students.Count;
    }

    // ------------------------------------------------------------
    // Composition (a directory owns its children)
    // ------------------------------------------------------------
    public interface IFileSystemNode
    {
        string Name { get; }
    }

    public class File : IFileSystemNode
    {
        public string Name { get; }
        private readonly string _meta;

        public File(string name, string meta)
        {
            Name = name;
            _meta = meta;
        }
    }

    public class Directory : IFileSystemNode
    {
        public string Name { get; }
        private readonly List<IFileSystemNode> _children = new();  // owned

        public Directory(string name) => Name = name;

        public void AddChild(string name, string meta)
            => _children.Add(new File(name, meta));

        public IReadOnlyList<IFileSystemNode> Children => _children;
    }

    // ------------------------------------------------------------
    // Runner
    // ------------------------------------------------------------
    public static class RelationshipDemo
    {
        public static void Run()
        {
            var students = new List<Student> { new("Neha"), new("Rahul") };

            var teacher = new Teacher();
            teacher.Teaches(students);
            teacher.GetSalary(new Payment(50000));

            var course = new LldCourse();
            foreach (var student in students) course.Enroll(student);
            Console.WriteLine($"Course has {course.Count} students");

            var root = new Directory("root");
            root.AddChild("Main.cs", "text/x-csharp");
            Console.WriteLine(
                $"{root.Name} contains {string.Join(", ", root.Children.Select(c => c.Name))}");
        }
    }
}
