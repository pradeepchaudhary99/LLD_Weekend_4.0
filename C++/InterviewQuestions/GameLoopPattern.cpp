#include <functional>
#include <iostream>
#include <map>
#include <stdexcept>

enum class Command { None, Right, Pause, Resume, Quit };

class GameLoop {
  public:
    int position = 0;
    int velocity = 0;
    int ticks = 0;
    bool paused = false;
    bool running = true;

    void processInput(Command command) {
        switch (command) {
        case Command::Right:
            velocity = 1;
            break;
        case Command::Pause:
            paused = true;
            break;
        case Command::Resume:
            paused = false;
            break;
        case Command::Quit:
            running = false;
            break;
        case Command::None:
            break;
        }
    }

    void update() {
        if (!paused) {
            position += velocity;
        }
    }

    void run(int maxTicks, const std::function<Command(int)> &input,
             const std::function<void(int, int)> &render) {
        if (maxTicks < 0) {
            throw std::invalid_argument("Invalid tick budget");
        }

        for (int count = 0; count < maxTicks && running; count++) {
            processInput(input(ticks));
            if (!running) {
                break;
            }

            update();
            render(ticks, position);
            ticks++;
        }
    }
};

int main() {
    GameLoop game;
    std::map<int, Command> commands{
        {0, Command::Right}, {2, Command::Pause}, {3, Command::Resume}, {4, Command::Quit}};
    game.run(
        10,
        [&](int tick) {
            auto found = commands.find(tick);
            return found == commands.end() ? Command::None : found->second;
        },
        [](int tick, int position) {
            std::cout << "Tick " << tick << ": position " << position << '\n';
        });
    if (game.ticks != 4 || game.position != 3 || game.running) {
        throw std::runtime_error("Unexpected game state");
    }

    std::cout << "Stopped after " << game.ticks << " ticks\n";
}
