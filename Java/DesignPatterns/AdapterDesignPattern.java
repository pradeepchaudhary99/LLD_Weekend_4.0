interface PaymentProcessor { void pay(int amount); }
class InHousePaymentProcessor implements PaymentProcessor {
    public void pay(int amount) { System.out.println("Legacy payment: " + amount); }
}
class RazorPayPaymentProcessor {
    void makePayment(int amount) { System.out.println("Third-party payment: " + amount); }
}
class RazorPayAdapter implements PaymentProcessor {
    private final RazorPayPaymentProcessor processor;
    RazorPayAdapter(RazorPayPaymentProcessor processor) { this.processor = java.util.Objects.requireNonNull(processor); }
    public void pay(int amount) { processor.makePayment(amount); }
}
class Application {
    private PaymentProcessor processor = new InHousePaymentProcessor();
    void setProcessor(PaymentProcessor processor) { this.processor = java.util.Objects.requireNonNull(processor); }
    void pay(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        processor.pay(amount);
    }
}
public class AdapterDesignPattern {
    public static void main(String[] args) {
        Application app = new Application(); app.pay(100);
        app.setProcessor(new RazorPayAdapter(new RazorPayPaymentProcessor())); app.pay(200);
        try { app.pay(0); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
    }
}
