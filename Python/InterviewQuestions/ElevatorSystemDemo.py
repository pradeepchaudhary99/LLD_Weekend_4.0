"""A deterministic controller: one tick moves each elevator at most one floor."""

from threading import RLock


class Display:
    def arrived(self, elevator_id, floor):
        print(f"Elevator {elevator_id} arrived: {floor}")


class Elevator:
    def __init__(self, elevator_id, display):
        self.id = elevator_id
        self.floor = 0
        self.direction = "IDLE"
        self.stops = set()
        self.display = display

    def serve(self):
        if self.floor in self.stops:
            self.stops.remove(self.floor)
            self.display.arrived(self.id, self.floor)

    def tick(self):
        self.serve()
        if not self.stops:
            self.direction = "IDLE"
            return
        above = sorted(stop for stop in self.stops if stop > self.floor)
        below = sorted((stop for stop in self.stops if stop < self.floor), reverse=True)
        candidates = (below or above) if self.direction == "DOWN" else (above or below)
        target = candidates[0]
        self.direction = "UP" if target > self.floor else "DOWN"
        self.floor += 1 if self.direction == "UP" else -1
        self.serve()
        if not self.stops:
            self.direction = "IDLE"


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
        if count <= 0 or top_floor < 1:
            raise ValueError("Invalid building")
        self.top_floor = top_floor
        self.elevators = [Elevator(index, display) for index in range(count)]
        self.strategy = NearestElevatorStrategy()
        self.lock = RLock()

    def validate(self, floor):
        if floor < 0 or floor > self.top_floor:
            raise ValueError("Invalid floor")

    def set_strategy(self, strategy):
        with self.lock:
            self.strategy = strategy

    def external_request(self, floor, direction):
        with self.lock:
            self.validate(floor)
            if (
                direction not in ("UP", "DOWN")
                or (floor == 0 and direction == "DOWN")
                or (floor == self.top_floor and direction == "UP")
            ):
                raise ValueError("Invalid hall direction")
            selected = self.strategy.select(self.elevators, floor)
            selected.stops.add(floor)
            return selected.id

    def internal_request(self, elevator_id, floor):
        with self.lock:
            self.validate(floor)
            if elevator_id < 0 or elevator_id >= len(self.elevators):
                raise ValueError("Invalid elevator")
            self.elevators[elevator_id].stops.add(floor)

    def run_until_idle(self):
        with self.lock:
            while any(elevator.stops for elevator in self.elevators):
                for elevator in self.elevators:
                    elevator.tick()


def main():
    system = ElevatorSystem(2, 10, Display())
    print(f"Selected: {system.external_request(3, 'UP')}")
    system.internal_request(0, 5)
    system.internal_request(0, 5)
    system.run_until_idle()
    print(f"Selected: {system.external_request(4, 'DOWN')}")
    system.internal_request(0, 1)
    system.run_until_idle()
    system.set_strategy(RoundRobinStrategy())
    print(f"Round robin: {system.external_request(0, 'UP')}")
    print(f"Round robin: {system.external_request(0, 'UP')}")
    system.run_until_idle()
    system.run_until_idle()
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
