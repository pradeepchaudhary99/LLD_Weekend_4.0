#define LLD_TEST
#include "../../C++/InterviewQuestions/PaymentServiceLLD.cpp"
#include <cassert>
#include <functional>
#include <thread>
#include <vector>
using namespace payments;

void rejects(const std::function<void()> &action) {
    bool rejected = false;
    try {
        action();
    } catch (const std::invalid_argument &) {
        rejected = true;
    }
    assert(rejected);
}

class UnknownGateway : public Gateway {
  public:
    int attempts = 0;
    bool allowRefund = false;

    std::string charge(const std::string &, const Request &) override {
        attempts++;
        return "UNKNOWN";
    }

    bool refund(const std::string &) override {
        return allowRefund;
    }
};

int main() {
    Request request{"order", 100, "CARD", "stripe"};
    auto gateway = std::make_shared<FakeGateway>(true);
    PaymentService service({{"stripe", gateway}});
    std::vector<std::thread> workers;
    for (int index = 0; index < 100; index++) {
        workers.emplace_back([&] {
            assert(service.pay("same-key", request).status == "SUCCESS");
        });
    }
    for (auto &worker : workers) {
        worker.join();
    }
    assert(gateway->charges() == 1);
    rejects([&] {
        service.pay("same-key", Request{"order", 101, "CARD", "stripe"});
    });
    rejects([&] {
        service.pay(" ", request);
    });
    rejects([&] {
        service.pay("invalid", Request{"order", 0, "CARD", "stripe"});
    });
    rejects([&] {
        service.pay("unknown", Request{"order", 100, "UPI", "missing"});
    });
    auto snapshot = service.pay("same-key", request);
    assert(service.refund("same-key").status == "REFUNDED");
    assert(service.refund("same-key").status == "REFUNDED");
    assert(snapshot.status == "SUCCESS");
    assert(service.webhook("late", "stripe", "same-key", "FAILED").status == "REFUNDED");
    rejects([&] {
        service.webhook("wrong", "paypal", "same-key", "SUCCESS");
    });
    rejects([&] {
        service.refund("missing");
    });
    auto unknown = std::make_shared<UnknownGateway>();
    PaymentService pending({{"stripe", unknown}});
    assert(pending.pay("pending", request).status == "PROCESSING");
    assert(unknown->attempts == 3);
    assert(pending.pay("pending", request).status == "PROCESSING");
    assert(unknown->attempts == 3);
    rejects([&] {
        pending.refund("pending");
    });
    assert(pending.webhook("event", "stripe", "pending", "SUCCESS").status == "SUCCESS");
    assert(pending.webhook("event", "stripe", "pending", "FAILED").status == "SUCCESS");
    bool retryable = false;
    try {
        pending.refund("pending");
    } catch (const std::runtime_error &) {
        retryable = true;
    }
    assert(retryable);
    assert(pending.pay("pending", request).status == "SUCCESS");
    unknown->allowRefund = true;
    assert(pending.refund("pending").status == "REFUNDED");
    PaymentService declined({{"stripe", std::make_shared<FakeGateway>(false, true)}});
    assert(declined.pay("declined", request).status == "FAILED");
    rejects([&] {
        declined.refund("declined");
    });
    for (const auto &method : {"CARD", "UPI", "NET_BANKING", "WALLET"}) {
        assert(service.pay(method, Request{"order", 100, method, "stripe"}).status == "SUCCESS");
    }
    std::cout << "PASS C++ payment edge cases and concurrent idempotency\n";
}
