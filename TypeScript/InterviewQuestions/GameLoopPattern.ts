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
 *   python3 scripts/run.py TypeScript InterviewQuestions/GameLoopPattern
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

export type Command = "NONE" | "RIGHT" | "PAUSE" | "RESUME" | "QUIT";

export class GameLoop {
    position = 0;
    velocity = 0;
    ticks = 0;
    paused = false;
    running = true;

    processInput(command: Command): void {
        switch (command) {
            case "RIGHT":
                this.velocity = 1;
                break;
            case "PAUSE":
                this.paused = true;
                break;
            case "RESUME":
                this.paused = false;
                break;
            case "QUIT":
                this.running = false;
                break;
            case "NONE":
                break;
        }
    }

    update(): void {
        if (!this.paused) {
            this.position += this.velocity;
        }
    }

    run(
        maxTicks: number,
        input: (tick: number) => Command,
        render: (tick: number, position: number) => void,
    ): void {
        if (!Number.isSafeInteger(maxTicks) || maxTicks < 0) {
            throw new RangeError("Invalid tick budget");
        }
        for (let count = 0; count < maxTicks && this.running; count++) {
            this.processInput(input(this.ticks));
            if (!this.running) {
                break;
            }
            this.update();
            render(this.ticks, this.position);
            this.ticks++;
        }
    }
}

function main(): void {
    const game = new GameLoop();
    const commands = new Map<number, Command>([
        [0, "RIGHT"],
        [2, "PAUSE"],
        [3, "RESUME"],
        [4, "QUIT"],
    ]);
    game.run(
        10,
        (tick) => commands.get(tick) ?? "NONE",
        (tick, position) => {
            console.log(`Tick ${tick}: position ${position}`);
        },
    );
    if (game.ticks !== 4 || game.position !== 3 || game.running) {
        throw new Error("Unexpected game state");
    }
    console.log(`Stopped after ${game.ticks} ticks`);
}

if (require.main === module) {
    main();
}
