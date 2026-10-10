"""One worker thread per elevator; the controller only assigns requests."""

from threading import Condition, RLock, Thread, current_thread


class Display:
    def __init__(self):
        self._arrivals = []
        self._lock = RLock()

    def arrived(self, elevator_id, floor):
        with self._lock:
            self._arrivals.append((elevator_id, floor))

    def print_arrivals(self):
        with self._lock:
            for elevator_id, floor in sorted(self._arrivals, key=lambda arrival: arrival[0]):
                print(f"Elevator {elevator_id} arrived: {floor}")
            self._arrivals.clear()


class Elevator:
    def __init__(self, elevator_id, display):
        self.id = elevator_id
        self._floor = 0
        self._direction = "IDLE"
        self._stops = set()
        self._display = display
        self._condition = Condition()
        self._shutdown = False
        self._notifying = False
        self._failure = None
        self.worker = Thread(target=self.run, name=f"elevator-{elevator_id}")

    @property
    def floor(self):
        with self._condition:
            return self._floor

    def _check_failure(self):
        if self._failure is not None:
            raise RuntimeError(f"Elevator {self.id} failed") from self._failure

    def add_stop(self, floor):
        with self._condition:
            self._check_failure()
            if self._shutdown:
                raise RuntimeError("Elevator shutting down")
            self._stops.add(floor)
            self._condition.notify_all()

    def is_idle(self):
        with self._condition:
            self._check_failure()
            return not self._stops and not self._notifying

    def await_idle(self):
        with self._condition:
            while not self.is_idle():
                self._condition.wait()

    def shutdown(self):
        with self._condition:
            self._shutdown = True
            self._condition.notify_all()

    def run(self):
        try:
            while True:
                arrived = None
                with self._condition:
                    while not self._stops and not self._shutdown:
                        self._condition.wait()
                    if not self._stops:
                        return
                    if self._floor in self._stops:
                        self._stops.remove(self._floor)
                        arrived = self._floor
                    else:
                        above = sorted(stop for stop in self._stops if stop > self._floor)
                        below = sorted(
                            (stop for stop in self._stops if stop < self._floor), reverse=True
                        )
                        candidates = (
                            (below or above) if self._direction == "DOWN" else (above or below)
                        )
                        target = candidates[0]
                        self._direction = "UP" if target > self._floor else "DOWN"
                        self._floor += 1 if self._direction == "UP" else -1
                        if self._floor in self._stops:
                            self._stops.remove(self._floor)
                            arrived = self._floor
                    if not self._stops:
                        self._direction = "IDLE"
                    self._notifying = arrived is not None
                if arrived is not None:
                    self._display.arrived(self.id, arrived)
                with self._condition:
                    self._notifying = False
                    self._condition.notify_all()
        except BaseException as error:
            with self._condition:
                self._failure = error
        finally:
            with self._condition:
                self._shutdown = True
                self._notifying = False
                self._condition.notify_all()


class NearestElevatorStrategy:
    def select(self, elevators, floor):
        return min(elevators, key=lambda elevator: (abs(elevator.floor - floor), elevator.id))


class RoundRobinStrategy:
    def __init__(self):
        self.next = 0

    def select(self, elevators, floor):
        selected = elevators[self.next]
        self.next = (self.next + 1) % len(elevators)
        return selected


class ElevatorSystem:
    def __init__(self, count, top_floor, display):
        if type(count) is not int or type(top_floor) is not int or count <= 0 or top_floor < 1:
            raise ValueError("Invalid building")
        self.top_floor = top_floor
        self.elevators = [Elevator(index, display) for index in range(count)]
        self.strategy = NearestElevatorStrategy()
        self.lock = RLock()
        self._started = False
        self._closed = False

    def _ensure_open(self):
        if self._closed:
            raise RuntimeError("System is closed")

    def validate(self, floor):
        if type(floor) is not int or not 0 <= floor <= self.top_floor:
            raise ValueError("Invalid floor")

    def set_strategy(self, strategy):
        with self.lock:
            self._ensure_open()
            if strategy is None:
                raise ValueError("Missing strategy")
            self.strategy = strategy

    def external_request(self, floor, direction):
        with self.lock:
            self._ensure_open()
            self.validate(floor)
            if (
                direction not in ("UP", "DOWN")
                or (floor == 0 and direction == "DOWN")
                or (floor == self.top_floor and direction == "UP")
            ):
                raise ValueError("Invalid hall direction")
            selected = self.strategy.select(self.elevators, floor)
            selected.add_stop(floor)
            return selected.id

    def internal_request(self, elevator_id, floor):
        with self.lock:
            self._ensure_open()
            self.validate(floor)
            if type(elevator_id) is not int or not 0 <= elevator_id < len(self.elevators):
                raise ValueError("Invalid elevator")
            self.elevators[elevator_id].add_stop(floor)

    def _start_workers(self):
        if not self._started:
            self._started = True
            for elevator in self.elevators:
                elevator.worker.start()

    def start(self):
        with self.lock:
            self._ensure_open()
            self._start_workers()

    def _reject_worker_wait(self):
        if any(elevator.worker is current_thread() for elevator in self.elevators):
            raise RuntimeError("Worker cannot wait for itself")

    def run_until_idle(self):
        self._reject_worker_wait()
        self.start()
        while True:
            for elevator in self.elevators:
                elevator.await_idle()
            with self.lock:
                if all(elevator.is_idle() for elevator in self.elevators):
                    return

    def close(self):
        self._reject_worker_wait()
        with self.lock:
            self._closed = True
            self._start_workers()
            for elevator in self.elevators:
                elevator.shutdown()
        for elevator in self.elevators:
            elevator.worker.join()
        for elevator in self.elevators:
            elevator.is_idle()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def main():
    display = Display()
    with ElevatorSystem(2, 10, display) as system:
        print(f"Selected: {system.external_request(3, 'UP')}")
        system.internal_request(0, 5)
        system.internal_request(0, 5)
        system.run_until_idle()
        display.print_arrivals()
        print(f"Selected: {system.external_request(4, 'DOWN')}")
        system.internal_request(0, 1)
        system.run_until_idle()
        display.print_arrivals()
        system.set_strategy(RoundRobinStrategy())
        print(f"Round robin: {system.external_request(0, 'UP')}")
        print(f"Round robin: {system.external_request(0, 'UP')}")
        system.run_until_idle()
        display.print_arrivals()
        system.run_until_idle()
        display.print_arrivals()
        try:
            system.internal_request(0, 11)
            raise AssertionError("Invalid floor accepted")
        except ValueError:
            print("Invalid floor rejected")
        try:
            system.external_request(0, "DOWN")
            raise AssertionError("Invalid direction accepted")
        except ValueError:
            print("Invalid direction rejected")


if __name__ == "__main__":
    main()
