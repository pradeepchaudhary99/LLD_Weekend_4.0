"""Thread synchronization and finite, owner-managed worker lifecycles."""

from concurrent.futures import ThreadPoolExecutor
from queue import Queue
from threading import Lock, Thread


class CustomThreadPool:
    def __init__(self, size):
        if size <= 0:
            raise ValueError("Pool size must be positive")
        self.tasks = Queue()
        self.lock = Lock()
        self.closed = False
        self.failures = 0
        self.workers = [Thread(target=self._work) for _ in range(size)]
        for worker in self.workers:
            worker.start()

    def _work(self):
        while True:
            task = self.tasks.get()
            if task is None:
                return
            try:
                task()
            except Exception:
                # Keep workers alive after ordinary task failures.
                with self.lock:
                    self.failures += 1

    def submit(self, task):
        with self.lock:
            if self.closed:
                raise RuntimeError("Pool is closed")
            if not callable(task):
                raise TypeError("Task must be callable")
            self.tasks.put(task)

    def close(self):
        # Owner only: joining the current worker would deadlock.
        with self.lock:
            if not self.closed:
                self.closed = True
                for _ in self.workers:
                    self.tasks.put(None)
        for worker in self.workers:
            worker.join()


def fundamentals():
    count = 0
    lock = Lock()

    def increment():
        nonlocal count
        for _ in range(1000):
            with lock:
                count += 1

    threads = [Thread(target=increment) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert count == 4000
    print(f"Counter: {count}")


def bounded_buffer(capacity):
    # Queue(0) means unbounded in Python, so reject it explicitly.
    if capacity <= 0:
        raise ValueError("Capacity must be positive")
    return Queue(maxsize=capacity)


def producer_consumer():
    buffer = bounded_buffer(1)
    consumed = []

    def consume():
        for _ in range(10):
            consumed.append(buffer.get())

    consumer = Thread(target=consume)
    consumer.start()
    for value in range(1, 11):
        buffer.put(value)
    consumer.join()
    assert consumed == list(range(1, 11))
    print(f"Consumed sum: {sum(consumed)}")
    try:
        bounded_buffer(0)
        raise AssertionError("Invalid capacity accepted")
    except ValueError:
        print("Invalid capacity rejected")


def custom_pool():
    pool = CustomThreadPool(3)
    values = []
    lock = Lock()

    def fail():
        raise ValueError("Expected teaching failure")

    def collect(value):
        with lock:
            values.append(value)

    pool.submit(fail)
    for value in range(1, 11):
        pool.submit(lambda number=value: collect(number))
    pool.close()
    pool.close()
    assert sorted(values) == list(range(1, 11)) and pool.failures == 1
    print(f"Completed sum: {sum(values)}")
    print(f"Task failures: {pool.failures}")
    try:
        pool.submit(lambda: None)
        raise AssertionError("Closed pool accepted work")
    except RuntimeError:
        print("Submission after shutdown rejected")
    try:
        CustomThreadPool(0)
        raise AssertionError("Invalid pool size accepted")
    except ValueError:
        print("Invalid pool size rejected")


def executor_pool():
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(int, range(1, 11)))
    assert results == list(range(1, 11))
    print(f"Executor sum: {sum(results)}")
