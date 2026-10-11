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
 * - Synchronous operations on one event loop; no cross-worker safety.
 *
 * Run / validation:
 * - From repository root:
 *   python3 scripts/run.py TypeScript InterviewQuestions/SplitWiseDemo
 * - Prerequisites and focused checks: see README.md and the topic guide.
 */

// Single-currency ledger. Amounts are safe integer minor units, never fractions.
const MAX_AMOUNT = 1_000_000_000;
const MAX_BALANCE = 1_000_000_000_000;

export function split(
    amount: number,
    count: number,
    kind: string,
    values: readonly number[],
): number[] {
    if (kind === "EQUAL") {
        if (values.length) {
            throw new Error("Equal split takes no values");
        }
        return Array.from(
            { length: count },
            (_, index) => Math.floor(amount / count) + (index < amount % count ? 1 : 0),
        );
    }
    const maximum = kind === "PERCENTAGE" ? 10000 : MAX_AMOUNT;
    if (
        values.length !== count ||
        values.some((value) => !Number.isSafeInteger(value) || value < 0 || value > maximum)
    ) {
        throw new Error("Invalid split values");
    }
    const sum = values.reduce((total, value) => total + value, 0);
    if (kind === "EXACT") {
        if (sum !== amount) {
            throw new Error("Exact shares must sum to amount");
        }
        return [...values];
    }
    if (kind !== "PERCENTAGE" || sum !== 10000) {
        throw new Error("Unknown strategy or invalid percentage total");
    }
    const shares = values.map((value) => Math.floor((amount * value) / 10000));
    const order = values
        .map((_, index) => index)
        .sort((a, b) => ((amount * values[b]) % 10000) - ((amount * values[a]) % 10000));
    const remainder = amount - shares.reduce((total, value) => total + value, 0);
    for (const index of order.slice(0, remainder)) {
        shares[index]++;
    }
    return shares;
}

class Group {
    readonly members: Set<string>;
    balances = new Map<string, number>();
    readonly history = new Map<string, string>();

    constructor(members: readonly string[]) {
        this.members = new Set(members);
    }
}

export class SplitWise {
    private readonly users = new Set<string>();
    private readonly groups = new Map<string, Group>();

    private validId(id: string): void {
        if (!/^[A-Za-z0-9_-]+$/.test(id)) {
            throw new Error("Invalid ID");
        }
    }

    addUser(user: string): void {
        this.validId(user);
        if (this.users.has(user)) {
            throw new Error("Duplicate user");
        }
        this.users.add(user);
    }

    createGroup(id: string, members: readonly string[]): void {
        this.validId(id);
        if (
            this.groups.has(id) ||
            !members.length ||
            members.length > 100 ||
            new Set(members).size !== members.length ||
            members.some((user) => !this.users.has(user))
        ) {
            throw new Error("Invalid group");
        }
        this.groups.set(id, new Group(members));
    }

    private group(id: string): Group {
        const group = this.groups.get(id);
        if (!group) {
            throw new Error("Unknown group");
        }
        return group;
    }

    addMember(id: string, user: string): void {
        const group = this.group(id);
        if (!this.users.has(user) || group.members.has(user) || group.members.size >= 100) {
            throw new Error("Invalid member");
        }
        group.members.add(user);
    }

    removeMember(id: string, user: string): void {
        const group = this.group(id);
        if (!group.members.has(user)) {
            throw new Error("Unknown member");
        }
        if ([...group.members].some((other) => this.balance(id, user, other) !== 0)) {
            throw new Error("Settle balances before leaving");
        }
        group.members.delete(user);
    }

    private key(left: string, right: string): string {
        return left < right ? left + ">" + right : right + ">" + left;
    }

    balance(id: string, debtor: string, creditor: string): number {
        const group = this.group(id);
        if (!group.members.has(debtor) || !group.members.has(creditor)) {
            throw new Error("Unknown member");
        }
        const amount = group.balances.get(this.key(debtor, creditor)) ?? 0;
        return amount === 0 ? 0 : debtor < creditor ? amount : -amount;
    }

    private change(
        balances: Map<string, number>,
        debtor: string,
        creditor: string,
        amount: number,
    ): void {
        if (debtor === creditor) {
            return;
        }
        const key = this.key(debtor, creditor);
        const updated = (balances.get(key) ?? 0) + (debtor < creditor ? amount : -amount);
        if (Math.abs(updated) > MAX_BALANCE) {
            throw new Error("Balance limit exceeded");
        }
        balances.set(key, updated);
    }

    private transaction(group: Group, id: string, amount: number): void {
        this.validId(id);
        if (
            !Number.isSafeInteger(amount) ||
            amount <= 0 ||
            amount > MAX_AMOUNT ||
            group.history.has(id)
        ) {
            throw new Error("Invalid amount or duplicate transaction");
        }
    }

    // Synchronous methods complete atomically within one event loop.
    addExpense(
        id: string,
        expense: string,
        payer: string,
        amount: number,
        participants: readonly string[],
        kind: string,
        values: readonly number[],
    ): number[] {
        const group = this.group(id);
        this.transaction(group, expense, amount);
        if (
            !participants.length ||
            participants.length > 100 ||
            new Set(participants).size !== participants.length ||
            !group.members.has(payer) ||
            participants.some((user) => !group.members.has(user))
        ) {
            throw new Error("Invalid participants");
        }
        const shares = split(amount, participants.length, kind, values);
        const updated = new Map(group.balances);
        participants.forEach((user, index) => this.change(updated, user, payer, shares[index]));
        group.balances = updated;
        group.history.set(
            expense,
            `EXPENSE ${expense} ${payer} ${amount} ${kind} ${JSON.stringify(participants)} ${JSON.stringify(shares)}`,
        );
        return [...shares];
    }

    settle(
        id: string,
        transaction: string,
        debtor: string,
        creditor: string,
        amount: number,
    ): void {
        const group = this.group(id);
        this.transaction(group, transaction, amount);
        if (debtor === creditor || this.balance(id, debtor, creditor) < amount) {
            throw new Error("Settlement exceeds debt");
        }
        const updated = new Map(group.balances);
        this.change(updated, debtor, creditor, -amount);
        group.balances = updated;
        group.history.set(transaction, `SETTLE ${transaction} ${debtor} ${creditor} ${amount}`);
    }

    history(id: string): string[] {
        return [...this.group(id).history.values()];
    }
}

export function main(): void {
    const service = new SplitWise();
    const members = ["A", "B", "C"];
    members.forEach((user) => service.addUser(user));
    service.createGroup("trip", members);
    const shares = service.addExpense("trip", "dinner", "A", 100, members, "EQUAL", []);
    console.log("Equal shares: " + shares.join(","));
    service.addExpense("trip", "taxi", "B", 60, ["A", "B"], "EXACT", [20, 40]);
    service.addExpense("trip", "tea", "C", 101, members, "PERCENTAGE", [5000, 2500, 2500]);
    console.log("B owes A: " + service.balance("trip", "B", "A"));
    console.log("A owes C: " + service.balance("trip", "A", "C"));
    service.settle("trip", "payment", "B", "A", 13);
    console.log("After settlement: " + service.balance("trip", "B", "A"));
    console.log("History entries: " + service.history("trip").length);
}

if (require.main === module) {
    main();
}
