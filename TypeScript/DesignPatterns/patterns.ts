// In-memory teaching implementations; each lesson has a separate runnable entry point.
interface Notification {
    send(message: string): void;
}

class ChannelNotification implements Notification {
    constructor(private channel: string) {}
    send(message: string): void {
        console.log(`${this.channel}: ${message}`);
    }
}

class NotificationFactory {
    private static cache = new Map<string, Notification>();
    static get(kind: string): Notification {
        const key = kind.toUpperCase();
        const channels: Record<string, string> = {
            SMS: "SMS",
            SLACK: "Slack",
            WHATSAPP: "WhatsApp",
        };
        if (!Object.prototype.hasOwnProperty.call(channels, key))
            throw new Error("Unknown notification type");
        if (!this.cache.has(key)) this.cache.set(key, new ChannelNotification(channels[key]));
        return this.cache.get(key)!;
    }
}

function showError(action: () => void): void {
    try {
        action();
    } catch (e) {
        if (!(e instanceof Error)) throw e;
        console.log(e.message);
    }
}

export function SimpleFactoryDesignPattern(): void {
    for (const kind of ["SMS", "SLACK", "Whatsapp"]) NotificationFactory.get(kind).send("Hello");
    console.log(
        `Same instance: ${NotificationFactory.get("SMS") === NotificationFactory.get("sms")}`,
    );
    showError(() => NotificationFactory.get("EMAIL"));
}

abstract class Creator {
    abstract create(): Notification;
    notify(): void {
        this.create().send("message");
    }
}

class SMSFactory extends Creator {
    create(): Notification {
        return new ChannelNotification("SMS");
    }
}

class WhatsappFactory extends Creator {
    create(): Notification {
        return new ChannelNotification("WhatsApp");
    }
}

class PushFactory extends Creator {
    create(): Notification {
        return new ChannelNotification("Push");
    }
}

export function FactoryMethodDesignPattern(): void {
    for (const f of [new SMSFactory(), new WhatsappFactory(), new PushFactory()]) f.notify();
}

class Student {
    constructor(
        readonly name: string,
        readonly age: number,
        readonly address: string,
        readonly wallet: number,
    ) {}
}

class StudentBuilder {
    private age = 0;
    private address = "";
    private wallet = 0;
    constructor(private name: string) {}
    setAge(value: number): this {
        this.age = value;
        return this;
    }
    setAddress(value: string): this {
        this.address = value;
        return this;
    }
    setWallet(value: number): this {
        this.wallet = value;
        return this;
    }
    build(): Student {
        return new Student(this.name, this.age, this.address, this.wallet);
    }
}

export function BuilderDesignPattern(): void {
    const s = new StudentBuilder("pradeep").setAge(23).setAddress("Delhi").setWallet(100).build();
    console.log(`${s.name} ${s.age} ${s.address} ${s.wallet.toFixed(1)}`);
}

class ConfigurationManager {
    private static readonly instance = new ConfigurationManager();
    private constructor() {}
    static getInstance(): ConfigurationManager {
        return this.instance;
    }
}

export function Singleton(): void {
    console.log(
        `Same instance: ${ConfigurationManager.getInstance() === ConfigurationManager.getInstance()}`,
    );
}

class UIComponent {
    constructor(
        private family: string,
        private kind: string,
    ) {}
    render(): void {
        console.log(`${this.family} ${this.kind} rendered`);
    }
}

class UIFactory {
    constructor(private family: string) {}
    button(): UIComponent {
        return new UIComponent(this.family, "button");
    }
    modal(): UIComponent {
        return new UIComponent(this.family, "modal");
    }
    screen(): UIComponent {
        return new UIComponent(this.family, "screen");
    }
}

class UIRender {
    constructor(factory: UIFactory) {
        this.toggle(factory);
    }
    toggle(factory: UIFactory): void {
        for (const p of [factory.button(), factory.modal(), factory.screen()]) p.render();
    }
}

export function Abstract_FactoryDesign(): void {
    new UIRender(new UIFactory("linux"));
}

abstract class NotificationDecorator implements Notification {
    constructor(protected wrapped: Notification) {}
    abstract send(message: string): void;
}

class RetryDecorator extends NotificationDecorator {
    send(message: string): void {
        for (let attempt = 0; attempt < 3; attempt++) {
            try {
                this.wrapped.send(message);
                return;
            } catch (error) {
                if (attempt === 2) throw error;
            }
        }
    }
}

class FormattingDecorator extends NotificationDecorator {
    send(message: string): void {
        this.wrapped.send(message.trim());
    }
}

export function DecoratorDesignPattern(): void {
    new FormattingDecorator(new RetryDecorator(new ChannelNotification("SMS"))).send(
        " Class starts at 1 PM ",
    );
}

interface PaymentProcessor {
    pay(amount: number): void;
}

class LegacyProcessor implements PaymentProcessor {
    pay(amount: number): void {
        console.log(`Legacy payment: ${amount}`);
    }
}

class RazorPayProcessor {
    makePayment(amount: number): void {
        console.log(`Third-party payment: ${amount}`);
    }
}

class RazorPayAdapter implements PaymentProcessor {
    constructor(private processor: RazorPayProcessor) {}
    pay(amount: number): void {
        this.processor.makePayment(amount);
    }
}

class PaymentApplication {
    processor: PaymentProcessor = new LegacyProcessor();
    pay(amount: number): void {
        if (amount <= 0) throw new Error("Amount must be positive");
        this.processor.pay(amount);
    }
}

export function AdapterDesignPattern(): void {
    const app = new PaymentApplication();
    app.pay(100);
    app.processor = new RazorPayAdapter(new RazorPayProcessor());
    app.pay(200);
    showError(() => app.pay(0));
}

interface Database {
    read(key: string): string | undefined;
    write(key: string, value: string): void;
}

class MemoryDatabase implements Database {
    private data = new Map<string, string>();
    read(key: string): string | undefined {
        console.log(`DB read: ${key}`);
        return this.data.get(key);
    }
    write(key: string, value: string): void {
        this.data.set(key, value);
    }
}

class ProxyDatabase implements Database {
    private cache = new Map<string, string>();
    constructor(private database: Database) {}
    read(key: string): string | undefined {
        if (this.cache.has(key)) {
            console.log(`Cache hit: ${key}`);
            return this.cache.get(key);
        }
        const value = this.database.read(key);
        if (value !== undefined) this.cache.set(key, value);
        return value;
    }
    write(key: string, value: string): void {
        this.database.write(key, value);
        this.cache.delete(key);
    }
}

export function ProxyDesignPattern(): void {
    const db = new ProxyDatabase(new MemoryDatabase());
    db.write("lesson", "Java");
    console.log(db.read("lesson"));
    console.log(db.read("lesson"));
    db.write("lesson", "Patterns");
    console.log(db.read("lesson"));
    console.log(db.read("missing") === undefined ? "Missing key" : "Found");
}

class Observer {
    constructor(private name: string) {}
    notify(value: number): void {
        console.log(`${this.name}: ${value}`);
    }
}

class Stock {
    private price = 0;
    private observers = new Set<Observer>();
    add(observer: Observer): void {
        this.observers.add(observer);
    }
    remove(observer: Observer): void {
        this.observers.delete(observer);
    }
    setPrice(value: number): void {
        if (value < 0) throw new Error("Price cannot be negative");
        if (value === this.price) return;
        this.price = value;
        for (const observer of [...this.observers]) observer.notify(value);
    }
}
type DisplayObserver = (state: { floor: number }) => void;
class Elevator {
    private observers = new Set<DisplayObserver>();
    add(observer: DisplayObserver): void {
        this.observers.add(observer);
    }
    remove(observer: DisplayObserver): void {
        this.observers.delete(observer);
    }
    moveTo(floor: number): void {
        for (const observer of [...this.observers]) observer({ floor });
    }
}

export function ObserverDesignPattern(): void {
    const stock = new Stock(),
        phone = new Observer("Phone"),
        tv = new Observer("TV");
    stock.add(phone);
    stock.add(phone);
    stock.add(tv);
    stock.setPrice(10);
    stock.setPrice(10);
    stock.remove(tv);
    stock.setPrice(20);
    const elevator = new Elevator();
    elevator.add((state) => console.log(`Floor: ${state.floor}`));
    elevator.moveTo(3);
}

class Server {
    constructor(
        readonly name: string,
        public connections: number,
    ) {}
}

interface Strategy {
    select(servers: Server[]): Server;
}

class RoundRobin implements Strategy {
    private next = 0;
    select(servers: Server[]): Server {
        const server = servers[this.next % servers.length];
        this.next = (this.next + 1) % servers.length;
        return server;
    }
}

class LeastConnections implements Strategy {
    select(servers: Server[]): Server {
        return servers.reduce((best, s) => (s.connections < best.connections ? s : best));
    }
}

class LoadBalancer {
    constructor(
        private servers: Server[],
        public strategy: Strategy,
    ) {}
    send(request: string): Server {
        if (!this.servers.length) throw new Error("No servers available");
        const server = this.strategy.select(this.servers);
        server.connections++;
        console.log(`${request} -> ${server.name}`);
        return server;
    }
    complete(server: Server): void {
        if (server.connections <= 0) throw new Error("No active request");
        server.connections--;
    }
}

export function StrategyDesign(): void {
    const lb = new LoadBalancer([new Server("A", 2), new Server("B", 0)], new RoundRobin());
    const first = lb.send("r1");
    lb.send("r2");
    lb.complete(first);
    lb.strategy = new LeastConnections();
    lb.send("r3");
    showError(() => new LoadBalancer([], new RoundRobin()).send("r4"));
}

interface PlayerState {
    press(player: Player): void;
}

class Paused implements PlayerState {
    press(player: Player): void {
        console.log("Playing");
        player.state = new Playing();
    }
}

class Playing implements PlayerState {
    press(player: Player): void {
        console.log("Paused");
        player.state = new Paused();
    }
}

class Player {
    state: PlayerState = new Paused();
    press(): void {
        this.state.press(this);
    }
}

export function StateDesignPattern(): void {
    const player = new Player();
    player.press();
    player.press();
    player.press();
}

abstract class ATMState {
    constructor(protected atm: ATM) {}
    abstract insert(): void;
    abstract dispense(): void;
    abstract cancel(): void;
    abstract eject(): void;
}

class NoCard extends ATMState {
    insert(): void {
        console.log("Card inserted");
        this.atm.state = this.atm.hasCard;
    }
    dispense(): void {
        console.log("Insert card first");
    }
    cancel(): void {
        console.log("No transaction");
    }
    eject(): void {
        console.log("No card");
    }
}

class HasCard extends ATMState {
    insert(): void {
        console.log("Card already inserted");
    }
    dispense(): void {
        console.log("Dispensing started");
        this.atm.state = this.atm.dispensing;
    }
    cancel(): void {
        console.log("Cancelled");
        this.eject();
    }
    eject(): void {
        console.log("Card ejected");
        this.atm.state = this.atm.noCard;
    }
}

class Dispensing extends ATMState {
    insert(): void {
        console.log("Please wait");
    }
    dispense(): void {
        console.log("Already dispensing");
    }
    cancel(): void {
        console.log("Cannot cancel dispensing");
    }
    eject(): void {
        console.log("Please wait");
    }
}

class ATM {
    readonly noCard = new NoCard(this);
    readonly hasCard = new HasCard(this);
    readonly dispensing = new Dispensing(this);
    state: ATMState = this.noCard;
    insert(): void {
        this.state.insert();
    }
    dispense(): void {
        this.state.dispense();
    }
    cancel(): void {
        this.state.cancel();
    }
    eject(): void {
        this.state.eject();
    }
    complete(): void {
        if (this.state !== this.dispensing) {
            console.log("Nothing to complete");
            return;
        }
        console.log("Cash dispensed: 100");
        this.state = this.hasCard;
        this.eject();
    }
}

export function ATMMachineStateDesign(): void {
    const atm = new ATM();
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

abstract class FileSystemNode {
    parent?: Folder;
    constructor(public name: string) {
        this.validate(name);
    }

    private validate(name: string): void {
        if (!name || name.includes("/")) throw new Error("Invalid name");
    }

    abstract size(): number;
    rename(name: string): void {
        this.validate(name);
        if (name === this.name) return;
        if (this.parent) {
            if (this.parent.children.has(name)) throw new Error("Duplicate name");
            this.parent.children.delete(this.name);
            this.parent.children.set(name, this);
        }
        this.name = name;
    }
    properties(): void {
        console.log(`${this.name}: ${this.size()}`);
    }
}

class File extends FileSystemNode {
    private content = "";
    size(): number {
        return this.content.length;
    }
    append(value: string): void {
        this.content += value;
    }
    modify(value: string, offset: number): void {
        if (!Number.isInteger(offset) || offset < 0 || offset > this.content.length)
            throw new Error("Invalid offset");
        this.content =
            this.content.slice(0, offset) + value + this.content.slice(offset + value.length);
    }
    open(): void {
        console.log(this.content);
    }
}

class Folder extends FileSystemNode {
    readonly children = new Map<string, FileSystemNode>();
    add(node: FileSystemNode): void {
        for (let ancestor: FileSystemNode | undefined = this; ancestor; ancestor = ancestor.parent)
            if (ancestor === node) throw new Error("Cycle rejected");
        if (node.parent) throw new Error("Already owned");
        if (this.children.has(node.name)) throw new Error("Duplicate name");
        this.children.set(node.name, node);
        node.parent = this;
    }
    size(): number {
        return [...this.children.values()].reduce((n, child) => n + child.size(), 0);
    }
    open(): void {
        for (const name of this.children.keys()) console.log(name);
    }
}

export function FileSystem_Node(): void {
    const root = new Folder("root"),
        notes = new Folder("notes"),
        file = new File("draft.txt");
    root.add(notes);
    notes.add(file);
    file.append("hello");
    file.modify("a", 1);
    file.rename("lesson.txt");
    file.open();
    root.properties();
    notes.open();
    showError(() => notes.add(root));
    showError(() => notes.add(new File("lesson.txt")));
    showError(() => file.modify("!", 99));
}

class TV {
    on(): void {
        console.log("TV is ON");
    }
    setInput(): void {
        console.log("TV input set to HDMI");
    }
}

class SoundSystem {
    on(): void {
        console.log("Sound System is ON");
    }
    setVolume(v: number): void {
        console.log(`Volume set to ${v}`);
    }
}

class StreamingDevice {
    on(): void {
        console.log("Streaming Device is ON");
    }
    playMovie(movie: string): void {
        console.log(`Playing movie: ${movie}`);
    }
}

class HomeTheaterFacade {
    private tv = new TV();
    private sound = new SoundSystem();
    private streaming = new StreamingDevice();
    watchMovie(movie: string): void {
        console.log("Preparing Home Theater...\n");
        this.tv.on();
        this.tv.setInput();
        this.sound.on();
        this.sound.setVolume(20);
        this.streaming.on();
        this.streaming.playMovie(movie);
        console.log("\nEnjoy your movie!");
    }
}

export function FacadePatternDemo(): void {
    new HomeTheaterFacade().watchMovie("Interstellar");
}
