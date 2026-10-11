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
 *   python3 scripts/run.py 'C#' InterviewQuestions/GameLoopPattern
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

using System;
using System.Collections.Generic;

namespace LLDWeekend4.InterviewQuestions;

public static class GameLoopPattern
{
    private enum Command
    {
        None,
        Right,
        Pause,
        Resume,
        Quit
    }

    private sealed class GameLoop
    {
        public int Position;
        public int Velocity;
        public int Ticks;
        public bool Paused;
        public bool Running = true;

        public void ProcessInput(Command command)
        {
            switch (command)
            {
            case Command.Right:
                Velocity = 1;
                break;
            case Command.Pause:
                Paused = true;
                break;
            case Command.Resume:
                Paused = false;
                break;
            case Command.Quit:
                Running = false;
                break;
            case Command.None:
                break;
            }
        }

        public void Update()
        {
            if (!Paused)
            {
                Position += Velocity;
            }
        }

        public void Run(int maxTicks, Func<int, Command> input, Action<int, int> render)
        {
            if (maxTicks < 0)
            {
                throw new ArgumentException("Invalid tick budget");
            }

            for (int count = 0; count < maxTicks && Running; count++)
            {
                ProcessInput(input(Ticks));
                if (!Running)
                {
                    break;
                }

                Update();
                render(Ticks, Position);
                Ticks++;
            }
        }
    }

    public static void Main()
    {
        GameLoop game = new();
        Dictionary<int, Command> commands = new() { [0] = Command.Right, [2] = Command.Pause,
                                                    [3] = Command.Resume, [4] = Command.Quit };
        game.Run(10, tick => commands.GetValueOrDefault(tick, Command.None),
                 (tick, position) =>
                 { Console.WriteLine($"Tick {tick}: position {position}"); });
        if (game.Ticks != 4 || game.Position != 3 || game.Running)
        {
            throw new Exception("Unexpected game state");
        }

        Console.WriteLine($"Stopped after {game.Ticks} ticks");
    }
}
