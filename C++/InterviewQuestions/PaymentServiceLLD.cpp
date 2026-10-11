/*
 * INTERVIEW DESIGN NOTES - Payment Service
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Create payments for CARD, UPI, NET_BANKING and WALLET methods.
 * - Choose a registered gateway and track PROCESSING, SUCCESS, FAILED or REFUNDED.
 * - Deduplicate identical keys and reject changed payloads under the same key.
 * - Retry ambiguous gateway outcomes up to three total attempts.
 * - Support full refunds and trusted, deduplicated reconciliation webhooks.
 *
 * Non-functional requirements:
 * - Serialize state transitions within a service instance.
 * - Use integer minor units and immutable/copied payment snapshots.
 * - Retain stable gateway payment identity across retries.
 * - Keep provider integration replaceable and failure scenarios testable locally.
 *
 * Core entities and responsibilities:
 * - Request: order, amount, method and gateway identity.
 * - Payment: ID, request and status snapshot.
 * - PaymentService: owns payment records and idempotency/event indexes.
 * - Gateway: charge/refund contract; FakeGateway simulates provider outcomes.
 *
 * Key operations and flow:
 * - pay -> validate -> check key -> store PROCESSING -> retry the same gateway ID.
 * - Definitive success/decline becomes SUCCESS/FAILED; exhaustion stays PROCESSING.
 * - refund requires SUCCESS; trusted webhook reconciles a PROCESSING payment.
 *
 * Design choices and invariants:
 * - Gateway interface isolates provider behavior from orchestration.
 * - An uncertain response is not proof of failure; do not invent a new payment ID.
 * - Full refund retries reuse the ID; late events cannot revert terminal states.
 * - Webhook event identity is scoped by provider; validate payment/provider match.
 *
 * Assumptions and out of scope:
 * - One assumed currency (INR), fake providers and in-memory records only.
 * - Webhook authentication happens before this trusted method; no HTTP endpoint.
 * - No partial refunds, persistent transactions, multi-process coordination,
 * - real payment credentials, retry backoff or conflicting-final-event recovery.
 *
 * Interview discussion and checks:
 * - Test concurrent duplicate keys, payload conflicts, lost responses and exhaustion.
 * - Test decline, failed/refunded retries, stale events and snapshot independence.
 * - Discuss durable unique constraints, provider idempotency and reconciliation.
 *
 * Concurrency in this edition:
 * - Instance-level synchronization protects service/game mutations.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py 'C++' InterviewQuestions/PaymentServiceLLD
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <tuple>
#include <utility>

namespace payments {
bool blank(const std::string &value) {
    return value.find_first_not_of(" \t\r\n") == std::string::npos;
}

struct Request {
    std::string order;
    long long minorUnits;
    std::string method;
    std::string gateway;

    void validate() const {
        const std::set<std::string> methods{"CARD", "UPI", "NET_BANKING", "WALLET"};
        if (blank(order) || minorUnits <= 0 || !methods.count(method) || blank(gateway)) {
            throw std::invalid_argument("Invalid payment request");
        }
    }

    bool operator==(const Request &other) const {
        return std::tie(order, minorUnits, method, gateway) ==
               std::tie(other.order, other.minorUnits, other.method, other.gateway);
    }
};

struct Payment {
    std::string id;
    Request request;
    std::string status = "PROCESSING";
};

class Gateway {
  public:
    virtual ~Gateway() = default;
    virtual std::string charge(const std::string &id, const Request &request) = 0;
    virtual bool refund(const std::string &id) = 0;
};

class FakeGateway : public Gateway {
    std::set<std::string> charged;
    std::set<std::string> refunded;
    bool loseFirstResponse;
    bool decline;
    mutable std::mutex gate;

  public:
    explicit FakeGateway(bool loseFirstResponse = false, bool decline = false)
        : loseFirstResponse(loseFirstResponse), decline(decline) {
    }

    std::string charge(const std::string &id, const Request &) override {
        std::lock_guard<std::mutex> lock(gate);
        if (decline) {
            return "DECLINED";
        }
        charged.insert(id);
        if (loseFirstResponse) {
            loseFirstResponse = false;
            return "UNKNOWN";
        }
        return "SUCCESS";
    }

    bool refund(const std::string &id) override {
        std::lock_guard<std::mutex> lock(gate);
        if (!charged.count(id)) {
            return false;
        }
        refunded.insert(id);
        return true;
    }

    size_t charges() const {
        std::lock_guard<std::mutex> lock(gate);
        return charged.size();
    }
};

class PaymentService {
    std::map<std::string, std::shared_ptr<Gateway>> gateways;
    std::map<std::string, Payment> payments;
    std::set<std::pair<std::string, std::string>> events;
    std::mutex gate;

    Payment requirePayment(const std::string &key) const {
        auto found = payments.find(key);
        if (found == payments.end()) {
            throw std::invalid_argument("Unknown payment");
        }
        return found->second;
    }

  public:
    explicit PaymentService(std::map<std::string, std::shared_ptr<Gateway>> gateways)
        : gateways(std::move(gateways)) {
    }

    Payment pay(const std::string &key, const Request &request) {
        std::lock_guard<std::mutex> lock(gate);
        request.validate();
        if (blank(key) || !gateways.count(request.gateway) || !gateways.at(request.gateway)) {
            throw std::invalid_argument("Invalid key or gateway");
        }
        auto existing = payments.find(key);
        if (existing != payments.end()) {
            if (!(existing->second.request == request)) {
                throw std::invalid_argument("Idempotency conflict");
            }
            return existing->second;
        }
        Payment payment{key, request, "PROCESSING"};
        payments[key] = payment;
        for (int attempt = 0; attempt < 3; attempt++) {
            auto outcome = gateways.at(request.gateway)->charge(key, request);
            if (outcome != "UNKNOWN") {
                payment.status = outcome == "SUCCESS" ? "SUCCESS" : "FAILED";
                payments[key] = payment;
                break;
            }
        }
        return payment;
    }

    Payment refund(const std::string &key) {
        std::lock_guard<std::mutex> lock(gate);
        auto payment = requirePayment(key);
        if (payment.status == "REFUNDED") {
            return payment;
        }
        if (payment.status != "SUCCESS") {
            throw std::invalid_argument("Only successful payments can be refunded");
        }
        if (!gateways.at(payment.request.gateway)->refund(key)) {
            throw std::runtime_error("Refund pending; retry with the same payment ID");
        }
        payment.status = "REFUNDED";
        payments[key] = payment;
        return payment;
    }

    // Provider authentication must happen before entering this trusted boundary.
    Payment webhook(const std::string &event, const std::string &gateway, const std::string &key,
                    const std::string &status) {
        std::lock_guard<std::mutex> lock(gate);
        auto payment = requirePayment(key);
        if (blank(event) || payment.request.gateway != gateway ||
            (status != "SUCCESS" && status != "FAILED")) {
            throw std::invalid_argument("Invalid webhook");
        }
        if (!events.insert({gateway, event}).second) {
            return payment;
        }
        if (payment.status == "PROCESSING") {
            payment.status = status;
            payments[key] = payment;
        }
        return payment;
    }
};
} // namespace payments

#ifndef LLD_TEST
int main() {
    using namespace payments;
    auto gateway = std::make_shared<FakeGateway>(true);
    PaymentService service({{"stripe", gateway}});
    Request request{"order-1", 1699900, "CARD", "stripe"};
    std::cout << "Payment: " << service.pay("key-1", request).status << '\n';
    service.pay("key-1", request);
    std::cout << "Gateway charges: " << gateway->charges() << '\n';
    std::cout << "Refund: " << service.refund("key-1").status << '\n';
    std::cout << "Late webhook: " << service.webhook("event-1", "stripe", "key-1", "SUCCESS").status
              << '\n';
}
#endif
