class Application{

    void sendNotification(NotificationFactory factory, String type){
        Notification notification = factory.getNotification();
        notification.send("message");
    }
}


//product
interface Notification{
    void send(String message);
}

class SMSNotification implements  Notification{

    @Override
    public void send(String message) {
        System.out.println("SMS: " + message);
    }

}

class SLACKNotification implements  Notification{

    @Override
    public void send(String message) {
        System.out.println("Slack: " + message);
    }

}

class WhatsappNotification implements  Notification{

    @Override
    public void send(String message) {
        System.out.println("WhatsApp: " + message);
    }

}

class PushNotification implements Notification{
    @Override
    public void send(String message) {
        System.out.println("Push: " + message);
    }
}



// product Factory

interface NotificationFactory{
    Notification getNotification();
}

class SMSNotificationFactory implements NotificationFactory{
    @Override
    public Notification getNotification() {
        return new SMSNotification();
    }
}

class WhatsappNotificationFactory implements  NotificationFactory{
    @Override
    public Notification getNotification() {
        return new WhatsappNotification();
    }
}

class PushNotificationFactory implements NotificationFactory{
    @Override
    public Notification getNotification() {
        return new PushNotification();
    }
}





//Factory Design Pattern / Method



public class FactoryMethodDesignPattern {
    public static void main(String[] args) {
        Application app = new Application();
        app.sendNotification(new SMSNotificationFactory(), "SMS");
        app.sendNotification(new WhatsappNotificationFactory(), "Whatsapp");
        app.sendNotification(new PushNotificationFactory(), "Push");
    }
}
