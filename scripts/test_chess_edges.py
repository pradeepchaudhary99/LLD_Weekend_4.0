#!/usr/bin/env python3
"""Exercise the same chess edge cases in all seven native implementations."""

import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
INITIAL = "rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR"


def position(*pieces):
    cells = ["."] * 64
    for piece in pieces:
        cells[(8 - int(piece[2])) * 8 + ord(piece[1]) - ord("a")] = piece[0]
    return "".join(cells)


CASES = [
    (
        INITIAL,
        True,
        [
            ("e7", "e5", False),
            ("a1", "a3", False),
            ("c1", "h6", False),
            ("e2", "e5", False),
            ("z1", "a2", False),
            ("e2", "e4", True),
            ("d7", "d5", True),
            ("e4", "d5", True),
            ("g8", "f6", True),
        ],
        "ACTIVE",
    ),
    (
        position("Ka1", "Ra2", "ra8", "kh8"),
        True,
        [("a2", "b2", False), ("a2", "a8", True)],
        "ACTIVE",
    ),
    (position("Ke1", "Ra2", "re8", "kh8"), True, [("a2", "e2", True)], "ACTIVE"),
    (position("Ke1", "Re2", "re8", "kh8"), True, [("e2", "e8", True)], "ACTIVE"),
    (position("Ka3", "Ra1", "pb5", "kh8"), True, [("a3", "a4", False)], "ACTIVE"),
    (position("Ke1", "Ra1", "ke3"), True, [("e1", "e2", False)], "ACTIVE"),
    (position("Ka1", "kh8", "Pa7"), True, [("a7", "a8", False)], "ACTIVE"),
    (
        position("Ke1", "ke8", "Pe2", "ne3"),
        True,
        [("e2", "e4", False), ("e2", "e3", False)],
        "ACTIVE",
    ),
    (position("ka8", "Kc6", "Qb6"), False, [("a8", "b8", False)], "STALEMATE"),
    (position("Ka1", "kh8"), True, [("a1", "a2", False)], "DRAW"),
    (
        INITIAL,
        True,
        [
            ("f2", "f3", True),
            ("e7", "e5", True),
            ("g2", "g4", True),
            ("d8", "h4", True),
            ("a2", "a3", False),
        ],
        "CHECKMATE",
    ),
]


def run(args, cwd=ROOT):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True, timeout=180)


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def check_python():
    chess = load("chess_lesson", ROOT / "Python/InterviewQuestions/ChessGameDemo.py")
    for board, white, moves, status in CASES:
        game = chess.ChessGame(board, white)
        for source, target, expected in moves:
            before = game.position
            accepted = True
            try:
                game.move(source, target)
            except ValueError:
                accepted = False
            assert accepted == expected, (source, target)
            assert accepted or game.position == before
        assert game.status == status
    print("PASS Python chess edge cases", flush=True)


def native_chess(directory):
    # Generated harnesses keep the fixtures identical without translating the engine.
    java = [
        "import InterviewQuestions.ChessGameDemo.*;",
        "public class ChessChecks {",
        "public static void main(String[] args) {",
    ]
    cs = [
        "using System;",
        "using static LLDWeekend4.InterviewQuestions.ChessGameDemo;",
        "public class ChessChecks {",
        "public static void Main() {",
    ]
    cpp = [
        "#define LLD_TEST",
        '#include "' + str(ROOT / "C++/InterviewQuestions/ChessGameDemo.cpp") + '"',
        "#include <cassert>",
        "int main() {",
        "using namespace chess;",
    ]
    go = ["package main", 'import "testing"', "func TestChessEdges(t *testing.T) {"]
    for index, (board, white, moves, status) in enumerate(CASES):
        name = f"game{index}"
        flag = str(white).lower()
        for lines in (java, cs):
            lines.append(f'ChessGame {name} = new ChessGame("{board}", {flag});')
        cpp.append(f'ChessGame {name}("{board}", {flag});')
        go += [
            f'{name}, err{index} := NewChessGame("{board}", {flag})',
            f"if err{index} != nil {{ t.Fatal(err{index}) }}",
        ]
        for source, target, expected in moves:
            flag = str(expected).lower()
            java += [
                "{",
                f"String before = {name}.position();",
                "boolean accepted = true;",
                f'try {{ {name}.move("{source}", "{target}"); }} catch (IllegalArgumentException error) {{ accepted = false; }}',
                f'if (accepted != {flag} || (!accepted && !before.equals({name}.position()))) throw new AssertionError("move {source}-{target}");',
                "}",
            ]
            cs += [
                "{",
                f"string before = {name}.position();",
                "bool accepted = true;",
                f'try {{ {name}.move("{source}", "{target}"); }} catch (ArgumentException) {{ accepted = false; }}',
                f'if (accepted != {flag} || (!accepted && before != {name}.position())) throw new Exception("move {source}-{target}");',
                "}",
            ]
            cpp += [
                "{",
                f"auto before = {name}.position();",
                "bool accepted = true;",
                f'try {{ {name}.move("{source}", "{target}"); }} catch (const std::invalid_argument&) {{ accepted = false; }}',
                f"assert(accepted == {flag});",
                f"assert(accepted || before == {name}.position());",
                "}",
            ]
            go += [
                "{",
                f"before := {name}.Position()",
                f'accepted := {name}.Move("{source}", "{target}") == nil',
                f'if accepted != {flag} || (!accepted && before != {name}.Position()) {{ t.Fatal("move {source}-{target}") }}',
                "}",
            ]
        java.append(f'if ({name}.status() != Status.{status}) throw new AssertionError("status");')
        cs.append(f'if ({name}.status() != Status.{status}) throw new Exception("status");')
        cpp.append(f'assert({name}.status() == "{status}");')
        go.append(f'if {name}.Status() != "{status}" {{ t.Fatal("status") }}')
    java += ['System.out.println("PASS Java chess edge cases");', "}", "}"]
    cs += ['Console.WriteLine("PASS C# chess edge cases");', "}", "}"]
    cpp += ['std::cout << "PASS C++ chess edge cases\\n";', "}"]
    go += ["}"]
    cs = [
        re.sub(
            r"\b(move|status|position)(?=\()",
            lambda match: (
                "GetStatus" if match.group(1) == "status" else match.group(1).capitalize()
            ),
            line,
        )
        for line in cs
    ]
    for filename, lines in [
        ("ChessChecks.java", java),
        ("ChessChecks.cs", cs),
        ("chess_checks.cpp", cpp),
    ]:
        (directory / filename).write_text("\n".join(lines) + "\n")
    run(
        [
            "javac",
            "-d",
            directory,
            ROOT / "Java/InterviewQuestions/ChessGameDemo.java",
            directory / "ChessChecks.java",
        ]
    )
    run(["java", "-cp", directory, "ChessChecks"])
    run(
        [
            "c++",
            "-std=c++17",
            "-pthread",
            "-Wall",
            "-Wextra",
            "-pedantic",
            directory / "chess_checks.cpp",
            "-o",
            directory / "chess_checks",
        ]
    )
    run([directory / "chess_checks"])
    go_dir = directory / "go-chess"
    go_dir.mkdir()
    (go_dir / "go.mod").write_text("module chesschecks\n\ngo 1.22\n")
    (go_dir / "main.go").write_text(
        (ROOT / "Go/InterviewQuestions/ChessGameDemo/main.go").read_text()
    )
    (go_dir / "main_test.go").write_text("\n".join(go) + "\n")
    run(["go", "test", "-race", "-v", "."], go_dir)
    project = directory / "ChessChecks.csproj"
    project.write_text(
        f"""<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net8.0</TargetFramework><Nullable>enable</Nullable><EnableDefaultCompileItems>false</EnableDefaultCompileItems><StartupObject>ChessChecks</StartupObject></PropertyGroup><ItemGroup><Compile Include="ChessChecks.cs"/><Compile Include="{ROOT / 'C#/InterviewQuestions/ChessGameDemo.cs'}"/></ItemGroup></Project>"""
    )
    run([os.environ.get("DOTNET", "dotnet"), "run", "--project", project, "--verbosity", "quiet"])
    js = """const assert = require("node:assert/strict");
const {ChessGame} = require(process.argv[2]);
const cases = JSON.parse(process.argv[3]);
for (const [board, white, moves, status] of cases) {
    const game = new ChessGame(board, white);
    for (const [from, to, expected] of moves) {
        const before = game.position;
        let accepted = true;
        try {
            game.move(from, to);
        } catch (error) {
            accepted = false;
        }
        assert.equal(accepted, expected, `${from}-${to}`);
        if (!accepted) {
            assert.equal(game.position, before);
        }
    }
    assert.equal(game.status, status);
}
console.log("PASS JS/TS chess edge cases");
"""
    (directory / "chess_checks.cjs").write_text(js)
    run(
        [
            "node",
            directory / "chess_checks.cjs",
            ROOT / "JavaScript/InterviewQuestions/ChessGameDemo.js",
            json.dumps(CASES),
        ]
    )
    run(
        [
            ROOT / "node_modules/.bin/tsc",
            "--strict",
            "--target",
            "ES2022",
            "--module",
            "commonjs",
            "--outDir",
            directory / "ts",
            ROOT / "TypeScript/InterviewQuestions/ChessGameDemo.ts",
        ]
    )
    run(
        [
            "node",
            directory / "chess_checks.cjs",
            directory / "ts/ChessGameDemo.js",
            json.dumps(CASES),
        ]
    )


if __name__ == "__main__":
    check_python()
    with tempfile.TemporaryDirectory(prefix="chess-checks-") as temp:
        native_chess(Path(temp))
