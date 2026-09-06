from abc import ABC, abstractmethod


# product
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


class PushNotification(Notification):
    def send(self, message: str) -> None:
        pass


# product factory
class NotificationFactory(ABC):
    @abstractmethod
    def get_notification(self) -> Notification:
        raise NotImplementedError


class SMSNotificationFactory(NotificationFactory):
    def get_notification(self) -> Notification:
        return SMSNotification()


class WhatsappNotificationFactory(NotificationFactory):
    def get_notification(self) -> Notification:
        return WhatsappNotification()


class PushNotificationFactory(NotificationFactory):
    def get_notification(self) -> Notification:
        return PushNotification()


class Application:
    def send_notification(self, factory: NotificationFactory, message_type: str) -> None:
        notification = factory.get_notification()
        notification.send("message")


# Factory Method Design Pattern


def main() -> None:
    pass


if __name__ == "__main__":
    main()
