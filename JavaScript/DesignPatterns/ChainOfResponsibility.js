"use strict";
Object.defineProperty(exports, "__esModule", { value: true });

class Handler {
    next;

    constructor(next) {
        this.next = next;
        // An absent next handler marks the end of the chain.
    }

    handle(level) {
        if (!Number.isInteger(level) || level < 0) {
            throw new RangeError("Level must be a non-negative integer");
        }
        if (this.canHandle(level)) {
            return this.name();
        }
        return this.next?.handle(level) ?? "Unhandled";
    }
}

class WarningHandler extends Handler {
    canHandle(level) {
        return level < 2;
    }

    name() {
        return "Warning";
    }
}

class ErrorHandler extends Handler {
    canHandle(level) {
        return level < 4;
    }

    name() {
        return "Error";
    }
}

class FatalHandler extends Handler {
    canHandle(level) {
        return level < 6;
    }

    name() {
        return "Fatal";
    }
}
const chain = new WarningHandler(new ErrorHandler(new FatalHandler()));
for (let level = 0; level <= 6; level++) {
    console.log(`${level}: ${chain.handle(level)}`);
}
console.log(`Truncated: ${new WarningHandler().handle(3)}`);
try {
    chain.handle(-1);
    throw new Error("Accepted negative level");
} catch (error) {
    if (!(error instanceof RangeError)) {
        throw error;
    }
    console.log("Invalid level rejected");
}
