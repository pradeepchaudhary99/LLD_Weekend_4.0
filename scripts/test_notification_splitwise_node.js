const assert = require("node:assert/strict");
const path = require("node:path");
const root = process.argv[2] || path.resolve(__dirname, "../JavaScript/InterviewQuestions");
const { SplitWise } = require(path.join(root, "SplitWiseDemo.js"));
const { NotificationService, FakeChannel } = require(path.join(root, "NotificationSystemDemo.js"));
const { ElevatorSystem } = require(path.join(root, "ElevatorSystemDemo.js"));

async function main() {
    const service = new SplitWise();
    const members = ["A", "B", "C"];
    for (const user of [...members, "D"]) {
        service.addUser(user);
    }
    service.createGroup("g", members);
    service.createGroup("other", members);
    assert.deepEqual(service.addExpense("g", "equal", "A", 2, members, "EQUAL", []), [1, 1, 0]);
    assert.deepEqual(
        service.addExpense("g", "percent", "B", 7, members, "PERCENTAGE", [5000, 2500, 2500]),
        [3, 2, 2],
    );
    assert.deepEqual(
        service.addExpense("g", "zero", "C", 1, members, "PERCENTAGE", [0, 0, 10000]),
        [0, 0, 1],
    );
    assert.equal(service.balance("g", "A", "B"), 2);
    assert.equal(service.balance("g", "B", "A"), -2);
    assert.equal(Math.abs(service.balance("other", "A", "B")), 0);
    assert.throws(() => service.addExpense("g", "bad", "A", 10, members, "EXACT", [2, 3, 4]));
    assert.throws(() =>
        service.addExpense("g", "bad", "A", 10, members, "PERCENTAGE", [5000, 2000, 2000]),
    );
    assert.throws(() => service.addExpense("g", "bad", "A", 10, ["A", "A"], "EQUAL", []));
    assert.throws(() => service.addExpense("g", "bad", "D", 10, members, "EQUAL", []));
    assert.throws(() => service.addExpense("g", "equal", "A", 10, members, "EQUAL", []));
    assert.throws(() => service.addExpense("g", "bad", "A", 0, members, "EQUAL", []));
    assert.throws(() => service.addExpense("g", "bad", "A", 1000000001, members, "EQUAL", []));
    assert.throws(() => service.settle("g", "bad", "A", "B", 3));
    assert.throws(() => service.removeMember("g", "A"));
    assert.equal(service.history("g").length, 3);
    assert.equal(service.balance("g", "A", "B"), 2);
    service.settle("g", "settle", "A", "B", 2);
    assert.equal(service.balance("g", "A", "B"), 0);
    service.removeMember("g", "A");
    service.addMember("g", "A");
    service.addMember("g", "D");
    service.removeMember("g", "D");
    assert.throws(() => service.settle("g", "settle", "C", "B", 1));
    assert.equal(service.history("g").length, 4);
    for (let index = 0; index < 1000; index++) {
        service.addExpense("g", `limit-${index}`, "A", 1000000000, ["B"], "EXACT", [1000000000]);
    }
    assert.throws(() => service.addExpense("g", "overflow", "A", 2, ["C", "B"], "EXACT", [1, 1]));
    assert.equal(Math.abs(service.balance("g", "C", "A")), 0);
    assert.equal(service.history("g").length, 1004);
    const snapshot = service.history("g");
    snapshot.length = 0;
    assert.equal(service.history("g").length, 1004);

    const email = new FakeChannel();
    const notification = new NotificationService(
        new Map([
            ["EMAIL", email],
            ["SMS", new FakeChannel(2)],
            ["PUSH", new FakeChannel(10)],
        ]),
        new Map([["t", "Hello {name}"]]),
        new Map([
            ["u", ["EMAIL", "SMS"]],
            ["bad", ["PUSH"]],
            ["off", []],
        ]),
    );
    const low = { id: "low", user: "u", template: "t", name: "Ada", priority: 2 };
    try {
        notification.submit(low);
        notification.submit({ ...low, id: "high", priority: 0 });
        notification.submit(low);
        notification.submit({ ...low, id: "fail", user: "bad", priority: 1 });
        notification.submit({ ...low, id: "skip", user: "off", priority: 1 });
        assert.equal(notification.status("low"), "QUEUED");
        assert.equal(notification.status("skip"), "SKIPPED");
        assert.throws(() => notification.submit({ ...low, name: "changed" }));
        assert.throws(() => notification.submit({ ...low, id: "missing", template: "missing" }));
        assert.throws(() => notification.submit({ ...low, id: "invalid", priority: 3 }));
        assert.throws(() => notification.status("missing"));
        await notification.awaitIdle();
        assert.deepEqual(notification.sentOrder(), [
            "high/EMAIL",
            "high/SMS",
            "low/EMAIL",
            "low/SMS",
        ]);
        assert.equal(notification.attempts("high", "SMS"), 3);
        assert.equal(notification.attempts("low", "EMAIL"), 1);
        assert.equal(notification.attempts("fail", "PUSH"), 3);
        assert.equal(notification.status("fail"), "FAILED");
        assert.equal(email.delivered[0], "high/EMAIL:Hello Ada");
        await Promise.all(
            Array.from({ length: 100 }, async () =>
                notification.submit({ ...low, id: "same", priority: 1 }),
            ),
        );
        await notification.awaitIdle();
        assert.equal(notification.attempts("same", "EMAIL"), 1);
        assert.equal(notification.sentOrder().length, 6);
        notification.submit({ ...low, id: "literal", name: "$&" });
        await notification.awaitIdle();
        assert.equal(email.delivered.at(-1), "literal/EMAIL:Hello $&");
    } finally {
        await notification.close();
    }
    await notification.close();
    assert.throws(() => notification.submit({ ...low, id: "late" }));
    assert.throws(() => notification.start());
    const drain = new NotificationService(
        new Map([["EMAIL", email]]),
        new Map([["t", "{name}"]]),
        new Map([["u", ["EMAIL"]]]),
    );
    drain.submit({ ...low, id: "drain" });
    await drain.close();
    assert.equal(drain.status("drain"), "SENT");

    let release;
    let firstArrived;
    let secondArrived;
    const blocked = new Promise((resolve) => {
        release = resolve;
    });
    const first = new Promise((resolve) => {
        firstArrived = resolve;
    });
    const second = new Promise((resolve) => {
        secondArrived = resolve;
    });
    const arrivals = [];
    const elevators = new ElevatorSystem(2, 10, {
        arrived: async (id, floor) => {
            arrivals.push([id, floor]);
            if (id === 0 && floor === 3) {
                firstArrived();
                await blocked;
            }
            if (id === 1) {
                secondArrived();
            }
        },
    });
    const timeout = setTimeout(() => {
        release();
        throw new Error("Independent elevator check timed out");
    }, 5000);
    try {
        elevators.internalRequest(0, 3);
        elevators.internalRequest(0, 5);
        elevators.start();
        await first;
        elevators.internalRequest(0, 4);
        elevators.internalRequest(0, 1);
        elevators.internalRequest(1, 2);
        await second;
        release();
        await elevators.runUntilIdle();
        assert.deepEqual(
            arrivals.filter(([id]) => id === 0).map(([, floor]) => floor),
            [3, 4, 5, 1],
        );
    } finally {
        clearTimeout(timeout);
        release();
        await elevators.close();
    }
    assert.throws(() => elevators.internalRequest(0, 1));
    console.log("JS/TS notification, Splitwise and independent elevator checks passed");
}

main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
