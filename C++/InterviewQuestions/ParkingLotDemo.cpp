#include <algorithm>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

enum class VehicleType { Bike, Car, Truck };
struct Vehicle {
    std::string plate;
    VehicleType type;
};
struct Slot {
    int level;
    int id;
    VehicleType type;
    int exitDistance;
    bool occupied = false;
};
struct Ticket {
    int id;
    Vehicle vehicle;
    std::shared_ptr<Slot> slot;
    int enteredAt;
};
using Slots = std::vector<std::shared_ptr<Slot>>;
using Payment = std::function<bool(int, long long)>;

class SlotAssignmentStrategy {
  public:
    virtual ~SlotAssignmentStrategy() = default;
    virtual std::shared_ptr<Slot> select(const Slots &slots, const Vehicle &vehicle) = 0;
};
class FirstFit : public SlotAssignmentStrategy {
  public:
    std::shared_ptr<Slot> select(const Slots &slots, const Vehicle &vehicle) override {
        for (const auto &slot : slots) {
            if (!slot->occupied && slot->type == vehicle.type) {
                return slot;
            }
        }

        return nullptr;
    }
};
class NearestExit : public SlotAssignmentStrategy {
  public:
    std::shared_ptr<Slot> select(const Slots &slots, const Vehicle &vehicle) override {
        std::shared_ptr<Slot> selected;
        for (const auto &slot : slots) {
            if (!slot->occupied && slot->type == vehicle.type &&
                (!selected || slot->exitDistance < selected->exitDistance)) {
                selected = slot;
            }
        }

        return selected;
    }
};
class FeesCalculationStrategy {
  public:
    virtual ~FeesCalculationStrategy() = default;
    virtual long long fee(long long minutes) const = 0;
};
class HourlyFee : public FeesCalculationStrategy {
  public:
    long long fee(long long minutes) const override {
        if (minutes < 0) {
            throw std::invalid_argument("Exit precedes entry");
        }

        return std::max(1LL, minutes / 60 + (minutes % 60 == 0 ? 0 : 1)) * 50;
    }
};
class ParkingLotManager {
    Slots slots;
    std::unique_ptr<FeesCalculationStrategy> fees;
    std::unique_ptr<SlotAssignmentStrategy> strategy = std::make_unique<FirstFit>();
    std::map<int, Ticket> tickets;
    std::set<std::string> plates;
    std::mutex mutex;
    int nextId = 1;

  public:
    ParkingLotManager(Slots slots, std::unique_ptr<FeesCalculationStrategy> fees)
        : slots(std::move(slots)), fees(std::move(fees)) {
    }

    void setStrategy(std::unique_ptr<SlotAssignmentStrategy> replacement) {
        std::lock_guard<std::mutex> guard(mutex);
        strategy = std::move(replacement);
    }

    Ticket park(const Vehicle &vehicle, int minute) {
        std::lock_guard<std::mutex> guard(mutex);
        if (vehicle.plate.find_first_not_of(" \t\r\n") == std::string::npos) {
            throw std::invalid_argument("Invalid vehicle");
        }

        if (minute < 0) {
            throw std::invalid_argument("Invalid entry time");
        }

        if (plates.count(vehicle.plate)) {
            throw std::invalid_argument("Vehicle already parked");
        }

        auto slot = strategy->select(slots, vehicle);
        if (!slot) {
            throw std::invalid_argument("No compatible slot");
        }

        Ticket ticket{nextId++, vehicle, slot, minute};
        tickets.emplace(ticket.id, ticket);
        plates.insert(vehicle.plate);
        slot->occupied = true;
        return ticket;
    }

    long long exit(int ticketId, int minute, const Payment &payment) {
        std::lock_guard<std::mutex> guard(mutex);
        auto found = tickets.find(ticketId);
        if (found == tickets.end()) {
            throw std::invalid_argument("Unknown or closed ticket");
        }

        const auto &ticket = found->second;
        auto amount = fees->fee(static_cast<long long>(minute) - ticket.enteredAt);
        if (!payment(ticketId, amount)) {
            throw std::invalid_argument("Payment failed; vehicle remains parked");
        }

        ticket.slot->occupied = false;
        plates.erase(ticket.vehicle.plate);
        tickets.erase(found);
        return amount;
    }
};
struct EntryGate {
    int id;
    ParkingLotManager &manager;
    Ticket enter(const Vehicle &vehicle, int minute) {
        return manager.park(vehicle, minute);
    }
};
struct ExitGate {
    int id;
    ParkingLotManager &manager;
    long long leave(int ticket, int minute, const Payment &payment) {
        return manager.exit(ticket, minute, payment);
    }
};
void reject(const std::function<void()> &action) {
    try {
        action();
    } catch (const std::invalid_argument &error) {
        std::cout << error.what() << '\n';
        return;
    }

    throw std::runtime_error("Expected rejection");
}

int main() {
    Slots slots;
    for (Slot slot : {Slot{0, 1, VehicleType::Car, 8}, Slot{0, 2, VehicleType::Bike, 1},
                      Slot{1, 3, VehicleType::Truck, 2}, Slot{1, 4, VehicleType::Car, 3}}) {
        slots.push_back(std::make_shared<Slot>(slot));
    }

    ParkingLotManager lot(slots, std::make_unique<HourlyFee>());
    EntryGate entryA{1, lot};
    EntryGate entryB{2, lot};
    ExitGate exit{1, lot};
    auto accept = [](int, long long) {
        return true;
    };
    auto fail = [](int, long long) {
        return false;
    };
    auto first = entryA.enter({"CAR-1", VehicleType::Car}, 0);
    std::cout << "Ticket " << first.id << ": " << first.slot->level << '/' << first.slot->id
              << '\n';
    lot.setStrategy(std::make_unique<NearestExit>());
    auto second = entryB.enter({"CAR-2", VehicleType::Car}, 0);
    std::cout << "Ticket " << second.id << ": " << second.slot->level << '/' << second.slot->id
              << '\n';
    reject([&] {
        entryB.enter({"CAR-1", VehicleType::Car}, 0);
    });
    reject([&] {
        entryA.enter({"CAR-3", VehicleType::Car}, 0);
    });
    reject([&] {
        exit.leave(first.id, -1, accept);
    });
    reject([&] {
        exit.leave(first.id, 61, fail);
    });
    reject([&] {
        entryA.enter({"CAR-3", VehicleType::Car}, 61);
    });
    std::cout << "Paid: " << exit.leave(first.id, 61, accept) << '\n';
    reject([&] {
        exit.leave(first.id, 61, accept);
    });
    auto reused = entryA.enter({"CAR-3", VehicleType::Car}, 62);
    std::cout << "Reused: " << reused.slot->level << '/' << reused.slot->id << '\n';
    std::cout << "Bike slot: " << entryA.enter({"BIKE-1", VehicleType::Bike}, 0).slot->id << '\n';
    std::cout << "Truck slot: " << entryB.enter({"TRUCK-1", VehicleType::Truck}, 0).slot->id
              << '\n';
}
