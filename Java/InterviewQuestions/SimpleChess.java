/*
 * SIMPLE JAVA - Chess
 *
 * Functional requirements (MVP): Alternate turns; validate six piece types, captures and king
 * safety; detect mate/stalemate. Non-functional requirements: readable, in-memory, safe access
 * within one JVM. Core entities: ChessGame owns an 8x8 character board, current turn and terminal
 * status. Operations and flow: move: check geometry -> simulate -> verify king safety -> restore ->
 * commit -> switch turn. Design choices and concurrency: synchronized protects board/turn together;
 * private move simulations never escape the lock. Assumptions / omitted features: Standard initial
 * board, auto-queen promotion. No castling, en passant, underpromotion, repetition, fifty-move or
 * insufficient-material draws. Not a full tournament engine. Edge cases to discuss/test: Wrong
 * turn, blocked moves, captures, check evasion, rejected-move stability and Fool's mate. Run from
 * repository root (Java 17+ and Python 3): python3 scripts/run.py Java
 * InterviewQuestions/SimpleChess Read SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

public class SimpleChess {
    static class ChessGame {
        // Uppercase = white; lowercase = black; '.' = empty. Row 0 is rank 8.
        private final char[][] board = {"rnbqkbnr".toCharArray(), "pppppppp".toCharArray(),
                                        "........".toCharArray(), "........".toCharArray(),
                                        "........".toCharArray(), "........".toCharArray(),
                                        "PPPPPPPP".toCharArray(), "RNBQKBNR".toCharArray()};
        private boolean whiteTurn = true;
        private String status = "ACTIVE";

        synchronized void move(String from, String to) {
            int start = square(from);
            int end = square(to);
            if (!status.equals("ACTIVE") || !legal(start, end, whiteTurn)) {
                throw new IllegalArgumentException("Illegal move");
            }
            char piece = board[start / 8][start % 8];
            board[start / 8][start % 8] = '.';
            // Keep promotion simple: always choose a queen.
            if (Character.toLowerCase(piece) == 'p' && (end / 8 == 0 || end / 8 == 7)) {
                piece = whiteTurn ? 'Q' : 'q';
            }
            board[end / 8][end % 8] = piece;
            whiteTurn = !whiteTurn;
            if (!hasLegalMove(whiteTurn)) {
                status = inCheck(whiteTurn) ? "CHECKMATE" : "STALEMATE";
            }
        }

        private int square(String name) {
            if (name == null || !name.matches("[a-h][1-8]")) {
                throw new IllegalArgumentException("Use a square such as e2");
            }
            return (8 - (name.charAt(1) - '0')) * 8 + name.charAt(0) - 'a';
        }

        // Called only while the public move holds this game's lock.
        private boolean legal(int from, int to, boolean white) {
            char piece = board[from / 8][from % 8];
            char target = board[to / 8][to % 8];
            if (from == to || piece == '.' || Character.isUpperCase(piece) != white ||
                (target != '.' && Character.isUpperCase(target) == white) ||
                Character.toLowerCase(target) == 'k' || !canReach(from, to, false)) {
                return false;
            }
            // Try the move, check king safety, then restore the board.
            board[from / 8][from % 8] = '.';
            board[to / 8][to % 8] = piece;
            boolean safe = !inCheck(white);
            board[from / 8][from % 8] = piece;
            board[to / 8][to % 8] = target;
            return safe;
        }

        private boolean canReach(int from, int to, boolean attackOnly) {
            int row = from / 8;
            int col = from % 8;
            int dr = to / 8 - row;
            int dc = to % 8 - col;
            char piece = board[row][col];
            char target = board[to / 8][to % 8];
            switch (Character.toLowerCase(piece)) {
            case 'p':
                int direction = Character.isUpperCase(piece) ? -1 : 1;
                if (attackOnly || target != '.') {
                    return dr == direction && Math.abs(dc) == 1;
                }
                int home = Character.isUpperCase(piece) ? 6 : 1;
                return dc == 0 && (dr == direction || (row == home && dr == 2 * direction &&
                                                       board[row + direction][col] == '.'));
            case 'n':
                return Math.abs(dr) * Math.abs(dc) == 2;
            case 'k':
                return Math.max(Math.abs(dr), Math.abs(dc)) == 1;
            case 'b':
                return Math.abs(dr) == Math.abs(dc) && pathClear(from, to);
            case 'r':
                return (dr == 0 || dc == 0) && pathClear(from, to);
            case 'q':
                return (dr == 0 || dc == 0 || Math.abs(dr) == Math.abs(dc)) && pathClear(from, to);
            default:
                return false;
            }
        }

        private boolean pathClear(int from, int to) {
            int rowStep = Integer.signum(to / 8 - from / 8);
            int colStep = Integer.signum(to % 8 - from % 8);
            int row = from / 8 + rowStep;
            int col = from % 8 + colStep;
            while (row != to / 8 || col != to % 8) {
                if (board[row][col] != '.') {
                    return false;
                }
                row += rowStep;
                col += colStep;
            }
            return true;
        }

        private boolean inCheck(boolean white) {
            int king = -1;
            for (int square = 0; square < 64; square++) {
                if (board[square / 8][square % 8] == (white ? 'K' : 'k')) {
                    king = square;
                }
            }
            for (int square = 0; square < 64; square++) {
                char piece = board[square / 8][square % 8];
                if (piece != '.' && Character.isUpperCase(piece) != white &&
                    canReach(square, king, true)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasLegalMove(boolean white) {
            for (int from = 0; from < 64; from++) {
                for (int to = 0; to < 64; to++) {
                    if (legal(from, to, white)) {
                        return true;
                    }
                }
            }
            return false;
        }

        synchronized String status() {
            return status;
        }

        synchronized char pieceAt(String square) {
            int index = square(square);
            return board[index / 8][index % 8];
        }
    }

    public static void main(String[] args) {
        ChessGame game = new ChessGame();
        game.move("f2", "f3");
        game.move("e7", "e5");
        game.move("g2", "g4");
        game.move("d8", "h4");
        System.out.println("Fool's mate: " + game.status());
    }
}
