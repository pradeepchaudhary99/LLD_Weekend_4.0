/*
 * INTERVIEW DESIGN NOTES - Splitwise / Expense Sharing
 *
 * Implementation status: runnable in-memory teaching example. The unfinished
 * classroom sketch is preserved in classroom-drafts/2026-10-11/.
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
 * - Synchronized service operations serialize in-memory ledger updates.
 *
 * Run / validation:
 * - From repository root: python3 scripts/run.py Java InterviewQuestions/SplitWiseDemo
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single-currency, in-memory group ledger. All amounts are integer minor units. */
public class SplitWiseDemo {
    static final long MAX_AMOUNT = 1_000_000_000L;
    static final long MAX_BALANCE = 1_000_000_000_000L;

    interface SplitStrategy {
        List<Long> calculate(long amount, int count, List<Long> values);
    }

    static class EqualSplit implements SplitStrategy {
        public List<Long> calculate(long amount, int count, List<Long> values) {
            if (!values.isEmpty()) {
                throw new IllegalArgumentException("Equal split takes no values");
            }
            List<Long> shares = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                shares.add(amount / count + (index < amount % count ? 1 : 0));
            }
            return shares;
        }
    }

    static class ExactSplit implements SplitStrategy {
        public List<Long> calculate(long amount, int count, List<Long> values) {
            validateValues(values, count, MAX_AMOUNT);
            if (values.stream().mapToLong(Long::longValue).sum() != amount) {
                throw new IllegalArgumentException("Exact shares must sum to amount");
            }
            return List.copyOf(values);
        }
    }

    static class PercentageSplit implements SplitStrategy {
        public List<Long> calculate(long amount, int count, List<Long> values) {
            validateValues(values, count, 10000);
            if (values.stream().mapToLong(Long::longValue).sum() != 10000) {
                throw new IllegalArgumentException("Percentages must total 10000 basis points");
            }
            List<Long> shares = new ArrayList<>();
            List<Integer> order = new ArrayList<>();
            long allocated = 0;
            for (int index = 0; index < count; index++) {
                long share = amount * values.get(index) / 10000;
                shares.add(share);
                allocated += share;
                order.add(index);
            }
            // Largest fractional remainder wins; input order breaks ties.
            order.sort((left, right)
                           -> Long.compare(amount * values.get(right) % 10000,
                                           amount * values.get(left) % 10000));
            for (int index = 0; index < amount - allocated; index++) {
                int participant = order.get(index);
                shares.set(participant, shares.get(participant) + 1);
            }
            return shares;
        }
    }

    static void validateValues(List<Long> values, int count, long maximum) {
        if (values.size() != count ||
            values.stream().anyMatch(value -> value == null || value < 0 || value > maximum)) {
            throw new IllegalArgumentException("Invalid split values");
        }
    }

    static SplitStrategy strategy(String kind) {
        return switch (kind) {
            case "EQUAL" -> new EqualSplit();
            case "EXACT" -> new ExactSplit();
            case "PERCENTAGE" -> new PercentageSplit();
            default -> throw new IllegalArgumentException("Unknown split strategy");
        };
    }

    static class Group {
        final Set<String> members;
        Map<String, Long> balances = new HashMap<>();
        final Map<String, String> history = new LinkedHashMap<>();

        Group(List<String> members) {
            this.members = new HashSet<>(members);
        }
    }

    static class SplitWise {
        private final Set<String> users = new HashSet<>();
        private final Map<String, Group> groups = new HashMap<>();

        private void validId(String id) {
            if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid ID");
            }
        }

        synchronized void addUser(String user) {
            validId(user);
            if (!users.add(user)) {
                throw new IllegalArgumentException("Duplicate user");
            }
        }

        synchronized void createGroup(String id, List<String> members) {
            validId(id);
            if (groups.containsKey(id) || members.isEmpty() || members.size() > 100 ||
                new HashSet<>(members).size() != members.size() || !users.containsAll(members)) {
                throw new IllegalArgumentException("Invalid group");
            }
            groups.put(id, new Group(members));
        }

        private Group group(String id) {
            Group group = groups.get(id);
            if (group == null) {
                throw new IllegalArgumentException("Unknown group");
            }
            return group;
        }

        synchronized void addMember(String id, String user) {
            Group group = group(id);
            if (!users.contains(user) || group.members.size() >= 100 || !group.members.add(user)) {
                throw new IllegalArgumentException("Invalid member");
            }
        }

        synchronized void removeMember(String id, String user) {
            Group group = group(id);
            if (!group.members.contains(user)) {
                throw new IllegalArgumentException("Unknown member");
            }
            for (String other : group.members) {
                if (balance(id, user, other) != 0) {
                    throw new IllegalArgumentException("Settle balances before leaving");
                }
            }
            group.members.remove(user);
        }

        private String key(String left, String right) {
            return left.compareTo(right) < 0 ? left + ">" + right : right + ">" + left;
        }

        synchronized long balance(String id, String debtor, String creditor) {
            Group group = group(id);
            if (!group.members.contains(debtor) || !group.members.contains(creditor)) {
                throw new IllegalArgumentException("Unknown member");
            }
            long amount = group.balances.getOrDefault(key(debtor, creditor), 0L);
            return debtor.compareTo(creditor) < 0 ? amount : -amount;
        }

        private void change(Map<String, Long> balances, String debtor, String creditor,
                            long amount) {
            if (debtor.equals(creditor)) {
                return;
            }
            String key = key(debtor, creditor);
            long updated = balances.getOrDefault(key, 0L) +
                           (debtor.compareTo(creditor) < 0 ? amount : -amount);
            if (Math.abs(updated) > MAX_BALANCE) {
                throw new IllegalArgumentException("Balance limit exceeded");
            }
            balances.put(key, updated);
        }

        private void transaction(Group group, String id, long amount) {
            validId(id);
            if (amount <= 0 || amount > MAX_AMOUNT || group.history.containsKey(id)) {
                throw new IllegalArgumentException("Invalid amount or duplicate transaction");
            }
        }

        synchronized List<Long> addExpense(String id, String expense, String payer, long amount,
                                           List<String> participants, String kind,
                                           List<Long> values) {
            Group group = group(id);
            transaction(group, expense, amount);
            if (participants.isEmpty() || participants.size() > 100 ||
                new HashSet<>(participants).size() != participants.size() ||
                !group.members.contains(payer) || !group.members.containsAll(participants)) {
                throw new IllegalArgumentException("Invalid participants");
            }
            List<Long> shares = strategy(kind).calculate(amount, participants.size(), values);
            Map<String, Long> updated = new HashMap<>(group.balances);
            for (int index = 0; index < participants.size(); index++) {
                change(updated, participants.get(index), payer, shares.get(index));
            }
            // Validate everything on a copy, then commit the ledger and history together.
            group.balances = updated;
            group.history.put(expense, "EXPENSE " + expense + " " + payer + " " + amount + " " +
                                           kind + " " + participants + " " + shares);
            return List.copyOf(shares);
        }

        synchronized void settle(String id, String transaction, String debtor, String creditor,
                                 long amount) {
            Group group = group(id);
            transaction(group, transaction, amount);
            if (debtor.equals(creditor) || balance(id, debtor, creditor) < amount) {
                throw new IllegalArgumentException("Settlement exceeds debt");
            }
            Map<String, Long> updated = new HashMap<>(group.balances);
            change(updated, debtor, creditor, -amount);
            group.balances = updated;
            group.history.put(transaction, "SETTLE " + transaction + " " + debtor + " " + creditor +
                                               " " + amount);
        }

        synchronized List<String> history(String id) {
            return List.copyOf(group(id).history.values());
        }
    }

    public static void main(String[] args) {
        SplitWise service = new SplitWise();
        for (String user : List.of("A", "B", "C")) {
            service.addUser(user);
        }
        List<String> members = List.of("A", "B", "C");
        service.createGroup("trip", members);
        List<Long> shares =
            service.addExpense("trip", "dinner", "A", 100, members, "EQUAL", List.of());
        System.out.println("Equal shares: " + shares.get(0) + "," + shares.get(1) + "," +
                           shares.get(2));
        service.addExpense("trip", "taxi", "B", 60, List.of("A", "B"), "EXACT", List.of(20L, 40L));
        service.addExpense("trip", "tea", "C", 101, members, "PERCENTAGE",
                           List.of(5000L, 2500L, 2500L));
        System.out.println("B owes A: " + service.balance("trip", "B", "A"));
        System.out.println("A owes C: " + service.balance("trip", "A", "C"));
        service.settle("trip", "payment", "B", "A", 13);
        System.out.println("After settlement: " + service.balance("trip", "B", "A"));
        System.out.println("History entries: " + service.history("trip").size());
    }
}
