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
