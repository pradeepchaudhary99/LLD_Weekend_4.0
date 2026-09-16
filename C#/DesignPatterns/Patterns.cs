using System;
using System.Collections.Generic;
using System.Linq;
namespace LLDWeekend4.Patterns;

public static class Lessons
{
    static void ShowError(Action action)
    {
        try
        {
            action();
        }
        catch (ArgumentException e)
        {
            Console.WriteLine(e.Message);
        }
    }

    public static void SimpleFactoryDesignPattern()
    {
        foreach (var kind in new[] { "SMS", "SLACK", "Whatsapp" })
            NotificationFactory.Get(kind).Send("Hello");
        Console.WriteLine(
            $"Same instance: {ReferenceEquals(NotificationFactory.Get("SMS"), NotificationFactory.Get("sms")).ToString().ToLowerInvariant()}");
        ShowError(() => NotificationFactory.Get("EMAIL"));
    }

    public static void FactoryMethodDesignPattern()
    {
        foreach (Creator f in new Creator[] { new SMSFactory(), new WhatsappFactory(),
                                              new PushFactory() })
            f.Notify();
    }

    public static void BuilderDesignPattern()
    {
        var s = new StudentBuilder("pradeep").SetAge(23).SetAddress("Delhi").SetWallet(100).Build();
        Console.WriteLine(
            FormattableString.Invariant($"{s.Name} {s.Age} {s.Address} {s.Wallet:F1}"));
    }

    public static void Singleton()
    {
        Console.WriteLine(
            $"Same instance: {ReferenceEquals(ConfigurationManager.Instance, ConfigurationManager.Instance).ToString().ToLowerInvariant()}");
    }

    public static void Abstract_FactoryDesign()
    {
        new UIRender(new UIFactory("linux"));
    }

    public static void DecoratorDesignPattern()
    {
        new FormattingDecorator(new RetryDecorator(new ChannelNotification("SMS")))
            .Send(" Class starts at 1 PM ");
    }

    public static void AdapterDesignPattern()
    {
        var app = new PaymentApplication();
        app.Pay(100);
        app.Processor = new RazorPayAdapter(new RazorPayProcessor());
        app.Pay(200);
        ShowError(() => app.Pay(0));
    }

    public static void ProxyDesignPattern()
    {
        IDatabase db = new ProxyDatabase(new MemoryDatabase());
        db.Write("lesson", "Java");
        Console.WriteLine(db.Read("lesson"));
        Console.WriteLine(db.Read("lesson"));
        db.Write("lesson", "Patterns");
        Console.WriteLine(db.Read("lesson"));
        Console.WriteLine(db.Read("missing") == null ? "Missing key" : "Found");
    }

    public static void ObserverDesignPattern()
    {
        var stock = new Stock();
        var phone = new Observer("Phone");
        var tv = new Observer("TV");
        stock.Add(phone);
        stock.Add(phone);
        stock.Add(tv);
        stock.SetPrice(10);
        stock.SetPrice(10);
        stock.Remove(tv);
        stock.SetPrice(20);
        var elevator = new Elevator();
        elevator.Add(state => Console.WriteLine($"Floor: {state.Floor}"));
        elevator.MoveTo(3);
    }

    public static void StrategyDesign()
    {
        var lb = new LoadBalancer(new List<Server> { new("A", 2), new("B", 0) }, new RoundRobin());
        var first = lb.Send("r1");
        lb.Send("r2");
        lb.Complete(first);
        lb.Strategy = new LeastConnections();
        lb.Send("r3");
        ShowError(() => new LoadBalancer(new List<Server>(), new RoundRobin()).Send("r4"));
    }

    public static void StateDesignPattern()
    {
        var player = new Player();
        player.Press();
        player.Press();
        player.Press();
    }

    public static void ATMMachineStateDesign()
    {
        var atm = new ATM();
        atm.Dispense();
        atm.Insert();
        atm.Insert();
        atm.Dispense();
        atm.Dispense();
        atm.Cancel();
        atm.Complete();
        atm.Insert();
        atm.Cancel();
        atm.Complete();
    }

    public static void FileSystem_Node()
    {
        var root = new Folder("root");
        var notes = new Folder("notes");
        var file = new File("draft.txt");
        root.Add(notes);
        notes.Add(file);
        file.Append("hello");
        file.Modify("a", 1);
        file.Rename("lesson.txt");
        file.Open();
        root.Properties();
        notes.Open();
        ShowError(() => notes.Add(root));
        ShowError(() => notes.Add(new File("lesson.txt")));
        ShowError(() => file.Modify("!", 99));
    }

    public static void FacadePatternDemo()
    {
        new HomeTheaterFacade().WatchMovie("Interstellar");
    }
}

interface INotification
{
    void Send(string message);
}

class ChannelNotification : INotification
{
    readonly string channel;
    public ChannelNotification(string channel)
    {
        this.channel = channel;
    }

    public void Send(string message)
    {
        Console.WriteLine($"{channel}: {message}");
    }
}

static class NotificationFactory
{
    static readonly Dictionary<string, INotification> cache = new();
    public static INotification Get(string kind)
    {
        var key = kind.ToUpperInvariant();
        if (cache.TryGetValue(key, out var cached))
            return cached;
        string channel =
            key switch { "SMS" => "SMS", "SLACK" => "Slack", "WHATSAPP" => "WhatsApp",
                         _ => throw new ArgumentException("Unknown notification type") };
        return cache[key] = new ChannelNotification(channel);
    }
}

abstract class Creator
{
    public abstract INotification Create();
    public void Notify()
    {
        Create().Send("message");
    }
}

class SMSFactory : Creator
{
    public override INotification Create() => new ChannelNotification("SMS");
}

class WhatsappFactory : Creator
{
    public override INotification Create() => new ChannelNotification("WhatsApp");
}

class PushFactory : Creator
{
    public override INotification Create() => new ChannelNotification("Push");
}

record Student(string Name, int Age, string Address, double Wallet);
class StudentBuilder
{
    readonly string name;
    int age;
    string address = "";
    double wallet;
    public StudentBuilder(string name)
    {
        this.name = name;
    }

    public StudentBuilder SetAge(int value)
    {
        age = value;
        return this;
    }

    public StudentBuilder SetAddress(string value)
    {
        address = value;
        return this;
    }

    public StudentBuilder SetWallet(double value)
    {
        wallet = value;
        return this;
    }

    public Student Build() => new(name, age, address, wallet);
}

sealed class ConfigurationManager
{
    private ConfigurationManager()
    {
    }

    private static readonly Lazy<ConfigurationManager> instance =
        new(() => new ConfigurationManager());
    public static ConfigurationManager Instance => instance.Value;
}

record UIComponent(string Family, string Kind)
{
    public void Render()
    {
        Console.WriteLine($"{Family} {Kind} rendered");
    }
}

class UIFactory
{
    readonly string family;
    public UIFactory(string family)
    {
        this.family = family;
    }

    public UIComponent Button() => new(family, "button");
    public UIComponent Modal() => new(family, "modal");
    public UIComponent Screen() => new(family, "screen");
}

class UIRender
{
    public UIRender(UIFactory factory)
    {
        Toggle(factory);
    }

    public void Toggle(UIFactory factory)
    {
        factory.Button().Render();
        factory.Modal().Render();
        factory.Screen().Render();
    }
}

abstract class NotificationDecorator : INotification
{
    protected readonly INotification wrapped;
    protected NotificationDecorator(INotification wrapped)
    {
        this.wrapped = wrapped;
    }

    public abstract void Send(string message);
}

class RetryDecorator : NotificationDecorator
{
    public RetryDecorator(INotification wrapped) : base(wrapped)
    {
    }

    public override void Send(string message)
    {
        for (int attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                wrapped.Send(message);
                return;
            }
            catch (InvalidOperationException)
            {
                if (attempt == 2)
                    throw;
            }
        }
    }
}

class FormattingDecorator : NotificationDecorator
{
    public FormattingDecorator(INotification wrapped) : base(wrapped)
    {
    }

    public override void Send(string message)
    {
        wrapped.Send(message.Trim());
    }
}

interface IPaymentProcessor
{
    void Pay(int amount);
}

class LegacyProcessor : IPaymentProcessor
{
    public void Pay(int amount)
    {
        Console.WriteLine($"Legacy payment: {amount}");
    }
}

class RazorPayProcessor
{
    public void MakePayment(int amount)
    {
        Console.WriteLine($"Third-party payment: {amount}");
    }
}

class RazorPayAdapter : IPaymentProcessor
{
    readonly RazorPayProcessor processor;
    public RazorPayAdapter(RazorPayProcessor processor)
    {
        this.processor = processor;
    }

    public void Pay(int amount)
    {
        processor.MakePayment(amount);
    }
}

class PaymentApplication
{
    public IPaymentProcessor Processor { get; set; } = new LegacyProcessor();
    public void Pay(int amount)
    {
        if (amount <= 0)
            throw new ArgumentException("Amount must be positive");
        Processor.Pay(amount);
    }
}

interface IDatabase
{
    string? Read(string key);
    void Write(string key, string value);
}

class MemoryDatabase : IDatabase
{
    readonly Dictionary<string, string> data = new();
    public string? Read(string key)
    {
        Console.WriteLine($"DB read: {key}");
        return data.GetValueOrDefault(key);
    }

    public void Write(string key, string value)
    {
        data[key] = value;
    }
}

class ProxyDatabase : IDatabase
{
    readonly IDatabase database;
    readonly Dictionary<string, string> cache = new();
    public ProxyDatabase(IDatabase database)
    {
        this.database = database;
    }

    public string? Read(string key)
    {
        if (cache.TryGetValue(key, out var cached))
        {
            Console.WriteLine($"Cache hit: {key}");
            return cached;
        }
        var value = database.Read(key);
        if (value != null)
            cache[key] = value;
        return value;
    }

    public void Write(string key, string value)
    {
        database.Write(key, value);
        cache.Remove(key);
    }
}

class Observer
{
    readonly string name;
    public Observer(string name)
    {
        this.name = name;
    }

    public void Notify(int value)
    {
        Console.WriteLine($"{name}: {value}");
    }
}

class Stock
{
    int price;
    readonly List<Observer> observers = new();
    public void Add(Observer observer)
    {
        if (!observers.Contains(observer))
            observers.Add(observer);
    }

    public void Remove(Observer observer)
    {
        observers.Remove(observer);
    }

    public void SetPrice(int value)
    {
        if (value < 0)
            throw new ArgumentException("Price cannot be negative");
        if (value == price)
            return;
        price = value;
        foreach (var observer in observers.ToArray())
            observer.Notify(value);
    }
}

record ElevatorState(int Floor);
class Elevator
{
    readonly List<Action<ElevatorState>> observers = new();
    public void Add(Action<ElevatorState> observer)
    {
        if (!observers.Contains(observer))
            observers.Add(observer);
    }

    public void Remove(Action<ElevatorState> observer)
    {
        observers.Remove(observer);
    }

    public void MoveTo(int floor)
    {
        foreach (var observer in observers.ToArray())
            observer(new ElevatorState(floor));
    }
}

class Server
{
    public string Name { get; }
    public int Connections { get; set; }
    public Server(string name, int connections)
    {
        Name = name;
        Connections = connections;
    }
}

interface IStrategy
{
    Server Select(List<Server> servers);
}

class RoundRobin : IStrategy
{
    int next;
    public Server Select(List<Server> servers)
    {
        var server = servers[next % servers.Count];
        next = (next + 1) % servers.Count;
        return server;
    }
}

class LeastConnections : IStrategy
{
    public Server Select(List<Server> servers) =>
        servers.Aggregate((best, s) => s.Connections < best.Connections ? s : best);
}

class LoadBalancer
{
    readonly List<Server> servers;
    public IStrategy Strategy { get; set; }
    public LoadBalancer(List<Server> servers, IStrategy strategy)
    {
        this.servers = servers;
        Strategy = strategy;
    }

    public Server Send(string request)
    {
        if (servers.Count == 0)
            throw new ArgumentException("No servers available");
        var server = Strategy.Select(servers);
        server.Connections++;
        Console.WriteLine($"{request} -> {server.Name}");
        return server;
    }

    public void Complete(Server server)
    {
        if (server.Connections <= 0)
            throw new ArgumentException("No active request");
        server.Connections--;
    }
}

interface IPlayerState
{
    void Press(Player player);
}

class Paused : IPlayerState
{
    public void Press(Player player)
    {
        Console.WriteLine("Playing");
        player.State = new Playing();
    }
}

class Playing : IPlayerState
{
    public void Press(Player player)
    {
        Console.WriteLine("Paused");
        player.State = new Paused();
    }
}

class Player
{
    public IPlayerState State = new Paused();
    public void Press()
    {
        State.Press(this);
    }
}

abstract class ATMState
{
    protected readonly ATM atm;
    protected ATMState(ATM atm)
    {
        this.atm = atm;
    }

    public abstract void Insert();
    public abstract void Dispense();
    public abstract void Cancel();
    public abstract void Eject();
}

class NoCard : ATMState
{
    public NoCard(ATM atm) : base(atm)
    {
    }

    public override void Insert()
    {
        Console.WriteLine("Card inserted");
        atm.State = atm.HasCard;
    }

    public override void Dispense()
    {
        Console.WriteLine("Insert card first");
    }

    public override void Cancel()
    {
        Console.WriteLine("No transaction");
    }

    public override void Eject()
    {
        Console.WriteLine("No card");
    }
}

class HasCard : ATMState
{
    public HasCard(ATM atm) : base(atm)
    {
    }

    public override void Insert()
    {
        Console.WriteLine("Card already inserted");
    }

    public override void Dispense()
    {
        Console.WriteLine("Dispensing started");
        atm.State = atm.Dispensing;
    }

    public override void Cancel()
    {
        Console.WriteLine("Cancelled");
        Eject();
    }

    public override void Eject()
    {
        Console.WriteLine("Card ejected");
        atm.State = atm.NoCard;
    }
}

class Dispensing : ATMState
{
    public Dispensing(ATM atm) : base(atm)
    {
    }

    public override void Insert()
    {
        Console.WriteLine("Please wait");
    }

    public override void Dispense()
    {
        Console.WriteLine("Already dispensing");
    }

    public override void Cancel()
    {
        Console.WriteLine("Cannot cancel dispensing");
    }

    public override void Eject()
    {
        Console.WriteLine("Please wait");
    }
}

class ATM
{
    public readonly ATMState NoCard, HasCard, Dispensing;
    public ATMState State;
    public ATM()
    {
        NoCard = new NoCard(this);
        HasCard = new HasCard(this);
        Dispensing = new Dispensing(this);
        State = NoCard;
    }

    public void Insert()
    {
        State.Insert();
    }

    public void Dispense()
    {
        State.Dispense();
    }

    public void Cancel()
    {
        State.Cancel();
    }

    public void Eject()
    {
        State.Eject();
    }

    public void Complete()
    {
        if (!ReferenceEquals(State, Dispensing))
        {
            Console.WriteLine("Nothing to complete");
            return;
        }
        Console.WriteLine("Cash dispensed: 100");
        State = HasCard;
        Eject();
    }
}

abstract class Node
{
    public string Name { get; private set; }
    public Folder? Parent;
    protected Node(string name)
    {
        Validate(name);
        Name = name;
    }

    static void Validate(string name)
    {
        if (string.IsNullOrEmpty(name) || name.Contains('/'))
            throw new ArgumentException("Invalid name");
    }

    public abstract int Size();
    public void Rename(string name)
    {
        Validate(name);
        if (name == Name)
            return;
        if (Parent != null)
        {
            if (Parent.Children.ContainsKey(name))
                throw new ArgumentException("Duplicate name");
            Parent.Children.Remove(Name);
            Parent.Children[name] = this;
        }
        Name = name;
    }

    public void Properties()
    {
        Console.WriteLine($"{Name}: {Size()}");
    }
}

class File : Node
{
    string content = "";
    public File(string name) : base(name)
    {
    }

    public override int Size() => content.Length;
    public void Append(string value)
    {
        content += value;
    }

    public void Modify(string value, int offset)
    {
        if (offset < 0 || offset > content.Length)
            throw new ArgumentException("Invalid offset");
        content =
            content[..offset] + value + content[Math.Min(content.Length, offset + value.Length)..];
    }

    public void Open()
    {
        Console.WriteLine(content);
    }
}

class Folder : Node
{
    public readonly Dictionary<string, Node> Children = new();
    public Folder(string name) : base(name)
    {
    }

    public void Add(Node node)
    {
        for (Node? ancestor = this; ancestor != null; ancestor = ancestor.Parent)
            if (ReferenceEquals(ancestor, node))
                throw new ArgumentException("Cycle rejected");
        if (node.Parent != null)
            throw new ArgumentException("Already owned");
        if (Children.ContainsKey(node.Name))
            throw new ArgumentException("Duplicate name");
        Children[node.Name] = node;
        node.Parent = this;
    }

    public override int Size() => Children.Values.Sum(child => child.Size());
    public void Open()
    {
        foreach (var name in Children.Keys)
            Console.WriteLine(name);
    }
}

class TV
{
    public void On()
    {
        Console.WriteLine("TV is ON");
    }

    public void SetInput()
    {
        Console.WriteLine("TV input set to HDMI");
    }
}

class SoundSystem
{
    public void On()
    {
        Console.WriteLine("Sound System is ON");
    }

    public void SetVolume(int v)
    {
        Console.WriteLine($"Volume set to {v}");
    }
}

class StreamingDevice
{
    public void On()
    {
        Console.WriteLine("Streaming Device is ON");
    }

    public void PlayMovie(string movie)
    {
        Console.WriteLine($"Playing movie: {movie}");
    }
}

class HomeTheaterFacade
{
    readonly TV tv = new();
    readonly SoundSystem sound = new();
    readonly StreamingDevice streaming = new();
    public void WatchMovie(string movie)
    {
        Console.WriteLine("Preparing Home Theater...\n");
        tv.On();
        tv.SetInput();
        sound.On();
        sound.SetVolume(20);
        streaming.On();
        streaming.PlayMovie(movie);
        Console.WriteLine("\nEnjoy your movie!");
    }
}
