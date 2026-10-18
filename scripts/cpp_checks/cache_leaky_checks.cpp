#define main cacheDemoMain
#define check cacheCheck
#include "../../C++/InterviewQuestions/LRU_CacheDemo.cpp"
#undef main
#undef check
#define main bucketDemoMain
#define check bucketCheck
#include "../../C++/InterviewQuestions/LeakyBucketPseduo.cpp"
#undef main
#undef check
#include <future>
#include <set>

int main() {
    LRUCache cache(8);
    std::vector<std::thread> producers;
    for (int worker = 0; worker < 8; worker++) {
        producers.emplace_back([&, worker] {
            for (int index = 0; index < 500; index++) {
                cache.put((index + worker) % 16, index);
                cache.get(index % 16);
            }
        });
    }
    for (auto &producer : producers) {
        producer.join();
    }
    auto keys = cache.keys();
    cacheCheck(keys.size() == 8 && std::set<int>(keys.begin(), keys.end()).size() == 8);
    std::promise<void> arrived;
    auto ready = arrived.get_future();
    std::vector<std::chrono::steady_clock::time_point> times;
    LeakyBucket bucket(4, 20, [&](int job) {
        times.push_back(std::chrono::steady_clock::now());
        if (job == 1) {
            arrived.set_value();
            throw std::runtime_error("Expected failure");
        }
    });
    bucket.start();
    bucketCheck(bucket.submit(1));
    bucketCheck(ready.wait_for(std::chrono::seconds(5)) == std::future_status::ready);
    bucketCheck(bucket.submit(2));
    bucketCheck(bucket.submit(3));
    bucket.close();
    bucket.close();
    bucketCheck(times.size() == 3 && bucket.failures() == 1 && !bucket.submit(4));
    for (size_t index = 1; index < times.size(); index++) {
        bucketCheck(times[index] - times[index - 1] >= std::chrono::milliseconds(18));
    }
    std::cout << "C++ cache/leaky-bucket concurrency and cadence checks passed\n";
}
