const assert = require("node:assert/strict");
const { ReadWriteLock } = require("../JavaScript/ReadWriteLockDemo");
const {
    ElevatorSystem,
    RoundRobinStrategy,
} = require("../JavaScript/InterviewQuestions/ElevatorSystemDemo");

async function main() {
    const lock = new ReadWriteLock();
    let releaseReaders;
    const gate = new Promise((resolve) => {
        releaseReaders = resolve;
    });
    let readers = 0;
    const order = [];
    const read = async () => {
        readers++;
        await gate;
        readers--;
    };
    const first = lock.read(read);
    const second = lock.read(read);
    const writer = lock.write(async () => {
        assert.equal(readers, 0);
        order.push("writer");
    });
    const later = lock.read(async () => {
        order.push("later reader");
    });
    await Promise.resolve();
    assert.equal(readers, 2);
    assert.deepEqual(order, []);
    releaseReaders();
    await Promise.all([first, second, writer, later]);
    assert.deepEqual(order, ["writer", "later reader"]);
    await assert.rejects(
        lock.write(async () => {
            throw new Error("expected");
        }),
        /expected/,
    );
    assert.equal(await lock.read(async () => 42), 42);

    const arrivals = [];
    const display = { arrived: (id, floor) => arrivals.push([id, floor]) };
    const system = new ElevatorSystem(1, 10, display);
    for (const floor of [0, 0, 10]) {
        system.internalRequest(0, floor);
    }
    await system.runUntilIdle();
    for (const floor of [7, 2]) {
        system.internalRequest(0, floor);
    }
    await system.runUntilIdle();
    await system.runUntilIdle();
    assert.deepEqual(arrivals, [
        [0, 0],
        [0, 10],
        [0, 7],
        [0, 2],
    ]);
    const multiple = new ElevatorSystem(2, 10, display);
    assert.equal(multiple.externalRequest(2, "UP"), 0);
    multiple.setStrategy(new RoundRobinStrategy());
    assert.deepEqual(
        [0, 1, 2].map(() => multiple.externalRequest(0, "UP")),
        [0, 1, 0],
    );
    assert.throws(() => new ElevatorSystem(0, 10, display), RangeError);
    assert.throws(() => system.internalRequest(1, 0), RangeError);
    assert.throws(() => system.internalRequest(0, 11), RangeError);
    assert.throws(() => system.externalRequest(10, "UP"), RangeError);
    await system.close();
    await multiple.close();
    console.log("Node lock and elevator checks passed");
}

main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
