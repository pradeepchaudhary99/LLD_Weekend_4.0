"""Focused concurrency checks: python3 scripts/test_task_scheduler.py."""

import importlib.util
from pathlib import Path
import sys
from threading import Event
import unittest

source = Path(__file__).resolve().parents[1] / "Python/InterviewQuestions/TaskSchedular_demo.py"
spec = importlib.util.spec_from_file_location("scheduler_lesson", source)
lesson = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = lesson
spec.loader.exec_module(lesson)


class SchedulerTests(unittest.TestCase):
    def test_parallel_workers_and_running_cancellation(self):
        scheduler = lesson.Scheduler(2)
        entered = [Event(), Event()]
        release = Event()

        def action(index):
            entered[index].set()
            if not release.wait(5):
                raise TimeoutError("Owner failed to release action")

        try:
            for index in range(2):
                scheduler.schedule(str(index), 0, 1, 1, lambda i=index: action(i))
            scheduler.start()
            self.assertTrue(all(event.wait(3) for event in entered))
            self.assertTrue(scheduler.cancel("0"))
            self.assertTrue(scheduler.cancel("1"))
        finally:
            release.set()
            scheduler.close()
        self.assertEqual(scheduler.snapshot("0"), ("CANCELLED", 1))
        self.assertEqual(scheduler.snapshot("1"), ("CANCELLED", 1))
        self.assertEqual(len(scheduler.dispatches()), 2)

    def test_deadlines_wakeup_and_fifo(self):
        scheduler = lesson.Scheduler(1)
        observed = []
        try:
            scheduler.schedule("future", 60000, 0, 0, lambda: None)
            scheduler.start()
            due = scheduler.now() + 30
            for name in ["a", "b"]:
                scheduler.schedule(name, due, 1, 0, lambda: observed.append(scheduler.now()))
            self.assertTrue(scheduler.await_runs("b", 1, 3000))
            self.assertEqual(scheduler.dispatches(), ["a", "b"])
            self.assertTrue(all(time >= due for time in observed))
        finally:
            scheduler.close()
        self.assertEqual(scheduler.snapshot("future"), ("CANCELLED", 0))

    def test_fixed_delay_and_failed_recurrence(self):
        scheduler = lesson.Scheduler(3)
        times = []
        try:
            scheduler.schedule("repeat", 0, 1, 20, lambda: times.append(scheduler.now()))
            scheduler.schedule("bad", 0, 1, 1, lesson.fail)
            scheduler.start()
            self.assertTrue(scheduler.await_runs("repeat", 3, 3000))
            scheduler.cancel("repeat")
        finally:
            scheduler.close()
        self.assertTrue(all(b - a >= 20 for a, b in zip(times, times[1:])))
        self.assertEqual(scheduler.snapshot("bad"), ("FAILED", 1))

    def test_lifecycle_and_validation(self):
        scheduler = lesson.Scheduler(1)
        scheduler.schedule("a", 0, 1, 0, lambda: None)
        with self.assertRaises(ValueError):
            scheduler.schedule("a", 0, 1, 0, lambda: None)
        with self.assertRaises(ValueError):
            scheduler.schedule("bad", -1, 1, 0, lambda: None)
        with self.assertRaises(ValueError):
            scheduler.cancel("unknown")
        scheduler.close()
        self.assertEqual(scheduler.snapshot("a"), ("CANCELLED", 0))
        with self.assertRaises(ValueError):
            scheduler.start()
        with self.assertRaises(ValueError):
            scheduler.schedule("new", 0, 1, 0, lambda: None)
        scheduler.close()


if __name__ == "__main__":
    unittest.main()
