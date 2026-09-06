from abc import ABC, abstractmethod


class Notification(ABC):
    @abstractmethod
    def send(self, message: str) -> None:
        raise NotImplementedError


class SMSNotification(Notification):
    def send(self, message: str) -> None:
        raise NotImplementedError("Unimplemented method 'send'")


class SlackNotification(Notification):
    def send(self, message: str) -> None:
        raise NotImplementedError("Unimplemented method 'send'")


class WhatsappNotification(Notification):
    def send(self, message: str) -> None:
        raise NotImplementedError("Unimplemented method 'send'")


class NotificationFactory:
    @staticmethod
    def get_notification(notification_type: str) -> Notification:
        if notification_type == "SMS":
            return SMSNotification()
        elif notification_type == "SLACK":
            return SlackNotification()
        elif notification_type == "Whatsapp":
            return WhatsappNotification()
        else:
            raise ValueError(f"Unknown notification type: {notification_type}")


class Application:
    def send_notification(self) -> None:
        notification = NotificationFactory.get_notification("SMS")
        notification.send("message")


# Simple Factory Design Pattern


def main() -> None:
    Application().send_notification()


if __name__ == "__main__":
    main()
