#include <iostream>
#include <memory>
#include <string>

struct Notification {
    virtual void send(const std::string& message) = 0;
    virtual ~Notification() = default;
};

struct SMSNotification : Notification {
    void send(const std::string& message) override {
        std::cout << "Sending SMS in a legacy way" << std::endl;
    }
};

struct WhatsappNotification : Notification {
    void send(const std::string& message) override {
        std::cout << "Sending SMS in a legacy way" << std::endl;
    }
};

// Retry // Formatting
class NotificationDecorator : public Notification {
protected:
    std::unique_ptr<Notification> wrappedNotification;

public:
    explicit NotificationDecorator(std::unique_ptr<Notification> notification)
        : wrappedNotification(std::move(notification)) {}
};

class RetryDecorator : public NotificationDecorator {
public:
    explicit RetryDecorator(std::unique_ptr<Notification> notification)
        : NotificationDecorator(std::move(notification)) {}

    void send(const std::string& message) override {
        std::cout << "We are retrying with lots of efforts" << std::endl;
        wrappedNotification->send(message);
    }
};

class FormattingDecorator : public NotificationDecorator {
public:
    explicit FormattingDecorator(std::unique_ptr<Notification> notification)
        : NotificationDecorator(std::move(notification)) {}

    void send(const std::string& message) override {
        std::cout << "Formatting code is running and processing" << std::endl;
        wrappedNotification->send(message);
    }
};

class App {
public:
    void setNotification(std::unique_ptr<Notification> notification) {
        this->notification = std::move(notification);
    }

    void sendNotification(const std::string& message) { notification->send(message); }

private:
    std::unique_ptr<Notification> notification;
};

int main() {
    // Notification notification = new SMSNotification(); // can be taken care using factory design pattern
    // Notification notification = new FormattingDecorator(new RetryDecorator(new SMSNotification()));

    auto sms = std::make_unique<SMSNotification>();
    auto decorator1 = std::make_unique<RetryDecorator>(std::move(sms));
    auto formatting = std::make_unique<FormattingDecorator>(std::move(decorator1));

    App app;
    app.setNotification(std::move(formatting));
    std::string message = "Hello";
    app.sendNotification(message);

    return 0;
}
