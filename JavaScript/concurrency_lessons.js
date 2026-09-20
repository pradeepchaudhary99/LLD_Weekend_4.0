"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CustomThreadPool = void 0;
exports.fundamentals = fundamentals;
exports.producerConsumer = producerConsumer;
exports.customPool = customPool;
exports.executorPool = executorPool;
const node_worker_threads_1 = require("node:worker_threads");
const assert = require("node:assert/strict");

function runWorker(source, workerData) {
    return new Promise((resolve, reject) => {
        const worker = new node_worker_threads_1.Worker(source, { eval: true, workerData });
        let result = 0;
        worker.on("message", (value) => {
            result = value;
        });
        worker.on("error", reject);
        worker.on("exit", (code) => {
            if (code === 0) {
                resolve(result);
            } else {
                reject(new Error(`Worker exited with code ${code}`));
            }
        });
    });
}

async function fundamentals() {
    const memory = new SharedArrayBuffer(Int32Array.BYTES_PER_ELEMENT);
    const source = `
        const { workerData } = require("node:worker_threads");
        const count = new Int32Array(workerData);
        for (let iteration = 0; iteration < 1000; iteration++) {
            Atomics.add(count, 0, 1);
        }
    `;
    await Promise.all(Array.from({ length: 4 }, () => runWorker(source, memory)));
    const count = Atomics.load(new Int32Array(memory), 0);
    assert.equal(count, 4000);
    console.log(`Counter: ${count}`);
}

function oneSlotBuffer(capacity) {
    // This teaching buffer intentionally models just one slot: [occupied, value].
    if (capacity !== 1) {
        throw new RangeError("This buffer requires capacity one");
    }
    return new SharedArrayBuffer(2 * Int32Array.BYTES_PER_ELEMENT);
}

async function producerConsumer() {
    const memory = oneSlotBuffer(1);
    const source = `
        const { workerData, parentPort } = require("node:worker_threads");
        const buffer = new Int32Array(workerData.memory);
        let total = 0;
        for (let value = 1; value <= 10; value++) {
            if (workerData.producer) {
                while (Atomics.load(buffer, 0) === 1) {
                    Atomics.wait(buffer, 0, 1);
                }
                Atomics.store(buffer, 1, value);
                Atomics.store(buffer, 0, 1);
            } else {
                while (Atomics.load(buffer, 0) === 0) {
                    Atomics.wait(buffer, 0, 0);
                }
                total += Atomics.load(buffer, 1);
                Atomics.store(buffer, 0, 0);
            }
            Atomics.notify(buffer, 0);
        }
        parentPort.postMessage(total);
    `;
    const [, total] = await Promise.all([
        runWorker(source, { memory, producer: true }),
        runWorker(source, { memory, producer: false }),
    ]);
    assert.equal(total, 55);
    console.log(`Consumed sum: ${total}`);
    assert.throws(() => oneSlotBuffer(0), RangeError);
    console.log("Invalid capacity rejected");
}

class CustomThreadPool {
    workers = [];
    idle = [];
    queued = [];
    active = new Map();
    closed = false;
    closing;
    drained;
    failures = 0;

    constructor(size) {
        if (!Number.isInteger(size) || size <= 0) {
            throw new RangeError("Pool size must be a positive integer");
        }
        // Functions/closures cannot cross worker boundaries. Submit numeric jobs;
        // negative input intentionally exercises the task-failure path.
        const source = `
            const { parentPort } = require("node:worker_threads");
            parentPort.on("message", (value) => {
                try {
                    if (value < 0) {
                        throw new Error("Expected teaching failure");
                    }
                    parentPort.postMessage({ value });
                } catch (error) {
                    parentPort.postMessage({ error: error.message });
                }
            });
        `;
        for (let index = 0; index < size; index++) {
            const worker = new node_worker_threads_1.Worker(source, { eval: true });
            this.workers.push(worker);
            this.idle.push(worker);
            worker.on("message", (result) => {
                const job = this.active.get(worker);
                if (!job) {
                    // Another worker may already have crashed and rejected all pending jobs.
                    return;
                }
                this.active.delete(worker);
                if (result.error) {
                    this.failures++;
                    job.reject(new Error(result.error));
                } else {
                    job.resolve(result.value);
                }
                this.idle.push(worker);
                this.dispatch();
            });
            worker.on("error", (error) => {
                // A worker crash is different from a caught task failure: abort pending work.
                this.closed = true;
                for (const job of this.active.values()) {
                    job.reject(error);
                }
                for (const job of this.queued.splice(0)) {
                    job.reject(error);
                }
                this.active.clear();
                for (const remaining of this.workers) {
                    void remaining.terminate();
                }
                this.drained?.();
            });
        }
    }

    submit(value) {
        if (this.closed) {
            return Promise.reject(new Error("Pool is closed"));
        }
        return new Promise((resolve, reject) => {
            this.queued.push({ value, resolve, reject });
            this.dispatch();
        });
    }

    dispatch() {
        while (this.idle.length > 0 && this.queued.length > 0) {
            const worker = this.idle.pop();
            const job = this.queued.shift();
            this.active.set(worker, job);
            worker.postMessage(job.value);
        }
        if (this.active.size === 0 && this.queued.length === 0) {
            this.drained?.();
        }
    }

    close() {
        if (!this.closing) {
            this.closed = true;
            this.closing = (async () => {
                if (this.active.size > 0 || this.queued.length > 0) {
                    await new Promise((resolve) => {
                        this.drained = resolve;
                    });
                }
                await Promise.all(this.workers.map((worker) => worker.terminate()));
            })();
        }
        return this.closing;
    }
}
exports.CustomThreadPool = CustomThreadPool;

async function customPool() {
    const pool = new CustomThreadPool(3);
    // Attach rejection handling immediately, before the worker can respond.
    const failed = assert.rejects(pool.submit(-1), /Expected teaching failure/);
    const jobs = Array.from({ length: 10 }, (_, index) => pool.submit(index + 1));
    await pool.close();
    await pool.close();
    await failed;
    const results = await Promise.all(jobs);
    assert.deepEqual(results, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    assert.equal(pool.failures, 1);
    console.log(`Completed sum: ${results.reduce((sum, value) => sum + value, 0)}`);
    console.log(`Task failures: ${pool.failures}`);
    await assert.rejects(pool.submit(1), /Pool is closed/);
    console.log("Submission after shutdown rejected");
    assert.throws(() => new CustomThreadPool(0), RangeError);
    console.log("Invalid pool size rejected");
}

async function executorPool() {
    // Node has no built-in ExecutorService; promises expose results from our worker pool.
    const pool = new CustomThreadPool(3);
    try {
        const results = await Promise.all(
            Array.from({ length: 10 }, (_, index) => pool.submit(index + 1)),
        );
        const total = results.reduce((sum, value) => sum + value, 0);
        assert.equal(total, 55);
        console.log(`Executor sum: ${total}`);
    } finally {
        await pool.close();
    }
}
