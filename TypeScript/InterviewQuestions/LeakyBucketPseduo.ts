/*
 * INTERVIEW DESIGN NOTES - Queued Leaky Bucket
 *
 * Functional requirements:
 * - Accept requests into a bounded FIFO queue; reject overflow and submissions after close.
 * - One worker attempts jobs with a minimum interval between scheduled attempt starts.
 * - Wake an idle worker on submission; count ordinary handler failures and continue.
 * - Close rejects new work, drains accepted work, and waits for the worker to finish.
 *
 * Non-functional requirements:
 * - Protect admission and queue state; execute callbacks outside queue locks.
 * - Use monotonic time, bounded waiting capacity and no idle busy-spinning.
 * - Do not accumulate catch-up credits after slow callbacks or scheduler delays.
 *
 * Core entities and responsibilities:
 * - LeakyBucket: capacity, waiting queue, worker lifecycle and cumulative failure count.
 * - Request: integer job ID; handler processes it synchronously (awaitable in JS/TS).
 * - Worker: thread/goroutine in native editions; async task on the Node event loop.
 * - Monotonic clock: determines remaining delay since the previous attempt started.
 *
 * Operations and flow:
 * - submit -> atomic closed/capacity check -> enqueue -> signal -> return acceptance.
 * - start -> wait for work -> wait remaining interval -> dequeue -> attempt handler.
 * - close before start starts a draining worker; repeated close waits safely again.
 * - Ordinary handler failure increments failures; the job is not retried or requeued.
 *
 * Design choices and invariants:
 * - Capacity counts waiting jobs, excluding the one executing; total outstanding <= capacity+1.
 * - First attempt can start immediately. Slow work lowers throughput rather than causing bursts.
 * - Callbacks are serial; start twice is invalid; submissions may be queued before start.
 * - Close is owner-only: handlers must not call/await close on their own bucket.
 * - Handlers must finish; no forced cancellation or shutdown timeout is promised.
 *
 * Assumptions and scope:
 * - Interval is an integer 1..60000 milliseconds. This is a queue/shaper, not the
 *   token-bucket or sliding-window admission algorithms in RateLimitingDemo.
 * - Native threads and Node timers are not real-time; OS scheduling can delay attempts.
 * - No durable queue, retry policy, distributed ordering, priorities or delivery guarantee
 *   across process crashes. Fatal runtime/process failures are outside the contract.
 * - C# uses Dispose for close; C++ also drains in its destructor; Go returns handler errors
 *   and counts recovered panics; JS/TS await rejected promises and count them as failures.
 * - Native FIFO operations are O(1); JS/TS Array.shift is O(queue length) in this small demo.
 *
 * Edge cases and discussion:
 * - Full queue before start, close before start, idle wakeup, failure followed by later work,
 *   repeated close, invalid configuration, cadence and concurrent producers during shutdown.
 * - Discuss deadlines, backpressure, cancellation, durable delivery and overload policies.
 *
 * Run commands (from repository root):
 * - python3 scripts/run.py "TypeScript" InterviewQuestions/LeakyBucketPseduo
 * - python3 scripts/run.py JavaScript InterviewQuestions/LeakyBucketPseduo
 * - Prerequisites and focused checks: see CACHE_AND_LEAKY_BUCKET.md.
 */

export {};

class LeakyBucket {
    private readonly queue: number[] = [];
    private running?: Promise<void>;
    private closed = false;
    private failureCount = 0;
    private wake?: () => void;

    constructor(
        private readonly capacity: number,
        private readonly intervalMs: number,
        private readonly handler: (request: number) => void | Promise<void>,
    ) {
        if (
            !Number.isSafeInteger(capacity) ||
            capacity <= 0 ||
            !Number.isInteger(intervalMs) ||
            intervalMs <= 0 ||
            intervalMs > 60000 ||
            typeof handler !== "function"
        ) {
            throw new Error("Invalid configuration");
        }
    }

    submit(request: number): boolean {
        if (this.closed || this.queue.length >= this.capacity) {
            return false;
        }
        this.queue.push(request);
        this.wake?.();
        return true;
    }

    private startWorker(): void {
        this.running = Promise.resolve().then(() => this.run());
    }

    start(): void {
        if (this.running || this.closed) {
            throw new Error("Already started/closed");
        }
        this.startWorker();
    }

    private async run(): Promise<void> {
        let lastStart: number | undefined;
        while (true) {
            while (this.queue.length === 0 && !this.closed) {
                await new Promise<void>((resolve) => {
                    this.wake = resolve;
                });
                this.wake = undefined;
            }
            if (this.queue.length === 0) {
                return;
            }
            if (lastStart !== undefined) {
                let remaining = this.intervalMs - (performance.now() - lastStart);
                while (remaining > 0) {
                    await new Promise<void>((resolve) => setTimeout(resolve, Math.ceil(remaining)));
                    remaining = this.intervalMs - (performance.now() - lastStart);
                }
            }
            const request = this.queue.shift()!;
            lastStart = performance.now();
            try {
                await this.handler(request);
            } catch {
                this.failureCount++;
            }
        }
    }

    failures(): number {
        return this.failureCount;
    }

    async close(): Promise<void> {
        this.closed = true;
        if (!this.running) {
            this.startWorker();
        }
        this.wake?.();
        await this.running;
    }
}

function check(condition: boolean): void {
    if (!condition) {
        throw new Error("Queue contract failed");
    }
}

async function main(): Promise<void> {
    const attempted: number[] = [];
    const bucket = new LeakyBucket(2, 1, (request) => {
        attempted.push(request);
        if (request === 1) {
            throw new Error("Simulated handler failure");
        }
    });
    check(bucket.submit(1));
    check(bucket.submit(2));
    check(!bucket.submit(3));
    bucket.start();
    await bucket.close();
    await bucket.close();
    check(!bucket.submit(4));
    check(attempted.join() === "1,2" && bucket.failures() === 1);
    const beforeStart = new LeakyBucket(1, 1, () => {});
    check(beforeStart.submit(7));
    await beforeStart.close();
    let rejected = false;
    try {
        new LeakyBucket(0, 1, () => {});
    } catch {
        rejected = true;
    }
    check(rejected);
    console.log("Overflow rejected: true");
    console.log("FIFO attempts: 1,2");
    console.log("Handler failures: 1");
    console.log("Shutdown drained: true");
}

main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
