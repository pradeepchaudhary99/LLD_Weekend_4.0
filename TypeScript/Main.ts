/*
 * ONE FILE - OOP Concepts in TypeScript
 *
 * Covers: Class & Object, Encapsulation, Abstraction (abstract class),
 * Interface, Inheritance, Polymorphism (overriding + overload signatures),
 * Composition, Association, Aggregation, static, readonly (final-like),
 * this / super.
 */

// ------------------------------------------------------------
// Abstraction
// ------------------------------------------------------------
abstract class Employee {
  protected constructor(
    protected name: string,
    protected salary: number,
  ) {}

  abstract work(): void;

  display(): void {
    console.log(`${this.name} earns ${this.salary}`);
  }
}

// ------------------------------------------------------------
// Interface
// ------------------------------------------------------------
interface Payable {
  paySalary(): void;
}

// ------------------------------------------------------------
// Inheritance + Method Overriding
// ------------------------------------------------------------
class Developer extends Employee implements Payable {
  constructor(name: string, salary: number) {
    super(name, salary); // super keyword
  }

  work(): void {
    console.log(`${this.name} is writing TypeScript code.`);
  }

  paySalary(): void {
    console.log(`Salary credited to ${this.name}`);
  }
}

// ------------------------------------------------------------
// Encapsulation
// ------------------------------------------------------------
class BankAccount {
  #balance: number;

  constructor(balance: number) {
    this.#balance = balance;
  }

  deposit(amount: number): void {
    this.#balance += amount;
  }

  withdraw(amount: number): void {
    if (amount <= this.#balance) this.#balance -= amount;
  }

  getBalance(): number {
    return this.#balance;
  }
}

// ------------------------------------------------------------
// Composition (strong Has-A)
// ------------------------------------------------------------
class Engine {
  start(): void {
    console.log('Engine Started');
  }
}

class Car {
  #engine = new Engine(); // owned by Car

  startCar(): void {
    this.#engine.start();
    console.log('Car Started');
  }
}

// ------------------------------------------------------------
// Aggregation (weak Has-A)
// ------------------------------------------------------------
class Department {
  constructor(public readonly name: string) {}
}

class Professor {
  constructor(
    private readonly name: string,
    private readonly department: Department, // shared, not owned
  ) {}

  showDepartment(): void {
    console.log(`${this.name} belongs to ${this.department.name}`);
  }
}

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
class Course {
  constructor(public readonly title: string) {}
}

class Student {
  constructor(private readonly name: string) {}

  attend(course: Course): void {
    console.log(`${this.name} attends ${course.title}`);
  }
}

// ------------------------------------------------------------
// Polymorphism (Method Overloading via overload signatures)
// ------------------------------------------------------------
class Calculator {
  add(a: number, b: number): number;
  add(a: number, b: number, c: number): number;
  add(a: number, b: number, c = 0): number {
    return a + b + c;
  }
}

// ------------------------------------------------------------
// static & readonly (final-like)
// ------------------------------------------------------------
class Company {
  static companyName = 'SYS Titans';

  constructor(public readonly companyId: number) {}

  static printCompany(): void {
    console.log(Company.companyName);
  }
}

// ------------------------------------------------------------
// this Keyword
// ------------------------------------------------------------
class Person {
  constructor(private readonly name: string) {}

  print(): void {
    console.log(this.name);
  }
}

// ------------------------------------------------------------
// Main
// ------------------------------------------------------------
function main(): void {
  // Class & Object
  new Person('Pradeep').print();

  // Encapsulation
  const account = new BankAccount(1000);
  account.deposit(500);
  account.withdraw(200);
  console.log(`Balance = ${account.getBalance()}`);

  // Inheritance + Abstraction + Runtime Polymorphism
  const emp: Employee = new Developer('Rahul', 120000);
  emp.display();
  emp.work();
  (emp as unknown as Payable).paySalary();

  // Method Overloading
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

  // readonly member
  const company = new Company(101);
  console.log(`Company ID = ${company.companyId}`);
}

main();

export {};
