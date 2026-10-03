// Ordinary moves only: no castling, en passant, or promotion.
export function square(name: string): number {
    if (!/^[a-h][1-8]$/.test(name)) {
        throw new Error("Invalid square");
    }
    return (8 - Number(name[1])) * 8 + name.charCodeAt(0) - 97;
}

export function position(...pieces: string[]): string {
    const cells = Array<string>(64).fill(".");
    for (const piece of pieces) {
        cells[square(piece.slice(1))] = piece[0];
    }
    return cells.join("");
}

function isWhite(piece: string): boolean {
    return piece >= "A" && piece <= "Z";
}

export class Board {
    readonly cells: string[];

    constructor(position: string) {
        if (
            position.length !== 64 ||
            position.split("K").length !== 2 ||
            position.split("k").length !== 2 ||
            !/^[.prnbqkPRNBQK]+$/.test(position)
        ) {
            throw new Error("Board requires 64 squares and one king per side");
        }
        this.cells = [...position];
    }

    private pathClear(from: number, to: number): boolean {
        const rowStep = Math.sign(Math.floor(to / 8) - Math.floor(from / 8));
        const colStep = Math.sign((to % 8) - (from % 8));
        let row = Math.floor(from / 8) + rowStep;
        let col = (from % 8) + colStep;
        while (row * 8 + col !== to) {
            if (this.cells[row * 8 + col] !== ".") {
                return false;
            }
            row += rowStep;
            col += colStep;
        }
        return true;
    }

    reaches(from: number, to: number, attack = false): boolean {
        const piece = this.cells[from];
        const row = Math.floor(to / 8) - Math.floor(from / 8);
        const col = (to % 8) - (from % 8);
        const dr = Math.abs(row);
        const dc = Math.abs(col);
        if (from === to || piece === ".") {
            return false;
        }
        switch (piece.toLowerCase()) {
            case "n":
                return dr * dc === 2;
            case "k":
                return Math.max(dr, dc) === 1;
            case "b":
                return dr === dc && this.pathClear(from, to);
            case "r":
                return (row === 0 || col === 0) && this.pathClear(from, to);
            case "q":
                return (dr === dc || row === 0 || col === 0) && this.pathClear(from, to);
            case "p": {
                const direction = isWhite(piece) ? -1 : 1;
                if (attack) {
                    return row === direction && dc === 1;
                }
                if (Math.floor(to / 8) === 0 || Math.floor(to / 8) === 7) {
                    return false;
                }
                if (dc === 1) {
                    return row === direction && this.cells[to] !== ".";
                }
                return (
                    col === 0 &&
                    this.cells[to] === "." &&
                    (row === direction ||
                        (Math.floor(from / 8) === (direction === -1 ? 6 : 1) &&
                            row === 2 * direction &&
                            this.cells[from + 8 * direction] === "."))
                );
            }
            default:
                return false;
        }
    }

    inCheck(white: boolean): boolean {
        const king = this.cells.indexOf(white ? "K" : "k");
        return this.cells.some(
            (piece, from) =>
                piece !== "." && isWhite(piece) !== white && this.reaches(from, king, true),
        );
    }

    legal(from: number, to: number, white: boolean): boolean {
        if (from < 0 || from >= 64 || to < 0 || to >= 64) {
            return false;
        }
        const moving = this.cells[from];
        const captured = this.cells[to];
        if (
            moving === "." ||
            isWhite(moving) !== white ||
            (captured !== "." && isWhite(captured) === white) ||
            captured.toLowerCase() === "k" ||
            !this.reaches(from, to)
        ) {
            return false;
        }
        this.cells[from] = ".";
        this.cells[to] = moving;
        try {
            return !this.inCheck(white);
        } finally {
            this.cells[from] = moving;
            this.cells[to] = captured;
        }
    }

    hasMove(white: boolean): boolean {
        for (let from = 0; from < 64; from++) {
            for (let to = 0; to < 64; to++) {
                if (this.legal(from, to, white)) {
                    return true;
                }
            }
        }
        return false;
    }
}

export class ChessGame {
    private readonly board: Board;
    private white: boolean;
    private gameStatus = "ACTIVE";

    constructor(
        position = "rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR",
        white = true,
    ) {
        this.board = new Board(position);
        this.white = white;
        this.updateStatus();
    }

    private updateStatus(): void {
        if (!this.board.hasMove(this.white)) {
            this.gameStatus = this.board.inCheck(this.white) ? "CHECKMATE" : "STALEMATE";
        } else if (this.board.cells.filter((piece) => piece !== ".").length === 2) {
            this.gameStatus = "DRAW";
        } else {
            this.gameStatus = "ACTIVE";
        }
    }

    get status(): string {
        return this.gameStatus;
    }

    get position(): string {
        return this.board.cells.join("");
    }

    // Synchronous commands run to completion within one JS event loop.
    move(from: string, to: string): void {
        const source = square(from);
        const target = square(to);
        if (this.gameStatus !== "ACTIVE" || !this.board.legal(source, target, this.white)) {
            throw new Error("Illegal move");
        }
        this.board.cells[target] = this.board.cells[source];
        this.board.cells[source] = ".";
        this.white = !this.white;
        this.updateStatus();
    }
}

if (require.main === module) {
    const game = new ChessGame();
    game.move("f2", "f3");
    game.move("e7", "e5");
    game.move("g2", "g4");
    game.move("d8", "h4");
    console.log("Fool's mate: " + game.status);
    console.log("No legal reply: " + new ChessGame(position("ka8", "Kc6", "Qb6"), false).status);
    console.log("Bare kings: " + new ChessGame(position("Ka1", "kh8")).status);
}
