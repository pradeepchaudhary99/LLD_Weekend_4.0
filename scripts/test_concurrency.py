#!/usr/bin/env python3
"""Lifecycle and backpressure checks beyond matching the teaching transcripts."""

from pathlib import Path
import sys
from threading import Event, Thread
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "Python"))
from concurrency_lessons import CustomThreadPool, bounded_buffer


class ConcurrencyTests(unittest.TestCase):
    def test_empty_pool_and_repeated_close(self):
        pool = CustomThreadPool(1)
        pool.close()
        pool.close()
        self.assertTrue(all(not worker.is_alive() for worker in pool.workers))
        with self.assertRaises(RuntimeError):
            pool.submit(lambda: None)

    def test_single_worker_survives_failure_and_drains_fifo(self):
        pool = CustomThreadPool(1)
        seen = []

        def fail():
            raise ValueError("expected")

        pool.submit(fail)
        for value in range(100):
            pool.submit(lambda number=value: seen.append(number))
        pool.close()
        self.assertEqual(seen, list(range(100)))
        self.assertEqual(pool.failures, 1)

    def test_bounded_buffer_blocks_until_space_is_available(self):
        buffer = bounded_buffer(1)
        buffer.put(1)
        started = Event()
        completed = Event()

        def produce():
            started.set()
            buffer.put(2)
            completed.set()

        producer = Thread(target=produce, daemon=True)
        producer.start()
        self.assertTrue(started.wait(2))
        try:
            self.assertFalse(completed.wait(0.05))
        finally:
            self.assertEqual(buffer.get(timeout=2), 1)
        self.assertTrue(completed.wait(2))
        producer.join(timeout=2)
        self.assertEqual(buffer.get(timeout=2), 2)

    def test_invalid_sizes_and_task(self):
        for size in (0, -1):
            with self.assertRaises(ValueError):
                CustomThreadPool(size)
            with self.assertRaises(ValueError):
                bounded_buffer(size)
        pool = CustomThreadPool(1)
        try:
            with self.assertRaises(TypeError):
                pool.submit(None)
        finally:
            pool.close()


if __name__ == "__main__":
    unittest.main()
