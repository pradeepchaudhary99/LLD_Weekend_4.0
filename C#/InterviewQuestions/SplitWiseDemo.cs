/*
 * INTERVIEW DESIGN NOTES - Splitwise / Expense Sharing
 *
 * Implementation status: runnable in-memory teaching example. Requirements
 * below describe this edition; production extensions are explicitly excluded.
 *
 * Functional requirements:
 * - Register users, create groups and add/remove members.
 * - Add expenses with equal, exact or percentage allocation.
 * - Show bilateral group balances and record settlements.
 * - Keep expense/settlement history and reject duplicate transaction IDs.
 *
 * Non-functional requirements:
 * - Use exact monetary arithmetic; preserve the total across rounding.
 * - Validate a whole transaction before changing balances/history.
 * - Serialize updates in one process and return independent snapshots.
 * - Keep split policy separate from group and balance management.
 *
 * Core entities and responsibilities:
 * - User: registered participant identity; Group: members and group ledger.
 * - Expense: payer, total, participants and computed shares.
 * - Split/SplitStrategy: allocates the total; equal, exact and percentage variants.
 * - Balance manager/service: nets bilateral debt and records settlements/history.
 * - The runnable editions encode some entities as IDs, maps and history entries.
 *
 * Key operations and flow:
 * - expense -> validate group/payer/participants -> choose split rule -> calculate
 * - shares -> validate copied balance updates -> commit balances and history.
 * - settlement -> validate outstanding debt -> reduce the pair balance -> record.
 *
 * Design choices and invariants:
 * - Intended final design uses integer minor units, not floating-point money.
 * - Equal split assigns leftovers in input order; percentage uses largest remainders.
 * - Percentage weights total 10000 basis points; exact shares total the amount.
 * - A owes B is the negative of B owes A; groups maintain separate ledgers.
 * - Leaving a group requires zero debt with every member, not merely net zero.
 *
 * Assumptions and out of scope:
 * - Runnable editions: one currency, <=100 members, expense <=1,000,000,000 units,
 * - and absolute bilateral balance <=1,000,000,000,000 units.
 * - No actual money transfer, authentication, persistence, currency conversion,
 * - expense editing or cross-group/multilateral debt simplification.
 *
 * Interview discussion and checks:
 * - Test rounding, duplicate participants/IDs, sum mismatches and over-settlement.
 * - Test membership rules, concurrent expenses and rollback at the balance bound.
 * - Discuss append-only history and durable atomic transactions.
 *
 * Concurrency in this edition:
 * - Instance-level synchronization protects service/game mutations.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py 'C#' InterviewQuestions/SplitWiseDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

using System;
using System.Collections.Generic;
using System.Linq;

namespace LLDWeekend4.InterviewQuestions;

public static class SplitWiseDemo
{
    private const long MaxAmount = 1_000_000_000;
    private const long MaxBalance = 1_000_000_000_000;

    public static List<long> Split(long amount, int count, string kind, IReadOnlyList<long> values)
    {
        if (kind == "EQUAL")
        {
            if (values.Count != 0)
            {
                throw new ArgumentException("Equal split takes no values");
            }
            return Enumerable.Range(0, count)
                .Select(index => amount / count + (index < amount % count ? 1 : 0))
                .ToList();
        }
        long maximum = kind == "PERCENTAGE" ? 10000 : MaxAmount;
        if (values.Count != count || values.Any(value => value < 0 || value > maximum))
        {
            throw new ArgumentException("Invalid split values");
        }
        if (kind == "EXACT")
        {
            if (values.Sum() != amount)
            {
                throw new ArgumentException("Exact shares must sum to amount");
            }
            return values.ToList();
        }
        if (kind != "PERCENTAGE" || values.Sum() != 10000)
        {
            throw new ArgumentException("Unknown strategy or invalid percentage total");
        }
        List<long> shares = values.Select(value => amount * value / 10000).ToList();
        var order =
            Enumerable.Range(0, count).OrderByDescending(index => amount * values[index] % 10000);
        foreach (int index in order.Take((int)(amount - shares.Sum())))
        {
            shares[index]++;
        }
        return shares;
    }

    private sealed class Group
    {
        public readonly HashSet<string> Members;
        public Dictionary<(string, string), long> Balances = new();
        public readonly Dictionary<string, string> History = new();

        public Group(IEnumerable<string> members)
        {
            Members = new(members);
        }
    }

    public sealed class SplitWise
    {
        private readonly HashSet<string> users = new();
        private readonly Dictionary<string, Group> groups = new();
        private readonly object gate = new();

        private static void ValidId(string id)
        {
            if (string.IsNullOrEmpty(id) ||
                id.Any(c => !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') &&
                            !(c >= '0' && c <= '9') && c != '_' && c != '-'))
            {
                throw new ArgumentException("Invalid ID");
            }
        }

        public void AddUser(string user)
        {
            lock (gate)
            {
                ValidId(user);
                if (!users.Add(user))
                {
                    throw new ArgumentException("Duplicate user");
                }
            }
        }

        public void CreateGroup(string id, IReadOnlyList<string> members)
        {
            lock (gate)
            {
                ValidId(id);
                if (groups.ContainsKey(id) || members.Count == 0 || members.Count > 100 ||
                    members.Distinct().Count() != members.Count ||
                    members.Any(user => !users.Contains(user)))
                {
                    throw new ArgumentException("Invalid group");
                }
                groups[id] = new Group(members);
            }
        }

        private Group GetGroup(string id)
        {
            return groups.TryGetValue(id, out var group)
                       ? group
                       : throw new ArgumentException("Unknown group");
        }

        public void AddMember(string id, string user)
        {
            lock (gate)
            {
                Group group = GetGroup(id);
                if (!users.Contains(user) || group.Members.Count >= 100 || !group.Members.Add(user))
                {
                    throw new ArgumentException("Invalid member");
                }
            }
        }

        public void RemoveMember(string id, string user)
        {
            lock (gate)
            {
                Group group = GetGroup(id);
                if (!group.Members.Contains(user))
                {
                    throw new ArgumentException("Unknown member");
                }
                if (group.Members.Any(other => Balance(id, user, other) != 0))
                {
                    throw new ArgumentException("Settle balances before leaving");
                }
                group.Members.Remove(user);
            }
        }

        private static (string, string) Key(string left, string right)
        {
            return string.CompareOrdinal(left, right) < 0 ? (left, right) : (right, left);
        }

        public long Balance(string id, string debtor, string creditor)
        {
            lock (gate)
            {
                Group group = GetGroup(id);
                if (!group.Members.Contains(debtor) || !group.Members.Contains(creditor))
                {
                    throw new ArgumentException("Unknown member");
                }
                long amount = group.Balances.GetValueOrDefault(Key(debtor, creditor));
                return string.CompareOrdinal(debtor, creditor) < 0 ? amount : -amount;
            }
        }

        private static void Change(Dictionary<(string, string), long> balances, string debtor,
                                   string creditor, long amount)
        {
            if (debtor == creditor)
            {
                return;
            }
            var key = Key(debtor, creditor);
            long updated = balances.GetValueOrDefault(key) +
                           (string.CompareOrdinal(debtor, creditor) < 0 ? amount : -amount);
            if (Math.Abs(updated) > MaxBalance)
            {
                throw new ArgumentException("Balance limit exceeded");
            }
            balances[key] = updated;
        }

        private static void Transaction(Group group, string id, long amount)
        {
            ValidId(id);
            if (amount <= 0 || amount > MaxAmount || group.History.ContainsKey(id))
            {
                throw new ArgumentException("Invalid amount or duplicate transaction");
            }
        }

        public List<long> AddExpense(string id, string expense, string payer, long amount,
                                     IReadOnlyList<string> participants, string kind,
                                     IReadOnlyList<long> values)
        {
            lock (gate)
            {
                Group group = GetGroup(id);
                Transaction(group, expense, amount);
                if (participants.Count == 0 || participants.Count > 100 ||
                    participants.Distinct().Count() != participants.Count ||
                    !group.Members.Contains(payer) ||
                    participants.Any(user => !group.Members.Contains(user)))
                {
                    throw new ArgumentException("Invalid participants");
                }
                List<long> shares = Split(amount, participants.Count, kind, values);
                var updated = new Dictionary<(string, string), long>(group.Balances);
                for (int index = 0; index < participants.Count; index++)
                {
                    Change(updated, participants[index], payer, shares[index]);
                }
                group.Balances = updated;
                group.History[expense] =
                    $"EXPENSE {expense} {payer} {amount} {kind} [{string.Join(",", participants)}] [{string.Join(",", shares)}]";
                return shares.ToList();
            }
        }

        public void Settle(string id, string transaction, string debtor, string creditor,
                           long amount)
        {
            lock (gate)
            {
                Group group = GetGroup(id);
                Transaction(group, transaction, amount);
                if (debtor == creditor || Balance(id, debtor, creditor) < amount)
                {
                    throw new ArgumentException("Settlement exceeds debt");
                }
                var updated = new Dictionary<(string, string), long>(group.Balances);
                Change(updated, debtor, creditor, -amount);
                group.Balances = updated;
                group.History[transaction] = $"SETTLE {transaction} {debtor} {creditor} {amount}";
            }
        }

        public List<string> History(string id)
        {
            lock (gate)
            {
                return GetGroup(id).History.Values.ToList();
            }
        }
    }

    public static void Main()
    {
        var service = new SplitWise();
        string[] members = { "A", "B", "C" };
        foreach (string user in members)
        {
            service.AddUser(user);
        }
        service.CreateGroup("trip", members);
        var shares =
            service.AddExpense("trip", "dinner", "A", 100, members, "EQUAL", Array.Empty<long>());
        Console.WriteLine("Equal shares: " + string.Join(",", shares));
        service.AddExpense("trip", "taxi", "B", 60, new[] { "A", "B" }, "EXACT",
                           new long[] { 20, 40 });
        service.AddExpense("trip", "tea", "C", 101, members, "PERCENTAGE",
                           new long[] { 5000, 2500, 2500 });
        Console.WriteLine("B owes A: " + service.Balance("trip", "B", "A"));
        Console.WriteLine("A owes C: " + service.Balance("trip", "A", "C"));
        service.Settle("trip", "payment", "B", "A", 13);
        Console.WriteLine("After settlement: " + service.Balance("trip", "B", "A"));
        Console.WriteLine("History entries: " + service.History("trip").Count);
    }
}
