#!/usr/bin/env python3
"""Run both lessons' native edge-case suites; requires all seven toolchains."""

import os
from pathlib import Path
import tempfile
from test_chess_edges import ROOT, check_python, native_chess, run
from test_payment_edges import main as payment_python


def main():
    check_python()
    payment_python()
    with tempfile.TemporaryDirectory(prefix="lld-edge-checks-") as temp:
        directory = Path(temp)
        native_chess(directory)
        run(
            [
                "javac",
                "-d",
                directory,
                ROOT / "Java/InterviewQuestions/PaymentServiceLLD.java",
                ROOT / "scripts/java_checks/PaymentChecks.java",
            ]
        )
        run(["java", "-cp", directory, "PaymentChecks"])
        run(
            [
                "c++",
                "-std=c++17",
                "-pthread",
                "-Wall",
                "-Wextra",
                "-pedantic",
                ROOT / "scripts/cpp_checks/payment_checks.cpp",
                "-o",
                directory / "payment_checks",
            ]
        )
        run([directory / "payment_checks"])
        run(["go", "test", "-race", "-v", "./InterviewQuestions/PaymentServiceLLD"], ROOT / "Go")
        payment_directory = directory / "csharp-payment"
        payment_directory.mkdir()
        project = payment_directory / "PaymentChecks.csproj"
        project.write_text(
            f"""<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net8.0</TargetFramework><Nullable>enable</Nullable><EnableDefaultCompileItems>false</EnableDefaultCompileItems><StartupObject>PaymentChecks</StartupObject></PropertyGroup><ItemGroup><Compile Include="{ROOT / 'scripts/csharp_checks/PaymentChecks.cs'}"/><Compile Include="{ROOT / 'C#/InterviewQuestions/PaymentServiceLLD.cs'}"/></ItemGroup></Project>"""
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
        run(["node", ROOT / "scripts/test_payment_edges_node.js"])
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
                ROOT / "TypeScript/InterviewQuestions/PaymentServiceLLD.ts",
            ]
        )
        run(
            [
                "node",
                ROOT / "scripts/test_payment_edges_node.js",
                directory / "ts/PaymentServiceLLD.js",
            ]
        )
    print("All chess and payment edge-case suites passed.")


if __name__ == "__main__":
    main()
