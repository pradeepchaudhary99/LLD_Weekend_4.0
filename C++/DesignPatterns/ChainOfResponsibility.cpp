#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

class Handler {
    std::unique_ptr<Handler> next;

  protected:
    virtual bool canHandle(int level) const = 0;
    virtual std::string name() const = 0;

  public:
    explicit Handler(std::unique_ptr<Handler> nextHandler = nullptr)
        : next(std::move(nextHandler)) {
    }

    virtual ~Handler() = default;
    std::string handle(int level) const {
        if (level < 0) {
            throw std::invalid_argument("Level cannot be negative");
        }

        if (canHandle(level)) {
            return name();
        }

        return next ? next->handle(level) : "Unhandled";
    }
};
class WarningHandler : public Handler {
  public:
    using Handler::Handler;
    bool canHandle(int level) const override {
        return level < 2;
    }

    std::string name() const override {
        return "Warning";
    }
};
class ErrorHandler : public Handler {
  public:
    using Handler::Handler;
    bool canHandle(int level) const override {
        return level < 4;
    }

    std::string name() const override {
        return "Error";
    }
};
class FatalHandler : public Handler {
  public:
    using Handler::Handler;
    bool canHandle(int level) const override {
        return level < 6;
    }

    std::string name() const override {
        return "Fatal";
    }
};
int main() {
    WarningHandler chain(std::make_unique<ErrorHandler>(std::make_unique<FatalHandler>()));
    for (int level = 0; level <= 6; ++level) {
        std::cout << level << ": " << chain.handle(level) << '\n';
    }

    std::cout << "Truncated: " << WarningHandler().handle(3) << '\n';
    try {
        chain.handle(-1);
        throw std::runtime_error("Accepted negative level");
    } catch (const std::invalid_argument &) {
        std::cout << "Invalid level rejected\n";
    }
}
