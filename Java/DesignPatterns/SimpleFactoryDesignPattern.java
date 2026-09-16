import java.util.HashMap;
import java.util.Map;
interface Notification {
    void send(String message);
}

class SMSNotification implements Notification {
    public void send(String message) {
        System.out.println("SMS: " + message);
    }
}

class SLACKNotification implements Notification {
    public void send(String message) {
        System.out.println("Slack: " + message);
    }
}

class WhatsappNotification implements Notification {
    public void send(String message) {
        System.out.println("WhatsApp: " + message);
    }
}

class NotificationFactory {
    // Cached stateless products; this teaching example is single threaded.
    private static final Map<String, Notification> cache = new HashMap<>();
    static Notification getNotification(String type) {
        if (type == null)
            throw new IllegalArgumentException("Unknown notification type");
        return cache.computeIfAbsent(type.toUpperCase(java.util.Locale.ROOT), key -> {
            switch (key) {
            case "SMS":
                return new SMSNotification();
            case "SLACK":
                return new SLACKNotification();
            case "WHATSAPP":
                return new WhatsappNotification();
            default:
                throw new IllegalArgumentException("Unknown notification type");
            }
        });
    }
}

public class SimpleFactoryDesignPattern {
    public static void main(String[] args) {
        for (String type : new String[] {"SMS", "SLACK", "Whatsapp"})
            NotificationFactory.getNotification(type).send("Hello");
        System.out.println("Same instance: " + (NotificationFactory.getNotification("SMS") ==
                                                NotificationFactory.getNotification("sms")));
        try {
            NotificationFactory.getNotification("EMAIL");
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
    }
}
