abstract class Handler {
    constructor(private readonly next?: Handler) {
        // An absent next handler marks the end of the chain.
    }

    protected abstract canHandle(level: number): boolean;
    protected abstract name(): string;

    handle(level: number): string {
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
    protected canHandle(level: number): boolean {
        return level < 2;
    }
    protected name(): string {
        return "Warning";
    }
}

class ErrorHandler extends Handler {
    protected canHandle(level: number): boolean {
        return level < 4;
    }
    protected name(): string {
        return "Error";
    }
}

class FatalHandler extends Handler {
    protected canHandle(level: number): boolean {
        return level < 6;
    }
    protected name(): string {
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

export {};
