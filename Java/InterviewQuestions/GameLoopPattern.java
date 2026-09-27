package InterviewQuestions;

import java.util.function.IntFunction;
import java.util.function.BiConsumer;

public class GameLoopPattern {
    enum Command { NONE, RIGHT, PAUSE, RESUME, QUIT }
    static class GameLoop {
        int position;
        int velocity;
        int ticks;
        boolean paused;
        boolean running = true;

        void processInput(Command command) {
            switch (command) {
            case RIGHT -> velocity = 1;
            case PAUSE -> paused = true;
            case RESUME -> paused = false;
            case QUIT -> running = false;
            case NONE -> {
                // Keep the previous state.
            }
            }
        }

        void update() {
            if (!paused) {
                position += velocity;
            }
        }

        void run(int maxTicks, IntFunction<Command> input, BiConsumer<Integer, Integer> render) {
            if (maxTicks < 0) {
                throw new IllegalArgumentException("Invalid tick budget");
            }

            for (int count = 0; count < maxTicks && running; count++) {
                processInput(input.apply(ticks));
                if (!running) {
                    break;
                }

                update();
                render.accept(ticks, position);
                ticks++;
            }
        }
    }

    public static void main(String[] args) {
        GameLoop game = new GameLoop();
        game.run(10, tick -> switch (tick) {
            case 0 -> Command.RIGHT;
            case 2 -> Command.PAUSE;
            case 3 -> Command.RESUME;
            case 4 -> Command.QUIT;
            default -> Command.NONE;
        }, (tick, position) -> System.out.println("Tick " + tick + ": position " + position));
        if (game.ticks != 4 || game.position != 3 || game.running) {
            throw new AssertionError("Unexpected game state");
        }

        System.out.println("Stopped after " + game.ticks + " ticks");
    }
}
