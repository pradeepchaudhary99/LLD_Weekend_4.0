// ONE FILE - OOP Concepts in Go
//
// Go has no classes/inheritance; it models the same ideas with structs,
// interfaces, embedding and methods.
//
// Covers: Struct & Value, Encapsulation (unexported fields), Abstraction
// (interfaces), "Inheritance" via embedding, Polymorphism (interfaces),
// Composition, Association, Aggregation, package-level state, constants, receivers.
package main

import "fmt"

// ------------------------------------------------------------
// Abstraction: an interface plays the role of an abstract type
// ------------------------------------------------------------
type Worker interface {
	Work()
	Display()
}

// ------------------------------------------------------------
// Interface
// ------------------------------------------------------------
type Payable interface {
	PaySalary()
}

// employee holds shared state; embedded into concrete types.
type employee struct {
	name   string
	salary float64
}

func (e employee) Display() {
	fmt.Printf("%s earns %v\n", e.name, e.salary)
}

// ------------------------------------------------------------
// "Inheritance" via embedding + method overriding
// ------------------------------------------------------------
type Developer struct {
	employee // embedded
}

func NewDeveloper(name string, salary float64) *Developer {
	return &Developer{employee{name: name, salary: salary}}
}

func (d Developer) Work() {
	fmt.Printf("%s is writing Go code.\n", d.name)
}

func (d Developer) PaySalary() {
	fmt.Printf("Salary credited to %s\n", d.name)
}

// ------------------------------------------------------------
// Encapsulation: unexported field, exported methods
// ------------------------------------------------------------
type BankAccount struct {
	balance float64
}

func NewBankAccount(balance float64) *BankAccount {
	return &BankAccount{balance: balance}
}

func (a *BankAccount) Deposit(amount float64) { a.balance += amount }

func (a *BankAccount) Withdraw(amount float64) {
	if amount <= a.balance {
		a.balance -= amount
	}
}

func (a *BankAccount) Balance() float64 { return a.balance }

// ------------------------------------------------------------
// Composition (strong Has-A)
// ------------------------------------------------------------
type Engine struct{}

func (Engine) Start() { fmt.Println("Engine Started") }

type Car struct {
	engine Engine // owned by value
}

func (c Car) StartCar() {
	c.engine.Start()
	fmt.Println("Car Started")
}

// ------------------------------------------------------------
// Aggregation (weak Has-A: Department shared via pointer)
// ------------------------------------------------------------
type Department struct {
	Name string
}

type Professor struct {
	name       string
	department *Department
}

func (p Professor) ShowDepartment() {
	fmt.Printf("%s belongs to %s\n", p.name, p.department.Name)
}

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
type Course struct {
	Title string
}

type Student struct {
	Name string
}

func (s Student) Attend(c Course) {
	fmt.Printf("%s attends %s\n", s.Name, c.Title)
}

// ------------------------------------------------------------
// Polymorphism-style "overloading": variadic instead of overloads
// ------------------------------------------------------------
type Calculator struct{}

func (Calculator) Add(nums ...float64) float64 {
	var sum float64
	for _, n := range nums {
		sum += n
	}
	return sum
}

// ------------------------------------------------------------
// Package-level (static) state & constant (final)
// ------------------------------------------------------------
const companyName = "SYS Titans"

type Company struct {
	CompanyID int
}

func PrintCompany() { fmt.Println(companyName) }

// ------------------------------------------------------------
// Person
// ------------------------------------------------------------
type Person struct {
	name string
}

func (p Person) Print() { fmt.Println(p.name) }

func oopDemo() {
	// Struct & Value
	person := Person{name: "Pradeep"}
	person.Print()

	// Encapsulation
	account := NewBankAccount(1000)
	account.Deposit(500)
	account.Withdraw(200)
	fmt.Printf("Balance = %v\n", account.Balance())

	// Abstraction + Polymorphism
	var w Worker = NewDeveloper("Rahul", 120000)
	w.Display()
	w.Work()

	if p, ok := w.(Payable); ok {
		p.PaySalary()
	}

	// "Overloading" via variadic
	calc := Calculator{}
	fmt.Println(calc.Add(2, 3))
	fmt.Println(calc.Add(2.5, 3.5))
	fmt.Println(calc.Add(1, 2, 3))

	// Composition
	Car{}.StartCar()

	// Aggregation
	department := &Department{Name: "Computer Science"}
	Professor{name: "Amit", department: department}.ShowDepartment()

	// Association
	Student{Name: "Neha"}.Attend(Course{Title: "Low Level Design"})

	// Package-level state
	PrintCompany()

	// Constant-backed field
	company := Company{CompanyID: 101}
	fmt.Printf("Company ID = %d\n", company.CompanyID)
}

func main() {
	oopDemo()

	fmt.Println("\n--- SOLID ---")
	solidDemo()

	fmt.Println("\n--- Relationships ---")
	relationshipDemo()
}
