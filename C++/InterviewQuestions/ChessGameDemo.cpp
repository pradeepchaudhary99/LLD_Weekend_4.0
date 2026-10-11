/*
 * INTERVIEW DESIGN NOTES - Chess Game
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Support two alternating players on a standard 8 x 8 board.
 * - Validate ordinary moves for king, queen, rook, bishop, knight and pawn.
 * - Support captures, initial pawn double moves and check detection.
 * - Reject moves exposing the moving side's king; detect checkmate and stalemate.
 * - Report ACTIVE, CHECKMATE, STALEMATE and bare-kings DRAW.
 *
 * Non-functional requirements:
 * - Keep board changes atomic within a game instance.
 * - Separate movement geometry, attack detection and turn/state management.
 * - Make rejected moves leave the board unchanged; keep scenarios reproducible.
 *
 * Core entities and responsibilities:
 * - ChessGame: owns current side, lifecycle status and move commands.
 * - Board: owns squares, paths, attack detection and legal-reply search.
 * - Piece/color: encoded as upper/lowercase symbols; a dot means empty.
 * - Position: a board index converted from algebraic squares such as e2.
 *
 * Key operations and flow:
 * - move(from, to): validate ownership and geometry, simulate, check king safety,
 * - restore the simulation, commit a legal move, switch side and classify replies.
 * - Checkmate requires no legal reply from ANY piece, not just no king move.
 *
 * Design choices and invariants:
 * - Composition separates game orchestration from board rules.
 * - Piece rules use localized dispatch rather than one subclass per piece.
 * - Pawn attacks differ from pawn advances; kings are never captured.
 * - Custom boards require 64 squares and exactly one king of each color.
 *
 * Assumptions and out of scope:
 * - No castling, en passant, promotion, clocks or move history.
 * - Moves requiring promotion are rejected; no repetition/fifty-move draw rules.
 * - Custom setups assume an otherwise legal position; this is not a full FIDE engine.
 *
 * Interview discussion and checks:
 * - Test pins, blocked paths, check evasion, pawn attacks and rejected-move stability.
 * - Discuss replacing piece dispatch with strategies and adding historical state.
 * - The fixed 64-square search favors clarity over chess-engine optimizations.
 *
 * Concurrency in this edition:
 * - Instance-level synchronization protects service/game mutations.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py 'C++' InterviewQuestions/ChessGameDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace chess {
int square(const std::string &name) {
    if (name.size() != 2 || name[0] < 'a' || name[0] > 'h' || name[1] < '1' || name[1] > '8') {
        throw std::invalid_argument("Invalid square");
    }
    return (8 - (name[1] - '0')) * 8 + name[0] - 'a';
}

std::string position(const std::vector<std::string> &pieces) {
    std::string cells(64, '.');
    for (const auto &piece : pieces) {
        cells[square(piece.substr(1))] = piece[0];
    }
    return cells;
}

bool isWhite(char piece) {
    return piece >= 'A' && piece <= 'Z';
}

char kind(char piece) {
    return isWhite(piece) ? static_cast<char>(piece + 'a' - 'A') : piece;
}

class Board {
  public:
    std::string cells;

    explicit Board(std::string position) : cells(std::move(position)) {
        if (cells.size() != 64 || std::count(cells.begin(), cells.end(), 'K') != 1 ||
            std::count(cells.begin(), cells.end(), 'k') != 1 ||
            cells.find_first_not_of(".prnbqkPRNBQK") != std::string::npos) {
            throw std::invalid_argument("Board requires 64 squares and one king per side");
        }
    }

    bool pathClear(int from, int to) const {
        int rowStep = (to / 8 > from / 8) - (to / 8 < from / 8);
        int colStep = (to % 8 > from % 8) - (to % 8 < from % 8);
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

    bool reaches(int from, int to, bool attack = false) const {
        char piece = cells[from];
        int row = to / 8 - from / 8;
        int col = to % 8 - from % 8;
        int dr = std::abs(row);
        int dc = std::abs(col);
        if (from == to || piece == '.') {
            return false;
        }
        switch (kind(piece)) {
        case 'n':
            return dr * dc == 2;
        case 'k':
            return std::max(dr, dc) == 1;
        case 'b':
            return dr == dc && pathClear(from, to);
        case 'r':
            return (row == 0 || col == 0) && pathClear(from, to);
        case 'q':
            return (dr == dc || row == 0 || col == 0) && pathClear(from, to);
        case 'p': {
            int direction = isWhite(piece) ? -1 : 1;
            if (attack) {
                return row == direction && dc == 1;
            }
            if (to / 8 == 0 || to / 8 == 7) {
                return false;
            }
            if (dc == 1) {
                return row == direction && cells[to] != '.';
            }
            return col == 0 && cells[to] == '.' &&
                   (row == direction ||
                    (from / 8 == (direction == -1 ? 6 : 1) && row == 2 * direction &&
                     cells[from + 8 * direction] == '.'));
        }
        default:
            return false;
        }
    }

    bool inCheck(bool white) const {
        int king = static_cast<int>(cells.find(white ? 'K' : 'k'));
        for (int from = 0; from < 64; from++) {
            if (cells[from] != '.' && isWhite(cells[from]) != white && reaches(from, king, true)) {
                return true;
            }
        }
        return false;
    }

    bool legal(int from, int to, bool white) {
        if (from < 0 || from >= 64 || to < 0 || to >= 64 || cells[from] == '.' ||
            isWhite(cells[from]) != white || (cells[to] != '.' && isWhite(cells[to]) == white) ||
            kind(cells[to]) == 'k' || !reaches(from, to)) {
            return false;
        }
        char moving = cells[from];
        char captured = cells[to];
        cells[from] = '.';
        cells[to] = moving;
        bool safe = !inCheck(white);
        cells[from] = moving;
        cells[to] = captured;
        return safe;
    }

    bool hasMove(bool white) {
        for (int from = 0; from < 64; from++) {
            for (int to = 0; to < 64; to++) {
                if (legal(from, to, white)) {
                    return true;
                }
            }
        }
        return false;
    }
};

class ChessGame {
    Board board;
    bool white;
    std::string gameStatus;
    mutable std::mutex gate;

    void updateStatus() {
        if (!board.hasMove(white)) {
            gameStatus = board.inCheck(white) ? "CHECKMATE" : "STALEMATE";
        } else if (std::count(board.cells.begin(), board.cells.end(), '.') == 62) {
            gameStatus = "DRAW";
        } else {
            gameStatus = "ACTIVE";
        }
    }

  public:
    explicit ChessGame(
        std::string position = "rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR",
        bool white = true)
        : board(std::move(position)), white(white) {
        updateStatus();
    }

    void move(const std::string &from, const std::string &to) {
        std::lock_guard<std::mutex> lock(gate);
        int source = square(from);
        int target = square(to);
        if (gameStatus != "ACTIVE" || !board.legal(source, target, white)) {
            throw std::invalid_argument("Illegal move");
        }
        board.cells[target] = board.cells[source];
        board.cells[source] = '.';
        white = !white;
        updateStatus();
    }

    std::string status() const {
        std::lock_guard<std::mutex> lock(gate);
        return gameStatus;
    }

    std::string position() const {
        std::lock_guard<std::mutex> lock(gate);
        return board.cells;
    }
};
} // namespace chess

#ifndef LLD_TEST
int main() {
    using namespace chess;
    ChessGame game;
    game.move("f2", "f3");
    game.move("e7", "e5");
    game.move("g2", "g4");
    game.move("d8", "h4");
    std::cout << "Fool's mate: " << game.status() << '\n';
    std::cout << "No legal reply: " << ChessGame(position({"ka8", "Kc6", "Qb6"}), false).status()
              << '\n';
    std::cout << "Bare kings: " << ChessGame(position({"Ka1", "kh8"})).status() << '\n';
}
#endif
