const assert = require("node:assert/strict");
const {
    Slot,
    Vehicle,
    ParkingLotManager,
    HourlyFee,
    NearestExit,
} = require("../JavaScript/InterviewQuestions/ParkingLotDemo");
const { GameLoop } = require("../JavaScript/InterviewQuestions/GameLoopPattern");

const fee = new HourlyFee();
assert.deepEqual(
    [0, 1, 59, 60, 61, 120, 121].map((minutes) => fee.fee(minutes)),
    [50, 50, 50, 50, 100, 100, 150],
);
const lot = new ParkingLotManager([new Slot(0, 1, "CAR", 8), new Slot(1, 2, "CAR", 1)], fee);
lot.setStrategy(new NearestExit());
const ticket = lot.park(new Vehicle("CAR-1", "CAR"), 0);
assert.equal(ticket.slot.id, 2);
assert.throws(() => lot.park(new Vehicle("CAR-1", "CAR"), 0), /already parked/);
assert.throws(() => lot.exit(ticket.id, -1, () => true), /precedes/);
assert.throws(() => lot.exit(ticket.id, 61, () => false), /Payment failed/);
assert.throws(
    () =>
        lot.exit(ticket.id, 61, () => {
            throw new Error("Unavailable");
        }),
    /Unavailable/,
);
assert.equal(ticket.slot.occupied, true);
let charged = 0;
const pay = () => {
    charged++;
    return true;
};
assert.equal(lot.exit(ticket.id, 61, pay), 100);
assert.throws(() => lot.exit(ticket.id, 61, pay), /closed ticket/);
assert.equal(charged, 1);
assert.equal(lot.park(new Vehicle("CAR-2", "CAR"), 62).slot.id, 2);
assert.throws(() => lot.park(new Vehicle("BIKE", "BIKE"), 0), /compatible slot/);

const game = new GameLoop();
const frames = [];
const render = (tick, position) => frames.push([tick, position]);
game.run(0, () => "RIGHT", render);
game.run(2, () => "RIGHT", render);
game.run(2, () => "PAUSE", render);
game.run(1, () => "RESUME", render);
game.run(10, () => "QUIT", render);
game.run(10, () => "RIGHT", render);
assert.deepEqual(frames, [
    [0, 1],
    [1, 2],
    [2, 2],
    [3, 2],
    [4, 3],
]);
assert.equal(game.running, false);
assert.throws(() => game.run(-1, () => "NONE", render), RangeError);
console.log("Node parking and game-loop checks passed");
