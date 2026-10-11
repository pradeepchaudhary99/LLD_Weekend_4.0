# INTERVIEW DESIGN NOTES - Game Loop
#
# Implementation status: runnable in-memory teaching example. Requirements
# below describe this edition; production extensions are explicitly excluded.
#
# Functional requirements:
# - Process input, update game state and render in that order for each tick.
# - Support RIGHT, NONE, PAUSE, RESUME and QUIT commands.
# - Keep state across ticks and stop at quit or the supplied tick budget.
# - While paused, render without advancing the simulated position.
#
# Non-functional requirements:
# - Use deterministic ticks and injectable input/render callbacks for testing.
# - Bound execution so a classroom demo finishes without wall-clock sleeps.
# - Keep input handling, simulation updates and rendering separate.
#
# Core entities and responsibilities:
# - GameLoop: owns position, velocity, tick count, paused and running flags.
# - Command: represents player intent rather than direct state mutation.
# - Input callback: supplies a command for a tick.
# - Render callback: observes the tick and resulting position.
#
# Key operations and flow:
# - run(maxTicks) -> read input -> process command -> stop if quit -> update ->
# - render -> increment ticks; continue while running and within the budget.
#
# Design choices and invariants:
# - Game Loop pattern with dependency-injected callbacks, not a UI framework.
# - NONE retains prior velocity; RIGHT sets velocity to one unit per tick.
# - QUIT performs no update/render/tick increment; a zero budget performs no work.
# - Pause freezes movement but does not terminate the loop.
#
# Assumptions and out of scope:
# - Single-owner sequential simulation; do not mutate state concurrently.
# - No real-time frame pacing, delta-time physics, graphics, networking or audio.
# - No restart/reset command; a quit loop remains stopped.
#
# Interview discussion and checks:
# - Test pause/resume, zero/negative budgets and immediate quit.
# - Discuss fixed-step versus variable-step simulation and decoupled rendering.
# - Runtime is linear in executed ticks, excluding callback cost.
#
# Run / validation:
# - From repository root:
#   python3 scripts/run.py Python InterviewQuestions/GameLoopPattern
# - Prerequisites and focused checks: see README.md and the topic guide.

class GameLoop:
    def __init__(self):
        self.position = 0
        self.velocity = 0
        self.ticks = 0
        self.paused = False
        self.running = True

    def process_input(self, command):
        if command == "RIGHT":
            self.velocity = 1
        elif command == "PAUSE":
            self.paused = True
        elif command == "RESUME":
            self.paused = False
        elif command == "QUIT":
            self.running = False
        elif command != "NONE":
            raise ValueError("Unknown command")

    def update(self):
        if not self.paused:
            self.position += self.velocity

    def run(self, max_ticks, input_source, render):
        if max_ticks < 0:
            raise ValueError("Invalid tick budget")
        for _ in range(max_ticks):
            if not self.running:
                break
            self.process_input(input_source(self.ticks))
            if not self.running:
                break
            self.update()
            render(self.ticks, self.position)
            self.ticks += 1


def main():
    game = GameLoop()
    commands = {0: "RIGHT", 2: "PAUSE", 3: "RESUME", 4: "QUIT"}
    game.run(
        10,
        lambda tick: commands.get(tick, "NONE"),
        lambda tick, position: print(f"Tick {tick}: position {position}"),
    )
    assert game.ticks == 4 and game.position == 3 and not game.running
    print(f"Stopped after {game.ticks} ticks")


if __name__ == "__main__":
    main()
