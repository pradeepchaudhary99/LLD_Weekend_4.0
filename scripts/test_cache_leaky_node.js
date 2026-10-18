const fs = require("fs");
const path = require("path");
const vm = require("vm");
const assert = require("assert/strict");

function load(topic, marker, name) {
    const filename = path.join(__dirname, "..", "JavaScript", "InterviewQuestions", topic + ".js");
    const source = fs.readFileSync(filename, "utf8");
    const boundary = source.indexOf(marker);
    assert(boundary >= 0, "Demo boundary missing");
    const exports = {};
    vm.runInNewContext(
        source.slice(0, boundary) + `\nexports.TestClass = ${name};`,
        { exports, require, console, performance, setTimeout, process },
        { filename },
    );
    return exports.TestClass;
}

async function main() {
    const Cache = load("LRU_CacheDemo", "const cache = new LRUCache", "LRUCache");
    const cache = new Cache(2);
    cache.put(1, 1);
    cache.put(2, 2);
    cache.get(1);
    cache.put(3, -1);
    assert.equal(cache.get(2), undefined);
    assert.equal(cache.get(3), -1);
    assert.deepEqual(Array.from(cache.keys()), [3, 1]);

    const Bucket = load("LeakyBucketPseduo", "main().catch", "LeakyBucket");
    const attempts = [];
    let signal;
    const arrived = new Promise((resolve) => {
        signal = resolve;
    });
    const bucket = new Bucket(4, 20, async (job) => {
        attempts.push({ job, at: performance.now() });
        if (job === 1) {
            signal();
            throw new Error("expected failure");
        }
    });
    bucket.start();
    assert(bucket.submit(1));
    await arrived;
    assert(bucket.submit(2));
    assert(bucket.submit(3));
    await Promise.all([bucket.close(), bucket.close()]);
    assert.deepEqual(
        attempts.map((item) => item.job),
        [1, 2, 3],
    );
    assert.equal(bucket.failures(), 1);
    assert(!bucket.submit(4));
    for (let index = 1; index < attempts.length; index++) {
        assert(attempts[index].at - attempts[index - 1].at >= 18);
    }
    const drained = [];
    const beforeStart = new Bucket(2, 1, (job) => {
        drained.push(job);
    });
    assert(beforeStart.submit(1));
    assert(beforeStart.submit(2));
    assert(!beforeStart.submit(3));
    await beforeStart.close();
    assert.deepEqual(drained, [1, 2]);
    console.log("Node cache/leaky-bucket lifecycle and cadence checks passed");
}

main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
