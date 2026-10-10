using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;

namespace LLDWeekend4.InterviewQuestions;

public static class NotificationSystemDemo
{
    public sealed record Request(string Id, string User, string Template, string Name,
                                 int Priority);

    public interface INotificationChannel
    {
        bool Send(string id, string message);
    }

    public sealed class FakeChannel : INotificationChannel
    {
        private int failures;
        public readonly List<string> Delivered = new();
        private readonly object gate = new();

        public FakeChannel(int failures = 0)
        {
            this.failures = failures;
        }

        public bool Send(string id, string message)
        {
            lock (gate)
            {
                if (failures > 0)
                {
                    failures--;
                    return false;
                }
                Delivered.Add(id + ":" + message);
                return true;
            }
        }
    }

    private sealed class Delivery
    {
        public readonly Request Request;
        public readonly string Channel;
        public readonly string Message;
        public string Status = "QUEUED";
        public int Attempts;

        public Delivery(Request request, string channel, string message)
        {
            Request = request;
            Channel = channel;
            Message = message;
        }
    }

    public sealed class NotificationService : IDisposable
    {
        private readonly Dictionary<string, INotificationChannel> channels;
        private readonly Dictionary<string, string> templates;
        private readonly Dictionary<string, List<string>> preferences;
        private readonly Dictionary<string, Request> requests = new();
        private readonly Dictionary<string, List<Delivery>> deliveries = new();
        private readonly PriorityQueue<Delivery, (int Priority, long Sequence)> queue = new();
        private readonly List<string> sentOrder = new();
        private readonly object gate = new();
        private readonly Thread worker;
        private bool started;
        private bool closed;
        private long sequence;
        private int active;

        public NotificationService(Dictionary<string, INotificationChannel> channels,
                                   Dictionary<string, string> templates,
                                   Dictionary<string, List<string>> preferences)
        {
            this.channels = new(channels);
            this.templates = new(templates);
            this.preferences =
                preferences.ToDictionary(pair => pair.Key, pair => pair.Value.ToList());
            worker = new Thread(Run) { Name = "notification-worker" };
        }

        public void Submit(Request request)
        {
            lock (gate)
            {
                if (closed || string.IsNullOrWhiteSpace(request.Id) || request.Name == null ||
                    request.Priority < 0 || request.Priority > 2 ||
                    !templates.ContainsKey(request.Template) ||
                    !preferences.ContainsKey(request.User))
                {
                    throw new ArgumentException("Invalid or closed request");
                }
                if (requests.TryGetValue(request.Id, out var existing))
                {
                    if (existing != request)
                    {
                        throw new ArgumentException("Idempotency conflict");
                    }
                    return;
                }
                var selected = preferences[request.User];
                if (selected.Distinct().Count() != selected.Count ||
                    selected.Any(channel => !channels.ContainsKey(channel)))
                {
                    throw new ArgumentException("Invalid channel preference");
                }
                string message = templates[request.Template].Replace("{name}", request.Name);
                var fanout =
                    selected.Select(channel => new Delivery(request, channel, message)).ToList();
                requests[request.Id] = request;
                deliveries[request.Id] = fanout;
                foreach (var delivery in fanout)
                {
                    queue.Enqueue(delivery, (request.Priority, sequence++));
                }
                Monitor.PulseAll(gate);
            }
        }

        public string Status(string id)
        {
            lock (gate)
            {
                if (!deliveries.TryGetValue(id, out var fanout))
                {
                    throw new ArgumentException("Unknown notification");
                }
                if (fanout.Count == 0)
                {
                    return "SKIPPED";
                }
                if (fanout.Any(delivery =>
                                   delivery.Status == "QUEUED" || delivery.Status == "PROCESSING"))
                {
                    return "QUEUED";
                }
                return fanout.Any(delivery => delivery.Status == "FAILED") ? "FAILED" : "SENT";
            }
        }

        public int Attempts(string id, string channel)
        {
            lock (gate)
            {
                Status(id);
                return deliveries[id]
                    .Where(delivery => delivery.Channel == channel)
                    .Sum(delivery => delivery.Attempts);
            }
        }

        public List<string> SentOrder()
        {
            lock (gate)
            {
                return sentOrder.ToList();
            }
        }

        private void StartWorker()
        {
            if (!started)
            {
                started = true;
                worker.Start();
            }
        }

        public void Start()
        {
            lock (gate)
            {
                if (closed)
                {
                    throw new InvalidOperationException("Service closed");
                }
                StartWorker();
            }
        }

        private void Run()
        {
            while (true)
            {
                Delivery delivery;
                lock (gate)
                {
                    while (queue.Count == 0 && !closed)
                    {
                        Monitor.Wait(gate);
                    }
                    if (queue.Count == 0)
                    {
                        return;
                    }
                    delivery = queue.Dequeue();
                    delivery.Status = "PROCESSING";
                    active++;
                }
                bool sent = false;
                int attempts = 0;
                while (!sent && attempts < 3)
                {
                    attempts++;
                    try
                    {
                        sent = channels[delivery.Channel].Send(
                            delivery.Request.Id + "/" + delivery.Channel, delivery.Message);
                    }
                    catch (Exception)
                    {
                        sent = false;
                    }
                }
                lock (gate)
                {
                    delivery.Attempts = attempts;
                    delivery.Status = sent ? "SENT" : "FAILED";
                    if (sent)
                    {
                        sentOrder.Add(delivery.Request.Id + "/" + delivery.Channel);
                    }
                    active--;
                    Monitor.PulseAll(gate);
                }
            }
        }

        public void AwaitIdle()
        {
            if (Thread.CurrentThread == worker)
            {
                throw new InvalidOperationException("Worker cannot wait for itself");
            }
            lock (gate)
            {
                Start();
                while (queue.Count != 0 || active != 0)
                {
                    Monitor.Wait(gate);
                }
            }
        }

        public void Dispose()
        {
            if (Thread.CurrentThread == worker)
            {
                throw new InvalidOperationException("Worker cannot join itself");
            }
            lock (gate)
            {
                closed = true;
                StartWorker();
                Monitor.PulseAll(gate);
            }
            worker.Join();
        }
    }

    public static void Main()
    {
        using var service = new NotificationService(
            new() { ["EMAIL"] = new FakeChannel(), ["SMS"] = new FakeChannel(1),
                    ["PUSH"] = new FakeChannel(9) },
            new() { ["welcome"] = "Hello {name}" },
            new() { ["alice"] = new() { "EMAIL", "SMS" }, ["bob"] = new() { "PUSH" },
                    ["quiet"] = new() });
        var low = new Request("low", "alice", "welcome", "Alice", 2);
        service.Submit(low);
        service.Submit(new Request("high", "alice", "welcome", "Alice", 0));
        service.Submit(low);
        service.Submit(new Request("fail", "bob", "welcome", "Bob", 1));
        service.Submit(new Request("off", "quiet", "welcome", "Quiet", 1));
        service.AwaitIdle();
        Console.WriteLine("Sent: " + string.Join(",", service.SentOrder()));
        Console.WriteLine("High: " + service.Status("high"));
        Console.WriteLine("SMS attempts: " + service.Attempts("high", "SMS"));
        Console.WriteLine("Push: " + service.Status("fail"));
        Console.WriteLine("Opt-out: " + service.Status("off"));
    }
}
