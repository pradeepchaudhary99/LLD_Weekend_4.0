# INTERVIEW DESIGN NOTES - Chess Game
#
# Implementation status: runnable in-memory teaching example. Requirements
# below describe this edition; production extensions are explicitly excluded.
#
# Functional requirements:
# - Support two alternating players on a standard 8 x 8 board.
# - Validate ordinary moves for king, queen, rook, bishop, knight and pawn.
# - Support captures, initial pawn double moves and check detection.
# - Reject moves exposing the moving side's king; detect checkmate and stalemate.
# - Report ACTIVE, CHECKMATE, STALEMATE and bare-kings DRAW.
#
# Non-functional requirements:
# - Keep board changes atomic within a game instance.
# - Separate movement geometry, attack detection and turn/state management.
# - Make rejected moves leave the board unchanged; keep scenarios reproducible.
#
# Core entities and responsibilities:
# - ChessGame: owns current side, lifecycle status and move commands.
# - Board: owns squares, paths, attack detection and legal-reply search.
# - Piece/color: encoded as upper/lowercase symbols; a dot means empty.
# - Position: a board index converted from algebraic squares such as e2.
#
# Key operations and flow:
# - move(from, to): validate ownership and geometry, simulate, check king safety,
# - restore the simulation, commit a legal move, switch side and classify replies.
# - Checkmate requires no legal reply from ANY piece, not just no king move.
#
# Design choices and invariants:
# - Composition separates game orchestration from board rules.
# - Piece rules use localized dispatch rather than one subclass per piece.
# - Pawn attacks differ from pawn advances; kings are never captured.
# - Custom boards require 64 squares and exactly one king of each color.
#
# Assumptions and out of scope:
# - No castling, en passant, promotion, clocks or move history.
# - Moves requiring promotion are rejected; no repetition/fifty-move draw rules.
# - Custom setups assume an otherwise legal position; this is not a full FIDE engine.
#
# Interview discussion and checks:
# - Test pins, blocked paths, check evasion, pawn attacks and rejected-move stability.
# - Discuss replacing piece dispatch with strategies and adding historical state.
# - The fixed 64-square search favors clarity over chess-engine optimizations.
#
# Concurrency in this edition:
# - Instance-level synchronization protects service/game mutations.
#
# Run / validation:
# - From repository root:
#   python3 scripts/run.py Python InterviewQuestions/ChessGameDemo
# - Prerequisites and focused checks: see README.md and the topic guide.

"""Ordinary chess moves; castling, promotion and en passant are excluded."""

from threading import RLock


def square(name):
    if (
        not isinstance(name, str)
        or len(name) != 2
        or name[0] not in "abcdefgh"
        or name[1] not in "12345678"
    ):
        raise ValueError("Invalid square")
    return (8 - int(name[1])) * 8 + ord(name[0]) - ord("a")


def position(*pieces):
    cells = ["."] * 64
    for piece in pieces:
        cells[square(piece[1:])] = piece[0]
    return "".join(cells)


class Board:
    def __init__(self, position):
        if (
            len(position) != 64
            or position.count("K") != 1
            or position.count("k") != 1
            or any(p not in ".prnbqkPRNBQK" for p in position)
        ):
            raise ValueError("Board requires 64 squares and one king per side")
        self.cells = list(position)

    def path_clear(self, source, target):
        row_step = (target // 8 > source // 8) - (target // 8 < source // 8)
        col_step = (target % 8 > source % 8) - (target % 8 < source % 8)
        row, col = source // 8 + row_step, source % 8 + col_step
        while row * 8 + col != target:
            if self.cells[row * 8 + col] != ".":
                return False
            row += row_step
            col += col_step
        return True

    def reaches(self, source, target, attack=False):
        piece = self.cells[source]
        row, col = target // 8 - source // 8, target % 8 - source % 8
        dr, dc = abs(row), abs(col)
        if source == target or piece == ".":
            return False
        kind = piece.lower()
        if kind == "n":
            return dr * dc == 2
        if kind == "k":
            return max(dr, dc) == 1
        if kind == "b":
            return dr == dc and self.path_clear(source, target)
        if kind == "r":
            return (row == 0 or col == 0) and self.path_clear(source, target)
        if kind == "q":
            return (dr == dc or row == 0 or col == 0) and self.path_clear(source, target)
        direction = -1 if piece.isupper() else 1
        if attack:
            return row == direction and dc == 1
        if target // 8 in (0, 7):
            return False
        if dc == 1:
            return row == direction and self.cells[target] != "."
        return (
            col == 0
            and self.cells[target] == "."
            and (
                row == direction
                or (
                    source // 8 == (6 if direction == -1 else 1)
                    and row == 2 * direction
                    and self.cells[source + 8 * direction] == "."
                )
            )
        )

    def in_check(self, white):
        king = self.cells.index("K" if white else "k")
        return any(
            piece != "." and piece.isupper() != white and self.reaches(source, king, True)
            for source, piece in enumerate(self.cells)
        )

    def legal(self, source, target, white):
        if not (0 <= source < 64 and 0 <= target < 64):
            return False
        piece, captured = self.cells[source], self.cells[target]
        if (
            piece == "."
            or piece.isupper() != white
            or (captured != "." and captured.isupper() == white)
            or captured.lower() == "k"
            or not self.reaches(source, target)
        ):
            return False
        self.cells[source], self.cells[target] = ".", piece
        try:
            return not self.in_check(white)
        finally:
            self.cells[source], self.cells[target] = piece, captured

    def has_move(self, white):
        return any(
            self.legal(source, target, white) for source in range(64) for target in range(64)
        )


class ChessGame:
    def __init__(
        self,
        position="rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR",
        white=True,
    ):
        self._board = Board(position)
        self._white = white
        self._lock = RLock()
        self._update_status()

    def _update_status(self):
        if not self._board.has_move(self._white):
            self._status = "CHECKMATE" if self._board.in_check(self._white) else "STALEMATE"
        elif sum(piece != "." for piece in self._board.cells) == 2:
            self._status = "DRAW"
        else:
            self._status = "ACTIVE"

    @property
    def status(self):
        with self._lock:
            return self._status

    @property
    def position(self):
        with self._lock:
            return "".join(self._board.cells)

    def move(self, source, target):
        with self._lock:
            source, target = square(source), square(target)
            if self._status != "ACTIVE" or not self._board.legal(source, target, self._white):
                raise ValueError("Illegal move")
            self._board.cells[target] = self._board.cells[source]
            self._board.cells[source] = "."
            self._white = not self._white
            self._update_status()


if __name__ == "__main__":
    game = ChessGame()
    for source, target in (("f2", "f3"), ("e7", "e5"), ("g2", "g4"), ("d8", "h4")):
        game.move(source, target)
    print("Fool's mate:", game.status)
    print("No legal reply:", ChessGame(position("ka8", "Kc6", "Qb6"), False).status)
    print("Bare kings:", ChessGame(position("Ka1", "kh8")).status)
