/*
 * SIMPLE JAVA - Parking lot
 *
 * Functional requirements (MVP): Park one vehicle in a free space; reject duplicates; leave and
 * reuse a space. Non-functional requirements: readable, in-memory, safe access within one JVM. Core
 * entities: ParkingLot owns a fixed array of spaces; a plate identifies a vehicle. Operations and
 * flow: park: validate -> find free space -> assign. leave: find plate -> clear space. Design
 * choices and concurrency: synchronized makes finding and reserving a space one atomic operation.
 * Assumptions / omitted features: One level, one vehicle size; no tickets, fees, reservations or
 * payment. Edge cases to discuss/test: Full lot, duplicate plate, unknown exit, concurrent
 * competition for one space. Run from repository root (Java 17+ and Python 3): python3
 * scripts/run.py Java InterviewQuestions/SimpleParkingLot Read SIMPLE_JAVA_GUIDE.md before
 * extending this example.
 */

package InterviewQuestions;

public class SimpleParkingLot {
    static class ParkingLot {
        private final String[] spaces;

        ParkingLot(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be positive");
            }
            spaces = new String[capacity];
        }

        // Finding a free space AND assigning it must happen under the same lock.
        synchronized int park(String plate) {
            if (plate == null || plate.isBlank()) {
                throw new IllegalArgumentException("Plate is required");
            }
            for (String parked : spaces) {
                if (plate.equals(parked)) {
                    throw new IllegalArgumentException("Vehicle is already parked");
                }
            }
            for (int index = 0; index < spaces.length; index++) {
                if (spaces[index] == null) {
                    spaces[index] = plate;
                    return index;
                }
            }
            return -1; // Full: no state changed.
        }

        synchronized void leave(String plate) {
            if (plate == null) {
                throw new IllegalArgumentException("Plate is required");
            }
            for (int index = 0; index < spaces.length; index++) {
                if (plate.equals(spaces[index])) {
                    spaces[index] = null;
                    return;
                }
            }
            throw new IllegalArgumentException("Vehicle is not parked");
        }
    }

    public static void main(String[] args) {
        ParkingLot lot = new ParkingLot(1);
        System.out.println("Car A space: " + lot.park("A"));
        System.out.println("Car B while full: " + lot.park("B"));
        lot.leave("A");
        System.out.println("Car B after exit: " + lot.park("B"));
    }
}
