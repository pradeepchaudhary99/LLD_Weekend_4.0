#!/usr/bin/env python3
"""Build and execute native edge-case suites for the October teaching updates."""

import os
from pathlib import Path
import subprocess
import tempfile
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]


def run(args, cwd=ROOT):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True, timeout=180)


def main():
    run(["python3", ROOT / "scripts/test_notification_splitwise.py"])
    run(["python3", ROOT / "scripts/test_lock_elevator.py"])
    run(["node", ROOT / "scripts/test_notification_splitwise_node.js"])
    run(["node", ROOT / "scripts/test_lock_elevator_node.js"])
    with tempfile.TemporaryDirectory(prefix="lld-weekend-checks-") as temp:
        build = Path(temp)
        sources = ["NotificationSystemDemo", "SplitWiseDemo", "ElevatorSystemDemo"]
        run(
            [
                "javac",
                "-d",
                build,
                *[ROOT / f"Java/InterviewQuestions/{name}.java" for name in sources],
                ROOT / "scripts/java_checks/NotificationSplitWiseChecks.java",
                ROOT / "scripts/java_checks/ElevatorRunnableChecks.java",
            ]
        )
        run(["java", "-cp", build, "InterviewQuestions.NotificationSplitWiseChecks"])
        run(["java", "-cp", build, "InterviewQuestions.ElevatorRunnableChecks"])
        run(
            [
                "c++",
                "-std=c++17",
                "-pthread",
                "-Wall",
                "-Wextra",
                "-pedantic",
                ROOT / "scripts/cpp_checks/notification_splitwise_checks.cpp",
                "-o",
                build / "cpp_checks",
            ]
        )
        run([build / "cpp_checks"])
        run(
            [
                "go",
                "test",
                "-race",
                "-count=1",
                "-v",
                "./InterviewQuestions/NotificationSystemDemo",
                "./InterviewQuestions/SplitWiseDemo",
                "./InterviewQuestions/ElevatorSystemDemo",
            ],
            ROOT / "Go",
        )
        for entry, check_source in [
            ("NotificationSplitWiseChecks", "NotificationSplitWiseChecks.cs"),
            ("ElevatorWorkerChecks", "ElevatorWorkerChecks.cs"),
        ]:
            directory = build / entry
            directory.mkdir()
            project = directory / "Checks.csproj"
            files = [ROOT / f"C#/InterviewQuestions/{name}.cs" for name in sources] + [
                ROOT / "scripts/csharp_checks" / check_source
            ]
            includes = "".join(f'<Compile Include="{escape(str(file))}"/>' for file in files)
            project.write_text(
                f'<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net8.0</TargetFramework><Nullable>enable</Nullable><EnableDefaultCompileItems>false</EnableDefaultCompileItems><StartupObject>{entry}</StartupObject></PropertyGroup><ItemGroup>{includes}</ItemGroup></Project>'
            )
            run(
                [
                    os.environ.get("DOTNET", "dotnet"),
                    "run",
                    "--project",
                    project,
                    "--verbosity",
                    "quiet",
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
                build / "ts",
                *[ROOT / f"TypeScript/InterviewQuestions/{name}.ts" for name in sources],
            ]
        )
        run(["node", ROOT / "scripts/test_notification_splitwise_node.js", build / "ts"])
    print("All notification, Splitwise and elevator checks passed.")


if __name__ == "__main__":
    main()
