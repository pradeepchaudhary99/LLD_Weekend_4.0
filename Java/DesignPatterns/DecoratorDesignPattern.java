
interface Notification{
    void send(String message);
}

class SMSNotification implements Notification{
    @Override
    public void send(String message) {
        System.out.println("Sending SMS in a legacy way");
    }
}

class WhatsappNotification implements Notification{
    @Override
    public void send(String message) {
        System.out.println("Sending SMS in a legacy way");
    }
}


// Retry // Formatting

abstract class NotificationDecorator implements Notification{
    protected Notification wrappedNotification;
    NotificationDecorator(Notification notification){
        this.wrappedNotification = notification;
    }
}

class RetryDecorator extends NotificationDecorator{

    public RetryDecorator(Notification notification){
        super(notification);
    }
    @Override
    public void send(String message) {
        System.out.println("We are retrying with lots of efforts");

        wrappedNotification.send(message);
    }
    
}

class FormattingDecorator extends NotificationDecorator{
   
    public FormattingDecorator(Notification notification){
        super(notification);
    }
    @Override
    public void send(String message) {
        System.out.println("Formatting code is running and processing");
        wrappedNotification.send(message);
    }
}


class App{

    Notification notification;
    void setNotification(Notification notification){
        this.notification = notification;
    }
    void sendNotification(String message){
        notification.send(message);
    }

}

public class DecoratorDesignPattern {
    public static void main(String[] args) {
        // Notification notification = new SMSNotification(); //can be taken care using factory design pattern 
        // Notification notification =    new FormattingDecorator(new RetryDecorator(new SMSNotification()));
        
        Notification SMS = new SMSNotification();
        Notification decorator1 = new RetryDecorator(SMS);
        Notification formatting = new FormattingDecorator(decorator1);
        
        App app = new App();
        app.setNotification(formatting);
        app.sendNotification(message);

        
    }    
}
