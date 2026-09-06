from abc import ABC, abstractmethod


# Products in UI
class IButton(ABC):
    @abstractmethod
    def render_button(self) -> None:
        raise NotImplementedError


class IModal(ABC):
    @abstractmethod
    def render_modal(self) -> None:
        raise NotImplementedError


class IScreen(ABC):
    @abstractmethod
    def render_screen(self) -> None:
        raise NotImplementedError


# Windows
class WindowButton(IButton):
    def render_button(self) -> None:
        print("Window button rendered")


class WindowModal(IModal):
    def render_modal(self) -> None:
        print("Window modal rendered")


class WindowScreen(IScreen):
    def render_screen(self) -> None:
        print("Window screen rendered")


# Mac
class MacButton(IButton):
    def render_button(self) -> None:
        print("Mac button rendered")


class MacModal(IModal):
    def render_modal(self) -> None:
        print("Mac modal rendered")


class MacScreen(IScreen):
    def render_screen(self) -> None:
        print("Mac screen rendered")


# Linux
class LinuxButton(IButton):
    def render_button(self) -> None:
        print("linux button rendered")


class LinuxModal(IModal):
    def render_modal(self) -> None:
        print("linux modal rendered")


class LinuxScreen(IScreen):
    def render_screen(self) -> None:
        print("linux screen rendered")


class IUIFactory(ABC):
    @abstractmethod
    def get_button(self) -> IButton:
        raise NotImplementedError

    @abstractmethod
    def get_modal(self) -> IModal:
        raise NotImplementedError

    @abstractmethod
    def get_screen(self) -> IScreen:
        raise NotImplementedError


class LinuxFactory(IUIFactory):
    def get_button(self) -> IButton:
        return LinuxButton()

    def get_modal(self) -> IModal:
        return LinuxModal()

    def get_screen(self) -> IScreen:
        return LinuxScreen()


class WindowUIFactory(IUIFactory):
    def get_button(self) -> IButton:
        return WindowButton()

    def get_modal(self) -> IModal:
        return WindowModal()

    def get_screen(self) -> IScreen:
        return WindowScreen()


class MacUIFactory(IUIFactory):
    def get_button(self) -> IButton:
        return MacButton()

    def get_modal(self) -> IModal:
        return MacModal()

    def get_screen(self) -> IScreen:
        return MacScreen()


class UIRender:
    def __init__(self, factory: IUIFactory) -> None:
        self.button = factory.get_button()
        self.modal = factory.get_modal()
        self.screen = factory.get_screen()
        self.render_ui()

    def render_ui(self) -> None:
        self.button.render_button()
        self.modal.render_modal()
        self.screen.render_screen()

    def toggle_ui(self, factory: IUIFactory) -> None:
        self.button = factory.get_button()
        self.modal = factory.get_modal()
        self.screen = factory.get_screen()
        self.render_ui()

    def toggle(self, factory: IUIFactory) -> None:
        self.toggle_ui(factory)


def main() -> None:
    UIRender(LinuxFactory())


if __name__ == "__main__":
    main()
