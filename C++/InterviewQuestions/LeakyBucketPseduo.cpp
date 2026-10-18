/*
 * INTERVIEW DESIGN NOTES - Queued Leaky Bucket
 *
 * Functional requirements:
 * - Accept requests into a bounded FIFO queue; reject overflow and submissions after close.
 * - One worker attempts jobs with a minimum interval between scheduled attempt starts.
 * - Wake an idle worker on submission; count ordinary handler failures and continue.
 * - Close rejects new work, drains accepted work, and waits for the worker to finish.
 *
 * Non-functional requirements:
 * - Protect admission and queue state; execute callbacks outside queue locks.
 * - Use monotonic time, bounded waiting capacity and no idle busy-spinning.
 * - Do not accumulate catch-up credits after slow callbacks or scheduler delays.
 *
 * Core entities and responsibilities:
 * - LeakyBucket: capacity, waiting queue, worker lifecycle and cumulative failure count.
 * - Request: integer job ID; handler processes it synchronously (awaitable in JS/TS).
 * - Worker: thread/goroutine in native editions; async task on the Node event loop.
 * - Monotonic clock: determines remaining delay since the previous attempt started.
 *
 * Operations and flow:
 * - submit -> atomic closed/capacity check -> enqueue -> signal -> return acceptance.
 * - start -> wait for work -> wait remaining interval -> dequeue -> attempt handler.
 * - close before start starts a draining worker; repeated close waits safely again.
 * - Ordinary handler failure increments failures; the job is not retried or requeued.
 *
 * Design choices and invariants:
 * - Capacity counts waiting jobs, excluding the one executing; total outstanding <= capacity+1.
 * - First attempt can start immediately. Slow work lowers throughput rather than causing bursts.
 * - Callbacks are serial; start twice is invalid; submissions may be queued before start.
 * - Close is owner-only: handlers must not call/await close on their own bucket.
 * - Handlers must finish; no forced cancellation or shutdown timeout is promised.
 *
 * Assumptions and scope:
 * - Interval is an integer 1..60000 milliseconds. This is a queue/shaper, not the
 *   token-bucket or sliding-window admission algorithms in RateLimitingDemo.
 * - Native threads and Node timers are not real-time; OS scheduling can delay attempts.
 * - No durable queue, retry policy, distributed ordering, priorities or delivery guarantee
 *   across process crashes. Fatal runtime/process failures are outside the contract.
 * - C# uses Dispose for close; C++ also drains in its destructor; Go returns handler errors
 *   and counts recovered panics; JS/TS await rejected promises and count them as failures.
 * - Native FIFO operations are O(1); JS/TS Array.shift is O(queue length) in this small demo.
 *
 * Edge cases and discussion:
 * - Full queue before start, close before start, idle wakeup, failure followed by later work,
 *   repeated close, invalid configuration, cadence and concurrent producers during shutdown.
 * - Discuss deadlines, backpressure, cancellation, durable delivery and overload policies.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "C++" InterviewQuestions/LeakyBucketPseduo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

class LeakyBucket {
    size_t capacity;
    std::chrono::milliseconds interval;
    std::function<void(int)> handler;
    std::mutex mutex;
    std::mutex joinMutex;
    std::condition_variable changed;
    std::deque<int> queue;
    std::thread worker;
    std::thread::id workerId;
    bool started = false;
    bool closed = false;
    int failureCount = 0;

    void startWorker() {
        worker = std::thread([this] {
            run();
        });
        workerId = worker.get_id();
        started = true;
    }

    void run() {
        auto lastStart = std::chrono::steady_clock::now();
        bool attempted = false;
        while (true) {
            int request;
            {
                std::unique_lock<std::mutex> lock(mutex);
                changed.wait(lock, [this] {
                    return closed || !queue.empty();
                });
                if (queue.empty()) {
                    return;
                }
                if (attempted) {
                    while (std::chrono::steady_clock::now() < lastStart + interval) {
                        changed.wait_until(lock, lastStart + interval);
                    }
                }
                request = queue.front();
                queue.pop_front();
            }
            lastStart = std::chrono::steady_clock::now();
            attempted = true;
            try {
                handler(request);
            } catch (...) {
                std::lock_guard<std::mutex> lock(mutex);
                failureCount++;
            }
        }
    }

  public:
    LeakyBucket(int capacity, int intervalMs, std::function<void(int)> handler)
        : interval(intervalMs), handler(std::move(handler)) {
        if (capacity <= 0 || intervalMs <= 0 || intervalMs > 60000 || !this->handler) {
            throw std::invalid_argument("Invalid configuration");
        }
        this->capacity = static_cast<size_t>(capacity);
    }
    ~LeakyBucket() {
        close();
    }
    bool submit(int request) {
        std::lock_guard<std::mutex> lock(mutex);
        if (closed || queue.size() >= capacity) {
            return false;
        }
        queue.push_back(request);
        changed.notify_all();
        return true;
    }
    void start() {
        std::lock_guard<std::mutex> lock(mutex);
        if (started || closed) {
            throw std::logic_error("Already started/closed");
        }
        startWorker();
    }
    int failures() {
        std::lock_guard<std::mutex> lock(mutex);
        return failureCount;
    }
    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (started && std::this_thread::get_id() == workerId) {
                throw std::logic_error("Handler cannot close its worker");
            }
            closed = true;
            if (!started) {
                startWorker();
            }
            changed.notify_all();
        }
        std::lock_guard<std::mutex> joinLock(joinMutex);
        if (worker.joinable()) {
            worker.join();
        }
    }
};

void check(bool condition) {
    if (!condition) {
        throw std::runtime_error("Queue contract failed");
    }
}

int main() {
    std::vector<int> attempted;
    LeakyBucket bucket(2, 1, [&](int request) {
        attempted.push_back(request);
        if (request == 1) {
            throw std::runtime_error("Simulated handler failure");
        }
    });
    check(bucket.submit(1));
    check(bucket.submit(2));
    check(!bucket.submit(3));
    bucket.start();
    bucket.close();
    bucket.close();
    check(!bucket.submit(4));
    check(attempted == std::vector<int>({1, 2}) && bucket.failures() == 1);
    LeakyBucket beforeStart(1, 1, [](int) {
    });
    check(beforeStart.submit(7));
    beforeStart.close();
    bool rejected = false;
    try {
        LeakyBucket invalid(0, 1, [](int) {
        });
    } catch (const std::invalid_argument &) {
        rejected = true;
    }
    check(rejected);
    std::cout << "Overflow rejected: true\nFIFO attempts: 1,2\nHandler failures: 1\nShutdown "
                 "drained: true\n";
    return 0;
}
