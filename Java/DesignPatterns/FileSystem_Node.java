package Java.DesignPatterns;

import java.util.ArrayList;

abstract class FileSystemNode{
    protected String name;
    protected int size;
    protected FileSystemNode parent;

    abstract void propertes();
    abstract int getSize();
    abstract void rename();
    abstract void open();

}

class File extends FileSystemNode{

    private String content;

    @Override
    void propertes() {

    }

    @Override
    int getSize() {

    }

    @Override
    void rename() {

    }

    @Override
    void open() {

    }
    
}

class Folder extends FileSystemNode{

    List<FileSystemNode> childrens;
    HashMap<>
    public Folder(){
        childrens = new ArrayList<>();
    }
    void addChilden(String name){
       childrens.add(new File());
       
       childrens.add(new Folder());
    }
    @Override
    void propertes() {

    }

    @Override
    int getSize() {
        int size = 0;
        for(FileSystemNode node : childrens){
            size += node.getSize();
        }
        return size;
    }

    @Override
    void rename() {

    }

    @Override
    void open() {
 
    }

}



public class FileSystem_Node {
    
}
