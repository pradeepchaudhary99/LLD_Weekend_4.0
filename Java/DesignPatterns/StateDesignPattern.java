// Minimal State example; see ATMMachineStateDesign for a larger state machine.
interface PlayerState { void press(Player player); }
class Paused implements PlayerState {
    public void press(Player player) { System.out.println("Playing"); player.state = new Playing(); }
}
class Playing implements PlayerState {
    public void press(Player player) { System.out.println("Paused"); player.state = new Paused(); }
}
class Player { PlayerState state = new Paused(); void press() { state.press(this); } }
public class StateDesignPattern {
    public static void main(String[] args) { Player player = new Player(); player.press(); player.press(); player.press(); }
}
