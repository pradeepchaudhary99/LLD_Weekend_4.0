

//Products in UI

interface IButton{
    void renderButton();
}

interface IModal{
    void renderModal();
}

interface IScreen{
    void renderScreen();
}

//wINDOW MAC, LINUX





















//Windows 
class WindowButton implements IButton{
    @Override
    public void renderButton() {
        System.out.println("Window button rendered");
    }
}

class WindowModal implements IModal{

    @Override
    public void renderModal() {
        System.out.println("Window modal rendered");
    }  
}

class WindowScreen implements IScreen{
    @Override
    public void renderScreen() {
        System.out.println("Window screen rendered");
    }    
}

//Mac 
class MacButton implements IButton{
    @Override
    public void renderButton() {
        System.out.println("Mac button rendered");
    }
}

class MacModal implements IModal{

    @Override
    public void renderModal() {
        System.out.println("Mac modal rendered");
    }  
}

class MacScreen implements IScreen{
    @Override
    public void renderScreen() {
        System.out.println("Mac screen rendered");
    }    
}

//Linux

//Mac 
class LinuxButton implements IButton{
    @Override
    public void renderButton() {
        System.out.println("linux button rendered");
    }
}

class LinuxModal implements IModal{

    @Override
    public void renderModal() {
        System.out.println("linux modal rendered");
    }  
}

class LinuxScreen implements IScreen{
    @Override
    public void renderScreen() {
        System.out.println("linux screen rendered");
    }    
}



interface I_UI_Factory{
    IButton getButton();
    IModal getModal();
    IScreen getScreen();
}

class LinuxFactory implements I_UI_Factory{

    @Override
    public IButton getButton() {
        return new LinuxButton();
    }

    @Override
    public IModal getModal() {
        return new LinuxModal();
    }

    @Override
    public IScreen getScreen() {
        return new LinuxScreen();
    }
    
}

class WindowUIFactory implements I_UI_Factory{
    @Override
    public IButton getButton() {
        return new WindowButton();
    }

    @Override
    public IModal getModal() {
        return new WindowModal();
    }

    @Override
    public IScreen getScreen() {
        return new WindowScreen();
    }
}


class MacUIFactory implements I_UI_Factory{
    @Override
    public IButton getButton() {
        return new MacButton();
    }

    @Override
    public IModal getModal() {
        return new MacModal();
    }

    @Override
    public IScreen getScreen() {
        return new MacScreen();
    }
}











class UIRender{
    IButton button;
    IModal modal;
    IScreen screen;

    public UIRender(I_UI_Factory factory){
        this.button = factory.getButton();
        this.modal = factory.getModal();
        this.screen = factory.getScreen();
        renderUI();
    }

    void renderUI(){
        button.renderButton();
        modal.renderModal();
        screen.renderScreen();
    }

    void toggleUI(I_UI_Factory factory){
        this.button = factory.getButton();
        this.modal = factory.getModal();
        this.screen = factory.getScreen();
        renderUI();
    }


    void toggle(I_UI_Factory factory){
        toggleUI(factory);
    }
}




public class Abstract_FactoryDesign {
    public static void main(String[] args) {
        UIRender myView = new UIRender(new LinuxFactory());

    }
}
