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
 *   python3 scripts/run.py 'C++' InterviewQuestions/SplitWiseDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

#include <algorithm>
#include <iostream>
#include <map>
#include <mutex>
#include <numeric>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace splitwise {
constexpr long long maxAmount = 1000000000;
constexpr long long maxBalance = 1000000000000;

std::vector<long long> split(long long amount, int count, const std::string &kind,
                             const std::vector<long long> &values) {
    if (kind == "EQUAL") {
        if (!values.empty()) {
            throw std::invalid_argument("Equal split takes no values");
        }
        std::vector<long long> shares(count, amount / count);
        for (int index = 0; index < amount % count; index++) {
            shares[index]++;
        }
        return shares;
    }
    auto maximum = kind == "PERCENTAGE" ? 10000 : maxAmount;
    if (values.size() != static_cast<size_t>(count) ||
        std::any_of(values.begin(), values.end(), [maximum](auto value) {
            return value < 0 || value > maximum;
        })) {
        throw std::invalid_argument("Invalid split values");
    }
    auto total = std::accumulate(values.begin(), values.end(), 0LL);
    if (kind == "EXACT") {
        if (total != amount) {
            throw std::invalid_argument("Exact shares must sum to amount");
        }
        return values;
    }
    if (kind != "PERCENTAGE" || total != 10000) {
        throw std::invalid_argument("Unknown strategy or invalid percentage total");
    }
    std::vector<long long> shares;
    std::vector<int> order;
    for (int index = 0; index < count; index++) {
        shares.push_back(amount * values[index] / 10000);
        order.push_back(index);
    }
    std::stable_sort(order.begin(), order.end(), [&](int left, int right) {
        return amount * values[left] % 10000 > amount * values[right] % 10000;
    });
    auto remainder = amount - std::accumulate(shares.begin(), shares.end(), 0LL);
    for (int index = 0; index < remainder; index++) {
        shares[order[index]]++;
    }
    return shares;
}

struct Group {
    std::set<std::string> members;
    std::map<std::pair<std::string, std::string>, long long> balances;
    std::vector<std::string> history;
    std::set<std::string> transactions;
};

class SplitWise {
    std::set<std::string> users;
    std::map<std::string, Group> groups;
    std::recursive_mutex gate;

    static void validId(const std::string &id) {
        if (id.empty() || id.find_first_not_of(
                              "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-") !=
                              std::string::npos) {
            throw std::invalid_argument("Invalid ID");
        }
    }

    Group &group(const std::string &id) {
        auto found = groups.find(id);
        if (found == groups.end()) {
            throw std::invalid_argument("Unknown group");
        }
        return found->second;
    }

    static std::pair<std::string, std::string> key(const std::string &left,
                                                   const std::string &right) {
        return left < right ? std::make_pair(left, right) : std::make_pair(right, left);
    }

    static void change(std::map<std::pair<std::string, std::string>, long long> &balances,
                       const std::string &debtor, const std::string &creditor, long long amount) {
        if (debtor == creditor) {
            return;
        }
        auto pair = key(debtor, creditor);
        auto updated = balances[pair] + (debtor < creditor ? amount : -amount);
        if (updated > maxBalance || updated < -maxBalance) {
            throw std::invalid_argument("Balance limit exceeded");
        }
        balances[pair] = updated;
    }

    static void transaction(const Group &group, const std::string &id, long long amount) {
        validId(id);
        if (amount <= 0 || amount > maxAmount || group.transactions.count(id)) {
            throw std::invalid_argument("Invalid amount or duplicate transaction");
        }
    }

  public:
    void addUser(const std::string &user) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        validId(user);
        if (!users.insert(user).second) {
            throw std::invalid_argument("Duplicate user");
        }
    }

    void createGroup(const std::string &id, const std::vector<std::string> &members) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        validId(id);
        std::set<std::string> unique(members.begin(), members.end());
        if (groups.count(id) || members.empty() || members.size() > 100 ||
            unique.size() != members.size() ||
            std::any_of(members.begin(), members.end(), [&](const auto &user) {
                return !users.count(user);
            })) {
            throw std::invalid_argument("Invalid group");
        }
        groups.emplace(id, Group{unique, {}, {}, {}});
    }

    void addMember(const std::string &id, const std::string &user) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        auto &selected = group(id);
        if (!users.count(user) || selected.members.size() >= 100 ||
            !selected.members.insert(user).second) {
            throw std::invalid_argument("Invalid member");
        }
    }

    void removeMember(const std::string &id, const std::string &user) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        auto &selected = group(id);
        if (!selected.members.count(user)) {
            throw std::invalid_argument("Unknown member");
        }
        for (const auto &other : selected.members) {
            if (balance(id, user, other) != 0) {
                throw std::invalid_argument("Settle balances before leaving");
            }
        }
        selected.members.erase(user);
    }

    long long balance(const std::string &id, const std::string &debtor,
                      const std::string &creditor) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        auto &selected = group(id);
        if (!selected.members.count(debtor) || !selected.members.count(creditor)) {
            throw std::invalid_argument("Unknown member");
        }
        auto found = selected.balances.find(key(debtor, creditor));
        auto amount = found == selected.balances.end() ? 0 : found->second;
        return debtor < creditor ? amount : -amount;
    }

    std::vector<long long> addExpense(const std::string &id, const std::string &expense,
                                      const std::string &payer, long long amount,
                                      const std::vector<std::string> &participants,
                                      const std::string &kind,
                                      const std::vector<long long> &values) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        auto &selected = group(id);
        transaction(selected, expense, amount);
        if (participants.empty() || participants.size() > 100 ||
            std::set<std::string>(participants.begin(), participants.end()).size() !=
                participants.size() ||
            !selected.members.count(payer) ||
            std::any_of(participants.begin(), participants.end(), [&](const auto &user) {
                return !selected.members.count(user);
            })) {
            throw std::invalid_argument("Invalid participants");
        }
        auto shares = split(amount, static_cast<int>(participants.size()), kind, values);
        auto updated = selected.balances;
        std::ostringstream entry;
        entry << "EXPENSE " << expense << ' ' << payer << ' ' << amount << ' ' << kind;
        for (size_t index = 0; index < participants.size(); index++) {
            change(updated, participants[index], payer, shares[index]);
            entry << ' ' << participants[index] << ':' << shares[index];
        }
        selected.balances = std::move(updated);
        selected.history.push_back(entry.str());
        selected.transactions.insert(expense);
        return shares;
    }

    void settle(const std::string &id, const std::string &tx, const std::string &debtor,
                const std::string &creditor, long long amount) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        auto &selected = group(id);
        transaction(selected, tx, amount);
        if (debtor == creditor || balance(id, debtor, creditor) < amount) {
            throw std::invalid_argument("Settlement exceeds debt");
        }
        auto updated = selected.balances;
        change(updated, debtor, creditor, -amount);
        selected.balances = std::move(updated);
        selected.history.push_back("SETTLE " + tx + " " + debtor + " " + creditor + " " +
                                   std::to_string(amount));
        selected.transactions.insert(tx);
    }

    std::vector<std::string> history(const std::string &id) {
        std::lock_guard<std::recursive_mutex> lock(gate);
        return group(id).history;
    }
};
} // namespace splitwise

#ifndef LLD_TEST
int main() {
    splitwise::SplitWise service;
    std::vector<std::string> members{"A", "B", "C"};
    for (const auto &user : members) {
        service.addUser(user);
    }
    service.createGroup("trip", members);
    auto shares = service.addExpense("trip", "dinner", "A", 100, members, "EQUAL", {});
    std::cout << "Equal shares: " << shares[0] << ',' << shares[1] << ',' << shares[2] << '\n';
    service.addExpense("trip", "taxi", "B", 60, {"A", "B"}, "EXACT", {20, 40});
    service.addExpense("trip", "tea", "C", 101, members, "PERCENTAGE", {5000, 2500, 2500});
    std::cout << "B owes A: " << service.balance("trip", "B", "A") << '\n';
    std::cout << "A owes C: " << service.balance("trip", "A", "C") << '\n';
    service.settle("trip", "payment", "B", "A", 13);
    std::cout << "After settlement: " << service.balance("trip", "B", "A") << '\n';
    std::cout << "History entries: " << service.history("trip").size() << '\n';
}
#endif
