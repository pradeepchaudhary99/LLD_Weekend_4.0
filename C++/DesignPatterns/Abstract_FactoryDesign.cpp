#include <iostream>
#include <memory>

// Products in UI
struct IButton {
    virtual void renderButton() = 0;
    virtual ~IButton() = default;
};

struct IModal {
    virtual void renderModal() = 0;
    virtual ~IModal() = default;
};

struct IScreen {
    virtual void renderScreen() = 0;
    virtual ~IScreen() = default;
};

// Windows
struct WindowButton : IButton {
    void renderButton() override { std::cout << "Window button rendered" << std::endl; }
};

struct WindowModal : IModal {
    void renderModal() override { std::cout << "Window modal rendered" << std::endl; }
};

struct WindowScreen : IScreen {
    void renderScreen() override { std::cout << "Window screen rendered" << std::endl; }
};

// Mac
struct MacButton : IButton {
    void renderButton() override { std::cout << "Mac button rendered" << std::endl; }
};

struct MacModal : IModal {
    void renderModal() override { std::cout << "Mac modal rendered" << std::endl; }
};

struct MacScreen : IScreen {
    void renderScreen() override { std::cout << "Mac screen rendered" << std::endl; }
};

// Linux
struct LinuxButton : IButton {
    void renderButton() override { std::cout << "linux button rendered" << std::endl; }
};

struct LinuxModal : IModal {
    void renderModal() override { std::cout << "linux modal rendered" << std::endl; }
};

struct LinuxScreen : IScreen {
    void renderScreen() override { std::cout << "linux screen rendered" << std::endl; }
};

struct IUIFactory {
    virtual std::unique_ptr<IButton> getButton() = 0;
    virtual std::unique_ptr<IModal> getModal() = 0;
    virtual std::unique_ptr<IScreen> getScreen() = 0;
    virtual ~IUIFactory() = default;
};

struct LinuxFactory : IUIFactory {
    std::unique_ptr<IButton> getButton() override { return std::make_unique<LinuxButton>(); }
    std::unique_ptr<IModal> getModal() override { return std::make_unique<LinuxModal>(); }
    std::unique_ptr<IScreen> getScreen() override { return std::make_unique<LinuxScreen>(); }
};

struct WindowUIFactory : IUIFactory {
    std::unique_ptr<IButton> getButton() override { return std::make_unique<WindowButton>(); }
    std::unique_ptr<IModal> getModal() override { return std::make_unique<WindowModal>(); }
    std::unique_ptr<IScreen> getScreen() override { return std::make_unique<WindowScreen>(); }
};

struct MacUIFactory : IUIFactory {
    std::unique_ptr<IButton> getButton() override { return std::make_unique<MacButton>(); }
    std::unique_ptr<IModal> getModal() override { return std::make_unique<MacModal>(); }
    std::unique_ptr<IScreen> getScreen() override { return std::make_unique<MacScreen>(); }
};

class UIRender {
public:
    explicit UIRender(IUIFactory& factory) {
        button = factory.getButton();
        modal = factory.getModal();
        screen = factory.getScreen();
        renderUI();
    }

    void renderUI() {
        button->renderButton();
        modal->renderModal();
        screen->renderScreen();
    }

    void toggleUI(IUIFactory& factory) {
        button = factory.getButton();
        modal = factory.getModal();
        screen = factory.getScreen();
        renderUI();
    }

    void toggle(IUIFactory& factory) { toggleUI(factory); }

private:
    std::unique_ptr<IButton> button;
    std::unique_ptr<IModal> modal;
    std::unique_ptr<IScreen> screen;
};

int main() {
    LinuxFactory linuxFactory;
    UIRender myView(linuxFactory);
    return 0;
}
