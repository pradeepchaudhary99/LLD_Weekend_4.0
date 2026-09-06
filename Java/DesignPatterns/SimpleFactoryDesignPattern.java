package Java.DesignPatterns;



class Application{

    void sendNotification(){
        Notification notification = NotificationFactory.getNotification("SMS"); 
        notification.send("message");
    }
}


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




class NotificationFactory{

    public static Notification getNotification(String type){

        if(type.equals("SMS")){
            return new SMSNotification();
        }else if(type.equals("SLACK")){
            return new SLACKNotification();
        }else if(type.equals("Whatsapp")){
            return new WhatsappNotification();
        }
    }
}


//Factory Design Pattern / Method 





public class SimpleFactoryDesignPattern {
    
}
