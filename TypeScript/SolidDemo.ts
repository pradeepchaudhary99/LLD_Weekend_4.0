/* SOLID Principles Demonstration in ONE FILE (TypeScript). */

// =======================
// 1. SINGLE RESPONSIBILITY PRINCIPLE (SRP)
// =======================

// BAD: one class doing multiple things
class UserBad {
  name = '';

  saveToDB(): void {
    console.log('Saving user to DB');
  }

  sendEmail(): void {
    console.log('Sending email');
  }
}

// GOOD: separate responsibilities
class User {
  name = '';
}

class UserRepository {
  save(_user: User): void {
    console.log('Saving user to DB');
  }
}

class EmailService {
  sendEmail(_user: User): void {
    console.log('Sending email');
  }
}

// =======================
// 2. OPEN CLOSED PRINCIPLE (OCP)
// =======================

// BAD: must edit for every new type
class DiscountCalculatorBad {
  calculate(type: string): number {
    if (type === 'NEW') return 10;
    if (type === 'PREMIUM') return 20;
    if (type === 'DIWALI') return 30;
    return 0;
  }
}

// GOOD: extend without modifying
interface DiscountStrategy {
  calculate(): number;
}

class NewCustomerDiscount implements DiscountStrategy {
  calculate(): number {
    return 10;
  }
}

class PremiumCustomerDiscount implements DiscountStrategy {
  calculate(): number {
    return 20;
  }
}

class DiscountCalculator {
  calculate(strategy: DiscountStrategy): number {
    return strategy.calculate();
  }
}

// =======================
// 3. LISKOV SUBSTITUTION PRINCIPLE (LSP)
// =======================

// BAD: violates substitution
class BirdBad {
  fly(): void {
    console.log('Flying');
  }
}

class PenguinBad extends BirdBad {
  fly(): never {
    throw new Error("Can't fly");
  }
}

// GOOD: only flying birds expose fly()
interface Bird {
  eat(): void;
}

interface FlyingBird extends Bird {
  fly(): void;
}

class Sparrow implements FlyingBird {
  eat(): void {
    console.log('Sparrow eating');
  }

  fly(): void {
    console.log('Flying');
  }
}

// =======================
// 4. INTERFACE SEGREGATION PRINCIPLE (ISP)
// =======================

// BAD: fat interface
interface WorkerBad {
  work(): void;
  eat(): void;
}

class RobotBad implements WorkerBad {
  work(): void {
    console.log('Working');
  }

  eat(): never {
    throw new Error("Robot doesn't eat");
  }
}

// GOOD: split interfaces
interface Workable {
  work(): void;
}

interface Eatable {
  eat(): void;
}

class Human implements Workable, Eatable {
  work(): void {
    console.log('Working');
  }

  eat(): void {
    console.log('Eating');
  }
}

class Robot implements Workable {
  work(): void {
    console.log('Working');
  }
}

// =======================
// 5. DEPENDENCY INVERSION PRINCIPLE (DIP)
// =======================

// BAD: high-level depends on low-level
class MySQLDatabase {
  connect(): void {
    console.log('Connecting to MySQL');
  }
}

class ApplicationBad {
  private db = new MySQLDatabase();

  start(): void {
    this.db.connect();
  }
}

// GOOD: depend on an abstraction
interface Database {
  connect(): void;
}

class MySQL implements Database {
  connect(): void {
    console.log('Connecting to MySQL');
  }
}

class PostgreSQL implements Database {
  connect(): void {
    console.log('Connecting to PostgreSQL');
  }
}

class NoSQL implements Database {
  connect(): void {
    console.log('Connecting to NoSQL');
  }
}

class Application {
  constructor(private db: Database) {}

  setDB(db: Database): void {
    this.db = db;
  }

  start(): void {
    this.db.connect();
  }
}

// =======================
// MAIN (TESTING)
// =======================
function main(): void {
  // SRP
  const user = new User();
  new UserRepository().save(user);
  new EmailService().sendEmail(user);

  // OCP
  const calc = new DiscountCalculator();
  console.log(calc.calculate(new PremiumCustomerDiscount()));

  // LSP
  const bird: FlyingBird = new Sparrow();
  bird.fly();

  // ISP
  const robot: Workable = new Robot();
  robot.work();

  // DIP
  const app = new Application(new MySQL());
  app.setDB(new NoSQL());
  app.start();
}

main();

export {};
