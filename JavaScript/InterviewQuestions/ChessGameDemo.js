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
 * - Synchronous operations on one event loop; no cross-worker safety.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py JavaScript InterviewQuestions/ChessGameDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ChessGame = exports.Board = void 0;
exports.square = square;
exports.position = position;
// Ordinary moves only: no castling, en passant, or promotion.

function square(name) {
    if (!/^[a-h][1-8]$/.test(name)) {
        throw new Error("Invalid square");
    }
    return (8 - Number(name[1])) * 8 + name.charCodeAt(0) - 97;
}

function position(...pieces) {
    const cells = Array(64).fill(".");
    for (const piece of pieces) {
        cells[square(piece.slice(1))] = piece[0];
    }
    return cells.join("");
}

function isWhite(piece) {
    return piece >= "A" && piece <= "Z";
}

class Board {
    cells;

    constructor(position) {
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

    pathClear(from, to) {
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

    reaches(from, to, attack = false) {
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

    inCheck(white) {
        const king = this.cells.indexOf(white ? "K" : "k");
        return this.cells.some(
            (piece, from) =>
                piece !== "." && isWhite(piece) !== white && this.reaches(from, king, true),
        );
    }

    legal(from, to, white) {
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

    hasMove(white) {
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
exports.Board = Board;

class ChessGame {
    board;
    white;
    gameStatus = "ACTIVE";

    constructor(
        position = "rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR",
        white = true,
    ) {
        this.board = new Board(position);
        this.white = white;
        this.updateStatus();
    }

    updateStatus() {
        if (!this.board.hasMove(this.white)) {
            this.gameStatus = this.board.inCheck(this.white) ? "CHECKMATE" : "STALEMATE";
        } else if (this.board.cells.filter((piece) => piece !== ".").length === 2) {
            this.gameStatus = "DRAW";
        } else {
            this.gameStatus = "ACTIVE";
        }
    }
    get status() {
        return this.gameStatus;
    }
    get position() {
        return this.board.cells.join("");
    }
    // Synchronous commands run to completion within one JS event loop.

    move(from, to) {
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
exports.ChessGame = ChessGame;
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
