#define LLD_TEST
#include "../../C++/InterviewQuestions/SplitWiseDemo.cpp"
#include "../../C++/InterviewQuestions/NotificationSystemDemo.cpp"
#include "../../C++/InterviewQuestions/ElevatorSystemDemo.cpp"
#include <cassert>
#include <future>

void check(bool condition) {
    assert(condition);
}

void rejects(const std::function<void()> &action) {
    try {
        action();
    } catch (const std::logic_error &) {
        return;
    }
    throw std::runtime_error("Invalid operation accepted");
}

void ledger() {
    splitwise::SplitWise service;
    std::vector<std::string> members = std::vector<std::string>{"A", "B", "C"};
    for (const auto &user : members) {
        service.addUser(user);
    }
    service.addUser("D");
    service.createGroup("g", members);
    service.createGroup("other", members);
    check(service.addExpense("g", "equal", "A", 2, members, "EQUAL", std::vector<long long>{}) ==
          (std::vector<long long>{1L, 1L, 0L}));
    check(service.addExpense("g", "percent", "B", 7, members, "PERCENTAGE",
                             std::vector<long long>{5000L, 2500L, 2500L}) ==
          (std::vector<long long>{3L, 2L, 2L}));
    check(service.addExpense("g", "zero", "C", 1, members, "PERCENTAGE",
                             std::vector<long long>{0L, 0L, 10000L}) ==
          (std::vector<long long>{0L, 0L, 1L}));
    check(service.balance("g", "A", "B") == 2);
    check(service.balance("g", "B", "A") == -2);
    check(service.balance("other", "A", "B") == 0);
    rejects([&] {
        service.addExpense("g", "bad", "A", 10, members, "EXACT",
                           std::vector<long long>{2L, 3L, 4L});
    });
    rejects([&] {
        service.addExpense("g", "bad", "A", 10, members, "PERCENTAGE",
                           std::vector<long long>{5000L, 2000L, 2000L});
    });
    rejects([&] {
        service.addExpense("g", "bad", "A", 10, std::vector<std::string>{"A", "A"}, "EQUAL",
                           std::vector<long long>{});
    });
    rejects([&] {
        service.addExpense("g", "bad", "D", 10, members, "EQUAL", std::vector<long long>{});
    });
    rejects([&] {
        service.addExpense("g", "equal", "A", 10, members, "EQUAL", std::vector<long long>{});
    });
    rejects([&] {
        service.addExpense("g", "bad", "A", 0, members, "EQUAL", std::vector<long long>{});
    });
    rejects([&] {
        service.addExpense("g", "bad", "A", 1000000001L, members, "EQUAL",
                           std::vector<long long>{});
    });
    rejects([&] {
        service.settle("g", "bad", "A", "B", 3);
    });
    rejects([&] {
        service.removeMember("g", "A");
    });
    check(service.history("g").size() == 3);
    check(service.balance("g", "A", "B") == 2);
    service.settle("g", "settle", "A", "B", 2);
    check(service.balance("g", "A", "B") == 0);
    service.removeMember("g", "A");
    service.addMember("g", "A");
    service.addMember("g", "D");
    service.removeMember("g", "D");
    rejects([&] {
        service.settle("g", "settle", "C", "B", 1);
    });
    check(service.history("g").size() == 4);
    std::vector<std::thread> workers;
    for (int index = 0; index < 100; index++) {
        workers.emplace_back([&, index] {
            service.addExpense("other", "parallel-" + std::to_string(index), "A", 10, {"B"},
                               "EXACT", {10});
        });
    }
    for (auto &worker : workers) {
        worker.join();
    }
    check(service.balance("other", "B", "A") == 1000);
    check(service.history("other").size() == 100);
    // Hitting the documented balance bound must reject the entire transaction.
    for (int index = 0; index < 1000; index++) {
        service.addExpense("g", "limit-" + std::to_string(index), "A", 1000000000L,
                           std::vector<std::string>{"B"}, "EXACT",
                           std::vector<long long>{1000000000L});
    }
    rejects([&] {
        service.addExpense("g", "overflow", "A", 2, std::vector<std::string>{"C", "B"}, "EXACT",
                           std::vector<long long>{1L, 1L});
    });
    check(service.balance("g", "C", "A") == 0);
    check(service.history("g").size() == 1004);
}

void notificationsCheck() {
    using namespace notifications;
    auto email = std::make_shared<FakeChannel>();
    NotificationService service({{"EMAIL", email},
                                 {"SMS", std::make_shared<FakeChannel>(2)},
                                 {"PUSH", std::make_shared<FakeChannel>(10)}},
                                {{"t", "Hello {name}"}},
                                {{"u", {"EMAIL", "SMS"}}, {"bad", {"PUSH"}}, {"off", {}}});
    Request low{"low", "u", "t", "Ada", 2};
    service.submit(low);
    service.submit({"high", "u", "t", "Ada", 0});
    service.submit(low);
    service.submit({"fail", "bad", "t", "Ada", 1});
    service.submit({"skip", "off", "t", "Ada", 1});
    check(service.status("low") == "QUEUED");
    check(service.status("skip") == "SKIPPED");
    rejects([&] {
        service.submit({"low", "u", "t", "changed", 2});
    });
    rejects([&] {
        service.submit({"missing", "u", "missing", "Ada", 1});
    });
    rejects([&] {
        service.submit({"invalid", "u", "t", "Ada", 3});
    });
    rejects([&] {
        service.status("missing");
    });
    service.awaitIdle();
    check(service.sentOrder() ==
          std::vector<std::string>{"high/EMAIL", "high/SMS", "low/EMAIL", "low/SMS"});
    check(service.attempts("high", "SMS") == 3);
    check(service.attempts("low", "EMAIL") == 1);
    check(service.attempts("fail", "PUSH") == 3);
    check(service.status("fail") == "FAILED");
    check(email->delivered[0] == "high/EMAIL:Hello Ada");
    std::vector<std::thread> workers;
    for (int index = 0; index < 100; index++) {
        workers.emplace_back([&] {
            service.submit({"same", "u", "t", "Ada", 1});
        });
    }
    for (auto &worker : workers) {
        worker.join();
    }
    service.awaitIdle();
    check(service.attempts("same", "EMAIL") == 1);
    check(service.sentOrder().size() == 6);
    service.close();
    service.close();
    rejects([&] {
        service.submit({"late", "u", "t", "Ada", 1});
    });
    rejects([&] {
        service.start();
    });
    NotificationService drain({{"EMAIL", email}}, {{"t", "{name}"}}, {{"u", {"EMAIL"}}});
    drain.submit({"drain", "u", "t", "Ada", 1});
    drain.close();
    check(drain.status("drain") == "SENT");
}

void elevatorsCheck() {
    std::promise<void> first, release, second;
    auto firstFuture = first.get_future();
    auto releaseFuture = release.get_future().share();
    auto secondFuture = second.get_future();
    std::vector<int> firstCarStops;
    ElevatorSystem system(2, 10, [&](int id, int floor) {
        if (id == 0) {
            firstCarStops.push_back(floor);
            if (floor == 3) {
                first.set_value();
                if (releaseFuture.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
                    throw std::runtime_error("Release timed out");
                }
            }
        } else {
            second.set_value();
        }
    });
    system.internalRequest(0, 3);
    system.internalRequest(0, 5);
    system.start();
    check(firstFuture.wait_for(std::chrono::seconds(2)) == std::future_status::ready);
    system.internalRequest(0, 4);
    system.internalRequest(0, 1);
    system.internalRequest(1, 2);
    check(secondFuture.wait_for(std::chrono::seconds(2)) == std::future_status::ready);
    release.set_value();
    system.runUntilIdle();
    check(firstCarStops == std::vector<int>{3, 4, 5, 1});
    system.close();
    system.close();
    rejects([&] {
        system.internalRequest(0, 1);
    });
}

int main() {
    ledger();
    notificationsCheck();
    elevatorsCheck();
    std::cout << "C++ notification, Splitwise and independent elevator checks passed\n";
}
