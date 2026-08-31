// Class relationships in Go.
//
//   Association - "uses-a" (independent values passed around)
//   Aggregation - "has-a"  (holds references to parts that outlive the whole)
//   Composition - "owns-a" (creates and owns its parts)
package main

import "fmt"

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
type RStudent struct{ Name string }

type RPayment struct{ Amount float64 }

type Teacher struct{}

func (Teacher) Teaches(students []*RStudent) {
	for _, s := range students {
		fmt.Printf("Teaching %s\n", s.Name)
	}
}

func (Teacher) GetSalary(p RPayment) {
	fmt.Printf("Teacher received %v\n", p.Amount)
}

// ------------------------------------------------------------
// Aggregation (students exist outside the course)
// ------------------------------------------------------------
type LLDCourse struct {
	students []*RStudent // non-owning references
}

func (c *LLDCourse) Enroll(s *RStudent) { c.students = append(c.students, s) }

func (c *LLDCourse) Size() int { return len(c.students) }

// ------------------------------------------------------------
// Composition (a directory owns its children)
// ------------------------------------------------------------
type FileSystemNode interface {
	Name() string
}

type File struct {
	name string
	meta string
}

func (f File) Name() string { return f.name }

type Directory struct {
	name     string
	children []FileSystemNode // owned by this directory
}

func (d *Directory) Name() string { return d.name }

func (d *Directory) AddChild(name, meta string) {
	d.children = append(d.children, File{name: name, meta: meta})
}

func (d *Directory) Children() []FileSystemNode { return d.children }

func relationshipDemo() {
	students := []*RStudent{{Name: "Neha"}, {Name: "Rahul"}}

	teacher := Teacher{}
	teacher.Teaches(students)
	teacher.GetSalary(RPayment{Amount: 50000})

	course := &LLDCourse{}
	for _, s := range students {
		course.Enroll(s)
	}
	fmt.Printf("Course has %d students\n", course.Size())

	root := &Directory{name: "root"}
	root.AddChild("main.go", "text/x-go")
	fmt.Printf("%s contains", root.Name())
	for _, child := range root.Children() {
		fmt.Printf(" %s", child.Name())
	}
	fmt.Println()
}
