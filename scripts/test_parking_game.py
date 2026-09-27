#!/usr/bin/env python3
"""State, payment, competing-gate and loop-boundary tests."""

from pathlib import Path
from threading import Barrier, Lock, Thread
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "Python" / "InterviewQuestions"))
from ParkingLotDemo import (
    EntryGate,
    ExitGate,
    HourlyFee,
    NearestExit,
    ParkingLotManager,
    Slot,
    Vehicle,
)
from GameLoopPattern import GameLoop


class LessonTests(unittest.TestCase):
    def lot(self):
        return ParkingLotManager([Slot(0, 1, "CAR", 1)], HourlyFee())

    def test_fee_boundaries(self):
        fee = HourlyFee()
        self.assertEqual(
            [fee.fee(value) for value in [0, 1, 59, 60, 61, 120, 121]],
            [50, 50, 50, 50, 100, 100, 150],
        )
        with self.assertRaises(ValueError):
            fee.fee(-1)

    def test_competing_gates_reserve_once(self):
        lot = self.lot()
        start = Barrier(8)
        results = []
        lock = Lock()

        def enter(index):
            start.wait(timeout=3)
            try:
                ticket = EntryGate(index, lot).enter(Vehicle(f"CAR-{index}", "CAR"), 0)
                with lock:
                    results.append(ticket)
            except ValueError:
                pass

        threads = [Thread(target=enter, args=(index,)) for index in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(3)
            self.assertFalse(thread.is_alive())
        self.assertEqual(len(results), 1)
        self.assertEqual(len(lot.tickets), 1)

    def test_payment_failure_exception_and_duplicate_exit(self):
        lot = self.lot()
        ticket = lot.park(Vehicle("CAR", "CAR"), 0)
        with self.assertRaises(ValueError):
            lot.exit(ticket.id, 61, lambda *_: False)

        def broken_payment(*_):
            raise RuntimeError("Payment unavailable")

        with self.assertRaises(RuntimeError):
            lot.exit(ticket.id, 61, broken_payment)
        with self.assertRaises(ValueError):
            lot.park(Vehicle("OTHER", "CAR"), 61)
        calls = []
        self.assertEqual(lot.exit(ticket.id, 61, lambda *args: calls.append(args) or True), 100)
        with self.assertRaises(ValueError):
            lot.exit(ticket.id, 61, lambda *args: calls.append(args) or True)
        self.assertEqual(calls, [(ticket.id, 100)])
        self.assertEqual(lot.park(Vehicle("OTHER", "CAR"), 62).slot.id, 1)

    def test_selection_and_invalid_requests(self):
        lot = ParkingLotManager([Slot(0, 1, "CAR", 9), Slot(1, 2, "CAR", 1)], HourlyFee())
        lot.set_strategy(NearestExit())
        self.assertEqual(lot.park(Vehicle("CAR", "CAR"), 0).slot.id, 2)
        with self.assertRaises(ValueError):
            lot.park(Vehicle("CAR", "CAR"), 0)
        with self.assertRaises(ValueError):
            lot.park(Vehicle("BIKE", "BIKE"), 0)
        with self.assertRaises(ValueError):
            lot.park(Vehicle("OTHER", "CAR"), -1)
        with self.assertRaises(ValueError):
            lot.exit(999, 0, lambda *_: True)
        with self.assertRaises(ValueError):
            Vehicle(" ", "CAR")

    def test_game_budget_pause_and_quit(self):
        game = GameLoop()
        frames = []
        render = lambda tick, position: frames.append((tick, position))
        game.run(0, lambda _: "RIGHT", render)
        self.assertEqual(frames, [])
        game.run(2, lambda _: "RIGHT", render)
        game.run(2, lambda _: "PAUSE", render)
        game.run(1, lambda _: "RESUME", render)
        game.run(5, lambda _: "QUIT", render)
        game.run(5, lambda _: "RIGHT", render)
        self.assertEqual(frames, [(0, 1), (1, 2), (2, 2), (3, 2), (4, 3)])
        self.assertFalse(game.running)
        with self.assertRaises(ValueError):
            game.run(-1, lambda _: "NONE", render)


if __name__ == "__main__":
    unittest.main()
