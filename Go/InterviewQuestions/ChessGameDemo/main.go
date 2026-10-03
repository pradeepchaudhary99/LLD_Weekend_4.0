// Ordinary moves only: castling, promotion and en passant are excluded.
package main

import (
	"errors"
	"fmt"
	"strings"
	"sync"
)

func square(name string) (int, error) {
	if len(name) != 2 || name[0] < 'a' || name[0] > 'h' || name[1] < '1' || name[1] > '8' {
		return 0, errors.New("invalid square")
	}
	return (8-int(name[1]-'0'))*8 + int(name[0]-'a'), nil
}

func position(pieces ...string) string {
	cells := []byte(strings.Repeat(".", 64))
	for _, piece := range pieces {
		index, err := square(piece[1:])
		if err != nil {
			panic(err)
		}
		cells[index] = piece[0]
	}
	return string(cells)
}

func isWhite(piece byte) bool {
	return piece >= 'A' && piece <= 'Z'
}

func kind(piece byte) byte {
	if isWhite(piece) {
		return piece + 'a' - 'A'
	}
	return piece
}

func abs(value int) int {
	if value < 0 {
		return -value
	}
	return value
}

func sign(value int) int {
	if value < 0 {
		return -1
	}
	if value > 0 {
		return 1
	}
	return 0
}

type Board struct {
	cells []byte
}

func newBoard(position string) (*Board, error) {
	if len(position) != 64 || strings.Count(position, "K") != 1 || strings.Count(position, "k") != 1 {
		return nil, errors.New("board requires 64 squares and one king per side")
	}
	for _, piece := range position {
		if !strings.ContainsRune(".prnbqkPRNBQK", piece) {
			return nil, errors.New("unknown piece")
		}
	}
	return &Board{cells: []byte(position)}, nil
}

func (b *Board) pathClear(from, to int) bool {
	rowStep := sign(to/8 - from/8)
	colStep := sign(to%8 - from%8)
	row, col := from/8+rowStep, from%8+colStep
	for row*8+col != to {
		if b.cells[row*8+col] != '.' {
			return false
		}
		row += rowStep
		col += colStep
	}
	return true
}

func (b *Board) reaches(from, to int, attack bool) bool {
	piece := b.cells[from]
	row, col := to/8-from/8, to%8-from%8
	dr, dc := abs(row), abs(col)
	if from == to || piece == '.' {
		return false
	}
	switch kind(piece) {
	case 'n':
		return dr*dc == 2
	case 'k':
		return max(dr, dc) == 1
	case 'b':
		return dr == dc && b.pathClear(from, to)
	case 'r':
		return (row == 0 || col == 0) && b.pathClear(from, to)
	case 'q':
		return (dr == dc || row == 0 || col == 0) && b.pathClear(from, to)
	case 'p':
		direction, start := 1, 1
		if isWhite(piece) {
			direction, start = -1, 6
		}
		if attack {
			return row == direction && dc == 1
		}
		if to/8 == 0 || to/8 == 7 {
			return false
		}
		if dc == 1 {
			return row == direction && b.cells[to] != '.'
		}
		return col == 0 && b.cells[to] == '.' && (row == direction || (from/8 == start && row == 2*direction && b.cells[from+8*direction] == '.'))
	}
	return false
}

func (b *Board) inCheck(white bool) bool {
	kingPiece := byte('k')
	if white {
		kingPiece = 'K'
	}
	king := strings.IndexByte(string(b.cells), kingPiece)
	for from, piece := range b.cells {
		if piece != '.' && isWhite(piece) != white && b.reaches(from, king, true) {
			return true
		}
	}
	return false
}

func (b *Board) legal(from, to int, white bool) bool {
	if from < 0 || from >= 64 || to < 0 || to >= 64 {
		return false
	}
	moving, captured := b.cells[from], b.cells[to]
	if moving == '.' || isWhite(moving) != white || (captured != '.' && isWhite(captured) == white) || kind(captured) == 'k' || !b.reaches(from, to, false) {
		return false
	}
	b.cells[from], b.cells[to] = '.', moving
	safe := !b.inCheck(white)
	b.cells[from], b.cells[to] = moving, captured
	return safe
}

func (b *Board) hasMove(white bool) bool {
	for from := 0; from < 64; from++ {
		for to := 0; to < 64; to++ {
			if b.legal(from, to, white) {
				return true
			}
		}
	}
	return false
}

type ChessGame struct {
	board      *Board
	white      bool
	gameStatus string
	gate       sync.Mutex
}

func NewChessGame(position string, white bool) (*ChessGame, error) {
	board, err := newBoard(position)
	if err != nil {
		return nil, err
	}
	game := &ChessGame{board: board, white: white}
	game.updateStatus()
	return game, nil
}

func (g *ChessGame) updateStatus() {
	if !g.board.hasMove(g.white) {
		g.gameStatus = "STALEMATE"
		if g.board.inCheck(g.white) {
			g.gameStatus = "CHECKMATE"
		}
	} else if strings.Count(string(g.board.cells), ".") == 62 {
		g.gameStatus = "DRAW"
	} else {
		g.gameStatus = "ACTIVE"
	}
}

func (g *ChessGame) Move(from, to string) error {
	g.gate.Lock()
	defer g.gate.Unlock()
	source, err := square(from)
	if err != nil {
		return err
	}
	target, err := square(to)
	if err != nil {
		return err
	}
	if g.gameStatus != "ACTIVE" || !g.board.legal(source, target, g.white) {
		return errors.New("illegal move")
	}
	g.board.cells[target] = g.board.cells[source]
	g.board.cells[source] = '.'
	g.white = !g.white
	g.updateStatus()
	return nil
}

func (g *ChessGame) Status() string {
	g.gate.Lock()
	defer g.gate.Unlock()
	return g.gameStatus
}

func (g *ChessGame) Position() string {
	g.gate.Lock()
	defer g.gate.Unlock()
	return string(g.board.cells)
}

func main() {
	game, err := NewChessGame("rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR", true)
	if err != nil {
		panic(err)
	}
	for _, move := range [][2]string{{"f2", "f3"}, {"e7", "e5"}, {"g2", "g4"}, {"d8", "h4"}} {
		if err := game.Move(move[0], move[1]); err != nil {
			panic(err)
		}
	}
	fmt.Println("Fool's mate:", game.Status())
	stalemate, _ := NewChessGame(position("ka8", "Kc6", "Qb6"), false)
	fmt.Println("No legal reply:", stalemate.Status())
	draw, _ := NewChessGame(position("Ka1", "kh8"), true)
	fmt.Println("Bare kings:", draw.Status())
}
