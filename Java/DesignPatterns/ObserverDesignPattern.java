import java.util.ArrayList;
import java.util.List;
interface Observer {
    void notifyMe(int state);
}

interface Observable {
    void addObserver(Observer observer);
    void deleteObserver(Observer observer);
}

class Stock implements Observable {
    private int price;
    private final List<Observer> observers = new ArrayList<>();
    public void addObserver(Observer observer) {
        if (!observers.contains(observer))
            observers.add(java.util.Objects.requireNonNull(observer));
    }

    public void deleteObserver(Observer observer) {
        observers.remove(observer);
    }
    void setStockPrice(int value) {
        if (value < 0)
            throw new IllegalArgumentException("Price cannot be negative");
        if (value == price)
            return;
        price = value;
        for (Observer observer : new ArrayList<>(observers))
            observer.notifyMe(price);
    }
}

class Phone implements Observer {
    public void notifyMe(int state) {
        System.out.println("Phone: " + state);
    }
}

class TV implements Observer {
    public void notifyMe(int state) {
        System.out.println("TV: " + state);
    }
}
// Elevator display uses a separate state object and observer contract.
class StateObject {
    final int floor;
    StateObject(int floor) {
        this.floor = floor;
    }
}

interface DisplayObserver {
    void displayNotify(StateObject state);
}

class Elevator {
    private final List<DisplayObserver> observers = new ArrayList<>();
    void addObserver(DisplayObserver observer) {
        if (!observers.contains(observer))
            observers.add(observer);
    }
    void deleteObserver(DisplayObserver observer) {
        observers.remove(observer);
    }
    void moveTo(int floor) {
        for (DisplayObserver observer : new ArrayList<>(observers))
            observer.displayNotify(new StateObject(floor));
    }
}

public class ObserverDesignPattern {
    public static void main(String[] args) {
        Stock stock = new Stock();
        Phone phone = new Phone();
        TV tv = new TV();
        stock.addObserver(phone);
        stock.addObserver(phone);
        stock.addObserver(tv);
        stock.setStockPrice(10);
        stock.setStockPrice(10);
        stock.deleteObserver(tv);
        stock.setStockPrice(20);
        Elevator elevator = new Elevator();
        elevator.addObserver(s -> System.out.println("Floor: " + s.floor));
        elevator.moveTo(3);
    }
}
