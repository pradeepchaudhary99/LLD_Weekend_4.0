import assert = require("node:assert/strict");

// Async tasks on one event loop, not a cross-worker shared-memory lock.
// Operations can suspend while holding a permit, so exclusion still matters.
export class ReadWriteLock {
    private readers = 0;
    private writer = false;
    private readonly waiting: { write: boolean; grant: () => void }[] = [];

    private acquire(write: boolean): Promise<() => void> {
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

    private drain(): void {
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

    async read<T>(action: () => Promise<T>): Promise<T> {
        const release = await this.acquire(false);
        try {
            return await action();
        } finally {
            release();
        }
    }

    async write<T>(action: () => Promise<T>): Promise<T> {
        const release = await this.acquire(true);
        try {
            return await action();
        } finally {
            release();
        }
    }
}

export class Cache {
    private readonly values = new Map<number, number>();
    private readonly lock = new ReadWriteLock();

    read(key: number): Promise<number | undefined> {
        return this.lock.read(async () => this.values.get(key));
    }

    write(key: number, value: number): Promise<void> {
        return this.lock.write(async () => {
            this.values.set(key, value);
        });
    }
}

async function main(): Promise<void> {
    const cache = new Cache();
    await Promise.all(Array.from({ length: 4 }, (_, key) => cache.write(key, key * 10)));
    const values = await Promise.all(Array.from({ length: 4 }, (_, key) => cache.read(key)));
    const total = values.reduce<number>((sum, value) => sum + (value ?? 0), 0);
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
    main().catch((error: unknown) => {
        console.error(error);
        process.exitCode = 1;
    });
}
