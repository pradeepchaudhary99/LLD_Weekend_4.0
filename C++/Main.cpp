// ONE FILE - OOP Concepts in C++ (C++17)
//
// Covers: Class & Object, Encapsulation, Abstraction, Inheritance,
// Polymorphism (overloading + overriding), Interface (pure virtual struct),
// Composition, Association, Aggregation, Static, const (final-like), this.

#include <iostream>
#include <memory>
#include <string>
#include <utility>

// ------------------------------------------------------------
// Abstraction
// ------------------------------------------------------------
class Employee {
protected:
    std::string name_;
    double salary_;

public:
    Employee(std::string name, double salary)
        : name_(std::move(name)), salary_(salary) {}
    virtual ~Employee() = default;

    virtual void work() = 0;  // abstract method

    void display() const {  // concrete method
        std::cout << name_ << " earns " << salary_ << '\n';
    }
};

// ------------------------------------------------------------
// Interface
// ------------------------------------------------------------
struct Payable {
    virtual ~Payable() = default;
    virtual void paySalary() = 0;
};

// ------------------------------------------------------------
// Inheritance + Method Overriding
// ------------------------------------------------------------
class Developer : public Employee, public Payable {
public:
    Developer(std::string name, double salary)
        : Employee(std::move(name), salary) {}

    void work() override {
        std::cout << name_ << " is writing C++ code.\n";
    }

    void paySalary() override {
        std::cout << "Salary credited to " << name_ << '\n';
    }
};

// ------------------------------------------------------------
// Encapsulation
// ------------------------------------------------------------
class BankAccount {
    double balance_;  // hidden data

public:
    explicit BankAccount(double balance) : balance_(balance) {}

    void deposit(double amount) { balance_ += amount; }

    void withdraw(double amount) {
        if (amount <= balance_) balance_ -= amount;
    }

    double getBalance() const { return balance_; }
};

// ------------------------------------------------------------
// Composition (strong Has-A: Engine lifetime tied to Car)
// ------------------------------------------------------------
class Engine {
public:
    void start() const { std::cout << "Engine Started\n"; }
};

class Car {
    Engine engine_;  // owned by value

public:
    void startCar() const {
        engine_.start();
        std::cout << "Car Started\n";
    }
};

// ------------------------------------------------------------
// Aggregation (weak Has-A: Department outlives Professor)
// ------------------------------------------------------------
class Department {
public:
    std::string name;
    explicit Department(std::string name) : name(std::move(name)) {}
};

class Professor {
    std::string name_;
    std::shared_ptr<Department> department_;

public:
    Professor(std::string name, std::shared_ptr<Department> department)
        : name_(std::move(name)), department_(std::move(department)) {}

    void showDepartment() const {
        std::cout << name_ << " belongs to " << department_->name << '\n';
    }
};

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
class Course {
public:
    std::string title;
    explicit Course(std::string title) : title(std::move(title)) {}
};

class Student {
    std::string name_;

public:
    explicit Student(std::string name) : name_(std::move(name)) {}

    void attend(const Course& course) const {
        std::cout << name_ << " attends " << course.title << '\n';
    }
};

// ------------------------------------------------------------
// Polymorphism (Method Overloading)
// ------------------------------------------------------------
class Calculator {
public:
    int add(int a, int b) { return a + b; }
    double add(double a, double b) { return a + b; }
    int add(int a, int b, int c) { return a + b + c; }
};

// ------------------------------------------------------------
// Static & const (final-like)
// ------------------------------------------------------------
class Company {
public:
    static std::string companyName;
    const int companyId;

    explicit Company(int id) : companyId(id) {}

    static void printCompany() { std::cout << companyName << '\n'; }
};

std::string Company::companyName = "SYS Titans";

// ------------------------------------------------------------
// this Keyword
// ------------------------------------------------------------
class Person {
    std::string name;

public:
    explicit Person(std::string name) { this->name = std::move(name); }

    void print() const { std::cout << this->name << '\n'; }
};

// ------------------------------------------------------------
// Main
// ------------------------------------------------------------
int main() {
    // Class & Object
    Person person("Pradeep");
    person.print();

    // Encapsulation
    BankAccount account(1000);
    account.deposit(500);
    account.withdraw(200);
    std::cout << "Balance = " << account.getBalance() << '\n';

    // Inheritance + Abstraction + Runtime Polymorphism
    std::unique_ptr<Employee> emp = std::make_unique<Developer>("Rahul", 120000);
    emp->display();
    emp->work();

    if (auto* payable = dynamic_cast<Payable*>(emp.get())) {
        payable->paySalary();
    }

    // Method Overloading
    Calculator calculator;
    std::cout << calculator.add(2, 3) << '\n';
    std::cout << calculator.add(2.5, 3.5) << '\n';
    std::cout << calculator.add(1, 2, 3) << '\n';

    // Composition
    Car car;
    car.startCar();

    // Aggregation
    auto department = std::make_shared<Department>("Computer Science");
    Professor professor("Amit", department);
    professor.showDepartment();

    // Association
    Student student("Neha");
    Course course("Low Level Design");
    student.attend(course);

    // Static
    Company::printCompany();

    // const member
    Company company(101);
    std::cout << "Company ID = " << company.companyId << '\n';

    return 0;
}
