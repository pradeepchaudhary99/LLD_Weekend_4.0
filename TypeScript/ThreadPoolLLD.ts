import { executorPool } from "./concurrency_lessons";

executorPool().catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
});
