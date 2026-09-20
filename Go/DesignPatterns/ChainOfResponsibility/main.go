package main

import "fmt"

// Go composes handlers using an interface instead of an inheritance hierarchy.
type Handler interface {
	Handle(level int) (string, error)
}

type LevelHandler struct {
	name           string
	exclusiveLimit int
	next           Handler
}

func (handler *LevelHandler) Handle(level int) (string, error) {
	if level < 0 {
		return "", fmt.Errorf("level cannot be negative")
	}
	if level < handler.exclusiveLimit {
		return handler.name, nil
	}
	if handler.next != nil {
		return handler.next.Handle(level)
	}
	return "Unhandled", nil
}

func main() {
	fatal := &LevelHandler{name: "Fatal", exclusiveLimit: 6}
	failure := &LevelHandler{name: "Error", exclusiveLimit: 4, next: fatal}
	warning := &LevelHandler{name: "Warning", exclusiveLimit: 2, next: failure}
	for level := 0; level <= 6; level++ {
		result, err := warning.Handle(level)
		if err != nil {
			panic(err)
		}
		fmt.Printf("%d: %s\n", level, result)
	}
	truncated := &LevelHandler{name: "Warning", exclusiveLimit: 2}
	result, _ := truncated.Handle(3)
	fmt.Println("Truncated:", result)
	if _, err := warning.Handle(-1); err == nil {
		panic("accepted negative level")
	}
	fmt.Println("Invalid level rejected")
}
