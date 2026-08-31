// SOLID Principles Demonstration in ONE FILE (C++17)

#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

// =======================
// 1. SINGLE RESPONSIBILITY PRINCIPLE (SRP)
// =======================

// BAD: One class doing multiple things
class UserBad {
public:
    std::string name;
    void saveToDB() { std::cout << "Saving user to DB\n"; }
    void sendEmail() { std::cout << "Sending email\n"; }
};

// GOOD: Separate responsibilities
class User {
public:
    std::string name;
};

class UserRepository {
public:
    void save(const User&) { std::cout << "Saving user to DB\n"; }
};

class EmailService {
public:
    void sendEmail(const User&) { std::cout << "Sending email\n"; }
};

// =======================
// 2. OPEN CLOSED PRINCIPLE (OCP)
// =======================

// BAD: Need to modify class for new types
class DiscountCalculatorBad {
public:
    double calculate(const std::string& type) {
        if (type == "NEW") return 10;
        if (type == "PREMIUM") return 20;
        if (type == "DIWALI") return 30;
        return 0;
    }
};

// GOOD: Extend without modifying
struct DiscountStrategy {
    virtual ~DiscountStrategy() = default;
    virtual double calculate() = 0;
};

class NewCustomerDiscount : public DiscountStrategy {
public:
    double calculate() override { return 10; }
};

class PremiumCustomerDiscount : public DiscountStrategy {
public:
    double calculate() override { return 20; }
};

class DiscountCalculator {
public:
    double calculate(DiscountStrategy& strategy) { return strategy.calculate(); }
};

// =======================
// 3. LISKOV SUBSTITUTION PRINCIPLE (LSP)
// =======================

// BAD: Violates substitution
class BirdBad {
public:
    virtual ~BirdBad() = default;
    virtual void fly() { std::cout << "Flying\n"; }
};

class PenguinBad : public BirdBad {
public:
    void fly() override { throw std::logic_error("Can't fly"); }
};

// GOOD: Only flying birds expose fly()
struct Bird {
    virtual ~Bird() = default;
    virtual void eat() = 0;
};

struct FlyingBird : Bird {
    virtual void fly() = 0;
};

class Sparrow : public FlyingBird {
public:
    void eat() override { std::cout << "Sparrow eating\n"; }
    void fly() override { std::cout << "Flying\n"; }
};

// =======================
// 4. INTERFACE SEGREGATION PRINCIPLE (ISP)
// =======================

// BAD: Fat interface
struct WorkerBad {
    virtual ~WorkerBad() = default;
    virtual void work() = 0;
    virtual void eat() = 0;
};

class RobotBad : public WorkerBad {
public:
    void work() override { std::cout << "Working\n"; }
    void eat() override { throw std::logic_error("Robot doesn't eat"); }
};

// GOOD: Split interfaces
struct Workable {
    virtual ~Workable() = default;
    virtual void work() = 0;
};

struct Eatable {
    virtual ~Eatable() = default;
    virtual void eat() = 0;
};

class Human : public Workable, public Eatable {
public:
    void work() override { std::cout << "Working\n"; }
    void eat() override { std::cout << "Eating\n"; }
};

class Robot : public Workable {
public:
    void work() override { std::cout << "Working\n"; }
};

// =======================
// 5. DEPENDENCY INVERSION PRINCIPLE (DIP)
// =======================

// BAD: High-level depends on low-level
class MySQLDatabase {
public:
    void connect() { std::cout << "Connecting to MySQL\n"; }
};

class ApplicationBad {
    MySQLDatabase db_;

public:
    void start() { db_.connect(); }
};

// GOOD: Depend on abstraction
struct Database {
    virtual ~Database() = default;
    virtual void connect() = 0;
};

class MySQL : public Database {
public:
    void connect() override { std::cout << "Connecting to MySQL\n"; }
};

class PostgreSQL : public Database {
public:
    void connect() override { std::cout << "Connecting to PostgreSQL\n"; }
};

class NoSQL : public Database {
public:
    void connect() override { std::cout << "Connecting to NoSQL\n"; }
};

class Application {
    std::shared_ptr<Database> db_;

public:
    explicit Application(std::shared_ptr<Database> db) : db_(std::move(db)) {}

    void setDB(std::shared_ptr<Database> db) { db_ = std::move(db); }

    void start() { db_->connect(); }
};

// =======================
// MAIN (TESTING)
// =======================
int main() {
    // SRP
    User user;
    UserRepository().save(user);
    EmailService().sendEmail(user);

    // OCP
    DiscountCalculator calc;
    PremiumCustomerDiscount premium;
    std::cout << calc.calculate(premium) << '\n';

    // LSP
    std::unique_ptr<FlyingBird> bird = std::make_unique<Sparrow>();
    bird->fly();

    // ISP
    std::unique_ptr<Workable> robot = std::make_unique<Robot>();
    robot->work();

    // DIP
    Application app(std::make_shared<MySQL>());
    app.setDB(std::make_shared<NoSQL>());
    app.start();

    return 0;
}
