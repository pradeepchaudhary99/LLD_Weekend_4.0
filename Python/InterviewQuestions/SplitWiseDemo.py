"""Single-currency group ledger, using integer minor units and atomic updates."""

import re
from threading import RLock

MAX_AMOUNT = 1_000_000_000
MAX_BALANCE = 1_000_000_000_000


def split(amount, count, kind, values):
    if kind == "EQUAL":
        if values:
            raise ValueError("Equal split takes no values")
        return [amount // count + (index < amount % count) for index in range(count)]
    maximum = 10000 if kind == "PERCENTAGE" else MAX_AMOUNT
    if len(values) != count or any(
        type(value) is not int or not 0 <= value <= maximum for value in values
    ):
        raise ValueError("Invalid split values")
    if kind == "EXACT":
        if sum(values) != amount:
            raise ValueError("Exact shares must sum to amount")
        return list(values)
    if kind != "PERCENTAGE" or sum(values) != 10000:
        raise ValueError("Unknown strategy or invalid percentage total")
    shares = [amount * value // 10000 for value in values]
    order = sorted(range(count), key=lambda index: -(amount * values[index] % 10000))
    for index in order[: amount - sum(shares)]:
        shares[index] += 1
    return shares


class Group:
    def __init__(self, members):
        self.members = set(members)
        self.balances = {}
        self.history = {}


class SplitWise:
    def __init__(self):
        self._users = set()
        self._groups = {}
        self._lock = RLock()

    @staticmethod
    def _valid_id(value):
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
            raise ValueError("Invalid ID")

    def add_user(self, user):
        with self._lock:
            self._valid_id(user)
            if user in self._users:
                raise ValueError("Duplicate user")
            self._users.add(user)

    def create_group(self, group_id, members):
        with self._lock:
            self._valid_id(group_id)
            if (
                group_id in self._groups
                or not 1 <= len(members) <= 100
                or len(set(members)) != len(members)
                or not set(members) <= self._users
            ):
                raise ValueError("Invalid group")
            self._groups[group_id] = Group(members)

    def _group(self, group_id):
        if group_id not in self._groups:
            raise ValueError("Unknown group")
        return self._groups[group_id]

    def add_member(self, group_id, user):
        with self._lock:
            group = self._group(group_id)
            if user not in self._users or user in group.members or len(group.members) >= 100:
                raise ValueError("Invalid member")
            group.members.add(user)

    def remove_member(self, group_id, user):
        with self._lock:
            group = self._group(group_id)
            if user not in group.members:
                raise ValueError("Unknown member")
            if any(self.balance(group_id, user, other) for other in group.members):
                raise ValueError("Settle balances before leaving")
            group.members.remove(user)

    def balance(self, group_id, debtor, creditor):
        with self._lock:
            group = self._group(group_id)
            if debtor not in group.members or creditor not in group.members:
                raise ValueError("Unknown member")
            amount = group.balances.get(tuple(sorted((debtor, creditor))), 0)
            return amount if debtor < creditor else -amount

    @staticmethod
    def _change(balances, debtor, creditor, amount):
        if debtor == creditor:
            return
        key = tuple(sorted((debtor, creditor)))
        updated = balances.get(key, 0) + (amount if debtor < creditor else -amount)
        if abs(updated) > MAX_BALANCE:
            raise ValueError("Balance limit exceeded")
        balances[key] = updated

    def _transaction(self, group, transaction_id, amount):
        self._valid_id(transaction_id)
        if (
            type(amount) is not int
            or not 0 < amount <= MAX_AMOUNT
            or transaction_id in group.history
        ):
            raise ValueError("Invalid amount or duplicate transaction")

    def add_expense(self, group_id, expense, payer, amount, participants, kind, values):
        with self._lock:
            group = self._group(group_id)
            self._transaction(group, expense, amount)
            if (
                not 1 <= len(participants) <= 100
                or len(set(participants)) != len(participants)
                or payer not in group.members
                or not set(participants) <= group.members
            ):
                raise ValueError("Invalid participants")
            shares = split(amount, len(participants), kind, values)
            updated = dict(group.balances)
            for user, share in zip(participants, shares):
                self._change(updated, user, payer, share)
            group.balances = updated
            group.history[expense] = (
                f"EXPENSE {expense} {payer} {amount} {kind} {participants} {shares}"
            )
            return list(shares)

    def settle(self, group_id, transaction_id, debtor, creditor, amount):
        with self._lock:
            group = self._group(group_id)
            self._transaction(group, transaction_id, amount)
            if debtor == creditor or self.balance(group_id, debtor, creditor) < amount:
                raise ValueError("Settlement exceeds debt")
            updated = dict(group.balances)
            self._change(updated, debtor, creditor, -amount)
            group.balances = updated
            group.history[transaction_id] = f"SETTLE {transaction_id} {debtor} {creditor} {amount}"

    def history(self, group_id):
        with self._lock:
            return list(self._group(group_id).history.values())


def main():
    service = SplitWise()
    members = ["A", "B", "C"]
    for user in members:
        service.add_user(user)
    service.create_group("trip", members)
    shares = service.add_expense("trip", "dinner", "A", 100, members, "EQUAL", [])
    print("Equal shares:", ",".join(map(str, shares)))
    service.add_expense("trip", "taxi", "B", 60, ["A", "B"], "EXACT", [20, 40])
    service.add_expense("trip", "tea", "C", 101, members, "PERCENTAGE", [5000, 2500, 2500])
    print("B owes A:", service.balance("trip", "B", "A"))
    print("A owes C:", service.balance("trip", "A", "C"))
    service.settle("trip", "payment", "B", "A", 13)
    print("After settlement:", service.balance("trip", "B", "A"))
    print("History entries:", len(service.history("trip")))


if __name__ == "__main__":
    main()
