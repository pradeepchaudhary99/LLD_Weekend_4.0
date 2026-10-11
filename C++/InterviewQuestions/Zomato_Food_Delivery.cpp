/*
 * INTERVIEW DESIGN NOTES - Food Delivery
 *
 * Implementation status: runnable, deliberately small in-memory teaching example.
 * The original classroom sketches are preserved in classroom-drafts/2026-10-11/.
 *
 * Functional requirements:
 * - Browse two fixed restaurant menus; submit a one-line cart (item and quantity).
 * - Charge an order, accept/reject it, prepare it and mark it ready for collection.
 * - Reserve one available rider, record pickup and delivery, then release the rider.
 * - Cancel before pickup; refund before changing state or releasing the rider.
 * - Record transition events for notification consumers and status tracking.
 *
 * Non-functional requirements:
 * - Reuse an order ID only for identical input; never charge it twice in this process.
 * - Use integer minor currency units; validate quantity 1..100 before charging.
 * - Serialize service operations; never assign one rider to two active deliveries.
 * - Return menu/event snapshots; retain copied order inputs and the charged amount.
 *
 * Core entities and responsibilities:
 * - Request: customer ID, address, restaurant and a single cart item/quantity.
 * - Order: immutable request/amount snapshot plus status and assigned rider.
 * - Restaurant/MenuItem: represented by fixed ID-to-price maps; no editable catalog.
 * - DeliveryPartner: ID-to-availability map; delivery state lives in Order.
 * - PaymentGateway: deterministic charge/refund fake; counters expose duplicate calls.
 * - FoodDelivery: owns order transitions, rider reservation and notification events.
 *
 * Key operations and flow:
 * - menu -> place -> ACCEPTED -> PREPARING -> READY -> assign -> PICKED_UP -> DELIVERED.
 * - place returns PLACED or PAYMENT_FAILED; repeated input returns current status.
 * - cancel(reject=true) rejects only PLACED orders; normal cancellation is pre-pickup.
 * - If no rider exists, assign returns false and the READY order can be retried.
 *
 * Design choices and invariants:
 * - Explicit transition table instead of a class for every state keeps the MVP small.
 * - One service owns coupled order/rider invariants; private helpers assume its lock.
 * - Failed refund preserves state/reservation; successful repeated cancel is a no-op.
 * - Failed charges are remembered; a new order ID is needed for a new attempt.
 * - Every committed transition appends an event; callers cannot mutate stored orders.
 *
 * Assumptions and out of scope:
 * - One currency; menu prices are minor units. No taxes, coupons or partial refunds.
 * - One item type per cart, fixed available menus and one rider keep the demo focused.
 * - Customer IDs/addresses are caller-provided; authentication/ownership is upstream.
 * - The fake gateway returns immediately without exceptions; configure it only when idle.
 * - No network payment ambiguity, durable idempotency, outbox, live GPS or real SMS.
 * - Real providers must not run under this lock: add durable intents, idempotency keys,
 *   reconciliation and an outbox before introducing remote calls or multiple processes.
 *
 * Interview discussion and checks:
 * - Demo checks duplicates/conflicts, wrong-restaurant items, invalid transitions,
 *   rider contention, refund retry, duplicate cancellation and cancellation after pickup.
 * - Discuss multiple cart lines, restaurant closure, rider rejection, payment timeouts,
 *   delivery timeouts, bounded event retention and restart-safe recovery.
 *
 * Concurrency in this edition:
 * - RAII lock_guard and recursive_mutex protect nested menu/place operations.
 *
 * Run / validation:
 * - From repository root: python3 scripts/run.py "C++" InterviewQuestions/Zomato_Food_Delivery
 * - Prerequisites, lifecycle and edge checks: see FOOD_DELIVERY.md.
 */

#include <functional>
#include <iostream>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

struct Request {
    std::string customer;
    std::string restaurant;
    std::string address;
    std::string item;
    int quantity;

    bool operator==(const Request &other) const {
        return std::tie(customer, restaurant, address, item, quantity) ==
               std::tie(other.customer, other.restaurant, other.address, other.item,
                        other.quantity);
    }
};

struct Order {
    Request request;
    int amount;
    std::string status;
    std::string partner;
};

struct PaymentGateway {
    bool acceptCharge = true;
    bool acceptRefund = true;
    int charges = 0;
    int refunds = 0;

    bool charge(int /*amount*/) {
        ++charges;
        return acceptCharge;
    }

    bool refund(int /*amount*/) {
        ++refunds;
        return acceptRefund;
    }
};

void require(bool condition, const std::string &message) {
    if (!condition) {
        throw std::invalid_argument(message);
    }
}

class FoodDelivery {
    PaymentGateway &gateway;
    std::recursive_mutex mutex;
    const std::map<std::string, std::map<std::string, int>> menus{{"r1", {{"pizza", 12000}}},
                                                                  {"r2", {{"rice", 8000}}}};
    std::map<std::string, Order> orders;
    std::map<std::string, bool> partners{{"rider1", true}};
    std::vector<std::string> audit;

    Order &order(const std::string &id) {
        require(orders.count(id) != 0, "Unknown order");
        return orders.at(id);
    }

    void change(const std::string &id, Order &order, const std::string &status) {
        order.status = status;
        audit.push_back(id + ":" + status);
    }

  public:
    explicit FoodDelivery(PaymentGateway &gateway) : gateway(gateway) {
    }

    std::map<std::string, int> menu(const std::string &restaurant) {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        require(menus.count(restaurant) != 0, "Unknown restaurant");
        return menus.at(restaurant);
    }

    std::string place(const std::string &id, const Request &request) {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        if (orders.count(id)) {
            require(orders.at(id).request == request, "Conflicting order ID");
            return orders.at(id).status;
        }
        require(!id.empty() && !request.customer.empty() && !request.address.empty(),
                "Required identity/address");
        auto items = menu(request.restaurant);
        require(items.count(request.item) && request.quantity >= 1 && request.quantity <= 100,
                "Invalid cart");
        int amount = items.at(request.item) * request.quantity;
        std::string status = gateway.charge(amount) ? "PLACED" : "PAYMENT_FAILED";
        orders.emplace(id, Order{request, amount, status, ""});
        audit.push_back(id + ":" + status);
        return status;
    }

    void advance(const std::string &id, const std::string &target) {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        auto &current = order(id);
        const std::map<std::string, std::string> next{{"PLACED", "ACCEPTED"},
                                                      {"ACCEPTED", "PREPARING"},
                                                      {"PREPARING", "READY"},
                                                      {"ASSIGNED", "PICKED_UP"},
                                                      {"PICKED_UP", "DELIVERED"}};
        auto found = next.find(current.status);
        require(found != next.end() && found->second == target, "Invalid transition");
        change(id, current, target);
        if (target == "DELIVERED") {
            partners.at(current.partner) = true;
        }
    }

    bool assign(const std::string &id) {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        auto &current = order(id);
        require(current.status == "READY", "Order not ready");
        for (auto &entry : partners) {
            if (entry.second) {
                entry.second = false;
                current.partner = entry.first;
                change(id, current, "ASSIGNED");
                return true;
            }
        }
        return false;
    }

    bool cancel(const std::string &id, bool reject = false) {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        auto &current = order(id);
        const std::string target = reject ? "REJECTED" : "CANCELLED";
        if (current.status == target) {
            return true;
        }
        bool allowed = current.status == "PLACED" ||
                       (!reject && (current.status == "ACCEPTED" || current.status == "PREPARING" ||
                                    current.status == "READY" || current.status == "ASSIGNED"));
        require(allowed, "Cannot cancel");
        if (!gateway.refund(current.amount)) {
            return false;
        }
        if (!current.partner.empty()) {
            partners.at(current.partner) = true;
        }
        change(id, current, target);
        return true;
    }

    std::string status(const std::string &id) {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        return order(id).status;
    }

    std::vector<std::string> events() {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        return audit;
    }
};

void check(bool condition) {
    if (!condition) {
        throw std::runtime_error("Contract failed");
    }
}

void rejected(const std::function<void()> &action) {
    try {
        action();
    } catch (const std::invalid_argument &) {
        return;
    }
    throw std::runtime_error("Expected rejection");
}

int main() {
    PaymentGateway gateway;
    FoodDelivery service(gateway);
    Request request{"alice", "r1", "Home", "pizza", 2};
    check(service.menu("r1").at("pizza") == 12000);
    check(service.place("o1", request) == "PLACED");
    check(service.place("o1", request) == "PLACED" && gateway.charges == 1);
    rejected([&] {
        service.place("o1", {"bob", "r1", "Home", "pizza", 2});
    });
    rejected([&] {
        service.place("bad", {"alice", "r1", "Home", "rice", 1});
    });
    rejected([&] {
        service.advance("o1", "DELIVERED");
    });
    for (const auto &id : {"o1", "o2"}) {
        service.place(id, request);
        for (const auto &state : {"ACCEPTED", "PREPARING", "READY"}) {
            service.advance(id, state);
        }
    }
    check(service.assign("o1"));
    check(!service.assign("o2"));
    gateway.acceptRefund = false;
    check(!service.cancel("o1") && service.status("o1") == "ASSIGNED");
    gateway.acceptRefund = true;
    check(service.cancel("o1"));
    int refunds = gateway.refunds;
    check(service.cancel("o1") && gateway.refunds == refunds);
    check(service.assign("o2"));
    service.advance("o2", "PICKED_UP");
    rejected([&] {
        service.cancel("o2");
    });
    service.advance("o2", "DELIVERED");
    rejected([&] {
        service.advance("o2", "PREPARING");
    });
    service.place("o3", request);
    check(service.cancel("o3", true));
    gateway.acceptCharge = false;
    check(service.place("o4", request) == "PAYMENT_FAILED");
    int charges = gateway.charges;
    check(service.place("o4", request) == "PAYMENT_FAILED" && gateway.charges == charges);
    rejected([&] {
        service.place("bad", {"alice", "r1", "Home", "pizza", 0});
    });
    rejected([&] {
        service.status("missing");
    });
    service.events().clear();
    check(!service.events().empty());
    std::cout << "Delivered: " << service.status("o2") << '\n';
    std::cout << "Cancelled: " << service.status("o1") << '\n';
    std::cout << "Rejected: " << service.status("o3") << '\n';
    std::cout << "Failed payment: " << service.status("o4") << '\n';
    std::cout << "Edge cases: passed\n";
}
