"""Independent boundary and concurrency checks for the two October 17 lessons."""

import importlib.util
from pathlib import Path
import sys
from concurrent.futures import ThreadPoolExecutor
import unittest


def load(name):
    path = Path(__file__).resolve().parents[1] / f"Python/InterviewQuestions/{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


lb = load("LoadBalancerDemo")
rl = load("RateLimitingDemo")


class Checks(unittest.TestCase):
    def test_atomic_allowance(self):
        for strategy in (rl.TokenBucket(17, 1000, 1), rl.SlidingWindow(17, 1000, 1)):
            with ThreadPoolExecutor(max_workers=16) as pool:
                results = list(pool.map(lambda _: strategy.allow("same", 0), range(200)))
            self.assertEqual(sum(results), 17)
            with self.assertRaises(ValueError):
                strategy.allow("other", 0)

    def test_refill_keeps_fractional_period(self):
        bucket = rl.TokenBucket(1, 1000, 1)
        self.assertTrue(bucket.allow("x", 0))
        for now in range(1, 1000):
            self.assertFalse(bucket.allow("x", now))
        self.assertTrue(bucket.allow("x", 1000))
        self.assertFalse(bucket.allow("x", 1999))
        self.assertTrue(bucket.allow("x", 2000))
        for now in (-1, 0.5, 1_000_000_000_001):
            with self.assertRaises(ValueError):
                bucket.allow("x", now)

    def test_window_denials_do_not_extend_quota(self):
        window = rl.SlidingWindow(1, 1000, 1)
        self.assertTrue(window.allow("x", 0))
        self.assertFalse(window.allow("x", 999))
        self.assertTrue(window.allow("x", 1000))
        with self.assertRaises(ValueError):
            window.allow("x", 999)
        self.assertFalse(window.allow("x", 1999))
        self.assertTrue(window.allow("x", 2000))

    def test_atomic_balancer_leases(self):
        balancer = lb.LoadBalancer()
        balancer.register("A", 1)
        balancer.register("B", 1)
        balancer.set_strategy(lb.LeastConnections())
        keys = [str(index) for index in range(200)]
        with ThreadPoolExecutor(max_workers=16) as pool:
            servers = list(pool.map(balancer.acquire, keys))
        self.assertEqual(servers.count("A"), 100)
        self.assertEqual(servers.count("B"), 100)
        balancer.on_health("A", False)
        with ThreadPoolExecutor(max_workers=16) as pool:
            list(pool.map(balancer.release, keys))
        balancer.remove("A")
        balancer.remove("B")
        with self.assertRaises(ValueError):
            balancer.acquire("empty")

    def test_membership_and_invalid_strategy(self):
        balancer = lb.LoadBalancer()
        balancer.register("A", 1)
        balancer.register("B", 1)
        balancer.remove("A")
        balancer.register("A", 1)
        self.assertEqual(balancer.acquire("first"), "B")
        balancer.release("first")

        class Invalid:
            def select(self, servers):
                return -1

        balancer.set_strategy(Invalid())
        with self.assertRaises(ValueError):
            balancer.acquire("bad")
        balancer.remove("A")
        balancer.remove("B")


if __name__ == "__main__":
    unittest.main()
