package Java.DesignPatterns;

class Student{
    String name; // mandatory param '
    //

    //optional 
    int age;
    String address;
    float wallet;

    private Student(StudentBuilder builder){
        this.name = name;
        this.age = age;
        this.address = address;
        this.wallet = wallet;
    }

    class StudentBuilder{
        String name; // mandatory param '
        //optional 
        int age;
        String address;
        float wallet;
        
        public StudentBuilder(String name){
            this.name = name;
        }

        StudentBuilder setAge(int age){
                this.age = age;
                return this;
        }
        StudentBuilder setaddress(String address){
            this.address = address;
            return this;
        }
        StudentBuilder setWallet(float w){
            this.wallet = w;
            return this;
        }

        Student build(){
            return new Student(this);
        }

    }






}


public class BuilderDesignPattern {
    public static void main(String[] args) {
        Student student = new Student.StudentBuilder("pradeep").setAge(123).setaddress("dasd").setWallet(12321).build();
    }
}
