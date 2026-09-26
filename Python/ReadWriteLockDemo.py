from contextlib import contextmanager
from threading import Condition, Thread


class ReadWriteLock:
    """Non-reentrant writer preference. Callers must not upgrade a read lock."""

    def __init__(self):
        self.condition = Condition()
        self.readers = 0
        self.writers_waiting = 0
        self.writer = False

    @contextmanager
    def read(self):
        with self.condition:
            while self.writer or self.writers_waiting:
                self.condition.wait()
            self.readers += 1
        try:
            yield
        finally:
            with self.condition:
                self.readers -= 1
                self.condition.notify_all()

    @contextmanager
    def write(self):
        with self.condition:
            self.writers_waiting += 1
            try:
                while self.writer or self.readers:
                    self.condition.wait()
                self.writer = True
            finally:
                self.writers_waiting -= 1
                self.condition.notify_all()
        try:
            yield
        finally:
            with self.condition:
                self.writer = False
                self.condition.notify_all()


class Cache:
    def __init__(self):
        self.values = {}
        self.lock = ReadWriteLock()

    def read(self, key):
        with self.lock.read():
            return self.values.get(key)

    def write(self, key, value):
        with self.lock.write():
            self.values[key] = value


def main():
    cache = Cache()
    writers = [Thread(target=cache.write, args=(key, key * 10)) for key in range(4)]
    for writer in writers:
        writer.start()
    for writer in writers:
        writer.join()
    total = sum(cache.read(key) for key in range(4))
    assert total == 60 and cache.read(99) is None
    print(f"Cache sum: {total}")
    print("Missing: true")
    cache.write(0, -1)
    print(f"Stored negative: {cache.read(0)}")
    cache.write(0, 7)
    print(f"Updated: {cache.read(0)}")


if __name__ == "__main__":
    main()
