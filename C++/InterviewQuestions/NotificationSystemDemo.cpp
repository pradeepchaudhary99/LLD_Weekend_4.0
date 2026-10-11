/*
 * INTERVIEW DESIGN NOTES - Notification System
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Submit notifications asynchronously using EMAIL, SMS and PUSH adapters.
 * - Render a named template with the literal {name} placeholder.
 * - Fan out to configured recipient channels; empty preferences mean opt-out.
 * - Schedule pending work by HIGH(0), NORMAL(1), LOW(2), then FIFO sequence.
 * - Track aggregate QUEUED/SENT/FAILED/SKIPPED and per-channel attempts.
 * - Deduplicate identical request IDs; retry each failed delivery up to three times.
 *
 * Non-functional requirements:
 * - Separate submission from provider I/O and protect shared service state.
 * - Keep successful channels from being retried when another channel fails.
 * - Copy configuration and expose snapshots instead of mutable repository state.
 * - Provide explicit start, idle wait and shutdown that drains accepted work.
 *
 * Core entities and responsibilities:
 * - Request: ID, user, template, name and priority.
 * - Delivery: one channel job with rendered message, status and attempt count.
 * - NotificationService: owns templates/preferences, request index and queue.
 * - NotificationChannel/FakeChannel: replaceable provider boundary and simulation.
 * - Worker: consumes pending jobs and updates per-channel results.
 *
 * Key operations and flow:
 * - submit -> validate request/preferences -> check ID -> render -> enqueue fan-out.
 * - worker -> choose priority/FIFO job -> attempt delivery -> record result.
 * - Aggregate status stays QUEUED while jobs remain; any final failure means FAILED.
 *
 * Design choices and invariants:
 * - Registry-based adapters allow new channels without changing orchestration.
 * - Idempotency is submission deduplication, not exactly-once external delivery.
 * - Priority never preempts a call already running; retries use the same delivery ID.
 * - No selected channels produces SKIPPED without contacting a provider.
 *
 * Assumptions and out of scope:
 * - Templates/preferences are fixed for the service lifetime; providers are fake.
 * - One service worker, no durable queue, backpressure, restart recovery or backoff.
 * - Adapters must finish calls; owner code manages lifecycle, not provider callbacks.
 * - Continuous high-priority arrivals can starve lower-priority work.
 *
 * Interview discussion and checks:
 * - Test FIFO ties, opt-out, unknown templates, retry exhaustion and payload conflicts.
 * - Test concurrent duplicate submissions, literal replacement text and close-before-start.
 * - Discuss outbox delivery, provider idempotency, bounded queues and retry scheduling.
 *
 * Concurrency in this edition:
 * - One service std::thread; mutex and condition_variable protect state.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py 'C++' InterviewQuestions/NotificationSystemDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

#include <algorithm>
#include <condition_variable>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <queue>
#include <set>
#include <stdexcept>
#include <string>
#include <thread>
#include <tuple>
#include <vector>

namespace notifications {
struct Request {
    std::string id;
    std::string user;
    std::string templateId;
    std::string name;
    int priority;

    bool operator==(const Request &other) const {
        return std::tie(id, user, templateId, name, priority) ==
               std::tie(other.id, other.user, other.templateId, other.name, other.priority);
    }
};

class NotificationChannel {
  public:
    virtual ~NotificationChannel() = default;
    virtual bool send(const std::string &id, const std::string &message) = 0;
};

class FakeChannel : public NotificationChannel {
    int failures;
    std::mutex gate;

  public:
    std::vector<std::string> delivered;

    explicit FakeChannel(int failures = 0) : failures(failures) {
    }

    bool send(const std::string &id, const std::string &message) override {
        std::lock_guard<std::mutex> lock(gate);
        if (failures > 0) {
            failures--;
            return false;
        }
        delivered.push_back(id + ":" + message);
        return true;
    }
};

struct Delivery {
    Request request;
    std::string channel;
    std::string message;
    size_t sequence;
    std::string status = "QUEUED";
    int attempts = 0;
};

struct Priority {
    bool operator()(const std::shared_ptr<Delivery> &left,
                    const std::shared_ptr<Delivery> &right) const {
        return std::tie(left->request.priority, left->sequence) >
               std::tie(right->request.priority, right->sequence);
    }
};

class NotificationService {
    std::map<std::string, std::shared_ptr<NotificationChannel>> channels;
    std::map<std::string, std::string> templates;
    std::map<std::string, std::vector<std::string>> preferences;
    std::map<std::string, Request> requests;
    std::map<std::string, std::vector<std::shared_ptr<Delivery>>> deliveries;
    std::priority_queue<std::shared_ptr<Delivery>, std::vector<std::shared_ptr<Delivery>>, Priority>
        queue;
    std::vector<std::string> order;
    std::mutex gate;
    std::mutex closeGate;
    std::condition_variable changed;
    std::thread worker;
    std::thread::id workerId;
    bool started = false;
    bool closed = false;
    size_t sequence = 0;
    int active = 0;

    void startWorker() {
        if (!started) {
            started = true;
            worker = std::thread(&NotificationService::run, this);
            workerId = worker.get_id();
        }
    }

    void run() {
        while (true) {
            std::shared_ptr<Delivery> delivery;
            {
                std::unique_lock<std::mutex> lock(gate);
                changed.wait(lock, [&] {
                    return !queue.empty() || closed;
                });
                if (queue.empty()) {
                    return;
                }
                delivery = queue.top();
                queue.pop();
                delivery->status = "PROCESSING";
                active++;
            }
            bool sent = false;
            int attempts = 0;
            while (!sent && attempts < 3) {
                attempts++;
                try {
                    sent = channels.at(delivery->channel)
                               ->send(delivery->request.id + "/" + delivery->channel,
                                      delivery->message);
                } catch (const std::exception &) {
                    sent = false;
                }
            }
            {
                std::lock_guard<std::mutex> lock(gate);
                delivery->attempts = attempts;
                delivery->status = sent ? "SENT" : "FAILED";
                if (sent) {
                    order.push_back(delivery->request.id + "/" + delivery->channel);
                }
                active--;
                changed.notify_all();
            }
        }
    }

  public:
    NotificationService(std::map<std::string, std::shared_ptr<NotificationChannel>> channels,
                        std::map<std::string, std::string> templates,
                        std::map<std::string, std::vector<std::string>> preferences)
        : channels(std::move(channels)), templates(std::move(templates)),
          preferences(std::move(preferences)) {
    }

    ~NotificationService() {
        close();
    }

    void submit(const Request &request) {
        std::lock_guard<std::mutex> lock(gate);
        if (closed || request.id.find_first_not_of(" \t\r\n") == std::string::npos ||
            request.priority < 0 || request.priority > 2 || !templates.count(request.templateId) ||
            !preferences.count(request.user)) {
            throw std::invalid_argument("Invalid or closed request");
        }
        auto existing = requests.find(request.id);
        if (existing != requests.end()) {
            if (!(existing->second == request)) {
                throw std::invalid_argument("Idempotency conflict");
            }
            return;
        }
        const auto &selected = preferences.at(request.user);
        if (std::set<std::string>(selected.begin(), selected.end()).size() != selected.size() ||
            std::any_of(selected.begin(), selected.end(), [&](const auto &channel) {
                return !channels.count(channel) || !channels.at(channel);
            })) {
            throw std::invalid_argument("Invalid channel preference");
        }
        std::string message = templates.at(request.templateId);
        size_t offset = 0;
        while ((offset = message.find("{name}", offset)) != std::string::npos) {
            message.replace(offset, 6, request.name);
            offset += request.name.size();
        }
        std::vector<std::shared_ptr<Delivery>> fanout;
        for (const auto &channel : selected) {
            fanout.push_back(
                std::make_shared<Delivery>(Delivery{request, channel, message, sequence++}));
        }
        requests.emplace(request.id, request);
        deliveries.emplace(request.id, fanout);
        for (const auto &delivery : fanout) {
            queue.push(delivery);
        }
        changed.notify_all();
    }

    std::string status(const std::string &id) {
        std::lock_guard<std::mutex> lock(gate);
        auto found = deliveries.find(id);
        if (found == deliveries.end()) {
            throw std::invalid_argument("Unknown notification");
        }
        const auto &fanout = found->second;
        if (fanout.empty()) {
            return "SKIPPED";
        }
        bool failed = false;
        for (const auto &delivery : fanout) {
            if (delivery->status == "QUEUED" || delivery->status == "PROCESSING") {
                return "QUEUED";
            }
            failed = failed || delivery->status == "FAILED";
        }
        return failed ? "FAILED" : "SENT";
    }

    int attempts(const std::string &id, const std::string &channel) {
        std::lock_guard<std::mutex> lock(gate);
        if (!deliveries.count(id)) {
            throw std::invalid_argument("Unknown notification");
        }
        int total = 0;
        for (const auto &delivery : deliveries.at(id)) {
            if (delivery->channel == channel) {
                total += delivery->attempts;
            }
        }
        return total;
    }

    std::vector<std::string> sentOrder() {
        std::lock_guard<std::mutex> lock(gate);
        return order;
    }

    void start() {
        std::lock_guard<std::mutex> lock(gate);
        if (closed) {
            throw std::logic_error("Service closed");
        }
        startWorker();
    }

    void awaitIdle() {
        std::unique_lock<std::mutex> lock(gate);
        if (closed || (started && std::this_thread::get_id() == workerId)) {
            throw std::logic_error("Closed service or worker self-wait");
        }
        startWorker();
        changed.wait(lock, [&] {
            return queue.empty() && active == 0;
        });
    }

    void close() {
        // The owner controls lifetime; providers must not destroy or close their service.
        std::lock_guard<std::mutex> closing(closeGate);
        {
            std::lock_guard<std::mutex> lock(gate);
            closed = true;
            startWorker();
            changed.notify_all();
        }
        if (worker.joinable()) {
            worker.join();
        }
    }
};
} // namespace notifications

#ifndef LLD_TEST
int main() {
    using namespace notifications;
    NotificationService service({{"EMAIL", std::make_shared<FakeChannel>()},
                                 {"SMS", std::make_shared<FakeChannel>(1)},
                                 {"PUSH", std::make_shared<FakeChannel>(9)}},
                                {{"welcome", "Hello {name}"}},
                                {{"alice", {"EMAIL", "SMS"}}, {"bob", {"PUSH"}}, {"quiet", {}}});
    Request low{"low", "alice", "welcome", "Alice", 2};
    service.submit(low);
    service.submit({"high", "alice", "welcome", "Alice", 0});
    service.submit(low);
    service.submit({"fail", "bob", "welcome", "Bob", 1});
    service.submit({"off", "quiet", "welcome", "Quiet", 1});
    service.awaitIdle();
    auto order = service.sentOrder();
    std::cout << "Sent: ";
    for (size_t index = 0; index < order.size(); index++) {
        std::cout << (index ? "," : "") << order[index];
    }
    std::cout << "\nHigh: " << service.status("high") << '\n';
    std::cout << "SMS attempts: " << service.attempts("high", "SMS") << '\n';
    std::cout << "Push: " << service.status("fail") << '\n';
    std::cout << "Opt-out: " << service.status("off") << '\n';
}
#endif
