from typing import Optional


class Student:
    def __init__(self, builder: "Student.StudentBuilder") -> None:
        self.name: str = builder.name
        self.age: Optional[int] = builder.age
        self.address: Optional[str] = builder.address
        self.wallet: Optional[float] = builder.wallet

    class StudentBuilder:
        def __init__(self, name: str) -> None:
            self.name = name  # mandatory param
            # optional
            self.age: Optional[int] = None
            self.address: Optional[str] = None
            self.wallet: Optional[float] = None

        def set_age(self, age: int) -> "Student.StudentBuilder":
            self.age = age
            return self

        def set_address(self, address: str) -> "Student.StudentBuilder":
            self.address = address
            return self

        def set_wallet(self, wallet: float) -> "Student.StudentBuilder":
            self.wallet = wallet
            return self

        def build(self) -> "Student":
            return Student(self)


def main() -> None:
    student = (
        Student.StudentBuilder("pradeep")
        .set_age(123)
        .set_address("dasd")
        .set_wallet(12321)
        .build()
    )


if __name__ == "__main__":
    main()
