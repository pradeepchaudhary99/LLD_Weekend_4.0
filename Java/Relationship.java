

//Association

class Student{

}

class Teacher{


    void teaches(List<Student> students){

    }

    void getSalary(Payment payment){

    }
}


// Aggregation 

class LLDCourse{
    List<Student> students;
}

class Students{

}

//Composition 

interface IFileSystemNode{

}

//File, Directory
class File implements IFileSystemNode{

}





class Directory implements IFileSystemNode{
    List<IFileSystemNode> childrens;

    void addChildren(String name, String meta){
        childrens.add(new File(dasdda));
    }
}

