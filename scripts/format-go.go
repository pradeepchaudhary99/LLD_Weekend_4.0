// Expand compact Go teaching declarations, then apply standard gofmt formatting.
// Run from the repository root: go run scripts/format-go.go
package main

import (
	"bytes"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

func main() {
	err := filepath.WalkDir("Go", func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || !strings.HasSuffix(path, ".go") {
			return nil
		}
		source, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		positions := token.NewFileSet()
		file, err := parser.ParseFile(positions, path, source, parser.ParseComments)
		if err != nil {
			return err
		}
		inserts := map[int]bool{}
		offset := func(pos token.Pos) int { return positions.Position(pos).Offset }
		expand := func(open, close token.Pos) {
			start, end := offset(open)+1, offset(close)
			if !bytes.Contains(source[start:end], []byte("\n")) {
				inserts[start] = true
				inserts[end] = true
			}
		}
		ast.Inspect(file, func(node ast.Node) bool {
			switch n := node.(type) {
			case *ast.BlockStmt:
				if len(n.List) > 0 {
					expand(n.Lbrace, n.Rbrace)
					for i := 0; i+1 < len(n.List); i++ {
						end, next := offset(n.List[i].End()), offset(n.List[i+1].Pos())
						if !bytes.Contains(source[end:next], []byte("\n")) {
							// Preserve explicit semicolons; gofmt removes them after expansion.
							inserts[next] = true
						}
					}
				}
			case *ast.StructType:
				if len(n.Fields.List) > 0 {
					expand(n.Fields.Opening, n.Fields.Closing)
				}
			case *ast.InterfaceType:
				if len(n.Methods.List) > 0 {
					expand(n.Methods.Opening, n.Methods.Closing)
				}
			}
			return true
		})
		offsets := make([]int, 0, len(inserts))
		for value := range inserts {
			offsets = append(offsets, value)
		}
		sort.Sort(sort.Reverse(sort.IntSlice(offsets)))
		for _, point := range offsets {
			source = append(source[:point], append([]byte("\n"), source[point:]...)...)
		}
		output, err := format.Source(source)
		if err != nil {
			return err
		}
		output = bytes.ReplaceAll(output, []byte("}\nfunc "), []byte("}\n\nfunc "))
		output = bytes.ReplaceAll(output, []byte("}\ntype "), []byte("}\n\ntype "))
		return os.WriteFile(path, output, 0644)
	})
	if err != nil {
		panic(err)
	}
}
