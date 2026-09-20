"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const concurrency_lessons_1 = require("./concurrency_lessons");
(0, concurrency_lessons_1.fundamentals)().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
