interface ATMState {
    void insertCard();
    void pressDispenseMoney();
    void cancel();
    void ejectCard();
}

class NoCardState implements ATMState {
    final ATMMachine atm;
    NoCardState(ATMMachine atm) {
        this.atm = atm;
    }

    public void insertCard() {
        System.out.println("Card inserted");
        atm.state = atm.hasCard;
    }

    public void pressDispenseMoney() {
        System.out.println("Insert card first");
    }

    public void cancel() {
        System.out.println("No transaction");
    }

    public void ejectCard() {
        System.out.println("No card");
    }
}

class HasCardState implements ATMState {
    final ATMMachine atm;
    HasCardState(ATMMachine atm) {
        this.atm = atm;
    }

    public void insertCard() {
        System.out.println("Card already inserted");
    }

    public void pressDispenseMoney() {
        System.out.println("Dispensing started");
        atm.state = atm.dispensing;
    }

    public void cancel() {
        System.out.println("Cancelled");
        ejectCard();
    }

    public void ejectCard() {
        System.out.println("Card ejected");
        atm.state = atm.noCard;
    }
}

class MoneyDispenseState implements ATMState {
    final ATMMachine atm;
    MoneyDispenseState(ATMMachine atm) {
        this.atm = atm;
    }

    public void insertCard() {
        System.out.println("Please wait");
    }

    public void pressDispenseMoney() {
        System.out.println("Already dispensing");
    }

    public void cancel() {
        System.out.println("Cannot cancel dispensing");
    }

    public void ejectCard() {
        System.out.println("Please wait");
    }
}

class ATMMachine {
    final ATMState noCard = new NoCardState(this), hasCard = new HasCardState(this),
                   dispensing = new MoneyDispenseState(this);
    ATMState state = noCard;
    void pleaseInsertCard() {
        state.insertCard();
    }
    void dispenseCash() {
        state.pressDispenseMoney();
    }
    void haltDisense() {
        state.cancel();
    }
    void removeCard() {
        state.ejectCard();
    }
    void completeDispense() {
        if (state != dispensing) {
            System.out.println("Nothing to complete");
            return;
        }
        System.out.println("Cash dispensed: 100");
        state = hasCard;
        removeCard();
    }
}

public class ATMMachineStateDesign {
    public static void main(String[] args) {
        ATMMachine atm = new ATMMachine();
        atm.dispenseCash();
        atm.pleaseInsertCard();
        atm.pleaseInsertCard();
        atm.dispenseCash();
        atm.dispenseCash();
        atm.haltDisense();
        atm.completeDispense();
        atm.pleaseInsertCard();
        atm.haltDisense();
        atm.completeDispense();
    }
}
