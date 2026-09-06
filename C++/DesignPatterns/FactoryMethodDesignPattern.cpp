#include <memory>
#include <stdexcept>
#include <string>

// product
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

struct PushNotification : Notification {
    void send(const std::string& message) override {}
};

// product factory
struct NotificationFactory {
    virtual std::unique_ptr<Notification> getNotification() = 0;
    virtual ~NotificationFactory() = default;
};

struct SMSNotificationFactory : NotificationFactory {
    std::unique_ptr<Notification> getNotification() override {
        return std::make_unique<SMSNotification>();
    }
};

struct WhatsappNotificationFactory : NotificationFactory {
    std::unique_ptr<Notification> getNotification() override {
        return std::make_unique<WhatsappNotification>();
    }
};

struct PushNotificationFactory : NotificationFactory {
    std::unique_ptr<Notification> getNotification() override {
        return std::make_unique<PushNotification>();
    }
};

class Application {
public:
    void sendNotification(NotificationFactory& factory, const std::string& type) {
        auto notification = factory.getNotification();
        notification->send("message");
    }
};

// Factory Method Design Pattern

int main() {
    return 0;
}
