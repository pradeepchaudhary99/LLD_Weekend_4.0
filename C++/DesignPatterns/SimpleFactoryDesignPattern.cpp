#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

struct Notification {
    virtual void send(const std::string& message) = 0;
    virtual ~Notification() = default;
};

struct SMSNotification : Notification {
    void send(const std::string& message) override {
        throw std::runtime_error("Unimplemented method 'send'");
    }
};

struct SlackNotification : Notification {
    void send(const std::string& message) override {
        throw std::runtime_error("Unimplemented method 'send'");
    }
};

struct WhatsappNotification : Notification {
    void send(const std::string& message) override {
        throw std::runtime_error("Unimplemented method 'send'");
    }
};

class NotificationFactory {
public:
    static std::unique_ptr<Notification> getNotification(const std::string& type) {
        if (type == "SMS") {
            return std::make_unique<SMSNotification>();
        } else if (type == "SLACK") {
            return std::make_unique<SlackNotification>();
        } else if (type == "Whatsapp") {
            return std::make_unique<WhatsappNotification>();
        }
        throw std::invalid_argument("Unknown notification type: " + type);
    }
};

class Application {
public:
    void sendNotification() {
        auto notification = NotificationFactory::getNotification("SMS");
        notification->send("message");
    }
};

// Simple Factory Design Pattern

int main() {
    Application application;
    application.sendNotification();
    return 0;
}
