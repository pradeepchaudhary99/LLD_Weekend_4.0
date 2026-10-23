/*
 * SIMPLE JAVA - Expense sharing
 *
 * Functional requirements (MVP): Split expenses equally; show net balances; settle from a debtor to
 * a creditor. Non-functional requirements: readable, in-memory, safe access within one JVM. Core
 * entities: ExpenseBook owns users and their integer minor-unit net balances. Operations and flow:
 * addExpense divides amount; assigns leftover units in participant order; commits balances. Design
 * choices and concurrency: synchronized makes a whole expense/settlement atomic. Compute on a copy
 * for overflow safety. Assumptions / omitted features: One group; net balances, NOT original
 * pairwise debts. Any debtor may pay any creditor. No percentages, transaction deduplication,
 * history, real transfers or multiple groups. Edge cases to discuss/test: Remainder units,
 * duplicate/unknown participants, over-settlement, concurrent expenses. Run from repository root
 * (Java 17+ and Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleSplitwise Read
 * SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class SimpleSplitwise {
    static class ExpenseBook {
        // Positive: should receive money. Negative: owes money.
        private final Map<String, Long> balances = new HashMap<>();

        ExpenseBook(List<String> users) {
            if (users == null || users.isEmpty()) {
                throw new IllegalArgumentException("Users are required");
            }
            for (String user : users) {
                if (user == null || user.isBlank() || balances.containsKey(user)) {
                    throw new IllegalArgumentException("Invalid/duplicate user");
                }
                balances.put(user, 0L);
            }
        }

        synchronized void addExpense(String payer, long cents, List<String> participants) {
            if (!balances.containsKey(payer) || cents <= 0 || participants == null ||
                participants.isEmpty()) {
                throw new IllegalArgumentException("Invalid expense");
            }
            if (new HashSet<>(participants).size() != participants.size()) {
                throw new IllegalArgumentException("Duplicate participant");
            }
            for (String user : participants) {
                if (!balances.containsKey(user)) {
                    throw new IllegalArgumentException("Unknown participant");
                }
            }
            // Calculate on a copy: an overflow cannot leave half an expense applied.
            Map<String, Long> updated = new HashMap<>(balances);
            updated.put(payer, Math.addExact(updated.get(payer), cents));
            long share = cents / participants.size();
            long remainder = cents % participants.size();
            for (int index = 0; index < participants.size(); index++) {
                String user = participants.get(index);
                long amount = share + (index < remainder ? 1 : 0);
                updated.put(user, Math.subtractExact(updated.get(user), amount));
            }
            balances.putAll(updated);
        }

        synchronized void settle(String debtor, String creditor, long cents) {
            if (cents <= 0 || !balances.containsKey(debtor) || !balances.containsKey(creditor) ||
                balances.get(debtor) > -cents || balances.get(creditor) < cents) {
                throw new IllegalArgumentException("Invalid/too large settlement");
            }
            balances.put(debtor, balances.get(debtor) + cents);
            balances.put(creditor, balances.get(creditor) - cents);
        }

        synchronized long balance(String user) {
            if (!balances.containsKey(user)) {
                throw new IllegalArgumentException("Unknown user");
            }
            return balances.get(user);
        }
    }

    public static void main(String[] args) {
        ExpenseBook book = new ExpenseBook(List.of("Alice", "Bob", "Carol"));
        book.addExpense("Alice", 300, List.of("Alice", "Bob", "Carol"));
        System.out.println("Alice receives: " + book.balance("Alice"));
        System.out.println("Bob balance: " + book.balance("Bob"));
        book.settle("Bob", "Alice", 100);
        System.out.println("Bob after settlement: " + book.balance("Bob"));
    }
}
