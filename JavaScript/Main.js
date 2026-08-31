/*
 * ONE FILE - OOP Concepts in JavaScript (ES2020+)
 *
 * Covers: Class & Object, Encapsulation (# private fields), Abstraction,
 * Inheritance, Polymorphism (overriding + arg-based "overloading"),
 * "Interface" (duck typing / mixin), Composition, Association,
 * Aggregation, static, "final" (Object.freeze), this / super.
 */

'use strict';

// ------------------------------------------------------------
// Abstraction
// ------------------------------------------------------------
class Employee {
  constructor(name, salary) {
    if (new.target === Employee) {
      throw new Error('Employee is abstract');
    }
    this._name = name;
    this._salary = salary;
  }

  work() {
    throw new Error('work() not implemented');
  }

  display() {
    console.log(`${this._name} earns ${this._salary}`);
  }
}

// ------------------------------------------------------------
// Inheritance + Method Overriding (+ Payable "interface")
// ------------------------------------------------------------
class Developer extends Employee {
  constructor(name, salary) {
    super(name, salary); // super keyword
  }

  work() {
    console.log(`${this._name} is writing JavaScript code.`);
  }

  paySalary() {
    console.log(`Salary credited to ${this._name}`);
  }
}

// ------------------------------------------------------------
// Encapsulation (# truly private)
// ------------------------------------------------------------
class BankAccount {
  #balance;

  constructor(balance) {
    this.#balance = balance;
  }

  deposit(amount) {
    this.#balance += amount;
  }

  withdraw(amount) {
    if (amount <= this.#balance) this.#balance -= amount;
  }

  getBalance() {
    return this.#balance;
  }
}

// ------------------------------------------------------------
// Composition (strong Has-A)
// ------------------------------------------------------------
class Engine {
  start() {
    console.log('Engine Started');
  }
}

class Car {
  #engine = new Engine(); // owned by Car

  startCar() {
    this.#engine.start();
    console.log('Car Started');
  }
}

// ------------------------------------------------------------
// Aggregation (weak Has-A)
// ------------------------------------------------------------
class Department {
  constructor(name) {
    this.name = name;
  }
}

class Professor {
  constructor(name, department) {
    this.name = name;
    this.department = department; // shared, not owned
  }

  showDepartment() {
    console.log(`${this.name} belongs to ${this.department.name}`);
  }
}

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
class Course {
  constructor(title) {
    this.title = title;
  }
}

class Student {
  constructor(name) {
    this.name = name;
  }

  attend(course) {
    console.log(`${this.name} attends ${course.title}`);
  }
}

// ------------------------------------------------------------
// Polymorphism ("overloading" via variable args)
// ------------------------------------------------------------
class Calculator {
  add(...nums) {
    return nums.reduce((sum, n) => sum + n, 0);
  }
}

// ------------------------------------------------------------
// static & "final"
// ------------------------------------------------------------
class Company {
  static companyName = 'SYS Titans';

  constructor(id) {
    this.companyId = id;
    Object.freeze(this); // final-like: no further mutation
  }

  static printCompany() {
    console.log(Company.companyName);
  }
}

// ------------------------------------------------------------
// this Keyword
// ------------------------------------------------------------
class Person {
  constructor(name) {
    this.name = name;
  }

  print() {
    console.log(this.name);
  }
}

// ------------------------------------------------------------
// Main
// ------------------------------------------------------------
function main() {
  // Class & Object
  new Person('Pradeep').print();

  // Encapsulation
  const account = new BankAccount(1000);
  account.deposit(500);
  account.withdraw(200);
  console.log(`Balance = ${account.getBalance()}`);

  // Inheritance + Abstraction + Runtime Polymorphism
  const emp = new Developer('Rahul', 120000);
  emp.display();
  emp.work();
  emp.paySalary();

  // "Overloading"
  const calculator = new Calculator();
  console.log(calculator.add(2, 3));
  console.log(calculator.add(2.5, 3.5));
  console.log(calculator.add(1, 2, 3));

  // Composition
  new Car().startCar();

  // Aggregation
  const department = new Department('Computer Science');
  new Professor('Amit', department).showDepartment();

  // Association
  new Student('Neha').attend(new Course('Low Level Design'));

  // Static
  Company.printCompany();

  // "final"
  const company = new Company(101);
  console.log(`Company ID = ${company.companyId}`);
}

main();

module.exports = {
  Employee,
  Developer,
  BankAccount,
  Car,
  Department,
  Professor,
  Student,
  Course,
  Calculator,
  Company,
  Person,
};
