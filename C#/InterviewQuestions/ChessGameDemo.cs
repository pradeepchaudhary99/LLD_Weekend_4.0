namespace LLDWeekend4.InterviewQuestions;

using System;
using System.Linq;

/** Ordinary chess moves only: no castling, en passant, or promotion. */
public class ChessGameDemo
{
    public enum Status
    {
        ACTIVE,
        CHECKMATE,
        STALEMATE,
        DRAW
    }

    public sealed class Board
    {
        internal readonly char[] cells;

        public Board(string position)
        {
            cells = position.ToCharArray();
            if (cells.Length != 64 || position.Count(c => c == 'K') != 1 ||
                position.Count(c => c == 'k') != 1 ||
                position.Any(c => !".prnbqkPRNBQK".Contains(c)))
            {
                throw new ArgumentException("Board requires 64 squares and one king per side");
            }
        }

        internal bool PathClear(int from, int to)
        {
            int rowStep = Math.Sign(to / 8 - from / 8);
            int colStep = Math.Sign(to % 8 - from % 8);
            int row = from / 8 + rowStep;
            int col = from % 8 + colStep;
            while (row * 8 + col != to)
            {
                if (cells[row * 8 + col] != '.')
                {
                    return false;
                }
                row += rowStep;
                col += colStep;
            }
            return true;
        }

        // Attack geometry is separate from pawn movement and king safety.
        internal bool Reaches(int from, int to, bool attack)
        {
            char piece = cells[from];
            int row = to / 8 - from / 8;
            int col = to % 8 - from % 8;
            int dr = Math.Abs(row);
            int dc = Math.Abs(col);
            if (from == to || piece == '.')
            {
                return false;
            }
            switch (char.ToLowerInvariant(piece))
            {
            case 'n':
                return dr * dc == 2;
            case 'k':
                return Math.Max(dr, dc) == 1;
            case 'b':
                return dr == dc && PathClear(from, to);
            case 'r':
                return (row == 0 || col == 0) && PathClear(from, to);
            case 'q':
                return (dr == dc || row == 0 || col == 0) && PathClear(from, to);
            case 'p':
                int direction = char.IsUpper(piece) ? -1 : 1;
                if (attack)
                {
                    return row == direction && dc == 1;
                }
                if (to / 8 == 0 || to / 8 == 7)
                {
                    return false;
                }
                if (dc == 1)
                {
                    return row == direction && cells[to] != '.';
                }
                return col == 0 && cells[to] == '.' &&
                       (row == direction ||
                        (from / 8 == (direction == -1 ? 6 : 1) && row == 2 * direction &&
                         cells[from + 8 * direction] == '.'));
            default:
                return false;
            }
        }

        internal bool InCheck(bool white)
        {
            int king = new string(cells).IndexOf(white ? 'K' : 'k');
            for (int from = 0; from < 64; from++)
            {
                if (cells[from] != '.' && char.IsUpper(cells[from]) != white &&
                    Reaches(from, king, true))
                {
                    return true;
                }
            }
            return false;
        }

        internal bool Legal(int from, int to, bool white)
        {
            if (from < 0 || from >= 64 || to < 0 || to >= 64 || cells[from] == '.' ||
                char.IsUpper(cells[from]) != white ||
                (cells[to] != '.' && char.IsUpper(cells[to]) == white) ||
                char.ToLowerInvariant(cells[to]) == 'k' || !Reaches(from, to, false))
            {
                return false;
            }
            char moving = cells[from];
            char captured = cells[to];
            cells[to] = moving;
            cells[from] = '.';
            bool safe = !InCheck(white);
            cells[from] = moving;
            cells[to] = captured;
            return safe;
        }

        internal bool HasMove(bool white)
        {
            for (int from = 0; from < 64; from++)
            {
                for (int to = 0; to < 64; to++)
                {
                    if (Legal(from, to, white))
                    {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public sealed class ChessGame
    {
        private readonly object gate = new();
        private readonly Board board;
        private bool white;
        private Status gameStatus;

        public ChessGame()
            : this("rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR", true)
        {
        }

        public ChessGame(string position, bool white)
        {
            board = new Board(position);
            this.white = white;
            UpdateStatus();
        }

        private void UpdateStatus()
        {
            if (!board.HasMove(white))
            {
                gameStatus = board.InCheck(white) ? Status.CHECKMATE : Status.STALEMATE;
            }
            else if (new string(board.cells).Replace(".", "").Length == 2)
            {
                gameStatus = Status.DRAW;
            }
            else
            {
                gameStatus = Status.ACTIVE;
            }
        }

        public void Move(string from, string to)
        {
            lock (gate)
            {
                int source = Square(from);
                int target = Square(to);
                if (gameStatus != Status.ACTIVE || !board.Legal(source, target, white))
                {
                    throw new ArgumentException("Illegal move");
                }
                board.cells[target] = board.cells[source];
                board.cells[source] = '.';
                white = !white;
                UpdateStatus();
            }
        }

        public Status GetStatus()
        {
            lock (gate)
            {
                return gameStatus;
            }
        }

        public string Position()
        {
            lock (gate)
            {
                return new string(board.cells);
            }
        }
    }

    public static int Square(string name)
    {
        if (name == null ||
            (name.Length != 2 || name[0] < 'a' || name[0] > 'h' || name[1] < '1' || name[1] > '8'))
        {
            throw new ArgumentException("Invalid square");
        }
        return (8 - (name[1] - '0')) * 8 + name[0] - 'a';
    }

    public static string Position(params string[] pieces)
    {
        char[] cells = new char[64];
        Array.Fill(cells, '.');
        foreach (string piece in pieces)
        {
            cells[Square(piece.Substring(1))] = piece[0];
        }
        return new string(cells);
    }

    public static void Main(string[] args)
    {
        ChessGame game = new ChessGame();
        game.Move("f2", "f3");
        game.Move("e7", "e5");
        game.Move("g2", "g4");
        game.Move("d8", "h4");
        Console.WriteLine("Fool's mate: " + game.GetStatus());
        ChessGame stalemate = new ChessGame(Position("ka8", "Kc6", "Qb6"), false);
        Console.WriteLine("No legal reply: " + stalemate.GetStatus());
        Console.WriteLine("Bare kings: " + new ChessGame(Position("Ka1", "kh8"), true).GetStatus());
    }
}
