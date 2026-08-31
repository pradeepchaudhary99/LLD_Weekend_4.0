"""
ONE FILE - OOP Concepts in Python

Covers:
    Class & Object
    Encapsulation
    Abstraction
    Inheritance
    Polymorphism
        - Method Overloading (via default args)
        - Method Overriding
    Interface (abc.ABC)
    Composition
    Association
    Aggregation
    Static (classmethod / class attribute)
    Final (naming convention / typing.Final)
    self / super
"""

from abc import ABC, abstractmethod


# ------------------------------------------------------------
# Abstraction
# ------------------------------------------------------------
class Employee(ABC):
    def __init__(self, name: str, salary: float) -> None:
        self._name = name
        self._salary = salary

    @abstractmethod
    def work(self) -> None:
        ...

    def display(self) -> None:
        print(f"{self._name} earns {self._salary}")


# ------------------------------------------------------------
# Interface
# ------------------------------------------------------------
class Payable(ABC):
    @abstractmethod
    def pay_salary(self) -> None:
        ...


# ------------------------------------------------------------
# Inheritance + Method Overriding
# ------------------------------------------------------------
class Developer(Employee, Payable):
    def __init__(self, name: str, salary: float) -> None:
        super().__init__(name, salary)  # super keyword

    def work(self) -> None:
        print(f"{self._name} is writing Python code.")

    def pay_salary(self) -> None:
        print(f"Salary credited to {self._name}")


# ------------------------------------------------------------
# Encapsulation
# ------------------------------------------------------------
class BankAccount:
    def __init__(self, balance: float) -> None:
        self.__balance = balance  # hidden data

    def deposit(self, amount: float) -> None:
        self.__balance += amount

    def withdraw(self, amount: float) -> None:
        if amount <= self.__balance:
            self.__balance -= amount

    def get_balance(self) -> float:
        return self.__balance


# ------------------------------------------------------------
# Composition (strong Has-A)
# ------------------------------------------------------------
class Engine:
    def start(self) -> None:
        print("Engine Started")


class Car:
    def __init__(self) -> None:
        self._engine = Engine()  # owned by Car

    def start_car(self) -> None:
        self._engine.start()
        print("Car Started")


# ------------------------------------------------------------
# Aggregation (weak Has-A)
# ------------------------------------------------------------
class Department:
    def __init__(self, name: str) -> None:
        self.name = name


class Professor:
    def __init__(self, name: str, department: Department) -> None:
        self.name = name
        self.department = department

    def show_department(self) -> None:
        print(f"{self.name} belongs to {self.department.name}")


# ------------------------------------------------------------
# Association
# ------------------------------------------------------------
class Course:
    def __init__(self, title: str) -> None:
        self.title = title


class Student:
    def __init__(self, name: str) -> None:
        self.name = name

    def attend(self, course: Course) -> None:
        print(f"{self.name} attends {course.title}")


# ------------------------------------------------------------
# Polymorphism (Method Overloading via default args)
# ------------------------------------------------------------
class Calculator:
    def add(self, a: float, b: float, c: float = 0) -> float:
        return a + b + c


# ------------------------------------------------------------
# Static & Final
# ------------------------------------------------------------
class Company:
    company_name = "SYS Titans"  # class (static) attribute

    def __init__(self, company_id: int) -> None:
        self.company_id = company_id  # treated as final by convention

    @staticmethod
    def print_company() -> None:
        print(Company.company_name)


# ------------------------------------------------------------
# self Keyword
# ------------------------------------------------------------
class Person:
    def __init__(self, name: str) -> None:
        self.name = name  # self keyword

    def print(self) -> None:
        print(self.name)


# ------------------------------------------------------------
# Main
# ------------------------------------------------------------
def main() -> None:
    # Class & Object
    person = Person("Pradeep")
    person.print()

    # Encapsulation
    account = BankAccount(1000)
    account.deposit(500)
    account.withdraw(200)
    print(f"Balance = {account.get_balance()}")

    # Inheritance + Abstraction + Runtime Polymorphism
    emp: Employee = Developer("Rahul", 120000)
    emp.display()
    emp.work()

    payable: Payable = emp  # type: ignore[assignment]
    payable.pay_salary()

    # Method Overloading
    calculator = Calculator()
    print(calculator.add(2, 3))
    print(calculator.add(2.5, 3.5))
    print(calculator.add(1, 2, 3))

    # Composition
    car = Car()
    car.start_car()

    # Aggregation
    department = Department("Computer Science")
    professor = Professor("Amit", department)
    professor.show_department()

    # Association
    student = Student("Neha")
    course = Course("Low Level Design")
    student.attend(course)

    # Static
    Company.print_company()

    # Final
    company = Company(101)
    print(f"Company ID = {company.company_id}")


if __name__ == "__main__":
    main()
