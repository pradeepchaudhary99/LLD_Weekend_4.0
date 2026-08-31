/* SOLID Principles Demonstration in ONE FILE (JavaScript). */

'use strict';

// =======================
// 1. SINGLE RESPONSIBILITY PRINCIPLE (SRP)
// =======================

// BAD: one class doing multiple things
class UserBad {
  saveToDB() {
    console.log('Saving user to DB');
  }

  sendEmail() {
    console.log('Sending email');
  }
}

// GOOD: separate responsibilities
class User {}

class UserRepository {
  save(user) {
    console.log('Saving user to DB');
  }
}

class EmailService {
  sendEmail(user) {
    console.log('Sending email');
  }
}

// =======================
// 2. OPEN CLOSED PRINCIPLE (OCP)
// =======================

// BAD: must edit for every new type
class DiscountCalculatorBad {
  calculate(type) {
    if (type === 'NEW') return 10;
    if (type === 'PREMIUM') return 20;
    if (type === 'DIWALI') return 30;
    return 0;
  }
}

// GOOD: extend without modifying
class DiscountStrategy {
  calculate() {
    throw new Error('not implemented');
  }
}

class NewCustomerDiscount extends DiscountStrategy {
  calculate() {
    return 10;
  }
}

class PremiumCustomerDiscount extends DiscountStrategy {
  calculate() {
    return 20;
  }
}

class DiscountCalculator {
  calculate(strategy) {
    return strategy.calculate();
  }
}

// =======================
// 3. LISKOV SUBSTITUTION PRINCIPLE (LSP)
// =======================

// BAD: violates substitution
class BirdBad {
  fly() {
    console.log('Flying');
  }
}

class PenguinBad extends BirdBad {
  fly() {
    throw new Error("Can't fly");
  }
}

// GOOD: only flying birds expose fly()
class Bird {
  eat() {
    throw new Error('not implemented');
  }
}

class FlyingBird extends Bird {
  fly() {
    throw new Error('not implemented');
  }
}

class Sparrow extends FlyingBird {
  eat() {
    console.log('Sparrow eating');
  }

  fly() {
    console.log('Flying');
  }
}

// =======================
// 4. INTERFACE SEGREGATION PRINCIPLE (ISP)
// =======================

// GOOD: small, focused capabilities
class Human {
  work() {
    console.log('Working');
  }

  eat() {
    console.log('Eating');
  }
}

class Robot {
  work() {
    console.log('Working');
  }
}

// =======================
// 5. DEPENDENCY INVERSION PRINCIPLE (DIP)
// =======================

// BAD: high-level depends on low-level
class MySQLDatabase {
  connect() {
    console.log('Connecting to MySQL');
  }
}

class ApplicationBad {
  constructor() {
    this.db = new MySQLDatabase();
  }

  start() {
    this.db.connect();
  }
}

// GOOD: depend on an abstraction
class Database {
  connect() {
    throw new Error('not implemented');
  }
}

class MySQL extends Database {
  connect() {
    console.log('Connecting to MySQL');
  }
}

class PostgreSQL extends Database {
  connect() {
    console.log('Connecting to PostgreSQL');
  }
}

class NoSQL extends Database {
  connect() {
    console.log('Connecting to NoSQL');
  }
}

class Application {
  #db;

  constructor(db) {
    this.#db = db;
  }

  setDB(db) {
    this.#db = db;
  }

  start() {
    this.#db.connect();
  }
}

// =======================
// MAIN (TESTING)
// =======================
function main() {
  // SRP
  const user = new User();
  new UserRepository().save(user);
  new EmailService().sendEmail(user);

  // OCP
  const calc = new DiscountCalculator();
  console.log(calc.calculate(new PremiumCustomerDiscount()));

  // LSP
  const bird = new Sparrow();
  bird.fly();

  // ISP
  const robot = new Robot();
  robot.work();

  // DIP
  const app = new Application(new MySQL());
  app.setDB(new NoSQL());
  app.start();
}

main();

module.exports = {
  User,
  UserRepository,
  EmailService,
  DiscountCalculator,
  NewCustomerDiscount,
  PremiumCustomerDiscount,
  Sparrow,
  Human,
  Robot,
  Application,
  MySQL,
  PostgreSQL,
  NoSQL,
};
