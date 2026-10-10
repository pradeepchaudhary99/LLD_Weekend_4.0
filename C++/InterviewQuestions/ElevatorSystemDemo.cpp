#include <algorithm>
#include <cmath>
#include <condition_variable>
#include <exception>
#include <optional>
#include <thread>
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
    int current = 0;
    Direction direction = Direction::Idle;
    std::set<int> stops;
    std::mutex gate;
    std::condition_variable changed;
    bool shutdownRequested = false;
    bool notifying = false;
    std::exception_ptr failure;

    void checkFailure() {
        if (failure) {
            std::rethrow_exception(failure);
        }
    }

  public:
    const int id;

    Elevator(int id, Observer display) : display(std::move(display)), id(id) {
        if (!this->display) {
            throw std::invalid_argument("Missing display");
        }
    }

    int floor() {
        std::lock_guard<std::mutex> lock(gate);
        return current;
    }

    void addStop(int floor) {
        std::lock_guard<std::mutex> lock(gate);
        checkFailure();
        if (shutdownRequested) {
            throw std::logic_error("Elevator shutting down");
        }
        stops.insert(floor);
        changed.notify_all();
    }

    bool isIdle() {
        std::lock_guard<std::mutex> lock(gate);
        checkFailure();
        return stops.empty() && !notifying;
    }

    void awaitIdle() {
        std::unique_lock<std::mutex> lock(gate);
        changed.wait(lock, [&] {
            return failure || (stops.empty() && !notifying);
        });
        checkFailure();
    }

    void shutdown() {
        std::lock_guard<std::mutex> lock(gate);
        shutdownRequested = true;
        changed.notify_all();
    }

    void run() {
        try {
            while (true) {
                std::optional<int> arrived;
                {
                    std::unique_lock<std::mutex> lock(gate);
                    changed.wait(lock, [&] {
                        return !stops.empty() || shutdownRequested;
                    });
                    if (stops.empty()) {
                        return;
                    }
                    if (stops.erase(current)) {
                        arrived = current;
                    } else {
                        auto above = stops.upper_bound(current);
                        auto below = stops.lower_bound(current);
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
                        direction = target > current ? Direction::Up : Direction::Down;
                        current += direction == Direction::Up ? 1 : -1;
                        if (stops.erase(current)) {
                            arrived = current;
                        }
                    }
                    if (stops.empty()) {
                        direction = Direction::Idle;
                    }
                    notifying = arrived.has_value();
                }
                if (arrived) {
                    display(id, *arrived);
                }
                {
                    std::lock_guard<std::mutex> lock(gate);
                    notifying = false;
                    changed.notify_all();
                }
            }
        } catch (...) {
            std::lock_guard<std::mutex> lock(gate);
            failure = std::current_exception();
            shutdownRequested = true;
            notifying = false;
            changed.notify_all();
        }
    }
};

class SelectionStrategy {
  public:
    virtual ~SelectionStrategy() = default;
    virtual std::size_t select(const std::vector<std::unique_ptr<Elevator>> &elevators,
                               int floor) = 0;
};

class NearestElevatorStrategy : public SelectionStrategy {
  public:
    std::size_t select(const std::vector<std::unique_ptr<Elevator>> &elevators,
                       int floor) override {
        std::size_t selected = 0;
        for (std::size_t index = 1; index < elevators.size(); index++) {
            if (std::abs(elevators[index]->floor() - floor) <
                std::abs(elevators[selected]->floor() - floor)) {
                selected = index;
            }
        }
        return selected;
    }
};

class RoundRobinStrategy : public SelectionStrategy {
    std::size_t next = 0;

  public:
    std::size_t select(const std::vector<std::unique_ptr<Elevator>> &elevators, int) override {
        auto selected = next;
        next = (next + 1) % elevators.size();
        return selected;
    }
};

class ElevatorSystem {
    int topFloor;
    std::vector<std::unique_ptr<Elevator>> elevators;
    std::unique_ptr<SelectionStrategy> strategy = std::make_unique<NearestElevatorStrategy>();
    std::mutex mutex;
    std::mutex closeGate;
    std::vector<std::thread> workers;
    bool started = false;
    bool closed = false;

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
            elevators.push_back(std::make_unique<Elevator>(id, display));
        }
    }

    void setStrategy(std::unique_ptr<SelectionStrategy> replacement) {
        std::lock_guard<std::mutex> guard(mutex);
        ensureOpen();
        if (!replacement) {
            throw std::invalid_argument("Missing strategy");
        }
        strategy = std::move(replacement);
    }

    int externalRequest(int floor, Direction direction) {
        std::lock_guard<std::mutex> guard(mutex);
        ensureOpen();
        validate(floor);
        if (direction == Direction::Idle || (floor == 0 && direction == Direction::Down) ||
            (floor == topFloor && direction == Direction::Up)) {
            throw std::invalid_argument("Invalid hall direction");
        }
        auto &selected = elevators.at(strategy->select(elevators, floor));
        selected->addStop(floor);
        return selected->id;
    }

    void internalRequest(int elevatorId, int floor) {
        std::lock_guard<std::mutex> guard(mutex);
        ensureOpen();
        validate(floor);
        if (elevatorId < 0 || static_cast<std::size_t>(elevatorId) >= elevators.size()) {
            throw std::invalid_argument("Invalid elevator");
        }
        elevators[elevatorId]->addStop(floor);
    }

    void ensureOpen() {
        if (closed) {
            throw std::logic_error("System closed");
        }
    }

    void startWorkers() {
        if (!started) {
            started = true;
            for (const auto &elevator : elevators) {
                workers.emplace_back(&Elevator::run, elevator.get());
            }
        }
    }

    void start() {
        std::lock_guard<std::mutex> lock(mutex);
        ensureOpen();
        startWorkers();
    }

    void runUntilIdle() {
        start();
        while (true) {
            for (const auto &elevator : elevators) {
                elevator->awaitIdle();
            }
            std::lock_guard<std::mutex> lock(mutex);
            if (std::all_of(elevators.begin(), elevators.end(), [](const auto &elevator) {
                    return elevator->isIdle();
                })) {
                return;
            }
        }
    }

    void close() {
        // The owner manages lifecycle; observer callbacks must not wait on the system.
        std::lock_guard<std::mutex> closing(closeGate);
        {
            std::lock_guard<std::mutex> lock(mutex);
            closed = true;
            startWorkers();
            for (const auto &elevator : elevators) {
                elevator->shutdown();
            }
        }
        for (auto &worker : workers) {
            if (worker.joinable()) {
                worker.join();
            }
        }
        for (const auto &elevator : elevators) {
            elevator->isIdle();
        }
    }

    ~ElevatorSystem() {
        try {
            close();
        } catch (...) {
            // Explicit close()/runUntilIdle() report failures; destructors only clean up.
        }
    }
};

#ifndef LLD_TEST

int main() {
    std::mutex displayGate;
    std::vector<std::pair<int, int>> arrivals;
    ElevatorSystem system(2, 10, [&](int id, int floor) {
        std::lock_guard<std::mutex> lock(displayGate);
        arrivals.emplace_back(id, floor);
    });
    auto printArrivals = [&] {
        std::lock_guard<std::mutex> lock(displayGate);
        std::stable_sort(arrivals.begin(), arrivals.end(), [](auto left, auto right) {
            return left.first < right.first;
        });
        for (const auto &arrival : arrivals) {
            std::cout << "Elevator " << arrival.first << " arrived: " << arrival.second << '\n';
        }
        arrivals.clear();
    };
    std::cout << "Selected: " << system.externalRequest(3, Direction::Up) << '\n';
    system.internalRequest(0, 5);
    system.internalRequest(0, 5);
    system.runUntilIdle();
    printArrivals();
    std::cout << "Selected: " << system.externalRequest(4, Direction::Down) << '\n';
    system.internalRequest(0, 1);
    system.runUntilIdle();
    printArrivals();
    system.setStrategy(std::make_unique<RoundRobinStrategy>());
    std::cout << "Round robin: " << system.externalRequest(0, Direction::Up) << '\n';
    std::cout << "Round robin: " << system.externalRequest(0, Direction::Up) << '\n';
    system.runUntilIdle();
    printArrivals();
    system.runUntilIdle();
    printArrivals();
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

#endif
