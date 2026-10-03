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
