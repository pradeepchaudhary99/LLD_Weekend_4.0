#include <iostream>
#include <map>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <stdexcept>
#include <thread>
#include <vector>

class Cache {
    std::map<int, int> values;
    mutable std::shared_mutex mutex;

  public:
    std::optional<int> read(int key) const {
        std::shared_lock<std::shared_mutex> guard(mutex);
        auto found = values.find(key);
        if (found == values.end()) {
            return std::nullopt;
        }
        return found->second;
    }
    void write(int key, int value) {
        std::unique_lock<std::shared_mutex> guard(mutex);
        values[key] = value;
    }
};

int main() {
    Cache cache;
    std::vector<std::thread> writers;
    for (int key = 0; key < 4; key++) {
        writers.emplace_back([&, key] {
            cache.write(key, key * 10);
        });
    }
    for (auto &writer : writers) {
        writer.join();
    }
    int total = 0;
    for (int key = 0; key < 4; key++) {
        total += cache.read(key).value();
    }
    if (total != 60 || cache.read(99).has_value()) {
        throw std::runtime_error("Cache contents incorrect");
    }
    std::cout << "Cache sum: " << total << "\nMissing: true\n";
    cache.write(0, -1);
    std::cout << "Stored negative: " << cache.read(0).value() << '\n';
    cache.write(0, 7);
    std::cout << "Updated: " << cache.read(0).value() << '\n';
}
