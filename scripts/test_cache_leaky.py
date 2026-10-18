"""Boundary, model, lifecycle, cadence and concurrent-producer checks."""

import importlib.util
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from random import Random
from threading import Event
from time import monotonic
import unittest


def load(name):
    path = Path(__file__).resolve().parents[1] / f"Python/InterviewQuestions/{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


cache_module = load("LRU_CacheDemo")
bucket_module = load("LeakyBucketPseduo")


class Checks(unittest.TestCase):
    def test_cache_model(self):
        random = Random(27)
        cache = cache_module.LRUCache(4)
        values = {}
        order = []
        for _ in range(1000):
            key = random.randrange(8)
            if random.randrange(2):
                value = random.randrange(-3, 4)
                if key in values:
                    order.remove(key)
                elif len(order) == 4:
                    del values[order.pop()]
                values[key] = value
                order.insert(0, key)
                cache.put(key, value)
            else:
                self.assertEqual(cache.get(key), values.get(key))
                if key in values:
                    order.remove(key)
                    order.insert(0, key)
            self.assertEqual(cache.keys(), order)

    def test_cache_concurrency(self):
        cache = cache_module.LRUCache(8)

        def work(index):
            cache.put(index % 16, index)
            cache.get((index + 1) % 16)

        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(work, range(1000)))
        keys = cache.keys()
        self.assertEqual(len(keys), 8)
        self.assertEqual(len(set(keys)), 8)
        for key in keys:
            self.assertIsNotNone(cache.get(key))

    def test_worker_wakeup_failure_cadence_and_drain(self):
        attempted = []
        arrived = Event()

        def handle(job):
            attempted.append((job, monotonic()))
            arrived.set()
            if job == 1:
                raise ValueError("expected handler failure")

        bucket = bucket_module.LeakyBucket(4, 20, handle)
        try:
            bucket.start()
            self.assertTrue(bucket.submit(1))
            self.assertTrue(arrived.wait(5), "idle worker did not wake")
            self.assertTrue(bucket.submit(2))
            self.assertTrue(bucket.submit(3))
        finally:
            bucket.close()
        self.assertEqual([job for job, _ in attempted], [1, 2, 3])
        self.assertEqual(bucket.failures(), 1)
        self.assertTrue(all(b[1] - a[1] >= 0.018 for a, b in zip(attempted, attempted[1:])))
        self.assertFalse(bucket.submit(4))
        bucket.close()
        with self.assertRaises(ValueError):
            bucket.start()

    def test_concurrent_admission_and_close_before_start(self):
        attempted = []
        bucket = bucket_module.LeakyBucket(10, 1, attempted.append)
        with ThreadPoolExecutor(max_workers=8) as pool:
            accepted = list(pool.map(bucket.submit, range(100)))
        self.assertEqual(sum(accepted), 10)
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda _: bucket.close(), range(2)))
        self.assertEqual(set(attempted), {index for index, ok in enumerate(accepted) if ok})
        self.assertEqual(len(attempted), 10)


if __name__ == "__main__":
    unittest.main()
