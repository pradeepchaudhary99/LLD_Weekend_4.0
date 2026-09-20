import { customPool } from "./concurrency_lessons";

customPool().catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
});
