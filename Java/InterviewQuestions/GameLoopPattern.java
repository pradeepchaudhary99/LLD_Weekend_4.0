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
 *   python3 scripts/run.py Java InterviewQuestions/GameLoopPattern
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

package InterviewQuestions;

import java.util.function.IntFunction;
import java.util.function.BiConsumer;

public class GameLoopPattern {
    enum Command { NONE, RIGHT, PAUSE, RESUME, QUIT }
    static class GameLoop {
        int position;
        int velocity;
        int ticks;
        boolean paused;
        boolean running = true;

        void processInput(Command command) {
            switch (command) {
            case RIGHT -> velocity = 1;
            case PAUSE -> paused = true;
            case RESUME -> paused = false;
            case QUIT -> running = false;
            case NONE -> {
                // Keep the previous state.
            }
            }
        }

        void update() {
            if (!paused) {
                position += velocity;
            }
        }

        void run(int maxTicks, IntFunction<Command> input, BiConsumer<Integer, Integer> render) {
            if (maxTicks < 0) {
                throw new IllegalArgumentException("Invalid tick budget");
            }

            for (int count = 0; count < maxTicks && running; count++) {
                processInput(input.apply(ticks));
                if (!running) {
                    break;
                }

                update();
                render.accept(ticks, position);
                ticks++;
            }
        }
    }

    public static void main(String[] args) {
        GameLoop game = new GameLoop();
        game.run(10, tick -> switch (tick) {
            case 0 -> Command.RIGHT;
            case 2 -> Command.PAUSE;
            case 3 -> Command.RESUME;
            case 4 -> Command.QUIT;
            default -> Command.NONE;
        }, (tick, position) -> System.out.println("Tick " + tick + ": position " + position));
        if (game.ticks != 4 || game.position != 3 || game.running) {
            throw new AssertionError("Unexpected game state");
        }

        System.out.println("Stopped after " + game.ticks + " ticks");
    }
}
