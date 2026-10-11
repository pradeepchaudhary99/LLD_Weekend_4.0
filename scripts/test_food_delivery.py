"""Exercise concurrent duplicate submission, rider reservation and input boundaries."""

import importlib.util
from pathlib import Path
import sys
import unittest
from concurrent.futures import ThreadPoolExecutor

path = Path(__file__).resolve().parents[1] / "Python/InterviewQuestions/Zomato_Food_Delivery.py"
spec = importlib.util.spec_from_file_location("food", path)
food = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = food
spec.loader.exec_module(food)


class FoodTests(unittest.TestCase):
    def setUp(self):
        self.gateway = food.PaymentGateway()
        self.service = food.FoodDelivery(self.gateway)
        self.request = food.Request("alice", "r1", "Home", "pizza", 2)

    def test_concurrent_duplicates_and_reservation(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: self.service.place("same", self.request), range(50)))
        self.assertEqual(results, ["PLACED"] * 50)
        self.assertEqual(self.gateway.charges, 1)
        for key in ("same", "other"):
            self.service.place(key, self.request)
            for state in ("ACCEPTED", "PREPARING", "READY"):
                self.service.advance(key, state)
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(sum(pool.map(self.service.assign, ["same", "other"])), 1)

    def test_boundaries_and_snapshots(self):
        for quantity in (0, -1, 101, 1.5, True):
            with self.assertRaises(ValueError):
                self.service.place("bad", food.Request("alice", "r1", "Home", "pizza", quantity))
        self.assertEqual(self.gateway.charges, 0)
        for restaurant, item in (("missing", "pizza"), ("r1", "rice")):
            with self.assertRaises(ValueError):
                self.service.place("bad", food.Request("alice", restaurant, "Home", item, 1))
        self.service.menu("r1")["pizza"] = 1
        self.assertEqual(self.service.menu("r1")["pizza"], 12000)
        self.service.place("max", food.Request("alice", "r1", "Home", "pizza", 100))
        self.assertEqual(self.gateway.charges, 1)

    def test_rider_released_after_delivery(self):
        for key in ("first", "second"):
            self.service.place(key, self.request)
            for state in ("ACCEPTED", "PREPARING", "READY"):
                self.service.advance(key, state)
        self.assertTrue(self.service.assign("first"))
        self.service.advance("first", "PICKED_UP")
        self.service.advance("first", "DELIVERED")
        self.assertTrue(self.service.assign("second"))


if __name__ == "__main__":
    unittest.main()
