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
 *   python3 scripts/run.py 'C++' InterviewQuestions/GameLoopPattern
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

#include <functional>
#include <iostream>
#include <map>
#include <stdexcept>

enum class Command { None, Right, Pause, Resume, Quit };

class GameLoop {
  public:
    int position = 0;
    int velocity = 0;
    int ticks = 0;
    bool paused = false;
    bool running = true;

    void processInput(Command command) {
        switch (command) {
        case Command::Right:
            velocity = 1;
            break;
        case Command::Pause:
            paused = true;
            break;
        case Command::Resume:
            paused = false;
            break;
        case Command::Quit:
            running = false;
            break;
        case Command::None:
            break;
        }
    }

    void update() {
        if (!paused) {
            position += velocity;
        }
    }

    void run(int maxTicks, const std::function<Command(int)> &input,
             const std::function<void(int, int)> &render) {
        if (maxTicks < 0) {
            throw std::invalid_argument("Invalid tick budget");
        }

        for (int count = 0; count < maxTicks && running; count++) {
            processInput(input(ticks));
            if (!running) {
                break;
            }

            update();
            render(ticks, position);
            ticks++;
        }
    }
};

int main() {
    GameLoop game;
    std::map<int, Command> commands{
        {0, Command::Right}, {2, Command::Pause}, {3, Command::Resume}, {4, Command::Quit}};
    game.run(
        10,
        [&](int tick) {
            auto found = commands.find(tick);
            return found == commands.end() ? Command::None : found->second;
        },
        [](int tick, int position) {
            std::cout << "Tick " << tick << ": position " << position << '\n';
        });
    if (game.ticks != 4 || game.position != 3 || game.running) {
        throw std::runtime_error("Unexpected game state");
    }

    std::cout << "Stopped after " << game.ticks << " ticks\n";
}
