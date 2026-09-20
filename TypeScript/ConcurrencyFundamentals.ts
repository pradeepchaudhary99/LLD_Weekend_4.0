import { fundamentals } from "./concurrency_lessons";

fundamentals().catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
});
