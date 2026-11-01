"""Run focused checks with python3 scripts/test_memory_filesystem.py."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
from pathlib import Path
import unittest

source = Path(__file__).resolve().parents[1] / "Python/InterviewQuestions/InMemoryFileSystem.py"
spec = importlib.util.spec_from_file_location("memory_filesystem", source)
lesson = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lesson)


class FileSystemChecks(unittest.TestCase):
    def setUp(self):
        self.fs = lesson.FileSystem()

    def test_paths_and_failed_mkdir_are_atomic(self):
        for path in ["relative", "", "/new//bad", "/new/../bad", "/new/", "/a\n", "/new/é"]:
            with self.assertRaises(ValueError):
                self.fs.mkdir(path)
        self.assertEqual(self.fs.list("/"), [])
        self.fs.create_file("/file", "data")
        with self.assertRaises(ValueError):
            self.fs.mkdir("/file/child")
        self.assertEqual(self.fs.read("/file"), "data")
        with self.assertRaises(ValueError):
            self.fs.create_file("/missing/file", "data")
        with self.assertRaises(ValueError):
            self.fs.read("/")

    def test_move_validation_and_working_directory(self):
        self.fs.mkdir("/a/child")
        self.fs.mkdir("/b")
        self.fs.create_file("/a/data", "original")
        self.fs.create_file("/b/data", "target")
        for source, target in [
            ("/a", "/a/child/nested"),
            ("/a/data", "/b/data"),
            ("/", "/root"),
            ("/a", "/a"),
            ("/a", "/missing/a"),
        ]:
            with self.assertRaises(ValueError):
                self.fs.move(source, target)
        self.assertEqual(self.fs.read("/a/data"), "original")
        self.assertEqual(self.fs.read("/b/data"), "target")
        self.fs.cd("/a/child")
        self.fs.rename("/a", "renamed")
        self.assertEqual(self.fs.pwd(), "/renamed/child")
        with self.assertRaises(ValueError):
            self.fs.delete("/renamed", True)
        with self.assertRaises(ValueError):
            self.fs.delete("/renamed/child", True)
        self.fs.cd("/")
        self.fs.delete("/renamed", True)
        self.assertEqual(self.fs.find("data"), ["/b/data"])

    def test_snapshots_and_utf8_size(self):
        self.fs.mkdir("/a/nested")
        self.fs.create_file("/a/nested/note", "é🙂")
        self.fs.create_file("/a/note", "")
        self.assertEqual(self.fs.size("/a"), 6)
        listing = self.fs.list("/a")
        listing.clear()
        self.assertEqual(self.fs.list("/a"), ["nested", "note"])
        self.assertEqual(self.fs.find("note"), ["/a/nested/note", "/a/note"])
        self.fs.write("/a/note", "abc")
        self.assertEqual(self.fs.size("/a"), 9)
        with self.assertRaises(ValueError):
            self.fs.delete("/a", False)
        with self.assertRaises(ValueError):
            self.fs.delete("/", True)
        self.assertEqual(self.fs.size("/a"), 9)

    def test_concurrent_creates_and_appends(self):
        def create(_):
            try:
                self.fs.create_file("/shared", "")
                return True
            except ValueError:
                return False

        with ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(sum(pool.map(create, range(50))), 1)
            list(pool.map(lambda _: self.fs.append("/shared", "x"), range(500)))
        self.assertEqual(self.fs.read("/shared"), "x" * 500)

    def test_competing_moves_keep_one_parent(self):
        self.fs.create_file("/source", "payload")

        def move(index):
            try:
                self.fs.move("/source", f"/target{index}")
                return True
            except ValueError:
                return False

        with ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(sum(pool.map(move, range(20))), 1)
        listing = self.fs.list("/")
        self.assertEqual(len(listing), 1)
        self.assertEqual(self.fs.read("/" + listing[0]), "payload")


if __name__ == "__main__":
    unittest.main()
