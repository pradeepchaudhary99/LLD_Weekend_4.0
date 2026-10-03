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
