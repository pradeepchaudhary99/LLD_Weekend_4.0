import { producerConsumer } from "./concurrency_lessons";

producerConsumer().catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
});
