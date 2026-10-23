/*
 * SIMPLE JAVA - Game loop
 *
 * Functional requirements (MVP): Read commands, update position/pause state, render; stop on QUIT.
 * Non-functional requirements: readable, in-memory, safe access within one JVM.
 * Core entities: Game owns state. Command expresses intent. BlockingQueue connects input and game
 * thread. Operations and flow: Input -> update -> render, one command per iteration. QUIT ends
 * before update/render. Design choices and concurrency: Thread confinement: only one loop thread
 * touches game state; input threads only enqueue. Assumptions / omitted features: Command-driven
 * loop, not a fixed-frame-rate game. No physics, UI, networking or timers. Stop producing commands
 * at QUIT; create exactly one loop thread per Game. Edge cases to discuss/test: Pause ignores
 * movement, resume restores it, FIFO commands, quit and interrupt terminate. Run from repository
 * root (Java 17+ and Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleGameLoop Read
 * SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SimpleGameLoop {
    enum Command { RIGHT, LEFT, PAUSE, RESUME, QUIT }

    static class Game implements Runnable {
        private final BlockingQueue<Command> inputs = new LinkedBlockingQueue<>();
        private int position; // Owned by the game-loop thread, never changed by input threads.
        private boolean paused;

        void submit(Command command) {
            if (command == null) {
                throw new IllegalArgumentException("Command is required");
            }
            inputs.add(command);
        }

        public void run() {
            try {
                while (true) {
                    Command command = inputs.take(); // Input: wait without busy-spinning.
                    if (command == Command.QUIT) {
                        return;
                    }
                    update(command);
                    System.out.println("Position: " + position); // Render.
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void update(Command command) {
            if (command == Command.PAUSE) {
                paused = true;
            } else if (command == Command.RESUME) {
                paused = false;
            } else if (!paused) {
                position += command == Command.RIGHT ? 1 : -1;
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Game game = new Game();
        Thread loop = new Thread(game, "game-loop");
        loop.start();
        game.submit(Command.RIGHT);
        game.submit(Command.PAUSE);
        game.submit(Command.RIGHT);
        game.submit(Command.RESUME);
        game.submit(Command.LEFT);
        game.submit(Command.QUIT);
        loop.join();
    }
}
