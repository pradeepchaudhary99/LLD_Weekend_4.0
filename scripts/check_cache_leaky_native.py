"""Build focused C++ and C# tests; execute generated JavaScript lifecycle tests."""

import os
from pathlib import Path
import subprocess
import tempfile
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]


def run(args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, timeout=120)


with tempfile.TemporaryDirectory(prefix="cache-leaky-checks-") as folder:
    build = Path(folder)
    run(
        [
            "c++",
            "-std=c++17",
            "-pthread",
            "-Wall",
            "-Wextra",
            "-pedantic",
            ROOT / "scripts/cpp_checks/cache_leaky_checks.cpp",
            "-o",
            build / "checks",
        ]
    )
    run([build / "checks"])
    sources = [
        ROOT / "C#/InterviewQuestions/LRU_CacheDemo.cs",
        ROOT / "C#/InterviewQuestions/LeakyBucketPseduo.cs",
        ROOT / "scripts/csharp_checks/CacheLeakyChecks.cs",
    ]
    includes = "".join(f'<Compile Include="{escape(str(source))}"/>' for source in sources)
    project = build / "Checks.csproj"
    project.write_text(
        '<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType>'
        "<TargetFramework>net8.0</TargetFramework><Nullable>enable</Nullable>"
        "<EnableDefaultCompileItems>false</EnableDefaultCompileItems>"
        "<StartupObject>CacheLeakyChecks</StartupObject></PropertyGroup>"
        f"<ItemGroup>{includes}</ItemGroup></Project>"
    )
    run([os.environ.get("DOTNET", "dotnet"), "run", "--project", project, "--verbosity", "quiet"])
    run(["node", ROOT / "scripts/test_cache_leaky_node.js"])
