#!/usr/bin/env python3
"""Behavior and concurrency checks for the Python teaching editions."""

import sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from threading import Event

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Python"))
from InterviewQuestions.SplitWiseDemo import SplitWise
from InterviewQuestions.NotificationSystemDemo import NotificationService, FakeChannel, Request
from InterviewQuestions.ElevatorSystemDemo import ElevatorSystem


def rejects(action):
    try:
        action()
    except (ValueError, RuntimeError):
        return
    raise AssertionError("Invalid operation accepted")


def ledger():
    service = SplitWise()
    members = ["A", "B", "C"]
    for user in members + ["D"]:
        service.add_user(user)
    service.create_group("g", members)
    service.create_group("other", members)
    assert service.add_expense("g", "equal", "A", 2, members, "EQUAL", []) == [1, 1, 0]
    assert service.add_expense(
        "g", "percent", "B", 7, members, "PERCENTAGE", [5000, 2500, 2500]
    ) == [3, 2, 2]
    assert service.add_expense("g", "zero", "C", 1, members, "PERCENTAGE", [0, 0, 10000]) == [
        0,
        0,
        1,
    ]
    assert service.balance("g", "A", "B") == 2
    assert service.balance("g", "B", "A") == -2
    assert service.balance("other", "A", "B") == 0
    rejects(lambda: service.add_expense("g", "bad", "A", 10, members, "EXACT", [2, 3, 4]))
    rejects(
        lambda: service.add_expense("g", "bad", "A", 10, members, "PERCENTAGE", [5000, 2000, 2000])
    )
    rejects(lambda: service.add_expense("g", "bad", "A", 10, ["A", "A"], "EQUAL", []))
    rejects(lambda: service.add_expense("g", "bad", "D", 10, members, "EQUAL", []))
    rejects(lambda: service.add_expense("g", "equal", "A", 10, members, "EQUAL", []))
    rejects(lambda: service.add_expense("g", "bad", "A", 0, members, "EQUAL", []))
    rejects(lambda: service.add_expense("g", "bad", "A", 1000000001, members, "EQUAL", []))
    rejects(lambda: service.settle("g", "bad", "A", "B", 3))
    rejects(lambda: service.remove_member("g", "A"))
    assert len(service.history("g")) == 3
    assert service.balance("g", "A", "B") == 2
    service.settle("g", "settle", "A", "B", 2)
    assert service.balance("g", "A", "B") == 0
    service.remove_member("g", "A")
    service.add_member("g", "A")
    service.add_member("g", "D")
    service.remove_member("g", "D")
    rejects(lambda: service.settle("g", "settle", "C", "B", 1))
    assert len(service.history("g")) == 4
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(
            pool.map(
                lambda index: service.add_expense(
                    "other", f"parallel-{index}", "A", 10, ["B"], "EXACT", [10]
                ),
                range(100),
            )
        )
    assert service.balance("other", "B", "A") == 1000
    assert len(service.history("other")) == 100
    for index in range(1000):
        service.add_expense("g", f"limit-{index}", "A", 1000000000, ["B"], "EXACT", [1000000000])
    rejects(lambda: service.add_expense("g", "overflow", "A", 2, ["C", "B"], "EXACT", [1, 1]))
    assert service.balance("g", "C", "A") == 0
    assert len(service.history("g")) == 1004
    snapshot = service.history("g")
    snapshot.clear()
    assert len(service.history("g")) == 1004


def notifications():
    email, sms, push = FakeChannel(), FakeChannel(2), FakeChannel(10)
    with NotificationService(
        {"EMAIL": email, "SMS": sms, "PUSH": push},
        {"t": "Hello {name}"},
        {"u": ["EMAIL", "SMS"], "bad": ["PUSH"], "off": []},
    ) as service:
        low = Request("low", "u", "t", "Ada", 2)
        service.submit(low)
        service.submit(Request("high", "u", "t", "Ada", 0))
        service.submit(low)
        service.submit(Request("fail", "bad", "t", "Ada", 1))
        service.submit(Request("skip", "off", "t", "Ada", 1))
        assert service.status("low") == "QUEUED"
        assert service.status("skip") == "SKIPPED"
        rejects(lambda: service.submit(Request("low", "u", "t", "changed", 2)))
        rejects(lambda: service.submit(Request("missing", "u", "missing", "Ada", 1)))
        rejects(lambda: service.submit(Request("invalid", "u", "t", "Ada", 3)))
        rejects(lambda: service.status("missing"))
        service.await_idle()
        assert service.sent_order() == ["high/EMAIL", "high/SMS", "low/EMAIL", "low/SMS"]
        assert service.attempts("high", "SMS") == 3
        assert service.attempts("low", "EMAIL") == 1
        assert service.attempts("fail", "PUSH") == 3
        assert service.status("fail") == "FAILED"
        assert email.delivered[0] == "high/EMAIL:Hello Ada"
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(
                pool.map(lambda _: service.submit(Request("same", "u", "t", "Ada", 1)), range(100))
            )
        service.await_idle()
        assert service.attempts("same", "EMAIL") == 1
        assert len(service.sent_order()) == 6
    drain = NotificationService({"EMAIL": email}, {"t": "{name}"}, {"u": ["EMAIL"]})
    drain.submit(Request("drain", "u", "t", "Ada", 1))
    drain.close()
    drain.close()
    assert drain.status("drain") == "SENT"
    rejects(lambda: drain.submit(Request("late", "u", "t", "Ada", 1)))
    rejects(drain.start)


def independent_elevators():
    first, release, second = Event(), Event(), Event()
    arrivals = []

    class Display:
        def arrived(self, elevator_id, floor):
            arrivals.append((elevator_id, floor))
            if elevator_id == 0 and floor == 3:
                first.set()
                if not release.wait(5):
                    raise RuntimeError("Release timed out")
            if elevator_id == 1:
                second.set()

    with ElevatorSystem(2, 10, Display()) as system:
        try:
            system.internal_request(0, 3)
            system.internal_request(0, 5)
            system.start()
            assert first.wait(2)
            system.internal_request(0, 4)
            system.internal_request(0, 1)
            system.internal_request(1, 2)
            assert second.wait(2), "Second car blocked behind first"
        finally:
            release.set()
        system.run_until_idle()
        assert [floor for elevator_id, floor in arrivals if elevator_id == 0] == [3, 4, 5, 1]
    rejects(lambda: system.internal_request(0, 1))
    assert all(not elevator.worker.is_alive() for elevator in system.elevators)


if __name__ == "__main__":
    ledger()
    notifications()
    independent_elevators()
    print("Python notification, Splitwise and independent elevator checks passed")
