"""A handler either resolves the request or delegates to the next handler."""

from abc import ABC, abstractmethod


class Handler(ABC):
    def __init__(self, next_handler=None):
        self.next_handler = next_handler

    @abstractmethod
    def can_handle(self, level):
        pass

    @property
    @abstractmethod
    def name(self):
        pass

    def handle(self, level):
        if level < 0:
            raise ValueError("Level cannot be negative")
        if self.can_handle(level):
            return self.name
        if self.next_handler is not None:
            return self.next_handler.handle(level)
        return "Unhandled"


class WarningHandler(Handler):
    def can_handle(self, level):
        return level < 2

    @property
    def name(self):
        return "Warning"


class ErrorHandler(Handler):
    def can_handle(self, level):
        return level < 4

    @property
    def name(self):
        return "Error"


class FatalHandler(Handler):
    def can_handle(self, level):
        return level < 6

    @property
    def name(self):
        return "Fatal"


def main():
    chain = WarningHandler(ErrorHandler(FatalHandler()))
    for level in range(7):
        print(f"{level}: {chain.handle(level)}")
    print(f"Truncated: {WarningHandler().handle(3)}")
    try:
        chain.handle(-1)
        raise AssertionError("Accepted negative level")
    except ValueError:
        print("Invalid level rejected")


if __name__ == "__main__":
    main()
