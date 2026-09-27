package main

import "fmt"

type GameLoop struct {
	Position int
	Velocity int
	Ticks    int
	Paused   bool
	Running  bool
}

func (game *GameLoop) ProcessInput(command string) {
	switch command {
	case "RIGHT":
		game.Velocity = 1
	case "PAUSE":
		game.Paused = true
	case "RESUME":
		game.Paused = false
	case "QUIT":
		game.Running = false
	}
}

func (game *GameLoop) Update() {
	if !game.Paused {
		game.Position += game.Velocity
	}
}

func (game *GameLoop) Run(maxTicks int, input func(int) string, render func(int, int)) error {
	if maxTicks < 0 {
		return fmt.Errorf("invalid tick budget")
	}
	for count := 0; count < maxTicks && game.Running; count++ {
		game.ProcessInput(input(game.Ticks))
		if !game.Running {
			break
		}
		game.Update()
		render(game.Ticks, game.Position)
		game.Ticks++
	}
	return nil
}

func main() {
	game := &GameLoop{Running: true}
	commands := map[int]string{0: "RIGHT", 2: "PAUSE", 3: "RESUME", 4: "QUIT"}
	err := game.Run(10, func(tick int) string {
		command, found := commands[tick]
		if !found {
			return "NONE"
		}
		return command
	}, func(tick, position int) {
		fmt.Printf("Tick %d: position %d\n", tick, position)
	})
	if err != nil {
		panic(err)
	}
	if game.Ticks != 4 || game.Position != 3 || game.Running {
		panic("unexpected game state")
	}
	fmt.Printf("Stopped after %d ticks\n", game.Ticks)
}
