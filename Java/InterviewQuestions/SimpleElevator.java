/*
 * SIMPLE JAVA - Elevator
 *
 * Functional requirements (MVP): Each elevator accepts floors 0..10, visits them FIFO and drains
 * before shutdown. Non-functional requirements: readable, in-memory, safe access within one JVM.
 * Core entities: Each Elevator implements Runnable and owns a BlockingQueue plus one worker Thread.
 * Operations and flow: request queues a floor. run takes a destination and moves one floor at a
 * time until arrival. Design choices and concurrency: One worker owns movement. BlockingQueue
 * passes requests safely. A STOP message drains earlier requests; join happens outside the object
 * lock to avoid deadlock. Assumptions / omitted features: No travel delay, doors, capacity,
 * dispatch strategy or duplicate-stop suppression. Queue is unbounded for teaching. Instantiate
 * once; do not invoke run on a second thread. Edge cases to discuss/test: Invalid floors, FIFO
 * order, independent workers, close draining, request after close. Run from repository root (Java
 * 17+ and Python 3): python3 scripts/run.py Java InterviewQuestions/SimpleElevator Read
 * SIMPLE_JAVA_GUIDE.md before extending this example.
 */

package InterviewQuestions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SimpleElevator {
    static class Elevator implements Runnable, AutoCloseable {
        private static final int STOP = -1;
        private final BlockingQueue<Integer> requests = new LinkedBlockingQueue<>();
        private final List<Integer> arrivals = new ArrayList<>();
        private final Thread worker;
        private boolean closed;
        private int floor; // Only run() reads/writes the moving elevator's position.

        Elevator(String name) {
            worker = new Thread(this, name);
            worker.start();
        }

        synchronized void request(int destination) {
            if (closed || destination < 0 || destination > 10) {
                throw new IllegalArgumentException("Closed elevator or invalid floor");
            }
            requests.add(destination);
        }

        public void run() {
            while (true) {
                int destination;
                try {
                    destination = requests.take(); // Wait without spinning when idle.
                } catch (InterruptedException ignored) {
                    continue; // The STOP message, not interruption, owns normal shutdown.
                }
                if (destination == STOP) {
                    return;
                }
                while (floor != destination) {
                    floor += Integer.compare(destination, floor);
                }
                synchronized (this) {
                    arrivals.add(floor);
                }
            }
        }

        synchronized List<Integer> arrivals() {
            return new ArrayList<>(arrivals);
        }

        public void close() throws InterruptedException {
            synchronized (this) {
                if (!closed) {
                    closed = true;
                    requests.add(STOP); // Appended after all accepted floor requests.
                }
            }
            worker.join(); // Never hold this object's lock while waiting for its worker.
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Elevator first = new Elevator("elevator-1");
        Elevator second = new Elevator("elevator-2");
        try {
            first.request(3);
            first.request(1);
            second.request(5);
        } finally {
            first.close();
            second.close();
        }
        System.out.println("Elevator 1 arrivals: " + first.arrivals());
        System.out.println("Elevator 2 arrivals: " + second.arrivals());
    }
}
