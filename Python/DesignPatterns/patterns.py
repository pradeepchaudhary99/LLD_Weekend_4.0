"""Standalone, in-memory LLD demonstrations. Public functions are lesson entry points."""

from __future__ import annotations
from abc import ABC, abstractmethod
from dataclasses import dataclass


class Notification(ABC):
    @abstractmethod
    def send(self, message: str) -> None:
        pass


class ChannelNotification(Notification):
    def __init__(self, channel):
        self.channel = channel

    def send(self, message):
        print(f"{self.channel}: {message}")


class NotificationFactory:
    cache = {}

    @classmethod
    def get(cls, kind):
        channels = {"SMS": "SMS", "SLACK": "Slack", "WHATSAPP": "WhatsApp"}
        key = kind.upper()
        if key not in channels:
            raise ValueError("Unknown notification type")
        if key not in cls.cache:
            cls.cache[key] = ChannelNotification(channels[key])
        return cls.cache[key]


def SimpleFactoryDesignPattern():
    for kind in ("SMS", "SLACK", "Whatsapp"):
        NotificationFactory.get(kind).send("Hello")
    print(
        f"Same instance: {str(NotificationFactory.get('SMS') is NotificationFactory.get('sms')).lower()}"
    )
    try:
        NotificationFactory.get("EMAIL")
    except ValueError as e:
        print(e)


class Creator(ABC):
    @abstractmethod
    def create(self):
        pass

    def notify(self):
        self.create().send("message")


class SMSFactory(Creator):
    def create(self):
        return ChannelNotification("SMS")


class WhatsappFactory(Creator):
    def create(self):
        return ChannelNotification("WhatsApp")


class PushFactory(Creator):
    def create(self):
        return ChannelNotification("Push")


def FactoryMethodDesignPattern():
    for factory in (SMSFactory(), WhatsappFactory(), PushFactory()):
        factory.notify()


@dataclass(frozen=True)
class Student:
    name: str
    age: int = 0
    address: str = ""
    wallet: float = 0


class StudentBuilder:
    def __init__(self, name):
        self.name, self.age, self.address, self.wallet = name, 0, "", 0

    def set_age(self, age):
        self.age = age
        return self

    def set_address(self, address):
        self.address = address
        return self

    def set_wallet(self, wallet):
        self.wallet = wallet
        return self

    def build(self):
        return Student(self.name, self.age, self.address, self.wallet)


def BuilderDesignPattern():
    s = StudentBuilder("pradeep").set_age(23).set_address("Delhi").set_wallet(100).build()
    print(s.name, s.age, s.address, float(s.wallet))


class ConfigurationManager:
    # Module initialization is serialized by Python's import machinery.
    pass


_configuration = ConfigurationManager()


def get_configuration():
    return _configuration


def Singleton():
    print(f"Same instance: {str(get_configuration() is get_configuration()).lower()}")


class UIComponent:
    def __init__(self, family, kind):
        self.family, self.kind = family, kind

    def render(self):
        print(f"{self.family} {self.kind} rendered")


class UIFactory:
    def __init__(self, family):
        self.family = family

    def button(self):
        return UIComponent(self.family, "button")

    def modal(self):
        return UIComponent(self.family, "modal")

    def screen(self):
        return UIComponent(self.family, "screen")


class UIRender:
    def __init__(self, factory):
        self.toggle(factory)

    def toggle(self, factory):
        self.products = (factory.button(), factory.modal(), factory.screen())
        for product in self.products:
            product.render()


def Abstract_FactoryDesign():
    UIRender(UIFactory("linux"))


class NotificationDecorator(Notification):
    def __init__(self, wrapped):
        self.wrapped = wrapped


class RetryDecorator(NotificationDecorator):
    # Retry only on failure; a successful delivery is never sent twice.
    def send(self, message):
        for attempt in range(3):
            try:
                self.wrapped.send(message)
                return
            except RuntimeError:
                if attempt == 2:
                    raise


class FormattingDecorator(NotificationDecorator):
    def send(self, message):
        self.wrapped.send(message.strip())


def DecoratorDesignPattern():
    FormattingDecorator(RetryDecorator(ChannelNotification("SMS"))).send(" Class starts at 1 PM ")


class PaymentProcessor(ABC):
    @abstractmethod
    def pay(self, amount):
        pass


class LegacyProcessor(PaymentProcessor):
    def pay(self, amount):
        print(f"Legacy payment: {amount}")


class RazorPayProcessor:
    def make_payment(self, amount):
        print(f"Third-party payment: {amount}")


class RazorPayAdapter(PaymentProcessor):
    def __init__(self, processor):
        self.processor = processor

    def pay(self, amount):
        self.processor.make_payment(amount)


class PaymentApplication:
    def __init__(self):
        self.processor = LegacyProcessor()

    def pay(self, amount):
        if amount <= 0:
            raise ValueError("Amount must be positive")
        self.processor.pay(amount)


def AdapterDesignPattern():
    app = PaymentApplication()
    app.pay(100)
    app.processor = RazorPayAdapter(RazorPayProcessor())
    app.pay(200)
    try:
        app.pay(0)
    except ValueError as e:
        print(e)


class Database(ABC):
    @abstractmethod
    def read(self, key):
        pass

    @abstractmethod
    def write(self, key, value):
        pass


class MemoryDatabase(Database):
    def __init__(self):
        self.data = {}

    def read(self, key):
        print(f"DB read: {key}")
        return self.data.get(key)

    def write(self, key, value):
        self.data[key] = value


class ProxyDatabase(Database):
    def __init__(self, database):
        self.database, self.cache = database, {}

    def read(self, key):
        if key in self.cache:
            print(f"Cache hit: {key}")
            return self.cache[key]
        value = self.database.read(key)
        if value is not None:
            self.cache[key] = value
        return value

    def write(self, key, value):
        self.database.write(key, value)
        self.cache.pop(key, None)


def ProxyDesignPattern():
    db = ProxyDatabase(MemoryDatabase())
    db.write("lesson", "Java")
    print(db.read("lesson"))
    print(db.read("lesson"))
    db.write("lesson", "Patterns")
    print(db.read("lesson"))
    print("Missing key" if db.read("missing") is None else "Found")


class Observer:
    def __init__(self, name):
        self.name = name

    def notify(self, value):
        print(f"{self.name}: {value}")


class Stock:
    def __init__(self):
        self.price, self.observers = 0, []

    def add(self, observer):
        if observer not in self.observers:
            self.observers.append(observer)

    def remove(self, observer):
        if observer in self.observers:
            self.observers.remove(observer)

    def set_price(self, value):
        if value < 0:
            raise ValueError("Price cannot be negative")
        if value == self.price:
            return
        self.price = value
        for observer in self.observers.copy():
            observer.notify(value)


@dataclass(frozen=True)
class ElevatorState:
    floor: int


class Elevator:
    def __init__(self):
        self.observers = []

    def add(self, observer):
        if observer not in self.observers:
            self.observers.append(observer)

    def remove(self, observer):
        if observer in self.observers:
            self.observers.remove(observer)

    def move_to(self, floor):
        for observer in self.observers.copy():
            observer(ElevatorState(floor))


def ObserverDesignPattern():
    stock = Stock()
    phone, tv = Observer("Phone"), Observer("TV")
    stock.add(phone)
    stock.add(phone)
    stock.add(tv)
    stock.set_price(10)
    stock.set_price(10)
    stock.remove(tv)
    stock.set_price(20)
    elevator = Elevator()
    elevator.add(lambda state: print(f"Floor: {state.floor}"))
    elevator.move_to(3)


@dataclass
class Server:
    name: str
    connections: int


class RoundRobin:
    def __init__(self):
        self.next = 0

    def select(self, servers):
        server = servers[self.next % len(servers)]
        self.next = (self.next + 1) % len(servers)
        return server


class LeastConnections:
    def select(self, servers):
        return min(servers, key=lambda s: s.connections)


class LoadBalancer:
    def __init__(self, servers, strategy):
        self.servers, self.strategy = servers, strategy

    def send(self, request):
        if not self.servers:
            raise ValueError("No servers available")
        server = self.strategy.select(self.servers)
        server.connections += 1
        print(f"{request} -> {server.name}")
        return server

    def complete(self, server):
        if server.connections <= 0:
            raise ValueError("No active request")
        server.connections -= 1


def StrategyDesign():
    lb = LoadBalancer([Server("A", 2), Server("B", 0)], RoundRobin())
    first = lb.send("r1")
    lb.send("r2")
    lb.complete(first)
    lb.strategy = LeastConnections()
    lb.send("r3")
    try:
        LoadBalancer([], RoundRobin()).send("r4")
    except ValueError as e:
        print(e)


class Paused:
    def press(self, player):
        print("Playing")
        player.state = Playing()


class Playing:
    def press(self, player):
        print("Paused")
        player.state = Paused()


class Player:
    def __init__(self):
        self.state = Paused()

    def press(self):
        self.state.press(self)


def StateDesignPattern():
    player = Player()
    player.press()
    player.press()
    player.press()


class ATMState:
    def __init__(self, atm):
        self.atm = atm


class NoCard(ATMState):
    def insert(self):
        print("Card inserted")
        self.atm.state = self.atm.has_card

    def dispense(self):
        print("Insert card first")

    def cancel(self):
        print("No transaction")

    def eject(self):
        print("No card")


class HasCard(ATMState):
    def insert(self):
        print("Card already inserted")

    def dispense(self):
        print("Dispensing started")
        self.atm.state = self.atm.dispensing

    def cancel(self):
        print("Cancelled")
        self.eject()

    def eject(self):
        print("Card ejected")
        self.atm.state = self.atm.no_card


class Dispensing(ATMState):
    def insert(self):
        print("Please wait")

    def dispense(self):
        print("Already dispensing")

    def cancel(self):
        print("Cannot cancel dispensing")

    def eject(self):
        print("Please wait")


class ATM:
    def __init__(self):
        self.no_card, self.has_card, self.dispensing = NoCard(self), HasCard(self), Dispensing(self)
        self.state = self.no_card

    def insert(self):
        self.state.insert()

    def dispense(self):
        self.state.dispense()

    def cancel(self):
        self.state.cancel()

    def eject(self):
        self.state.eject()

    def complete(self):
        if self.state is not self.dispensing:
            print("Nothing to complete")
            return
        print("Cash dispensed: 100")
        self.state = self.has_card
        self.eject()


def ATMMachineStateDesign():
    atm = ATM()
    atm.dispense()
    atm.insert()
    atm.insert()
    atm.dispense()
    atm.dispense()
    atm.cancel()
    atm.complete()
    atm.insert()
    atm.cancel()
    atm.complete()


class Node(ABC):
    def __init__(self, name):
        self.validate(name)
        self.name, self.parent = name, None

    @staticmethod
    def validate(name):
        if not name or "/" in name:
            raise ValueError("Invalid name")

    @abstractmethod
    def size(self):
        pass

    def rename(self, name):
        self.validate(name)
        if name == self.name:
            return
        if self.parent:
            if name in self.parent.children:
                raise ValueError("Duplicate name")
            del self.parent.children[self.name]
            self.parent.children[name] = self
        self.name = name

    def properties(self):
        print(f"{self.name}: {self.size()}")


class File(Node):
    def __init__(self, name):
        super().__init__(name)
        self.content = ""

    def size(self):
        return len(self.content)

    def append(self, value):
        self.content += value

    def modify(self, value, offset):
        if offset < 0 or offset > len(self.content):
            raise ValueError("Invalid offset")
        self.content = self.content[:offset] + value + self.content[offset + len(value) :]

    def open(self):
        print(self.content)


class Folder(Node):
    def __init__(self, name):
        super().__init__(name)
        self.children = {}

    def add(self, node):
        ancestor = self
        while ancestor:
            if ancestor is node:
                raise ValueError("Cycle rejected")
            ancestor = ancestor.parent
        if node.parent:
            raise ValueError("Already owned")
        if node.name in self.children:
            raise ValueError("Duplicate name")
        self.children[node.name] = node
        node.parent = self

    def size(self):
        return sum(child.size() for child in self.children.values())

    def open(self):
        for name in self.children:
            print(name)


def FileSystem_Node():
    root, notes, file = Folder("root"), Folder("notes"), File("draft.txt")
    root.add(notes)
    notes.add(file)
    file.append("hello")
    file.modify("a", 1)
    file.rename("lesson.txt")
    file.open()
    root.properties()
    notes.open()
    for action in (
        lambda: notes.add(root),
        lambda: notes.add(File("lesson.txt")),
        lambda: file.modify("!", 99),
    ):
        try:
            action()
        except ValueError as e:
            print(e)


class TV:
    def on(self):
        print("TV is ON")

    def set_input(self):
        print("TV input set to HDMI")


class SoundSystem:
    def on(self):
        print("Sound System is ON")

    def set_volume(self, volume):
        print(f"Volume set to {volume}")


class StreamingDevice:
    def on(self):
        print("Streaming Device is ON")

    def play_movie(self, movie):
        print(f"Playing movie: {movie}")


class HomeTheaterFacade:
    def __init__(self):
        self.tv, self.sound, self.streaming = TV(), SoundSystem(), StreamingDevice()

    def watch_movie(self, movie):
        print("Preparing Home Theater...\n")
        self.tv.on()
        self.tv.set_input()
        self.sound.on()
        self.sound.set_volume(20)
        self.streaming.on()
        self.streaming.play_movie(movie)
        print("\nEnjoy your movie!")


def FacadePatternDemo():
    HomeTheaterFacade().watch_movie("Interstellar")
