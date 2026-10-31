"""Focused lesson checks: python3 scripts/test_locker_kafka.py."""

import importlib.util
from pathlib import Path
import sys
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(
        name, ROOT / "Python/InterviewQuestions" / (name + ".py")
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


locker = load("AmazonLockerDemo")
kafka = load("KafkaLLDDemo")


class LockerChecks(unittest.TestCase):
    def test_last_slot_and_single_pickup(self):
        service = locker.LockerService()
        service.add_location("A", [0])
        barrier = Barrier(8)

        def deposit(index):
            barrier.wait(timeout=5)
            try:
                return service.deposit(str(index), "A", 0, 0, 10)
            except ValueError:
                return None

        with ThreadPoolExecutor(max_workers=8) as pool:
            winners = [result for result in pool.map(deposit, range(8)) if result]
        self.assertEqual(len(winners), 1)
        assignment = winners[0]
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(
                pool.map(
                    lambda _: service.pickup(assignment.package_id, assignment.code, 1), range(8)
                )
            )
        self.assertEqual(sum(results), 1)
        self.assertEqual(len(service.notifications()), 1)

    def test_expiry_retains_occupancy_until_staff_return(self):
        service = locker.LockerService()
        service.add_location("A", [0])
        parcel = service.deposit("p", "A", 0, 0, 10)
        self.assertFalse(service.return_expired("p", 9))
        self.assertFalse(service.pickup("p", parcel.code, 10))
        with self.assertRaises(ValueError):
            service.deposit("q", "A", 0, 10, 10)
        self.assertTrue(service.return_expired("p", 10))
        self.assertFalse(service.return_expired("p", 10))
        self.assertEqual(service.deposit("q", "A", 0, 10, 10).locker, 0)
        with self.assertRaises(ValueError):
            service.deposit("p", "A", 0, 10, 10)
        self.assertEqual(len(service.notifications()), 2)

    def test_invalid_requests_and_snapshot(self):
        service = locker.LockerService()
        with self.assertRaises(ValueError):
            service.add_location("A", [0, 8])
        service.add_location("A", [1])
        for size, ttl in [(-1, 1), (3, 1), (0, 0)]:
            with self.assertRaises(ValueError):
                service.deposit("p", "A", size, 0, ttl)
        with self.assertRaises(ValueError):
            service.deposit("p", "missing", 0, 0, 10)
        self.assertFalse(service.pickup("unknown", "x", 0))
        self.assertEqual(service.notifications(), ())


class KafkaChecks(unittest.TestCase):
    def test_concurrent_appends_and_snapshot(self):
        broker = kafka.Broker()
        broker.create_topic("t", 1)
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda i: broker.publish("t", "key", str(i)), range(500)))
        broker.join("t", "g", "member")
        batch = broker.poll("t", "g", "member", 1000)[0]
        self.assertEqual([message.offset for message in batch.messages], list(range(500)))
        self.assertEqual(len({message.value for message in batch.messages}), 500)
        broker.publish("t", "key", "later")
        self.assertEqual(len(batch.messages), 500)
        self.assertTrue(broker.commit(batch))
        self.assertEqual(broker.poll("t", "g", "member", 1)[0].messages[0].offset, 500)

    def test_rebalance_empty_group_and_independent_offsets(self):
        broker = kafka.Broker()
        broker.create_topic("t", 1)
        broker.publish("t", "", "one")
        broker.join("t", "g", "a")
        old = broker.poll("t", "g", "a", 1)[0]
        broker.join("t", "g", "b")
        self.assertFalse(broker.commit(old))
        self.assertEqual(broker.poll("t", "g", "b", 1), ())
        current = broker.poll("t", "g", "a", 1)[0]
        self.assertTrue(broker.commit(current))
        self.assertFalse(broker.commit(current))
        broker.leave("t", "g", "a")
        broker.leave("t", "g", "b")
        broker.join("t", "g", "c")
        self.assertEqual(broker.poll("t", "g", "c", 1), ())
        broker.join("t", "other", "c")
        self.assertEqual(len(broker.poll("t", "other", "c", 1)[0].messages), 1)

    def test_invalid_input_and_group_key_collision(self):
        broker = kafka.Broker()
        with self.assertRaises(ValueError):
            broker.create_topic("bad", 0)
        for topic, group in [("a:b", "c"), ("a", "b:c")]:
            broker.create_topic(topic, 1)
            broker.publish(topic, "a", "data")
            broker.join(topic, group, "x")
        self.assertTrue(broker.commit(broker.poll("a:b", "c", "x", 1)[0]))
        self.assertEqual(len(broker.poll("a", "b:c", "x", 1)), 1)
        with self.assertRaises(ValueError):
            broker.publish("a", "é", "data")
        with self.assertRaises(ValueError):
            broker.join("a", "b:c", "x")
        with self.assertRaises(ValueError):
            broker.poll("a", "b:c", "x", 0)
        with self.assertRaises(ValueError):
            broker.leave("a", "b:c", "missing")


if __name__ == "__main__":
    unittest.main()
