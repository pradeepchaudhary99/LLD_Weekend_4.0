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
