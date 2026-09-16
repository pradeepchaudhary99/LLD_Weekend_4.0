import java.util.LinkedHashMap;
import java.util.Map;
abstract class FileSystemNode {
    String name;
    Folder parent;
    FileSystemNode(String name) {
        validate(name);
        this.name = name;
    }

    static void validate(String name) {
        if (name == null || name.isEmpty() || name.contains("/"))
            throw new IllegalArgumentException("Invalid name");
    }

    abstract int getSize();
    abstract void open();
    void properties() {
        System.out.println(name + ": " + getSize());
    }
    void rename(String value) {
        validate(value);
        if (value.equals(name))
            return;
        if (parent != null) {
            if (parent.children.containsKey(value))
                throw new IllegalArgumentException("Duplicate name");
            parent.children.remove(name);
            parent.children.put(value, this);
        }
        name = value;
    }
}

class File extends FileSystemNode {
    private String content = "";
    File(String name) {
        super(name);
    }
    int getSize() {
        return content.length();
    }
    void appendContent(String value) {
        content += value;
    }
    // Overwrite from offset, preserving any remaining suffix; allow append at end.
    void modifyFile(String value, int offset) {
        if (offset < 0 || offset > content.length())
            throw new IllegalArgumentException("Invalid offset");
        content = content.substring(0, offset) + value +
                  content.substring(Math.min(content.length(), offset + value.length()));
    }
    void open() {
        System.out.println(content);
    }
}

class Folder extends FileSystemNode {
    final Map<String, FileSystemNode> children = new LinkedHashMap<>();
    Folder(String name) {
        super(name);
    }
    void addChild(FileSystemNode node) {
        for (FileSystemNode ancestor = this; ancestor != null; ancestor = ancestor.parent)
            if (ancestor == node)
                throw new IllegalArgumentException("Cycle rejected");
        if (node.parent != null)
            throw new IllegalArgumentException("Already owned");
        if (children.containsKey(node.name))
            throw new IllegalArgumentException("Duplicate name");
        children.put(node.name, node);
        node.parent = this;
    }
    int getSize() {
        int total = 0;
        for (FileSystemNode node : children.values())
            total += node.getSize();
        return total;
    }
    void open() {
        for (FileSystemNode node : children.values())
            System.out.println(node.name);
    }
}

public class FileSystem_Node {
    public static void main(String[] args) {
        Folder root = new Folder("root"), notes = new Folder("notes");
        File file = new File("draft.txt");
        root.addChild(notes);
        notes.addChild(file);
        file.appendContent("hello");
        file.modifyFile("a", 1);
        file.rename("lesson.txt");
        file.open();
        root.properties();
        notes.open();
        try {
            notes.addChild(root);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
        try {
            notes.addChild(new File("lesson.txt"));
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
        try {
            file.modifyFile("!", 99);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
    }
}
