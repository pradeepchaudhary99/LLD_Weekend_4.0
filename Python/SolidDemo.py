"""SOLID Principles Demonstration in ONE FILE (Python)."""

from abc import ABC, abstractmethod


# =======================
# 1. SINGLE RESPONSIBILITY PRINCIPLE (SRP)
# =======================

# BAD: One class doing multiple things
class UserBad:
    def __init__(self) -> None:
        self.name = ""

    def save_to_db(self) -> None:
        print("Saving user to DB")

    def send_email(self) -> None:
        print("Sending email")


# GOOD: Separate responsibilities
class User:
    def __init__(self) -> None:
        self.name = ""


class UserRepository:
    def save(self, user: User) -> None:
        print("Saving user to DB")


class EmailService:
    def send_email(self, user: User) -> None:
        print("Sending email")


# =======================
# 2. OPEN CLOSED PRINCIPLE (OCP)
# =======================

# BAD: Need to modify class for new types
class DiscountCalculatorBad:
    def calculate(self, type_: str) -> float:
        if type_ == "NEW":
            return 10
        if type_ == "PREMIUM":
            return 20
        if type_ == "DIWALI":
            return 30
        return 0


# GOOD: Extend without modifying
class DiscountStrategy(ABC):
    @abstractmethod
    def calculate(self) -> float:
        ...


class NewCustomerDiscount(DiscountStrategy):
    def calculate(self) -> float:
        return 10


class PremiumCustomerDiscount(DiscountStrategy):
    def calculate(self) -> float:
        return 20


class DiscountCalculator:
    def calculate(self, strategy: DiscountStrategy) -> float:
        return strategy.calculate()


# =======================
# 3. LISKOV SUBSTITUTION PRINCIPLE (LSP)
# =======================

# BAD: Violates substitution
class BirdBad:
    def fly(self) -> None:
        print("Flying")


class PenguinBad(BirdBad):
    def fly(self) -> None:
        raise NotImplementedError("Can't fly")


# GOOD: Only flying birds expose fly()
class Bird(ABC):
    @abstractmethod
    def eat(self) -> None:
        ...


class FlyingBird(Bird):
    @abstractmethod
    def fly(self) -> None:
        ...


class Sparrow(FlyingBird):
    def eat(self) -> None:
        print("Sparrow eating")

    def fly(self) -> None:
        print("Flying")


# =======================
# 4. INTERFACE SEGREGATION PRINCIPLE (ISP)
# =======================

# BAD: Fat interface
class WorkerBad(ABC):
    @abstractmethod
    def work(self) -> None:
        ...

    @abstractmethod
    def eat(self) -> None:
        ...


class RobotBad(WorkerBad):
    def work(self) -> None:
        print("Working")

    def eat(self) -> None:
        raise NotImplementedError("Robot doesn't eat")


# GOOD: Split interfaces
class Workable(ABC):
    @abstractmethod
    def work(self) -> None:
        ...


class Eatable(ABC):
    @abstractmethod
    def eat(self) -> None:
        ...


class Human(Workable, Eatable):
    def work(self) -> None:
        print("Working")

    def eat(self) -> None:
        print("Eating")


class Robot(Workable):
    def work(self) -> None:
        print("Working")


# =======================
# 5. DEPENDENCY INVERSION PRINCIPLE (DIP)
# =======================

# BAD: High-level depends on low-level
class MySQLDatabase:
    def connect(self) -> None:
        print("Connecting to MySQL")


class ApplicationBad:
    def __init__(self) -> None:
        self.db = MySQLDatabase()

    def start(self) -> None:
        self.db.connect()


# GOOD: Depend on abstraction
class Database(ABC):
    @abstractmethod
    def connect(self) -> None:
        ...


class MySQL(Database):
    def connect(self) -> None:
        print("Connecting to MySQL")


class PostgreSQL(Database):
    def connect(self) -> None:
        print("Connecting to PostgreSQL")


class NoSQL(Database):
    def connect(self) -> None:
        print("Connecting to NoSQL")


class Application:
    def __init__(self, db: Database) -> None:
        self._db = db

    def set_db(self, db: Database) -> None:
        self._db = db

    def start(self) -> None:
        self._db.connect()


# =======================
# MAIN (TESTING)
# =======================
def main() -> None:
    # SRP
    user = User()
    UserRepository().save(user)
    EmailService().send_email(user)

    # OCP
    calc = DiscountCalculator()
    print(calc.calculate(PremiumCustomerDiscount()))

    # LSP
    bird: FlyingBird = Sparrow()
    bird.fly()

    # ISP
    robot: Workable = Robot()
    robot.work()

    # DIP
    app = Application(MySQL())
    app.set_db(NoSQL())
    app.start()


if __name__ == "__main__":
    main()
