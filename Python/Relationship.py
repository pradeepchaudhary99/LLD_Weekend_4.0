"""
Class relationships in Python.

    Association  - "uses-a"  (objects reference each other, independent lifetimes)
    Aggregation  - "has-a"   (whole references parts, parts outlive the whole)
    Composition  - "owns-a"  (whole creates/owns parts, shared lifetime)
"""

from __future__ import annotations

from abc import ABC, abstractmethod


# ------------------------------------------------------------
# Association
# ------------------------------------------------------------
class Student:
    def __init__(self, name: str) -> None:
        self.name = name


class Payment:
    def __init__(self, amount: float) -> None:
        self.amount = amount


class Teacher:
    def teaches(self, students: list[Student]) -> None:
        for student in students:
            print(f"Teaching {student.name}")

    def get_salary(self, payment: Payment) -> None:
        print(f"Teacher received {payment.amount}")


# ------------------------------------------------------------
# Aggregation
# ------------------------------------------------------------
class LLDCourse:
    def __init__(self) -> None:
        self.students: list[Student] = []  # students exist outside the course

    def enroll(self, student: Student) -> None:
        self.students.append(student)


# ------------------------------------------------------------
# Composition
# ------------------------------------------------------------
class IFileSystemNode(ABC):
    @abstractmethod
    def name(self) -> str:
        ...


class File(IFileSystemNode):
    def __init__(self, name: str, meta: str = "") -> None:
        self._name = name
        self._meta = meta

    def name(self) -> str:
        return self._name


class Directory(IFileSystemNode):
    def __init__(self, name: str) -> None:
        self._name = name
        self._children: list[IFileSystemNode] = []  # owned by this directory

    def name(self) -> str:
        return self._name

    def add_child(self, name: str, meta: str) -> None:
        self._children.append(File(name, meta))

    def children(self) -> list[IFileSystemNode]:
        return self._children


def main() -> None:
    students = [Student("Neha"), Student("Rahul")]

    teacher = Teacher()
    teacher.teaches(students)
    teacher.get_salary(Payment(50000))

    course = LLDCourse()
    for student in students:
        course.enroll(student)
    print(f"Course has {len(course.students)} students")

    root = Directory("root")
    root.add_child("main.py", "text/x-python")
    print(f"{root.name()} contains {[c.name() for c in root.children()]}")


if __name__ == "__main__":
    main()
