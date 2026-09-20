using System;

namespace LLDWeekend4.Patterns;

public static class ChainOfResponsibilityProgram
{
    private abstract class Handler
    {
        private readonly Handler? next;
        protected Handler(Handler? next)
        {
            this.next = next;
        }

        protected abstract bool CanHandle(int level);
        protected abstract string Name();
        public string Handle(int level)
        {
            if (level < 0)
            {
                throw new ArgumentException("Level cannot be negative");
            }

            if (CanHandle(level))
            {
                return Name();
            }

            return next == null ? "Unhandled" : next.Handle(level);
        }
    }

    private sealed class WarningHandler : Handler
    {
        public WarningHandler(Handler? next = null) : base(next)
        {
        }

        protected override bool CanHandle(int level)
        {
            return level < 2;
        }

        protected override string Name()
        {
            return "Warning";
        }
    }

    private sealed class ErrorHandler : Handler
    {
        public ErrorHandler(Handler? next = null) : base(next)
        {
        }

        protected override bool CanHandle(int level)
        {
            return level < 4;
        }

        protected override string Name()
        {
            return "Error";
        }
    }

    private sealed class FatalHandler : Handler
    {
        public FatalHandler(Handler? next = null) : base(next)
        {
        }

        protected override bool CanHandle(int level)
        {
            return level < 6;
        }

        protected override string Name()
        {
            return "Fatal";
        }
    }

    public static void Main()
    {
        Handler chain = new WarningHandler(new ErrorHandler(new FatalHandler()));
        for (int level = 0; level <= 6; level++)
        {
            Console.WriteLine($"{level}: {chain.Handle(level)}");
        }

        Console.WriteLine($"Truncated: {new WarningHandler().Handle(3)}");
        try
        {
            chain.Handle(-1);
            throw new Exception("Accepted negative level");
        }
        catch (ArgumentException)
        {
            Console.WriteLine("Invalid level rejected");
        }
    }
}
