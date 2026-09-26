"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Cache = exports.ReadWriteLock = void 0;
const assert = require("node:assert/strict");
// Async tasks on one event loop, not a cross-worker shared-memory lock.
// Operations can suspend while holding a permit, so exclusion still matters.

class ReadWriteLock {
    readers = 0;
    writer = false;
    waiting = [];

    acquire(write) {
        return new Promise((resolve) => {
            this.waiting.push({
                write,
                grant: () => {
                    let released = false;
                    resolve(() => {
                        if (released) {
                            throw new Error("Permit already released");
                        }
                        released = true;
                        if (write) {
                            this.writer = false;
                        } else {
                            this.readers--;
                        }
                        this.drain();
                    });
                },
            });
            this.drain();
        });
    }

    drain() {
        while (!this.writer && this.waiting.length > 0) {
            const next = this.waiting[0];
            if (next.write && this.readers > 0) {
                return;
            }
            this.waiting.shift();
            if (next.write) {
                this.writer = true;
            } else {
                this.readers++;
            }
            next.grant();
        }
    }

    async read(action) {
        const release = await this.acquire(false);
        try {
            return await action();
        } finally {
            release();
        }
    }

    async write(action) {
        const release = await this.acquire(true);
        try {
            return await action();
        } finally {
            release();
        }
    }
}
exports.ReadWriteLock = ReadWriteLock;

class Cache {
    values = new Map();
    lock = new ReadWriteLock();

    read(key) {
        return this.lock.read(async () => this.values.get(key));
    }

    write(key, value) {
        return this.lock.write(async () => {
            this.values.set(key, value);
        });
    }
}
exports.Cache = Cache;

async function main() {
    const cache = new Cache();
    await Promise.all(Array.from({ length: 4 }, (_, key) => cache.write(key, key * 10)));
    const values = await Promise.all(Array.from({ length: 4 }, (_, key) => cache.read(key)));
    const total = values.reduce((sum, value) => sum + (value ?? 0), 0);
    assert.equal(total, 60);
    assert.equal(await cache.read(99), undefined);
    console.log(`Cache sum: ${total}`);
    console.log("Missing: true");
    await cache.write(0, -1);
    console.log(`Stored negative: ${await cache.read(0)}`);
    await cache.write(0, 7);
    console.log(`Updated: ${await cache.read(0)}`);
}
if (require.main === module) {
    main().catch((error) => {
        console.error(error);
        process.exitCode = 1;
    });
}
