package Java.DesignPatterns;



package Java.DesignPatterns;

import org.w3c.dom.Notation;

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
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'send'");
    }

}

class SLACKNotification implements  Notification{

    @Override
    public void send(String message) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'send'");
    }

}

class WhatsappNotification implements  Notification{

    @Override
    public void send(String message) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'send'");
    }

}

class PushNotification implements Notification{
    @Override
    public void send(String message) {

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
        
    }
}
