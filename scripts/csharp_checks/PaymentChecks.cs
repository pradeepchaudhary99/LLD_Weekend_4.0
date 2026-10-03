using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using static LLDWeekend4.InterviewQuestions.PaymentServiceLLD;
public class PaymentChecks
{
    static void check(bool condition)
    {
        if (!condition)
        {
            throw new Exception("Payment invariant violated");
        }
    }

    static void rejects(Action action)
    {
        bool rejected = false;
        try
        {
            action();
        }
        catch (ArgumentException)
        {
            rejected = true;
        }
        check(rejected);
    }

    sealed class UnknownGateway : Gateway
    {
        public int attempts;
        public bool allowRefund;

        public Outcome Charge(string id, Request request)
        {
            attempts++;
            return Outcome.UNKNOWN;
        }

        public bool Refund(string id)
        {
            return allowRefund;
        }
    }

    public static void Main(string[] args)
    {
        Request request = new Request("order", 100, Method.CARD, "stripe");
        FakeGateway gateway = new FakeGateway(true, false);
        PaymentService service =
            new PaymentService(new Dictionary<string, Gateway> { ["stripe"] = gateway });
        Parallel.For(0, 100,
                     index =>
                     { check(service.Pay("same-key", request).Status == Status.SUCCESS); });
        check(gateway.Charges() == 1);
        rejects(() => service.Pay("same-key", new Request("order", 101, Method.CARD, "stripe")));
        rejects(() => service.Pay(" ", request));
        rejects(() => new Request("order", 0, Method.CARD, "stripe"));
        rejects(() => service.Pay("unknown", new Request("order", 100, Method.UPI, "missing")));
        Payment snapshot = service.Pay("same-key", request);
        check(service.Refund("same-key").Status == Status.REFUNDED);
        check(service.Refund("same-key").Status == Status.REFUNDED);
        check(snapshot.Status == Status.SUCCESS);
        check(service.Webhook("late", "stripe", "same-key", Status.FAILED).Status ==
              Status.REFUNDED);
        rejects(() => service.Webhook("wrong", "paypal", "same-key", Status.SUCCESS));
        rejects(() => service.Refund("missing"));
        UnknownGateway unknown = new UnknownGateway();
        PaymentService pending =
            new PaymentService(new Dictionary<string, Gateway> { ["stripe"] = unknown });
        check(pending.Pay("pending", request).Status == Status.PROCESSING);
        check(unknown.attempts == 3);
        check(pending.Pay("pending", request).Status == Status.PROCESSING);
        check(unknown.attempts == 3);
        rejects(() => pending.Refund("pending"));
        check(pending.Webhook("event", "stripe", "pending", Status.SUCCESS).Status ==
              Status.SUCCESS);
        check(pending.Webhook("event", "stripe", "pending", Status.FAILED).Status ==
              Status.SUCCESS);
        bool retryable = false;
        try
        {
            pending.Refund("pending");
        }
        catch (InvalidOperationException)
        {
            retryable = true;
        }
        check(retryable);
        check(pending.Pay("pending", request).Status == Status.SUCCESS);
        unknown.allowRefund = true;
        check(pending.Refund("pending").Status == Status.REFUNDED);
        PaymentService declined = new PaymentService(
            new Dictionary<string, Gateway> { ["stripe"] = new FakeGateway(false, true) });
        check(declined.Pay("declined", request).Status == Status.FAILED);
        rejects(() => declined.Refund("declined"));
        foreach (Method method in Enum.GetValues<Method>())
        {
            check(service.Pay(method.ToString(), new Request("order", 100, method, "stripe"))
                      .Status == Status.SUCCESS);
        }
        Console.WriteLine("PASS C# payment edge cases and concurrent idempotency");
    }
}
