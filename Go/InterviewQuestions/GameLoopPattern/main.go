/*
 * INTERVIEW DESIGN NOTES - Game Loop
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Process input, update game state and render in that order for each tick.
 * - Support RIGHT, NONE, PAUSE, RESUME and QUIT commands.
 * - Keep state across ticks and stop at quit or the supplied tick budget.
 * - While paused, render without advancing the simulated position.
 *
 * Non-functional requirements:
 * - Use deterministic ticks and injectable input/render callbacks for testing.
 * - Bound execution so a classroom demo finishes without wall-clock sleeps.
 * - Keep input handling, simulation updates and rendering separate.
 *
 * Core entities and responsibilities:
 * - GameLoop: owns position, velocity, tick count, paused and running flags.
 * - Command: represents player intent rather than direct state mutation.
 * - Input callback: supplies a command for a tick.
 * - Render callback: observes the tick and resulting position.
 *
 * Key operations and flow:
 * - run(maxTicks) -> read input -> process command -> stop if quit -> update ->
 * - render -> increment ticks; continue while running and within the budget.
 *
 * Design choices and invariants:
 * - Game Loop pattern with dependency-injected callbacks, not a UI framework.
 * - NONE retains prior velocity; RIGHT sets velocity to one unit per tick.
 * - QUIT performs no update/render/tick increment; a zero budget performs no work.
 * - Pause freezes movement but does not terminate the loop.
 *
 * Assumptions and out of scope:
 * - Single-owner sequential simulation; do not mutate state concurrently.
 * - No real-time frame pacing, delta-time physics, graphics, networking or audio.
 * - No restart/reset command; a quit loop remains stopped.
 *
 * Interview discussion and checks:
 * - Test pause/resume, zero/negative budgets and immediate quit.
 * - Discuss fixed-step versus variable-step simulation and decoupled rendering.
 * - Runtime is linear in executed ticks, excluding callback cost.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py Go InterviewQuestions/GameLoopPattern
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

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
