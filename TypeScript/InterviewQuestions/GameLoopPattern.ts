export type Command = "NONE" | "RIGHT" | "PAUSE" | "RESUME" | "QUIT";

export class GameLoop {
    position = 0;
    velocity = 0;
    ticks = 0;
    paused = false;
    running = true;

    processInput(command: Command): void {
        switch (command) {
            case "RIGHT":
                this.velocity = 1;
                break;
            case "PAUSE":
                this.paused = true;
                break;
            case "RESUME":
                this.paused = false;
                break;
            case "QUIT":
                this.running = false;
                break;
            case "NONE":
                break;
        }
    }

    update(): void {
        if (!this.paused) {
            this.position += this.velocity;
        }
    }

    run(
        maxTicks: number,
        input: (tick: number) => Command,
        render: (tick: number, position: number) => void,
    ): void {
        if (!Number.isSafeInteger(maxTicks) || maxTicks < 0) {
            throw new RangeError("Invalid tick budget");
        }
        for (let count = 0; count < maxTicks && this.running; count++) {
            this.processInput(input(this.ticks));
            if (!this.running) {
                break;
            }
            this.update();
            render(this.ticks, this.position);
            this.ticks++;
        }
    }
}

function main(): void {
    const game = new GameLoop();
    const commands = new Map<number, Command>([
        [0, "RIGHT"],
        [2, "PAUSE"],
        [3, "RESUME"],
        [4, "QUIT"],
    ]);
    game.run(
        10,
        (tick) => commands.get(tick) ?? "NONE",
        (tick, position) => {
            console.log(`Tick ${tick}: position ${position}`);
        },
    );
    if (game.ticks !== 4 || game.position !== 3 || game.running) {
        throw new Error("Unexpected game state");
    }
    console.log(`Stopped after ${game.ticks} ticks`);
}

if (require.main === module) {
    main();
}
