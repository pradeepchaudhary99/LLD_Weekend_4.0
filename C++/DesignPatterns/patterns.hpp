#pragma once
#include <algorithm>
#include <cctype>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>
namespace lld {
inline void say(const std::string &s) {
    std::cout << s << '\n';
}

inline void showError(const std::function<void()> &action) {
    try {
        action();
    } catch (const std::invalid_argument &e) {
        say(e.what());
    }
}

struct Notification {
    virtual ~Notification() = default;
    virtual void send(const std::string &) = 0;
};

struct ChannelNotification : Notification {
    std::string channel;
    explicit ChannelNotification(std::string c) : channel(std::move(c)) {
    }
    void send(const std::string &message) override {
        say(channel + ": " + message);
    }
};

struct NotificationFactory {
    static std::shared_ptr<Notification> get(std::string key) {
        for (auto &c : key)
            c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
        static const std::map<std::string, std::string> channels{
            {"SMS", "SMS"}, {"SLACK", "Slack"}, {"WHATSAPP", "WhatsApp"}};
        static std::map<std::string, std::shared_ptr<Notification>> cache;
        if (!channels.count(key))
            throw std::invalid_argument("Unknown notification type");
        if (!cache.count(key))
            cache[key] = std::make_shared<ChannelNotification>(channels.at(key));
        return cache.at(key);
    }
};

inline void SimpleFactoryDesignPattern() {
    for (const auto &kind : {"SMS", "SLACK", "Whatsapp"})
        NotificationFactory::get(kind)->send("Hello");
    say(std::string("Same instance: ") +
        (NotificationFactory::get("SMS") == NotificationFactory::get("sms") ? "true" : "false"));
    showError([] {
        NotificationFactory::get("EMAIL");
    });
}

struct Creator {
    virtual ~Creator() = default;
    virtual std::unique_ptr<Notification> create() = 0;
    void notify() {
        create()->send("message");
    }
};

struct SMSFactory : Creator {
    std::unique_ptr<Notification> create() override {
        return std::make_unique<ChannelNotification>("SMS");
    }
};

struct WhatsappFactory : Creator {
    std::unique_ptr<Notification> create() override {
        return std::make_unique<ChannelNotification>("WhatsApp");
    }
};

struct PushFactory : Creator {
    std::unique_ptr<Notification> create() override {
        return std::make_unique<ChannelNotification>("Push");
    }
};

inline void FactoryMethodDesignPattern() {
    SMSFactory().notify();
    WhatsappFactory().notify();
    PushFactory().notify();
}

struct Student {
    std::string name;
    int age;
    std::string address;
    double wallet;
};

class StudentBuilder {
    Student value;

  public:
    explicit StudentBuilder(std::string name) : value{std::move(name), 0, "", 0} {
    }
    StudentBuilder &setAge(int age) {
        value.age = age;
        return *this;
    }
    StudentBuilder &setAddress(std::string address) {
        value.address = std::move(address);
        return *this;
    }
    StudentBuilder &setWallet(double wallet) {
        value.wallet = wallet;
        return *this;
    }
    Student build() const {
        return value;
    }
};

inline void BuilderDesignPattern() {
    auto s = StudentBuilder("pradeep").setAge(23).setAddress("Delhi").setWallet(100).build();
    say(s.name + " " + std::to_string(s.age) + " " + s.address + " " +
        std::to_string(static_cast<int>(s.wallet)) + ".0");
}

class ConfigurationManager {
    ConfigurationManager() = default;

  public:
    ConfigurationManager(const ConfigurationManager &) = delete;
    ConfigurationManager &operator=(const ConfigurationManager &) = delete;
    static ConfigurationManager &get() {
        static ConfigurationManager instance;
        return instance;
    }
};

inline void Singleton() {
    say(std::string("Same instance: ") +
        (&ConfigurationManager::get() == &ConfigurationManager::get() ? "true" : "false"));
}

struct UIComponent {
    std::string family, kind;
    void render() const {
        say(family + " " + kind + " rendered");
    }
};

struct UIFactory {
    std::string family;
    UIComponent button() const {
        return {family, "button"};
    }
    UIComponent modal() const {
        return {family, "modal"};
    }
    UIComponent screen() const {
        return {family, "screen"};
    }
};

struct UIRender {
    explicit UIRender(const UIFactory &f) {
        toggle(f);
    }
    void toggle(const UIFactory &f) {
        f.button().render();
        f.modal().render();
        f.screen().render();
    }
};

inline void Abstract_FactoryDesign() {
    UIRender ui(UIFactory{"linux"});
}

struct NotificationDecorator : Notification {
    std::unique_ptr<Notification> wrapped;
    explicit NotificationDecorator(std::unique_ptr<Notification> n) : wrapped(std::move(n)) {
    }
};

struct RetryDecorator : NotificationDecorator {
    using NotificationDecorator::NotificationDecorator;
    void send(const std::string &message) override {
        for (int attempt = 0; attempt < 3; ++attempt) {
            try {
                wrapped->send(message);
                return;
            } catch (const std::runtime_error &) {
                if (attempt == 2)
                    throw;
            }
        }
    }
};

struct FormattingDecorator : NotificationDecorator {
    using NotificationDecorator::NotificationDecorator;
    void send(const std::string &message) override {
        auto start = message.find_first_not_of(" \t\r\n"),
             end = message.find_last_not_of(" \t\r\n");
        wrapped->send(start == std::string::npos ? "" : message.substr(start, end - start + 1));
    }
};

inline void DecoratorDesignPattern() {
    FormattingDecorator notification(
        std::make_unique<RetryDecorator>(std::make_unique<ChannelNotification>("SMS")));
    notification.send(" Class starts at 1 PM ");
}

struct PaymentProcessor {
    virtual ~PaymentProcessor() = default;
    virtual void pay(int) = 0;
};

struct LegacyProcessor : PaymentProcessor {
    void pay(int amount) override {
        say("Legacy payment: " + std::to_string(amount));
    }
};

struct RazorPayProcessor {
    void makePayment(int amount) {
        say("Third-party payment: " + std::to_string(amount));
    }
};

struct RazorPayAdapter : PaymentProcessor {
    RazorPayProcessor processor;
    void pay(int amount) override {
        processor.makePayment(amount);
    }
};

struct PaymentApplication {
    std::unique_ptr<PaymentProcessor> processor = std::make_unique<LegacyProcessor>();
    void pay(int amount) {
        if (amount <= 0)
            throw std::invalid_argument("Amount must be positive");
        processor->pay(amount);
    }
};

inline void AdapterDesignPattern() {
    PaymentApplication app;
    app.pay(100);
    app.processor = std::make_unique<RazorPayAdapter>();
    app.pay(200);
    showError([&] {
        app.pay(0);
    });
}

struct Database {
    virtual ~Database() = default;
    virtual std::optional<std::string> read(const std::string &) = 0;
    virtual void write(const std::string &, const std::string &) = 0;
};

struct MemoryDatabase : Database {
    std::map<std::string, std::string> data;
    std::optional<std::string> read(const std::string &key) override {
        say("DB read: " + key);
        if (!data.count(key))
            return std::nullopt;
        return data.at(key);
    }
    void write(const std::string &key, const std::string &value) override {
        data[key] = value;
    }
};

struct ProxyDatabase : Database {
    std::unique_ptr<Database> database;
    std::map<std::string, std::string> cache;
    explicit ProxyDatabase(std::unique_ptr<Database> db) : database(std::move(db)) {
    }
    std::optional<std::string> read(const std::string &key) override {
        if (cache.count(key)) {
            say("Cache hit: " + key);
            return cache.at(key);
        }
        auto value = database->read(key);
        if (value)
            cache[key] = *value;
        return value;
    }
    void write(const std::string &key, const std::string &value) override {
        database->write(key, value);
        cache.erase(key);
    }
};

inline void ProxyDesignPattern() {
    ProxyDatabase db(std::make_unique<MemoryDatabase>());
    db.write("lesson", "Java");
    say(db.read("lesson").value());
    say(db.read("lesson").value());
    db.write("lesson", "Patterns");
    say(db.read("lesson").value());
    say(db.read("missing") ? "Found" : "Missing key");
}

struct Observer {
    std::string name;
    explicit Observer(std::string n) : name(std::move(n)) {
    }
    virtual ~Observer() = default;
    virtual void notify(int value) {
        say(name + ": " + std::to_string(value));
    }
};

struct Stock {
    int price = 0;
    std::vector<std::shared_ptr<Observer>> observers;
    void add(std::shared_ptr<Observer> o) {
        if (std::find(observers.begin(), observers.end(), o) == observers.end())
            observers.push_back(o);
    }
    void remove(const std::shared_ptr<Observer> &o) {
        observers.erase(std::remove(observers.begin(), observers.end(), o), observers.end());
    }
    void setPrice(int value) {
        if (value < 0)
            throw std::invalid_argument("Price cannot be negative");
        if (value == price)
            return;
        price = value;
        auto snapshot = observers;
        for (auto &o : snapshot)
            o->notify(value);
    }
};

struct ElevatorState {
    int floor;
};

struct DisplayObserver {
    virtual ~DisplayObserver() = default;
    virtual void notify(ElevatorState state) = 0;
};

struct FloorDisplay : DisplayObserver {
    void notify(ElevatorState state) override {
        say("Floor: " + std::to_string(state.floor));
    }
};

struct Elevator {
    std::vector<std::shared_ptr<DisplayObserver>> observers;
    void add(std::shared_ptr<DisplayObserver> o) {
        if (std::find(observers.begin(), observers.end(), o) == observers.end())
            observers.push_back(o);
    }
    void remove(const std::shared_ptr<DisplayObserver> &o) {
        observers.erase(std::remove(observers.begin(), observers.end(), o), observers.end());
    }
    void moveTo(int floor) {
        auto snapshot = observers;
        for (auto &o : snapshot)
            o->notify({floor});
    }
};

inline void ObserverDesignPattern() {
    Stock stock;
    auto phone = std::make_shared<Observer>("Phone"), tv = std::make_shared<Observer>("TV");
    stock.add(phone);
    stock.add(phone);
    stock.add(tv);
    stock.setPrice(10);
    stock.setPrice(10);
    stock.remove(tv);
    stock.setPrice(20);
    Elevator elevator;
    elevator.add(std::make_shared<FloorDisplay>());
    elevator.moveTo(3);
}

struct Server {
    std::string name;
    int connections;
};

struct Strategy {
    virtual ~Strategy() = default;
    virtual std::size_t select(const std::vector<Server> &) = 0;
};

struct RoundRobin : Strategy {
    std::size_t next = 0;
    std::size_t select(const std::vector<Server> &servers) override {
        auto index = next % servers.size();
        next = (next + 1) % servers.size();
        return index;
    }
};

struct LeastConnections : Strategy {
    std::size_t select(const std::vector<Server> &servers) override {
        return std::distance(
            servers.begin(),
            std::min_element(servers.begin(), servers.end(), [](const Server &a, const Server &b) {
                return a.connections < b.connections;
            }));
    }
};

struct LoadBalancer {
    std::vector<Server> servers;
    std::unique_ptr<Strategy> strategy;
    LoadBalancer(std::vector<Server> s, std::unique_ptr<Strategy> p)
        : servers(std::move(s)), strategy(std::move(p)) {
    }
    std::size_t send(const std::string &request) {
        if (servers.empty())
            throw std::invalid_argument("No servers available");
        auto index = strategy->select(servers);
        servers[index].connections++;
        say(request + " -> " + servers[index].name);
        return index;
    }
    void complete(std::size_t index) {
        auto &s = servers.at(index);
        if (s.connections <= 0)
            throw std::invalid_argument("No active request");
        --s.connections;
    }
};

inline void StrategyDesign() {
    LoadBalancer lb({{"A", 2}, {"B", 0}}, std::make_unique<RoundRobin>());
    auto first = lb.send("r1");
    lb.send("r2");
    lb.complete(first);
    lb.strategy = std::make_unique<LeastConnections>();
    lb.send("r3");
    showError([] {
        LoadBalancer({}, std::make_unique<RoundRobin>()).send("r4");
    });
}

struct Player;
struct PlayerState {
    virtual ~PlayerState() = default;
    virtual void press(Player &) = 0;
};

struct Paused : PlayerState {
    void press(Player &) override;
};

struct Playing : PlayerState {
    void press(Player &) override;
};

struct Player {
    Paused paused;
    Playing playing;
    PlayerState *state = &paused;
    void press() {
        state->press(*this);
    }
};

inline void Paused::press(Player &p) {
    say("Playing");
    p.state = &p.playing;
}

inline void Playing::press(Player &p) {
    say("Paused");
    p.state = &p.paused;
}

inline void StateDesignPattern() {
    Player player;
    player.press();
    player.press();
    player.press();
}

struct ATM;
struct ATMState {
    ATM &atm;
    explicit ATMState(ATM &a) : atm(a) {
    }
    virtual ~ATMState() = default;
    virtual void insert() = 0;
    virtual void dispense() = 0;
    virtual void cancel() = 0;
    virtual void eject() = 0;
};

struct NoCard : ATMState {
    using ATMState::ATMState;
    void insert() override;
    void dispense() override {
        say("Insert card first");
    }
    void cancel() override {
        say("No transaction");
    }
    void eject() override {
        say("No card");
    }
};

struct HasCard : ATMState {
    using ATMState::ATMState;
    void insert() override {
        say("Card already inserted");
    }
    void dispense() override;
    void cancel() override {
        say("Cancelled");
        eject();
    }
    void eject() override;
};

struct Dispensing : ATMState {
    using ATMState::ATMState;
    void insert() override {
        say("Please wait");
    }
    void dispense() override {
        say("Already dispensing");
    }
    void cancel() override {
        say("Cannot cancel dispensing");
    }
    void eject() override {
        say("Please wait");
    }
};

struct ATM {
    NoCard noCard{*this};
    HasCard hasCard{*this};
    Dispensing dispensing{*this};
    ATMState *state = &noCard;
    ATM() = default;
    ATM(const ATM &) = delete;
    ATM &operator=(const ATM &) = delete;
    void insert() {
        state->insert();
    }
    void dispense() {
        state->dispense();
    }
    void cancel() {
        state->cancel();
    }
    void eject() {
        state->eject();
    }
    void complete() {
        if (state != &dispensing) {
            say("Nothing to complete");
            return;
        }
        say("Cash dispensed: 100");
        state = &hasCard;
        eject();
    }
};

inline void NoCard::insert() {
    say("Card inserted");
    atm.state = &atm.hasCard;
}

inline void HasCard::dispense() {
    say("Dispensing started");
    atm.state = &atm.dispensing;
}

inline void HasCard::eject() {
    say("Card ejected");
    atm.state = &atm.noCard;
}

inline void ATMMachineStateDesign() {
    ATM atm;
    atm.dispense();
    atm.insert();
    atm.insert();
    atm.dispense();
    atm.dispense();
    atm.cancel();
    atm.complete();
    atm.insert();
    atm.cancel();
    atm.complete();
}

struct Folder;
struct Node {
    std::string name;
    Folder *parent = nullptr;
    explicit Node(std::string n) : name(std::move(n)) {
        validate(name);
    }
    virtual ~Node() = default;
    static void validate(const std::string &n) {
        if (n.empty() || n.find('/') != std::string::npos)
            throw std::invalid_argument("Invalid name");
    }
    virtual std::size_t size() const = 0;
    void rename(const std::string &);
    void properties() const {
        say(name + ": " + std::to_string(size()));
    }
};

struct File : Node {
    std::string content;
    using Node::Node;
    std::size_t size() const override {
        return content.size();
    }
    void append(const std::string &value) {
        content += value;
    }
    void modify(const std::string &value, int offset) {
        if (offset < 0 || static_cast<std::size_t>(offset) > content.size())
            throw std::invalid_argument("Invalid offset");
        content.replace(offset, value.size(), value);
    }
    void open() const {
        say(content);
    }
};

struct Folder : Node {
    // Shared handles permit inspecting children; parent is non-owning. Cycles are rejected.
    std::map<std::string, std::shared_ptr<Node>> children;
    using Node::Node;
    ~Folder() override {
        for (auto &entry : children)
            entry.second->parent = nullptr;
    }
    void add(std::shared_ptr<Node> node) {
        for (Node *ancestor = this; ancestor; ancestor = ancestor->parent)
            if (ancestor == node.get())
                throw std::invalid_argument("Cycle rejected");
        if (node->parent)
            throw std::invalid_argument("Already owned");
        if (children.count(node->name))
            throw std::invalid_argument("Duplicate name");
        children[node->name] = node;
        node->parent = this;
    }
    std::size_t size() const override {
        std::size_t total = 0;
        for (const auto &entry : children)
            total += entry.second->size();
        return total;
    }
    void open() const {
        for (const auto &entry : children)
            say(entry.first);
    }
};

inline void Node::rename(const std::string &value) {
    validate(value);
    if (value == name)
        return;
    if (parent) {
        if (parent->children.count(value))
            throw std::invalid_argument("Duplicate name");
        auto node = parent->children.at(name);
        parent->children.erase(name);
        parent->children[value] = node;
    }
    name = value;
}

inline void FileSystem_Node() {
    auto root = std::make_shared<Folder>("root"), notes = std::make_shared<Folder>("notes");
    auto file = std::make_shared<File>("draft.txt");
    root->add(notes);
    notes->add(file);
    file->append("hello");
    file->modify("a", 1);
    file->rename("lesson.txt");
    file->open();
    root->properties();
    notes->open();
    showError([&] {
        notes->add(root);
    });
    showError([&] {
        notes->add(std::make_shared<File>("lesson.txt"));
    });
    showError([&] {
        file->modify("!", 99);
    });
}

struct TV {
    void on() {
        say("TV is ON");
    }
    void setInput() {
        say("TV input set to HDMI");
    }
};

struct SoundSystem {
    void on() {
        say("Sound System is ON");
    }
    void setVolume(int v) {
        say("Volume set to " + std::to_string(v));
    }
};

struct StreamingDevice {
    void on() {
        say("Streaming Device is ON");
    }
    void playMovie(const std::string &m) {
        say("Playing movie: " + m);
    }
};

struct HomeTheaterFacade {
    TV tv;
    SoundSystem sound;
    StreamingDevice streaming;
    void watchMovie(const std::string &movie) {
        say("Preparing Home Theater...\n");
        tv.on();
        tv.setInput();
        sound.on();
        sound.setVolume(20);
        streaming.on();
        streaming.playMovie(movie);
        say("\nEnjoy your movie!");
    }
};

inline void FacadePatternDemo() {
    HomeTheaterFacade().watchMovie("Interstellar");
}
} // namespace lld
