from abc import ABC, abstractmethod


class Notification(ABC):
    @abstractmethod
    def send(self, message: str) -> None:
        raise NotImplementedError


class SMSNotification(Notification):
    def send(self, message: str) -> None:
        print("Sending SMS in a legacy way")


class WhatsappNotification(Notification):
    def send(self, message: str) -> None:
        print("Sending SMS in a legacy way")


# Retry // Formatting
class NotificationDecorator(Notification, ABC):
    def __init__(self, notification: Notification) -> None:
        self.wrapped_notification = notification


class RetryDecorator(NotificationDecorator):
    def __init__(self, notification: Notification) -> None:
        super().__init__(notification)

    def send(self, message: str) -> None:
        print("We are retrying with lots of efforts")
        self.wrapped_notification.send(message)


class FormattingDecorator(NotificationDecorator):
    def __init__(self, notification: Notification) -> None:
        super().__init__(notification)

    def send(self, message: str) -> None:
        print("Formatting code is running and processing")
        self.wrapped_notification.send(message)


class App:
    def __init__(self) -> None:
        self.notification: Notification | None = None

    def set_notification(self, notification: Notification) -> None:
        self.notification = notification

    def send_notification(self, message: str) -> None:
        self.notification.send(message)


def main() -> None:
    # notification = SMSNotification()  # can be taken care using factory design pattern
    # notification = FormattingDecorator(RetryDecorator(SMSNotification()))

    sms = SMSNotification()
    decorator1 = RetryDecorator(sms)
    formatting = FormattingDecorator(decorator1)

    app = App()
    app.set_notification(formatting)
    message = "Hello"
    app.send_notification(message)


if __name__ == "__main__":
    main()
