#pragma once
#include <atomic>
#include <condition_variable>
#include <functional>
#include <future>
#include <iostream>
#include <memory>
#include <mutex>
#include <queue>
#include <stdexcept>
#include <thread>
#include <utility>
#include <vector>

namespace concurrency_lessons {
class CustomThreadPool {
    std::queue<std::function<void()>> tasks;
    std::mutex mutex;
    std::condition_variable available;
    std::vector<std::thread> workers;
    bool closed = false;

    void work() {
        while (true) {
            std::function<void()> task;
            {
                std::unique_lock<std::mutex> lock(mutex);
                available.wait(lock, [this] {
                    return closed || !tasks.empty();
                });
                if (tasks.empty()) {
                    return;
                }

                task = std::move(tasks.front());
                tasks.pop();
            }

            try {
                task();
            } catch (...) {
                ++failures;
            }
        }
    }

  public:
    std::atomic<int> failures{0};
    explicit CustomThreadPool(int size) {
        if (size <= 0) {
            throw std::invalid_argument("Pool size must be positive");
        }

        try {
            for (int index = 0; index < size; ++index) {
                workers.emplace_back([this] {
                    work();
                });
            }
        } catch (...) {
            close();
            throw;
        }
    }

    void submit(std::function<void()> task) {
        std::lock_guard<std::mutex> lock(mutex);
        if (closed) {
            throw std::logic_error("Pool is closed");
        }

        if (!task) {
            throw std::invalid_argument("Task is empty");
        }

        tasks.push(std::move(task));
        available.notify_one();
    }

    // Lifecycle methods belong to one owner, outside the workers.
    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            closed = true;
        }

        available.notify_all();
        for (auto &worker : workers) {
            if (worker.joinable()) {
                worker.join();
            }
        }
    }

    ~CustomThreadPool() {
        close();
    }
};

inline void fundamentals() {
    std::atomic<int> count{0};
    std::vector<std::thread> threads;
    for (int worker = 0; worker < 4; ++worker) {
        threads.emplace_back([&] {
            for (int iteration = 0; iteration < 1000; ++iteration) {
                ++count;
            }
        });
    }

    for (auto &thread : threads) {
        thread.join();
    }

    if (count != 4000) {
        throw std::runtime_error("Lost updates");
    }

    std::cout << "Counter: " << count << '\n';
}

class BoundedBuffer {
    std::queue<int> items;
    const std::size_t capacity;
    std::mutex mutex;
    std::condition_variable changed;

  public:
    explicit BoundedBuffer(int size) : capacity(size) {
        if (size <= 0) {
            throw std::invalid_argument("Capacity must be positive");
        }
    }

    void produce(int value) {
        std::unique_lock<std::mutex> lock(mutex);
        changed.wait(lock, [this] {
            return items.size() < capacity;
        });
        items.push(value);
        changed.notify_all();
    }

    int consume() {
        std::unique_lock<std::mutex> lock(mutex);
        changed.wait(lock, [this] {
            return !items.empty();
        });
        int value = items.front();
        items.pop();
        changed.notify_all();
        return value;
    }
};
inline void producer_consumer() {
    BoundedBuffer buffer(1);
    int total = 0;
    std::thread consumer([&] {
        for (int index = 0; index < 10; ++index) {
            total += buffer.consume();
        }
    });
    for (int value = 1; value <= 10; ++value) {
        buffer.produce(value);
    }

    consumer.join();
    if (total != 55) {
        throw std::runtime_error("Missing items");
    }

    std::cout << "Consumed sum: " << total << '\n';
    try {
        BoundedBuffer invalid(0);
        throw std::runtime_error("Accepted invalid capacity");
    } catch (const std::invalid_argument &) {
        std::cout << "Invalid capacity rejected\n";
    }
}

inline void custom_pool() {
    CustomThreadPool pool(3);
    std::atomic<int> total{0};
    pool.submit([] {
        throw std::runtime_error("Expected teaching failure");
    });
    for (int value = 1; value <= 10; ++value) {
        pool.submit([&, value] {
            total += value;
        });
    }

    pool.close();
    pool.close();
    if (total != 55 || pool.failures != 1) {
        throw std::runtime_error("Tasks lost or failures hidden");
    }

    std::cout << "Completed sum: " << total << "\nTask failures: " << pool.failures << '\n';
    try {
        pool.submit([] {
        });
        throw std::runtime_error("Accepted work after shutdown");
    } catch (const std::logic_error &) {
        std::cout << "Submission after shutdown rejected\n";
    }

    try {
        CustomThreadPool invalid(0);
        throw std::runtime_error("Accepted invalid pool size");
    } catch (const std::invalid_argument &) {
        std::cout << "Invalid pool size rejected\n";
    }
}

inline void executor_pool() {
    // C++17 has no standard fixed thread pool. Package futures on our three-worker pool.
    CustomThreadPool pool(3);
    std::vector<std::future<int>> results;
    for (int value = 1; value <= 10; ++value) {
        auto task = std::make_shared<std::packaged_task<int()>>([value] {
            return value;
        });
        results.push_back(task->get_future());
        pool.submit([task] {
            (*task)();
        });
    }

    int total = 0;
    for (auto &result : results) {
        total += result.get();
    }

    pool.close();
    if (total != 55) {
        throw std::runtime_error("Missing results");
    }

    std::cout << "Executor sum: " << total << '\n';
}
} // namespace concurrency_lessons
