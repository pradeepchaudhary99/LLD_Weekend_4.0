using System;
using System.Collections.Generic;
namespace LLDWeekend4.InterviewQuestions;

/** In-memory payment orchestration with fake gateways, never real money. */
public class PaymentServiceLLD
{
    public enum Method
    {
        CARD,
        UPI,
        NET_BANKING,
        WALLET
    }
    public enum Status
    {
        PROCESSING,
        SUCCESS,
        FAILED,
        REFUNDED
    }
    public enum Outcome
    {
        SUCCESS,
        DECLINED,
        UNKNOWN
    }

    public sealed record Request
    {
        public string Order { get; }
        public long MinorUnits { get; }
        public Method Method { get; }
        public string Gateway { get; }

        public Request(string order, long minorUnits, Method method, string gateway)
        {
            if (string.IsNullOrWhiteSpace(order) || minorUnits <= 0 || !Enum.IsDefined(method) ||
                string.IsNullOrWhiteSpace(gateway))
            {
                throw new ArgumentException("Invalid payment request");
            }
            Order = order;
            MinorUnits = minorUnits;
            Method = method;
            Gateway = gateway;
        }
    }

    public sealed record Payment(string Id, Request Request, Status Status);

    public interface Gateway
    {
        Outcome Charge(string paymentId, Request request);
        bool Refund(string paymentId);
    }

    public sealed class FakeGateway : Gateway
    {
        private readonly object gate = new();
        private readonly HashSet<string> charged = new();
        private readonly HashSet<string> refunded = new();
        private bool loseFirstResponse;
        private readonly bool decline;

        public FakeGateway(bool loseFirstResponse, bool decline)
        {
            this.loseFirstResponse = loseFirstResponse;
            this.decline = decline;
        }

        public Outcome Charge(string paymentId, Request request)
        {
            lock (gate)
            {
                if (decline)
                {
                    return Outcome.DECLINED;
                }
                // Gateway idempotency also protects retries after an ambiguous timeout.
                charged.Add(paymentId);
                if (loseFirstResponse)
                {
                    loseFirstResponse = false;
                    return Outcome.UNKNOWN;
                }
                return Outcome.SUCCESS;
            }
        }

        public bool Refund(string paymentId)
        {
            lock (gate)
            {
                if (!charged.Contains(paymentId))
                {
                    return false;
                }
                refunded.Add(paymentId);
                return true;
            }
        }

        public int Charges()
        {
            lock (gate)
            {
                return charged.Count;
            }
        }
    }

    public sealed class PaymentService
    {
        private readonly object gate = new();
        private readonly Dictionary<string, Gateway> gateways;
        private readonly Dictionary<string, Payment> payments = new();
        private readonly HashSet<(string Gateway, string Event)> events = new();

        public PaymentService(Dictionary<string, Gateway> gateways)
        {
            this.gateways = new(gateways);
        }

        public Payment Pay(string key, Request request)
        {
            lock (gate)
            {
                if (key == null || key.Trim().Length == 0 || request == null ||
                    !gateways.ContainsKey(request.Gateway))
                {
                    throw new ArgumentException("Invalid key or gateway");
                }
                Payment? existing = payments.GetValueOrDefault(key);
                if (existing != null)
                {
                    if (!existing.Request.Equals(request))
                    {
                        throw new ArgumentException("Idempotency conflict");
                    }
                    return existing;
                }
                Payment payment = new Payment(key, request, Status.PROCESSING);
                payments[key] = payment;
                for (int attempt = 0; attempt < 3; attempt++)
                {
                    Outcome outcome = gateways[request.Gateway].Charge(key, request);
                    if (outcome != Outcome.UNKNOWN)
                    {
                        payment = new Payment(key, request,
                                              outcome == Outcome.SUCCESS ? Status.SUCCESS
                                                                         : Status.FAILED);
                        payments[key] = payment;
                        break;
                    }
                }
                // UNKNOWN remains PROCESSING until a trusted reconciliation event arrives.
                return payment;
            }
        }

        public Payment Refund(string key)
        {
            lock (gate)
            {
                Payment payment = RequirePayment(key);
                if (payment.Status == Status.REFUNDED)
                {
                    return payment;
                }
                if (payment.Status != Status.SUCCESS)
                {
                    throw new ArgumentException("Only successful payments can be refunded");
                }
                if (!gateways[payment.Request.Gateway].Refund(key))
                {
                    throw new InvalidOperationException(
                        "Refund pending; retry with the same payment ID");
                }
                Payment updated = new Payment(key, payment.Request, Status.REFUNDED);
                payments[key] = updated;
                return updated;
            }
        }

        // Called AFTER provider authentication. No public network endpoint in this lesson.
        public Payment Webhook(string eventId, string gateway, string key, Status status)
        {
            lock (gate)
            {
                Payment payment = RequirePayment(key);
                if (eventId == null || eventId.Trim().Length == 0 ||
                    !payment.Request.Gateway.Equals(gateway) ||
                    (status != Status.SUCCESS && status != Status.FAILED))
                {
                    throw new ArgumentException("Invalid webhook");
                }
                if (!events.Add((gateway, eventId)))
                {
                    return payment;
                }
                if (payment.Status == Status.PROCESSING)
                {
                    payment = new Payment(key, payment.Request, status);
                    payments[key] = payment;
                }
                return payment;
            }
        }

        private Payment RequirePayment(string key)
        {
            Payment? payment = payments.GetValueOrDefault(key);
            if (payment == null)
            {
                throw new ArgumentException("Unknown payment");
            }
            return payment;
        }
    }

    public static void Main(string[] args)
    {
        FakeGateway gateway = new FakeGateway(true, false);
        PaymentService service =
            new PaymentService(new Dictionary<string, Gateway> { ["stripe"] = gateway });
        Request request = new Request("order-1", 1699900, Method.CARD, "stripe");
        Console.WriteLine("Payment: " + service.Pay("key-1", request).Status);
        service.Pay("key-1", request);
        Console.WriteLine("Gateway charges: " + gateway.Charges());
        Console.WriteLine("Refund: " + service.Refund("key-1").Status);
        Console.WriteLine("Late webhook: " +
                          service.Webhook("event-1", "stripe", "key-1", Status.SUCCESS).Status);
    }
}
