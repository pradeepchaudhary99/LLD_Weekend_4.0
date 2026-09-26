#!/usr/bin/env python3
"""Run one lesson in an isolated build directory. Invoke from any directory."""

import argparse
import os
import re
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = ("Java", "Python", "C++", "Go", "C#", "JavaScript", "TypeScript")


def run(language, lesson):
    stem = Path(lesson)
    if stem.is_absolute() or ".." in stem.parts or len(stem.parts) not in (1, 2):
        raise ValueError("Use a lesson name or DesignPatterns/<lesson>")
    env = dict(
        os.environ, DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1", PYTHONDONTWRITEBYTECODE="1"
    )

    def call(args, cwd=ROOT):
        subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True)

    with tempfile.TemporaryDirectory(prefix="lld-") as directory:
        build = Path(directory)
        if language == "Java":
            source = ROOT / language / (lesson + ".java")
            call(["javac", "-d", build, source])
            package = re.search(r"^package\s+([\w.]+)\s*;", source.read_text(), re.MULTILINE)
            entrypoint = (package.group(1) + "." if package else "") + stem.name
            call(["java", "-cp", build, entrypoint])
        elif language == "Python":
            call(["python3", ROOT / language / (lesson + ".py")])
        elif language == "C++":
            binary = build / "lesson"
            call(
                [
                    "c++",
                    "-std=c++17",
                    "-pthread",
                    "-Wall",
                    "-Wextra",
                    "-pedantic",
                    ROOT / language / (lesson + ".cpp"),
                    "-o",
                    binary,
                ]
            )
            call([binary])
        elif language == "JavaScript":
            call(["node", ROOT / language / (lesson + ".js")])
        elif language == "TypeScript":
            tsc = ROOT / "node_modules" / ".bin" / "tsc"
            if not tsc.exists():
                raise ValueError("Run npm ci in the repository first (TypeScript compiler).")
            call(
                [
                    tsc,
                    "--strict",
                    "--target",
                    "ES2022",
                    "--module",
                    "commonjs",
                    "--outDir",
                    build,
                    ROOT / language / (lesson + ".ts"),
                ]
            )
            call(["node", build / (stem.name + ".js")])
        elif language == "Go":
            if len(stem.parts) == 2 or (ROOT / "Go" / lesson / "main.go").exists():
                call(["go", "run", "./" + lesson], ROOT / "Go")
            else:
                # Existing root Go lessons use one menu-style main.
                call(["go", "run", ".", lesson], ROOT / "Go")
        elif language == "C#":
            dotnet = os.environ.get("DOTNET", "dotnet")
            if stem.parts[0] == "DesignPatterns":
                project = ROOT / "C#" / "DesignPatterns" / "DesignPatterns.csproj"
                prop = "-p:Lesson=" + stem.name
            else:
                project = ROOT / "C#" / "LLDWeekend4.csproj"
                entrypoints = {
                    "Main": "Oop.Program",
                    "ReadWriteLockDemo": "Concurrency.ReadWriteLockDemo",
                    "InterviewQuestions/ElevatorSystemDemo": "InterviewQuestions.ElevatorSystemDemo",
                    **{
                        name: "Concurrency." + name
                        for name in (
                            "ConcurrencyFundamentals",
                            "ProducerConsumerDemo",
                            "ThreadPool",
                            "ThreadPoolLLD",
                        )
                    },
                    "SolidDemo": "Solid.SolidDemo",
                    "Relationship": "Relationships.RelationshipDemo",
                }
                prop = "-p:StartupObject=LLDWeekend4." + entrypoints[lesson]
            # Build in isolation so changing StartupObject cannot reuse a stale binary.
            call(
                [
                    dotnet,
                    "build",
                    project,
                    prop,
                    "-o",
                    build / "out",
                    "-p:BaseIntermediateOutputPath=" + str(build / "obj") + "/",
                    "--nologo",
                    "-v:q",
                ]
            )
            call([dotnet, build / "out" / (project.stem + ".dll")])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("language", choices=LANGUAGES)
    parser.add_argument("lesson", help="e.g. DesignPatterns/ATMMachineStateDesign")
    args = parser.parse_args()
    try:
        run(args.language, args.lesson)
    except (ValueError, FileNotFoundError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
