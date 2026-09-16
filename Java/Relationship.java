import java.util.ArrayList;
import java.util.List;
// Association: independently created students and payments are used by a teacher.
class Student {
    final String name;
    Student(String name) {
        this.name = name;
    }
}

class Payment {
    final double amount;
    Payment(double amount) {
        this.amount = amount;
    }
}

class Teacher {
    void teaches(List<Student> students) {
        for (Student student : students)
            System.out.println("Teaching " + student.name);
    }
    void getSalary(Payment payment) {
        System.out.println("Teacher received " + payment.amount);
    }
}
// Aggregation: the course references existing students.
class LLDCourse {
    final List<Student> students = new ArrayList<>();
    void addStudent(Student student) {
        students.add(student);
    }
}
// Composition: a directory creates and owns its file objects.
interface IFileSystemNode {
    String name();
}

class File implements IFileSystemNode {
    private final String name, meta;
    File(String name, String meta) {
        this.name = name;
        this.meta = meta;
    }

    public String name() {
        return name;
    }
}

class Directory implements IFileSystemNode {
    private final String name;
    private final List<IFileSystemNode> children = new ArrayList<>();
    Directory(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
    void addChildren(String name, String meta) {
        children.add(new File(name, meta));
    }
    void list() {
        for (IFileSystemNode child : children)
            System.out.println(child.name());
    }
}

public class Relationship {
    public static void main(String[] args) {
        List<Student> students = java.util.Arrays.asList(new Student("Neha"), new Student("Rahul"));
        Teacher teacher = new Teacher();
        teacher.teaches(students);
        teacher.getSalary(new Payment(50000));
        LLDCourse course = new LLDCourse();
        for (Student student : students)
            course.addStudent(student);
        System.out.println("Course has " + course.students.size() + " students");
        Directory root = new Directory("root");
        root.addChildren("main.java", "text/java");
        root.list();
    }
}
