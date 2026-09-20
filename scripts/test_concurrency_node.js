// Tests the checked-in JS; the full TS build checks its typed source too.
const assert = require("node:assert/strict");
const { CustomThreadPool } = require("../JavaScript/concurrency_lessons");

async function main() {
    const empty = new CustomThreadPool(1);
    await empty.close();
    await empty.close();
    await assert.rejects(empty.submit(1), /Pool is closed/);

    const pool = new CustomThreadPool(1);
    const failed = assert.rejects(pool.submit(-1), /Expected teaching failure/);
    const work = Promise.all(Array.from({ length: 100 }, (_, index) => pool.submit(index)));
    await pool.close();
    await failed;
    assert.deepEqual(
        await work,
        Array.from({ length: 100 }, (_, index) => index),
    );
    assert.equal(pool.failures, 1);
    for (const size of [0, -1, 1.5]) {
        assert.throws(() => new CustomThreadPool(size), RangeError);
    }
    console.log("Node pool lifecycle checks passed");
}

main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
