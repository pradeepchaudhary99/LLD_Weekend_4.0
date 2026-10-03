package InterviewQuestions;

import java.util.Arrays;

/** Ordinary chess moves only: no castling, en passant, or promotion. */
public class ChessGameDemo {
    public enum Status { ACTIVE, CHECKMATE, STALEMATE, DRAW }

    public static final class Board {
        private final char[] cells;

        public Board(String position) {
            cells = position.toCharArray();
            if (cells.length != 64 || position.chars().filter(c -> c == 'K').count() != 1 ||
                position.chars().filter(c -> c == 'k').count() != 1 ||
                !position.matches("[.prnbqkPRNBQK]+")) {
                throw new IllegalArgumentException(
                    "Board requires 64 squares and one king per side");
            }
        }

        boolean pathClear(int from, int to) {
            int rowStep = Integer.signum(to / 8 - from / 8);
            int colStep = Integer.signum(to % 8 - from % 8);
            int row = from / 8 + rowStep;
            int col = from % 8 + colStep;
            while (row * 8 + col != to) {
                if (cells[row * 8 + col] != '.') {
                    return false;
                }
                row += rowStep;
                col += colStep;
            }
            return true;
        }

        // Attack geometry is separate from pawn movement and king safety.
        boolean reaches(int from, int to, boolean attack) {
            char piece = cells[from];
            int row = to / 8 - from / 8;
            int col = to % 8 - from % 8;
            int dr = Math.abs(row);
            int dc = Math.abs(col);
            if (from == to || piece == '.') {
                return false;
            }
            return switch (Character.toLowerCase(piece)) {
                case 'n' -> dr *dc == 2;
                case 'k' -> Math.max(dr, dc) == 1;
                case 'b' -> dr == dc &&pathClear(from, to);
                case 'r' -> (row == 0 || col == 0) && pathClear(from, to);
                case 'q' -> (dr == dc || row == 0 || col == 0) && pathClear(from, to);
                case 'p' -> {
                    int direction = Character.isUpperCase(piece) ? -1 : 1;
                    if (attack) {
                        yield row == direction &&dc == 1;
                    }
                    // Reject a move requiring the explicitly excluded promotion rule.
                    if (to / 8 == 0 || to / 8 == 7) {
                        yield false;
                    }
                    if (dc == 1) {
                        yield row == direction &&cells[to] != '.';
                    }
                    yield col == 0 && cells[to] == '.' &&
                        (row == direction ||
                         (from / 8 == (direction == -1 ? 6 : 1) && row == 2 * direction &&
                          cells[from + 8 * direction] == '.'));
                }
                default -> false;
            };
        }

        boolean inCheck(boolean white) {
            int king = new String(cells).indexOf(white ? 'K' : 'k');
            for (int from = 0; from < 64; from++) {
                if (cells[from] != '.' && Character.isUpperCase(cells[from]) != white &&
                    reaches(from, king, true)) {
                    return true;
                }
            }
            return false;
        }

        boolean legal(int from, int to, boolean white) {
            if (from < 0 || from >= 64 || to < 0 || to >= 64 || cells[from] == '.' ||
                Character.isUpperCase(cells[from]) != white ||
                (cells[to] != '.' && Character.isUpperCase(cells[to]) == white) ||
                Character.toLowerCase(cells[to]) == 'k' || !reaches(from, to, false)) {
                return false;
            }
            char moving = cells[from];
            char captured = cells[to];
            cells[to] = moving;
            cells[from] = '.';
            boolean safe = !inCheck(white);
            cells[from] = moving;
            cells[to] = captured;
            return safe;
        }

        boolean hasMove(boolean white) {
            for (int from = 0; from < 64; from++) {
                for (int to = 0; to < 64; to++) {
                    if (legal(from, to, white)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public static final class ChessGame {
        private final Board board;
        private boolean white;
        private Status status;

        public ChessGame() {
            this("rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR", true);
        }

        public ChessGame(String position, boolean white) {
            board = new Board(position);
            this.white = white;
            updateStatus();
        }

        private void updateStatus() {
            if (!board.hasMove(white)) {
                status = board.inCheck(white) ? Status.CHECKMATE : Status.STALEMATE;
            } else if (new String(board.cells).replace(".", "").length() == 2) {
                status = Status.DRAW;
            } else {
                status = Status.ACTIVE;
            }
        }

        public synchronized void move(String from, String to) {
            int source = square(from);
            int target = square(to);
            if (status != Status.ACTIVE || !board.legal(source, target, white)) {
                throw new IllegalArgumentException("Illegal move");
            }
            board.cells[target] = board.cells[source];
            board.cells[source] = '.';
            white = !white;
            updateStatus();
        }

        public synchronized Status status() {
            return status;
        }

        public synchronized String position() {
            return new String(board.cells);
        }
    }

    public static int square(String name) {
        if (name == null || !name.matches("[a-h][1-8]")) {
            throw new IllegalArgumentException("Invalid square");
        }
        return (8 - (name.charAt(1) - '0')) * 8 + name.charAt(0) - 'a';
    }

    public static String position(String... pieces) {
        char[] cells = new char[64];
        Arrays.fill(cells, '.');
        for (String piece : pieces) {
            cells[square(piece.substring(1))] = piece.charAt(0);
        }
        return new String(cells);
    }

    public static void main(String[] args) {
        ChessGame game = new ChessGame();
        game.move("f2", "f3");
        game.move("e7", "e5");
        game.move("g2", "g4");
        game.move("d8", "h4");
        System.out.println("Fool's mate: " + game.status());
        ChessGame stalemate = new ChessGame(position("ka8", "Kc6", "Qb6"), false);
        System.out.println("No legal reply: " + stalemate.status());
        System.out.println("Bare kings: " + new ChessGame(position("Ka1", "kh8"), true).status());
    }
}
