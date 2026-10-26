// Execute the generated teaching class in isolation without its demo entry point.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const source = fs.readFileSync(
    path.join(__dirname, "../JavaScript/InterviewQuestions/TaskSchedular_demo.js"),
    "utf8",
);
const context = { exports: {}, performance, setTimeout, clearTimeout, console };
vm.createContext(context);
vm.runInContext(
    source.slice(0, source.lastIndexOf("main().catch")) + "\nthis.Scheduler = Scheduler;",
    context,
);

async function checks() {
    const scheduler = new context.Scheduler(2);
    let release;
    const gate = new Promise((resolve) => {
        release = resolve;
    });
    let entered = 0;
    let ready;
    const both = new Promise((resolve) => {
        ready = resolve;
    });
    const action = async () => {
        entered++;
        if (entered === 2) ready();
        await gate;
    };
    let timeout;
    try {
        scheduler.schedule("a", 0, 1, 1, action);
        scheduler.schedule("b", 0, 1, 1, action);
        scheduler.start();
        await Promise.race([
            both,
            new Promise((_, reject) => {
                timeout = setTimeout(() => reject(new Error("workers did not progress")), 3000);
            }),
        ]);
        assert.equal(scheduler.cancel("a"), true);
        assert.equal(scheduler.cancel("b"), true);
    } finally {
        clearTimeout(timeout);
        release();
        await scheduler.close();
    }
    assert.equal(scheduler.snapshot("a").runs, 1);
    assert.equal(scheduler.snapshot("b").runs, 1);
    assert.equal(scheduler.dispatches().length, 2);
    const timed = new context.Scheduler(1);
    try {
        timed.schedule("future", 60000, 0, 0, () => {});
        timed.start();
        const due = timed.now() + 30;
        let observed = -1;
        timed.schedule("early", due, 1, 0, () => {
            observed = timed.now();
        });
        assert.equal(await timed.awaitRuns("early", 1, 3000), true);
        assert.ok(observed >= due);
    } finally {
        await timed.close();
    }
    assert.equal(timed.snapshot("future").status, "CANCELLED");
    console.log("JavaScript scheduler concurrency checks passed");
}
checks().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
