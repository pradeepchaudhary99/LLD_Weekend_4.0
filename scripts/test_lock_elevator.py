#!/usr/bin/env python3
"""Behavior checks for the new lock and elevator teaching examples."""

import sys
from pathlib import Path
from threading import Event, Thread
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Python"))
from ReadWriteLockDemo import ReadWriteLock
from InterviewQuestions.ElevatorSystemDemo import ElevatorSystem, RoundRobinStrategy


class RecordingDisplay:
    def __init__(self):
        self.arrivals = []

    def arrived(self, elevator_id, floor):
        self.arrivals.append((elevator_id, floor))


class LessonTests(unittest.TestCase):
    def test_readers_overlap_and_writer_waits(self):
        lock = ReadWriteLock()
        second_reader_entered = Event()
        release_reader = Event()
        writer_started = Event()
        writer_entered = Event()

        def reader():
            with lock.read():
                second_reader_entered.set()
                release_reader.wait(3)

        def writer():
            writer_started.set()
            with lock.write():
                writer_entered.set()

        with lock.read():
            reader_thread = Thread(target=reader, daemon=True)
            writer_thread = Thread(target=writer, daemon=True)
            reader_thread.start()
            try:
                self.assertTrue(second_reader_entered.wait(2))
                writer_thread.start()
                self.assertTrue(writer_started.wait(2))
                self.assertFalse(writer_entered.wait(0.05))
            finally:
                release_reader.set()
        reader_thread.join(2)
        writer_thread.join(2)
        self.assertTrue(writer_entered.is_set())
        self.assertFalse(reader_thread.is_alive() or writer_thread.is_alive())

    def test_exception_releases_lock(self):
        lock = ReadWriteLock()
        with self.assertRaises(ValueError):
            with lock.write():
                raise ValueError("expected")
        completed = Event()

        def read():
            with lock.read():
                completed.set()

        thread = Thread(target=read, daemon=True)
        thread.start()
        thread.join(2)
        self.assertTrue(completed.is_set())

    def test_stops_deduplicate_reverse_and_idle(self):
        display = RecordingDisplay()
        system = ElevatorSystem(1, 10, display)
        system.internal_request(0, 0)
        system.internal_request(0, 0)
        system.internal_request(0, 10)
        system.run_until_idle()
        system.internal_request(0, 7)
        system.internal_request(0, 2)
        system.run_until_idle()
        system.run_until_idle()
        self.assertEqual(display.arrivals, [(0, 0), (0, 10), (0, 7), (0, 2)])

    def test_strategy_switch_and_validation(self):
        system = ElevatorSystem(2, 10, RecordingDisplay())
        self.assertEqual(system.external_request(2, "UP"), 0)
        system.set_strategy(RoundRobinStrategy())
        self.assertEqual([system.external_request(0, "UP") for _ in range(3)], [0, 1, 0])
        for count, top in [(0, 10), (1, 0)]:
            with self.assertRaises(ValueError):
                ElevatorSystem(count, top, RecordingDisplay())
        for elevator, floor in [(-1, 0), (2, 0), (0, -1), (0, 11)]:
            with self.assertRaises(ValueError):
                system.internal_request(elevator, floor)
        for floor, direction in [(0, "DOWN"), (10, "UP"), (5, "IDLE")]:
            with self.assertRaises(ValueError):
                system.external_request(floor, direction)


if __name__ == "__main__":
    unittest.main()
