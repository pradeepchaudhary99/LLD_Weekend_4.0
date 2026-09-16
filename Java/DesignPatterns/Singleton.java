class ConfigurationManager {
    private ConfigurationManager() {
    }
    // Initialization-on-demand holder: lazy and thread safe.
    private static class Holder {
        private static final ConfigurationManager INSTANCE = new ConfigurationManager();
    }

    public static ConfigurationManager getInstance() {
        return Holder.INSTANCE;
    }
}

public class Singleton {
    public static void main(String[] args) {
        System.out.println("Same instance: " + (ConfigurationManager.getInstance() ==
                                                ConfigurationManager.getInstance()));
    }
}
