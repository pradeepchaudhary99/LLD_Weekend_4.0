from abc import ABC, abstractmethod
from typing import List, Optional


class FileSystemNode(ABC):
    def __init__(self, name: str) -> None:
        self.name = name
        self.size: int = 0
        self.parent: Optional["FileSystemNode"] = None

    @abstractmethod
    def properties(self) -> None:
        raise NotImplementedError

    @abstractmethod
    def get_size(self) -> int:
        raise NotImplementedError

    @abstractmethod
    def rename(self, new_name: str) -> None:
        raise NotImplementedError

    @abstractmethod
    def open(self) -> None:
        raise NotImplementedError


class File(FileSystemNode):
    def __init__(self, name: str, content: str = "") -> None:
        super().__init__(name)
        self.content = content
        self.size = len(content)

    def properties(self) -> None:
        print(f"File: {self.name}, size: {self.size}")

    def get_size(self) -> int:
        return self.size

    def rename(self, new_name: str) -> None:
        self.name = new_name

    def open(self) -> None:
        print(f"Opening file {self.name}")


class Folder(FileSystemNode):
    def __init__(self, name: str) -> None:
        super().__init__(name)
        self.children: List[FileSystemNode] = []

    def add_child(self, node: FileSystemNode) -> None:
        node.parent = self
        self.children.append(node)

    def properties(self) -> None:
        print(f"Folder: {self.name}, size: {self.get_size()}")

    def get_size(self) -> int:
        return sum(child.get_size() for child in self.children)

    def rename(self, new_name: str) -> None:
        self.name = new_name

    def open(self) -> None:
        print(f"Opening folder {self.name}")


def main() -> None:
    root = Folder("root")
    root.add_child(File("readme.txt", "hello"))
    root.add_child(Folder("subfolder"))


if __name__ == "__main__":
    main()
