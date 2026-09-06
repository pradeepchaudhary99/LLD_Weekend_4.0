#include <memory>
#include <mutex>
#include <string>

class ConfigurationManager {
public:
    ConfigurationManager(const ConfigurationManager&) = delete;
    ConfigurationManager& operator=(const ConfigurationManager&) = delete;

    static ConfigurationManager& getInstance() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (instance_ == nullptr) {
            instance_ = std::unique_ptr<ConfigurationManager>(new ConfigurationManager());
        }
        return *instance_;
    }

private:
    ConfigurationManager() = default;

    std::string path;
    std::string currentDirectory;
    std::string metadata;

    static std::unique_ptr<ConfigurationManager> instance_;
    static std::mutex mutex_;
};

std::unique_ptr<ConfigurationManager> ConfigurationManager::instance_ = nullptr;
std::mutex ConfigurationManager::mutex_;

int main() {
    ConfigurationManager::getInstance();
    return 0;
}
