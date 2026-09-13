"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SimpleFactoryDesignPattern = SimpleFactoryDesignPattern;
exports.FactoryMethodDesignPattern = FactoryMethodDesignPattern;
exports.BuilderDesignPattern = BuilderDesignPattern;
exports.Singleton = Singleton;
exports.Abstract_FactoryDesign = Abstract_FactoryDesign;
exports.DecoratorDesignPattern = DecoratorDesignPattern;
exports.AdapterDesignPattern = AdapterDesignPattern;
exports.ProxyDesignPattern = ProxyDesignPattern;
exports.ObserverDesignPattern = ObserverDesignPattern;
exports.StrategyDesign = StrategyDesign;
exports.StateDesignPattern = StateDesignPattern;
exports.ATMMachineStateDesign = ATMMachineStateDesign;
exports.FileSystem_Node = FileSystem_Node;
exports.FacadePatternDemo = FacadePatternDemo;
class ChannelNotification {
    channel;
    constructor(channel) {
        this.channel = channel;
    }
    send(message) { console.log(`${this.channel}: ${message}`); }
}
class NotificationFactory {
    static cache = new Map();
    static get(kind) {
        const key = kind.toUpperCase();
        const channels = { SMS: 'SMS', SLACK: 'Slack', WHATSAPP: 'WhatsApp' };
        if (!Object.prototype.hasOwnProperty.call(channels, key))
            throw new Error('Unknown notification type');
        if (!this.cache.has(key))
            this.cache.set(key, new ChannelNotification(channels[key]));
        return this.cache.get(key);
    }
}
function showError(action) { try {
    action();
}
catch (e) {
    if (!(e instanceof Error))
        throw e;
    console.log(e.message);
} }
function SimpleFactoryDesignPattern() {
    for (const kind of ['SMS', 'SLACK', 'Whatsapp'])
        NotificationFactory.get(kind).send('Hello');
    console.log(`Same instance: ${NotificationFactory.get('SMS') === NotificationFactory.get('sms')}`);
    showError(() => NotificationFactory.get('EMAIL'));
}
class Creator {
    notify() { this.create().send('message'); }
}
class SMSFactory extends Creator {
    create() { return new ChannelNotification('SMS'); }
}
class WhatsappFactory extends Creator {
    create() { return new ChannelNotification('WhatsApp'); }
}
class PushFactory extends Creator {
    create() { return new ChannelNotification('Push'); }
}
function FactoryMethodDesignPattern() { for (const f of [new SMSFactory(), new WhatsappFactory(), new PushFactory()])
    f.notify(); }
class Student {
    name;
    age;
    address;
    wallet;
    constructor(name, age, address, wallet) {
        this.name = name;
        this.age = age;
        this.address = address;
        this.wallet = wallet;
    }
}
class StudentBuilder {
    name;
    age = 0;
    address = '';
    wallet = 0;
    constructor(name) {
        this.name = name;
    }
    setAge(value) { this.age = value; return this; }
    setAddress(value) { this.address = value; return this; }
    setWallet(value) { this.wallet = value; return this; }
    build() { return new Student(this.name, this.age, this.address, this.wallet); }
}
function BuilderDesignPattern() {
    const s = new StudentBuilder('pradeep').setAge(23).setAddress('Delhi').setWallet(100).build();
    console.log(`${s.name} ${s.age} ${s.address} ${s.wallet.toFixed(1)}`);
}
class ConfigurationManager {
    static instance = new ConfigurationManager();
    constructor() { }
    static getInstance() { return this.instance; }
}
function Singleton() { console.log(`Same instance: ${ConfigurationManager.getInstance() === ConfigurationManager.getInstance()}`); }
class UIComponent {
    family;
    kind;
    constructor(family, kind) {
        this.family = family;
        this.kind = kind;
    }
    render() { console.log(`${this.family} ${this.kind} rendered`); }
}
class UIFactory {
    family;
    constructor(family) {
        this.family = family;
    }
    button() { return new UIComponent(this.family, 'button'); }
    modal() { return new UIComponent(this.family, 'modal'); }
    screen() { return new UIComponent(this.family, 'screen'); }
}
class UIRender {
    constructor(factory) { this.toggle(factory); }
    toggle(factory) { for (const p of [factory.button(), factory.modal(), factory.screen()])
        p.render(); }
}
function Abstract_FactoryDesign() { new UIRender(new UIFactory('linux')); }
class NotificationDecorator {
    wrapped;
    constructor(wrapped) {
        this.wrapped = wrapped;
    }
}
class RetryDecorator extends NotificationDecorator {
    send(message) {
        for (let attempt = 0; attempt < 3; attempt++) {
            try {
                this.wrapped.send(message);
                return;
            }
            catch (error) {
                if (attempt === 2)
                    throw error;
            }
        }
    }
}
class FormattingDecorator extends NotificationDecorator {
    send(message) { this.wrapped.send(message.trim()); }
}
function DecoratorDesignPattern() { new FormattingDecorator(new RetryDecorator(new ChannelNotification('SMS'))).send(' Class starts at 1 PM '); }
class LegacyProcessor {
    pay(amount) { console.log(`Legacy payment: ${amount}`); }
}
class RazorPayProcessor {
    makePayment(amount) { console.log(`Third-party payment: ${amount}`); }
}
class RazorPayAdapter {
    processor;
    constructor(processor) {
        this.processor = processor;
    }
    pay(amount) { this.processor.makePayment(amount); }
}
class PaymentApplication {
    processor = new LegacyProcessor();
    pay(amount) { if (amount <= 0)
        throw new Error('Amount must be positive'); this.processor.pay(amount); }
}
function AdapterDesignPattern() {
    const app = new PaymentApplication();
    app.pay(100);
    app.processor = new RazorPayAdapter(new RazorPayProcessor());
    app.pay(200);
    showError(() => app.pay(0));
}
class MemoryDatabase {
    data = new Map();
    read(key) { console.log(`DB read: ${key}`); return this.data.get(key); }
    write(key, value) { this.data.set(key, value); }
}
class ProxyDatabase {
    database;
    cache = new Map();
    constructor(database) {
        this.database = database;
    }
    read(key) {
        if (this.cache.has(key)) {
            console.log(`Cache hit: ${key}`);
            return this.cache.get(key);
        }
        const value = this.database.read(key);
        if (value !== undefined)
            this.cache.set(key, value);
        return value;
    }
    write(key, value) { this.database.write(key, value); this.cache.delete(key); }
}
function ProxyDesignPattern() {
    const db = new ProxyDatabase(new MemoryDatabase());
    db.write('lesson', 'Java');
    console.log(db.read('lesson'));
    console.log(db.read('lesson'));
    db.write('lesson', 'Patterns');
    console.log(db.read('lesson'));
    console.log(db.read('missing') === undefined ? 'Missing key' : 'Found');
}
class Observer {
    name;
    constructor(name) {
        this.name = name;
    }
    notify(value) { console.log(`${this.name}: ${value}`); }
}
class Stock {
    price = 0;
    observers = new Set();
    add(observer) { this.observers.add(observer); }
    remove(observer) { this.observers.delete(observer); }
    setPrice(value) {
        if (value < 0)
            throw new Error('Price cannot be negative');
        if (value === this.price)
            return;
        this.price = value;
        for (const observer of [...this.observers])
            observer.notify(value);
    }
}
class Elevator {
    observers = new Set();
    add(observer) { this.observers.add(observer); }
    remove(observer) { this.observers.delete(observer); }
    moveTo(floor) { for (const observer of [...this.observers])
        observer({ floor }); }
}
function ObserverDesignPattern() {
    const stock = new Stock(), phone = new Observer('Phone'), tv = new Observer('TV');
    stock.add(phone);
    stock.add(phone);
    stock.add(tv);
    stock.setPrice(10);
    stock.setPrice(10);
    stock.remove(tv);
    stock.setPrice(20);
    const elevator = new Elevator();
    elevator.add(state => console.log(`Floor: ${state.floor}`));
    elevator.moveTo(3);
}
class Server {
    name;
    connections;
    constructor(name, connections) {
        this.name = name;
        this.connections = connections;
    }
}
class RoundRobin {
    next = 0;
    select(servers) { const server = servers[this.next % servers.length]; this.next = (this.next + 1) % servers.length; return server; }
}
class LeastConnections {
    select(servers) { return servers.reduce((best, s) => s.connections < best.connections ? s : best); }
}
class LoadBalancer {
    servers;
    strategy;
    constructor(servers, strategy) {
        this.servers = servers;
        this.strategy = strategy;
    }
    send(request) {
        if (!this.servers.length)
            throw new Error('No servers available');
        const server = this.strategy.select(this.servers);
        server.connections++;
        console.log(`${request} -> ${server.name}`);
        return server;
    }
    complete(server) { if (server.connections <= 0)
        throw new Error('No active request'); server.connections--; }
}
function StrategyDesign() {
    const lb = new LoadBalancer([new Server('A', 2), new Server('B', 0)], new RoundRobin());
    const first = lb.send('r1');
    lb.send('r2');
    lb.complete(first);
    lb.strategy = new LeastConnections();
    lb.send('r3');
    showError(() => new LoadBalancer([], new RoundRobin()).send('r4'));
}
class Paused {
    press(player) { console.log('Playing'); player.state = new Playing(); }
}
class Playing {
    press(player) { console.log('Paused'); player.state = new Paused(); }
}
class Player {
    state = new Paused();
    press() { this.state.press(this); }
}
function StateDesignPattern() { const player = new Player(); player.press(); player.press(); player.press(); }
class ATMState {
    atm;
    constructor(atm) {
        this.atm = atm;
    }
}
class NoCard extends ATMState {
    insert() { console.log('Card inserted'); this.atm.state = this.atm.hasCard; }
    dispense() { console.log('Insert card first'); }
    cancel() { console.log('No transaction'); }
    eject() { console.log('No card'); }
}
class HasCard extends ATMState {
    insert() { console.log('Card already inserted'); }
    dispense() { console.log('Dispensing started'); this.atm.state = this.atm.dispensing; }
    cancel() { console.log('Cancelled'); this.eject(); }
    eject() { console.log('Card ejected'); this.atm.state = this.atm.noCard; }
}
class Dispensing extends ATMState {
    insert() { console.log('Please wait'); }
    dispense() { console.log('Already dispensing'); }
    cancel() { console.log('Cannot cancel dispensing'); }
    eject() { console.log('Please wait'); }
}
class ATM {
    noCard = new NoCard(this);
    hasCard = new HasCard(this);
    dispensing = new Dispensing(this);
    state = this.noCard;
    insert() { this.state.insert(); }
    dispense() { this.state.dispense(); }
    cancel() { this.state.cancel(); }
    eject() { this.state.eject(); }
    complete() {
        if (this.state !== this.dispensing) {
            console.log('Nothing to complete');
            return;
        }
        console.log('Cash dispensed: 100');
        this.state = this.hasCard;
        this.eject();
    }
}
function ATMMachineStateDesign() {
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
class FileSystemNode {
    name;
    parent;
    constructor(name) {
        this.name = name;
        this.validate(name);
    }
    validate(name) { if (!name || name.includes('/'))
        throw new Error('Invalid name'); }
    rename(name) {
        this.validate(name);
        if (name === this.name)
            return;
        if (this.parent) {
            if (this.parent.children.has(name))
                throw new Error('Duplicate name');
            this.parent.children.delete(this.name);
            this.parent.children.set(name, this);
        }
        this.name = name;
    }
    properties() { console.log(`${this.name}: ${this.size()}`); }
}
class File extends FileSystemNode {
    content = '';
    size() { return this.content.length; }
    append(value) { this.content += value; }
    modify(value, offset) {
        if (!Number.isInteger(offset) || offset < 0 || offset > this.content.length)
            throw new Error('Invalid offset');
        this.content = this.content.slice(0, offset) + value + this.content.slice(offset + value.length);
    }
    open() { console.log(this.content); }
}
class Folder extends FileSystemNode {
    children = new Map();
    add(node) {
        for (let ancestor = this; ancestor; ancestor = ancestor.parent)
            if (ancestor === node)
                throw new Error('Cycle rejected');
        if (node.parent)
            throw new Error('Already owned');
        if (this.children.has(node.name))
            throw new Error('Duplicate name');
        this.children.set(node.name, node);
        node.parent = this;
    }
    size() { return [...this.children.values()].reduce((n, child) => n + child.size(), 0); }
    open() { for (const name of this.children.keys())
        console.log(name); }
}
function FileSystem_Node() {
    const root = new Folder('root'), notes = new Folder('notes'), file = new File('draft.txt');
    root.add(notes);
    notes.add(file);
    file.append('hello');
    file.modify('a', 1);
    file.rename('lesson.txt');
    file.open();
    root.properties();
    notes.open();
    showError(() => notes.add(root));
    showError(() => notes.add(new File('lesson.txt')));
    showError(() => file.modify('!', 99));
}
class TV {
    on() { console.log('TV is ON'); }
    setInput() { console.log('TV input set to HDMI'); }
}
class SoundSystem {
    on() { console.log('Sound System is ON'); }
    setVolume(v) { console.log(`Volume set to ${v}`); }
}
class StreamingDevice {
    on() { console.log('Streaming Device is ON'); }
    playMovie(movie) { console.log(`Playing movie: ${movie}`); }
}
class HomeTheaterFacade {
    tv = new TV();
    sound = new SoundSystem();
    streaming = new StreamingDevice();
    watchMovie(movie) {
        console.log('Preparing Home Theater...\n');
        this.tv.on();
        this.tv.setInput();
        this.sound.on();
        this.sound.setVolume(20);
        this.streaming.on();
        this.streaming.playMovie(movie);
        console.log('\nEnjoy your movie!');
    }
}
function FacadePatternDemo() { new HomeTheaterFacade().watchMovie('Interstellar'); }
