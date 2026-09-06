package Java.DesignPatterns;



class ConfigurationManager{
    private String path;
    private String currentDirectory;
    private String metadata;

    private static ConfigurationManager instance;
    private ConfigurationManager(){
    //   path;
    //   currentDirectory;
    //   metadata;
    }

    public ConfigurationManager getInstance(){

        if(instance == null){

            
            //locking
                if(instance == null){
                    instance = new ConfigurationManager();
                }
        }
        return instance;

    }
}



public class Singleton {
    
}
