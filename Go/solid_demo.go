// SOLID Principles Demonstration in Go.
package main

import "fmt"

// =======================
// 1. SINGLE RESPONSIBILITY PRINCIPLE (SRP)
// =======================

// BAD: one type doing multiple things.
type UserBad struct{ Name string }

func (UserBad) SaveToDB()   { fmt.Println("Saving user to DB") }
func (UserBad) SendEmail()  { fmt.Println("Sending email") }

// GOOD: separate responsibilities.
type User struct{ Name string }

type UserRepository struct{}

func (UserRepository) Save(User) { fmt.Println("Saving user to DB") }

type EmailService struct{}

func (EmailService) SendEmail(User) { fmt.Println("Sending email") }

// =======================
// 2. OPEN CLOSED PRINCIPLE (OCP)
// =======================

// BAD: must edit the function for every new type.
func discountBad(kind string) float64 {
	switch kind {
	case "NEW":
		return 10
	case "PREMIUM":
		return 20
	case "DIWALI":
		return 30
	default:
		return 0
	}
}

// GOOD: extend without modifying.
type DiscountStrategy interface {
	Calculate() float64
}

type NewCustomerDiscount struct{}

func (NewCustomerDiscount) Calculate() float64 { return 10 }

type PremiumCustomerDiscount struct{}

func (PremiumCustomerDiscount) Calculate() float64 { return 20 }

type DiscountCalculator struct{}

func (DiscountCalculator) Calculate(s DiscountStrategy) float64 { return s.Calculate() }

// =======================
// 3. LISKOV SUBSTITUTION PRINCIPLE (LSP)
// =======================

// BAD: a "bird" whose fly() breaks the contract.
type BirdBad struct{}

func (BirdBad) Fly() { fmt.Println("Flying") }

type PenguinBad struct{ BirdBad }

func (PenguinBad) Fly() { panic("Can't fly") }

// GOOD: only flying birds expose Fly().
type Bird interface{ Eat() }

type FlyingBird interface {
	Bird
	Fly()
}

type Sparrow struct{}

func (Sparrow) Eat() { fmt.Println("Sparrow eating") }
func (Sparrow) Fly() { fmt.Println("Flying") }

// =======================
// 4. INTERFACE SEGREGATION PRINCIPLE (ISP)
// =======================

// GOOD: small, focused interfaces.
type Workable interface{ Work() }

type Eatable interface{ Eat() }

type Human struct{}

func (Human) Work() { fmt.Println("Working") }
func (Human) Eat()  { fmt.Println("Eating") }

type Robot struct{}

func (Robot) Work() { fmt.Println("Working") }

// =======================
// 5. DEPENDENCY INVERSION PRINCIPLE (DIP)
// =======================

// BAD: high-level code depends on a concrete type.
type MySQLDatabase struct{}

func (MySQLDatabase) Connect() { fmt.Println("Connecting to MySQL") }

type ApplicationBad struct{ db MySQLDatabase }

func (a ApplicationBad) Start() { a.db.Connect() }

// GOOD: depend on an abstraction.
type Database interface{ Connect() }

type MySQL struct{}

func (MySQL) Connect() { fmt.Println("Connecting to MySQL") }

type PostgreSQL struct{}

func (PostgreSQL) Connect() { fmt.Println("Connecting to PostgreSQL") }

type NoSQL struct{}

func (NoSQL) Connect() { fmt.Println("Connecting to NoSQL") }

type Application struct{ db Database }

func NewApplication(db Database) *Application { return &Application{db: db} }

func (a *Application) SetDB(db Database) { a.db = db }

func (a *Application) Start() { a.db.Connect() }

func solidDemo() {
	// SRP
	user := User{}
	UserRepository{}.Save(user)
	EmailService{}.SendEmail(user)

	// OCP
	fmt.Println(DiscountCalculator{}.Calculate(PremiumCustomerDiscount{}))

	// LSP
	var bird FlyingBird = Sparrow{}
	bird.Fly()

	// ISP
	var robot Workable = Robot{}
	robot.Work()

	// DIP
	app := NewApplication(MySQL{})
	app.SetDB(NoSQL{})
	app.Start()
}
