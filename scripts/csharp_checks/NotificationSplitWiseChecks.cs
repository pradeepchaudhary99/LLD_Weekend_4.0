using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using static LLDWeekend4.InterviewQuestions.NotificationSystemDemo;
using static LLDWeekend4.InterviewQuestions.SplitWiseDemo;
public class NotificationSplitWiseChecks
{
    static void check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Lesson invariant failed");
        }
    }

    static void rejects(Action action)
    {
        try
        {
            action();
        }
        catch (Exception error)
            when (error is ArgumentException || error is InvalidOperationException)
        {
            return;
        }
        throw new Exception("Invalid operation accepted");
    }

    static void ledger()
    {
        SplitWise service = new SplitWise();
        string[] members = new string[] { "A", "B", "C" };
        foreach (string user in members)
        {
            service.AddUser(user);
        }
        service.AddUser("D");
        service.CreateGroup("g", members);
        service.CreateGroup("other", members);
        check(service.AddExpense("g", "equal", "A", 2, members, "EQUAL", Array.Empty<long>())
                  .SequenceEqual(new long[] { 1L, 1L, 0L }));
        check(service
                  .AddExpense("g", "percent", "B", 7, members, "PERCENTAGE",
                              new long[] { 5000L, 2500L, 2500L })
                  .SequenceEqual(new long[] { 3L, 2L, 2L }));
        check(service
                  .AddExpense("g", "zero", "C", 1, members, "PERCENTAGE",
                              new long[] { 0L, 0L, 10000L })
                  .SequenceEqual(new long[] { 0L, 0L, 1L }));
        check(service.Balance("g", "A", "B") == 2);
        check(service.Balance("g", "B", "A") == -2);
        check(service.Balance("other", "A", "B") == 0);
        rejects(() => service.AddExpense("g", "bad", "A", 10, members, "EXACT",
                                         new long[] { 2L, 3L, 4L }));
        rejects(() => service.AddExpense("g", "bad", "A", 10, members, "PERCENTAGE",
                                         new long[] { 5000L, 2000L, 2000L }));
        rejects(() => service.AddExpense("g", "bad", "A", 10, new string[] { "A", "A" }, "EQUAL",
                                         Array.Empty<long>()));
        rejects(() =>
                    service.AddExpense("g", "bad", "D", 10, members, "EQUAL", Array.Empty<long>()));
        rejects(
            () => service.AddExpense("g", "equal", "A", 10, members, "EQUAL", Array.Empty<long>()));
        rejects(() =>
                    service.AddExpense("g", "bad", "A", 0, members, "EQUAL", Array.Empty<long>()));
        rejects(() => service.AddExpense("g", "bad", "A", 1000000001L, members, "EQUAL",
                                         Array.Empty<long>()));
        rejects(() => service.Settle("g", "bad", "A", "B", 3));
        rejects(() => service.RemoveMember("g", "A"));
        check(service.History("g").Count == 3);
        check(service.Balance("g", "A", "B") == 2);
        service.Settle("g", "settle", "A", "B", 2);
        check(service.Balance("g", "A", "B") == 0);
        service.RemoveMember("g", "A");
        service.AddMember("g", "A");
        service.AddMember("g", "D");
        service.RemoveMember("g", "D");
        rejects(() => service.Settle("g", "settle", "C", "B", 1));
        check(service.History("g").Count == 4);
        Parallel.For(0, 100,
                     index =>
                     {
                         service.AddExpense("other", "parallel-" + index, "A", 10, new[] { "B" },
                                            "EXACT", new long[] { 10 });
                     });
        check(service.Balance("other", "B", "A") == 1000);
        check(service.History("other").Count == 100);
        // Hitting the documented balance bound must reject the entire transaction.
        for (int index = 0; index < 1000; index++)
        {
            service.AddExpense("g", "limit-" + index, "A", 1000000000L, new string[] { "B" },
                               "EXACT", new long[] { 1000000000L });
        }
        rejects(() => service.AddExpense("g", "overflow", "A", 2, new string[] { "C", "B" },
                                         "EXACT", new long[] { 1L, 1L }));
        check(service.Balance("g", "C", "A") == 0);
        check(service.History("g").Count == 1004);
    }

    static void notifications()
    {
        FakeChannel email = new FakeChannel(0);
        FakeChannel sms = new FakeChannel(2);
        FakeChannel push = new FakeChannel(10);
        using (NotificationService service = new NotificationService(
                   new() { ["EMAIL"] = email, ["SMS"] = sms, ["PUSH"] = push },
                   new() { ["t"] = "Hello {name}" },
                   new() { ["u"] = new() { "EMAIL", "SMS" }, ["bad"] = new() { "PUSH" },
                           ["off"] = new() }))
        {
            Request low = new Request("low", "u", "t", "Ada", 2);
            service.Submit(low);
            service.Submit(new Request("high", "u", "t", "Ada", 0));
            service.Submit(low);
            service.Submit(new Request("fail", "bad", "t", "Ada", 1));
            service.Submit(new Request("skip", "off", "t", "Ada", 1));
            check(service.Status("low").Equals("QUEUED"));
            check(service.Status("skip").Equals("SKIPPED"));
            rejects(() => service.Submit(new Request("low", "u", "t", "changed", 2)));
            rejects(() => service.Submit(new Request("missing", "u", "missing", "Ada", 1)));
            rejects(() => service.Submit(new Request("invalid", "u", "t", "Ada", 3)));
            rejects(() => service.Status("missing"));
            service.AwaitIdle();
            check(service.SentOrder().SequenceEqual(
                new string[] { "high/EMAIL", "high/SMS", "low/EMAIL", "low/SMS" }));
            check(service.Attempts("high", "SMS") == 3);
            check(service.Attempts("low", "EMAIL") == 1);
            check(service.Attempts("fail", "PUSH") == 3);
            check(service.Status("fail").Equals("FAILED"));
            check(email.Delivered[0].Equals("high/EMAIL:Hello Ada"));
            Parallel.For(0, 100,
                         index =>
                         { service.Submit(new Request("same", "u", "t", "Ada", 1)); });
            service.AwaitIdle();
            check(service.Attempts("same", "EMAIL") == 1);
            check(service.SentOrder().Count == 6);
        }
        NotificationService drain =
            new NotificationService(new() { ["EMAIL"] = email }, new() { ["t"] = "{name}" },
                                    new() { ["u"] = new() { "EMAIL" } });
        drain.Submit(new Request("drain", "u", "t", "Ada", 1));
        drain.Dispose();
        drain.Dispose();
        check(drain.Status("drain").Equals("SENT"));
        rejects(() => drain.Submit(new Request("late", "u", "t", "Ada", 1)));
        rejects(drain.Start);
    }

    public static void Main(string[] args)
    {
        ledger();
        notifications();
        Console.WriteLine("C# notification and Splitwise checks passed");
    }
}
