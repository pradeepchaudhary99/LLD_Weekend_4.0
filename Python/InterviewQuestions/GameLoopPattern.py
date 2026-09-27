class GameLoop:
    def __init__(self):
        self.position = 0
        self.velocity = 0
        self.ticks = 0
        self.paused = False
        self.running = True

    def process_input(self, command):
        if command == "RIGHT":
            self.velocity = 1
        elif command == "PAUSE":
            self.paused = True
        elif command == "RESUME":
            self.paused = False
        elif command == "QUIT":
            self.running = False
        elif command != "NONE":
            raise ValueError("Unknown command")

    def update(self):
        if not self.paused:
            self.position += self.velocity

    def run(self, max_ticks, input_source, render):
        if max_ticks < 0:
            raise ValueError("Invalid tick budget")
        for _ in range(max_ticks):
            if not self.running:
                break
            self.process_input(input_source(self.ticks))
            if not self.running:
                break
            self.update()
            render(self.ticks, self.position)
            self.ticks += 1


def main():
    game = GameLoop()
    commands = {0: "RIGHT", 2: "PAUSE", 3: "RESUME", 4: "QUIT"}
    game.run(
        10,
        lambda tick: commands.get(tick, "NONE"),
        lambda tick, position: print(f"Tick {tick}: position {position}"),
    )
    assert game.ticks == 4 and game.position == 3 and not game.running
    print(f"Stopped after {game.ticks} ticks")


if __name__ == "__main__":
    main()
