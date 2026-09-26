#include <algorithm>
#include <cmath>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <set>
#include <stdexcept>
#include <vector>

using Observer = std::function<void(int, int)>;
enum class Direction { Up, Down, Idle };

class Elevator {
    Observer display;
    void serve() {
        if (stops.erase(floor)) {
            display(id, floor);
        }
    }

  public:
    int id;
    int floor = 0;
    Direction direction = Direction::Idle;
    std::set<int> stops;

    Elevator(int id, Observer display) : display(display), id(id) {
    }

    void tick() {
        serve();
        if (stops.empty()) {
            direction = Direction::Idle;
            return;
        }
        auto above = stops.upper_bound(floor);
        auto below = stops.lower_bound(floor);
        bool hasAbove = above != stops.end();
        bool hasBelow = below != stops.begin();
        int target;
        if (direction == Direction::Down && hasBelow) {
            target = *std::prev(below);
        } else if (hasAbove) {
            target = *above;
        } else {
            target = *std::prev(below);
        }
        direction = target > floor ? Direction::Up : Direction::Down;
        floor += direction == Direction::Up ? 1 : -1;
        serve();
        if (stops.empty()) {
            direction = Direction::Idle;
        }
    }
};

class SelectionStrategy {
  public:
    virtual ~SelectionStrategy() = default;
    virtual std::size_t select(const std::vector<Elevator> &elevators, int floor) = 0;
};

class NearestElevatorStrategy : public SelectionStrategy {
  public:
    std::size_t select(const std::vector<Elevator> &elevators, int floor) override {
        std::size_t selected = 0;
        for (std::size_t index = 1; index < elevators.size(); index++) {
            if (std::abs(elevators[index].floor - floor) <
                std::abs(elevators[selected].floor - floor)) {
                selected = index;
            }
        }
        return selected;
    }
};

class RoundRobinStrategy : public SelectionStrategy {
    std::size_t next = 0;

  public:
    std::size_t select(const std::vector<Elevator> &elevators, int) override {
        auto selected = next;
        next = (next + 1) % elevators.size();
        return selected;
    }
};

class ElevatorSystem {
    int topFloor;
    std::vector<Elevator> elevators;
    std::unique_ptr<SelectionStrategy> strategy = std::make_unique<NearestElevatorStrategy>();
    std::mutex mutex;

    void validate(int floor) const {
        if (floor < 0 || floor > topFloor) {
            throw std::invalid_argument("Invalid floor");
        }
    }

  public:
    ElevatorSystem(int count, int topFloor, Observer display) : topFloor(topFloor) {
        if (count <= 0 || topFloor < 1) {
            throw std::invalid_argument("Invalid building");
        }
        for (int id = 0; id < count; id++) {
            elevators.emplace_back(id, display);
        }
    }

    void setStrategy(std::unique_ptr<SelectionStrategy> replacement) {
        std::lock_guard<std::mutex> guard(mutex);
        if (!replacement) {
            throw std::invalid_argument("Missing strategy");
        }
        strategy = std::move(replacement);
    }

    int externalRequest(int floor, Direction direction) {
        std::lock_guard<std::mutex> guard(mutex);
        validate(floor);
        if (direction == Direction::Idle || (floor == 0 && direction == Direction::Down) ||
            (floor == topFloor && direction == Direction::Up)) {
            throw std::invalid_argument("Invalid hall direction");
        }
        auto &selected = elevators.at(strategy->select(elevators, floor));
        selected.stops.insert(floor);
        return selected.id;
    }

    void internalRequest(int elevatorId, int floor) {
        std::lock_guard<std::mutex> guard(mutex);
        validate(floor);
        if (elevatorId < 0 || static_cast<std::size_t>(elevatorId) >= elevators.size()) {
            throw std::invalid_argument("Invalid elevator");
        }
        elevators[elevatorId].stops.insert(floor);
    }

    void runUntilIdle() {
        std::lock_guard<std::mutex> guard(mutex);
        while (std::any_of(elevators.begin(), elevators.end(), [](const Elevator &elevator) {
            return !elevator.stops.empty();
        })) {
            for (auto &elevator : elevators) {
                elevator.tick();
            }
        }
    }
};

int main() {
    ElevatorSystem system(2, 10, [](int id, int floor) {
        std::cout << "Elevator " << id << " arrived: " << floor << '\n';
    });
    std::cout << "Selected: " << system.externalRequest(3, Direction::Up) << '\n';
    system.internalRequest(0, 5);
    system.internalRequest(0, 5);
    system.runUntilIdle();
    std::cout << "Selected: " << system.externalRequest(4, Direction::Down) << '\n';
    system.internalRequest(0, 1);
    system.runUntilIdle();
    system.setStrategy(std::make_unique<RoundRobinStrategy>());
    std::cout << "Round robin: " << system.externalRequest(0, Direction::Up) << '\n';
    std::cout << "Round robin: " << system.externalRequest(0, Direction::Up) << '\n';
    system.runUntilIdle();
    system.runUntilIdle();
    try {
        system.internalRequest(0, 11);
        throw std::runtime_error("Invalid floor accepted");
    } catch (const std::invalid_argument &) {
        std::cout << "Invalid floor rejected\n";
    }
    try {
        system.externalRequest(0, Direction::Down);
        throw std::runtime_error("Invalid direction accepted");
    } catch (const std::invalid_argument &) {
        std::cout << "Invalid direction rejected\n";
    }
}
