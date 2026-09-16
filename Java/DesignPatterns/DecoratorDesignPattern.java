interface Notification {
    void send(String message);
}

class SMSNotification implements Notification {
    @Override
    public void send(String message) {
        System.out.println("SMS: " + message);
    }
}

class WhatsappNotification implements Notification {
    @Override
    public void send(String message) {
        System.out.println("WhatsApp: " + message);
    }
}

// Retry // Formatting

abstract class NotificationDecorator implements Notification {
    protected Notification wrappedNotification;
    NotificationDecorator(Notification notification) {
        this.wrappedNotification = notification;
    }
}

class RetryDecorator extends NotificationDecorator {

    public RetryDecorator(Notification notification) {
        super(notification);
    }
    @Override
    public void send(String message) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                wrappedNotification.send(message);
                return;
            } catch (RuntimeException failure) {
                if (attempt == 2)
                    throw failure;
            }
        }
    }
}

class FormattingDecorator extends NotificationDecorator {

    public FormattingDecorator(Notification notification) {
        super(notification);
    }
    @Override
    public void send(String message) {

        wrappedNotification.send(message.trim());
    }
}

class App {

    Notification notification;
    void setNotification(Notification notification) {
        this.notification = notification;
    }
    void sendNotification(String message) {
        notification.send(message);
    }
}

public class DecoratorDesignPattern {
    public static void main(String[] args) {
        // Notification notification = new SMSNotification(); //can be taken care using factory
        // design pattern Notification notification =    new FormattingDecorator(new
        // RetryDecorator(new SMSNotification()));

        Notification SMS = new SMSNotification();
        Notification decorator1 = new RetryDecorator(SMS);
        Notification formatting = new FormattingDecorator(decorator1);

        App app = new App();
        app.setNotification(formatting);
        app.sendNotification("Class starts at 1 PM");
    }
}
