"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.NotificationService = exports.FakeChannel = void 0;
exports.main = main;
// Independent asynchronous delivery on the Node event loop, using local adapters.
const promises_1 = require("node:timers/promises");

class FakeChannel {
    failures;
    delivered = [];

    constructor(failures = 0) {
        this.failures = failures;
        // Deterministic provider failure injection.
    }

    async send(id, message) {
        await (0, promises_1.setImmediate)();
        if (this.failures > 0) {
            this.failures--;
            return false;
        }
        this.delivered.push(`${id}:${message}`);
        return true;
    }
}
exports.FakeChannel = FakeChannel;

class NotificationService {
    channels;
    templates;
    preferences;
    requests = new Map();
    deliveries = new Map();
    order = [];
    queue = [];
    sequence = 0;
    closed = false;
    started = false;
    worker;

    constructor(channels, templates, preferences) {
        this.channels = new Map(channels);
        this.templates = new Map(templates);
        this.preferences = new Map([...preferences].map(([user, values]) => [user, [...values]]));
    }

    submit(input) {
        const request = Object.freeze({ ...input });
        if (
            this.closed ||
            typeof request.id !== "string" ||
            !request.id.trim() ||
            typeof request.name !== "string" ||
            !Number.isInteger(request.priority) ||
            request.priority < 0 ||
            request.priority > 2 ||
            !this.templates.has(request.template) ||
            !this.preferences.has(request.user)
        ) {
            throw new Error("Invalid or closed request");
        }
        const existing = this.requests.get(request.id);
        if (existing) {
            if (
                existing.user !== request.user ||
                existing.template !== request.template ||
                existing.name !== request.name ||
                existing.priority !== request.priority
            ) {
                throw new Error("Idempotency conflict");
            }
            return;
        }
        const selected = this.preferences.get(request.user);
        if (
            new Set(selected).size !== selected.length ||
            selected.some((channel) => !this.channels.has(channel))
        ) {
            throw new Error("Invalid channel preference");
        }
        const message = this.templates
            .get(request.template)
            .replaceAll("{name}", () => request.name);
        const fanout = selected.map((channel) => ({
            request,
            channel,
            message,
            sequence: this.sequence++,
            status: "QUEUED",
            attempts: 0,
        }));
        this.requests.set(request.id, request);
        this.deliveries.set(request.id, fanout);
        this.queue.push(...fanout);
        if (this.started) {
            this.wake();
        }
    }

    status(id) {
        const fanout = this.deliveries.get(id);
        if (!fanout) {
            throw new Error("Unknown notification");
        }
        if (!fanout.length) {
            return "SKIPPED";
        }
        if (fanout.some((delivery) => ["QUEUED", "PROCESSING"].includes(delivery.status))) {
            return "QUEUED";
        }
        return fanout.some((delivery) => delivery.status === "FAILED") ? "FAILED" : "SENT";
    }

    attempts(id, channel) {
        this.status(id);
        return this.deliveries
            .get(id)
            .filter((delivery) => delivery.channel === channel)
            .reduce((sum, delivery) => sum + delivery.attempts, 0);
    }

    sentOrder() {
        return [...this.order];
    }

    start() {
        if (this.closed) {
            throw new Error("Service closed");
        }
        this.started = true;
        this.wake();
    }

    wake() {
        if (!this.worker && this.queue.length) {
            this.worker = this.run().finally(() => {
                this.worker = undefined;
                this.wake();
            });
        }
    }

    async run() {
        // Yield before delivery so submission never invokes a provider inline.
        await (0, promises_1.setImmediate)();
        while (this.queue.length) {
            this.queue.sort(
                (a, b) => a.request.priority - b.request.priority || a.sequence - b.sequence,
            );
            const delivery = this.queue.shift();
            delivery.status = "PROCESSING";
            let sent = false;
            while (!sent && delivery.attempts < 3) {
                delivery.attempts++;
                try {
                    sent = await this.channels
                        .get(delivery.channel)
                        .send(`${delivery.request.id}/${delivery.channel}`, delivery.message);
                } catch {
                    sent = false;
                }
            }
            delivery.status = sent ? "SENT" : "FAILED";
            if (sent) {
                this.order.push(`${delivery.request.id}/${delivery.channel}`);
            }
        }
    }

    async awaitIdle() {
        this.start();
        while (this.worker) {
            await this.worker;
        }
    }

    async close() {
        this.closed = true;
        this.started = true;
        this.wake();
        while (this.worker) {
            await this.worker;
        }
    }
}
exports.NotificationService = NotificationService;

async function main() {
    const service = new NotificationService(
        new Map([
            ["EMAIL", new FakeChannel()],
            ["SMS", new FakeChannel(1)],
            ["PUSH", new FakeChannel(9)],
        ]),
        new Map([["welcome", "Hello {name}"]]),
        new Map([
            ["alice", ["EMAIL", "SMS"]],
            ["bob", ["PUSH"]],
            ["quiet", []],
        ]),
    );
    try {
        const low = { id: "low", user: "alice", template: "welcome", name: "Alice", priority: 2 };
        service.submit(low);
        service.submit({ ...low, id: "high", priority: 0 });
        service.submit(low);
        service.submit({ id: "fail", user: "bob", template: "welcome", name: "Bob", priority: 1 });
        service.submit({
            id: "off",
            user: "quiet",
            template: "welcome",
            name: "Quiet",
            priority: 1,
        });
        await service.awaitIdle();
        console.log("Sent: " + service.sentOrder().join(","));
        console.log("High: " + service.status("high"));
        console.log("SMS attempts: " + service.attempts("high", "SMS"));
        console.log("Push: " + service.status("fail"));
        console.log("Opt-out: " + service.status("off"));
    } finally {
        await service.close();
    }
}
if (require.main === module) {
    main().catch((error) => {
        console.error(error);
        process.exitCode = 1;
    });
}
