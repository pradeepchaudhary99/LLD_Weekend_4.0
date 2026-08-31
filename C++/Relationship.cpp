// Class relationships in C++ (C++17)
//
//   Association  - "uses-a"  (independent lifetimes, passed by reference)
//   Aggregation  - "has-a"   (whole holds non-owning refs to parts)
//   Composition  - "owns-a"  (whole owns parts, shared lifetime)

#include <iostream>
#include <memory>
#include <string>
#include <utility>
#include <vector>

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
class Student {
public:
    std::string name;
    explicit Student(std::string name) : name(std::move(name)) {}
};

class Payment {
public:
    double amount;
    explicit Payment(double amount) : amount(amount) {}
};

class Teacher {
public:
    void teaches(const std::vector<Student*>& students) const {
        for (const auto* student : students) {
            std::cout << "Teaching " << student->name << '\n';
        }
    }

    void getSalary(const Payment& payment) const {
        std::cout << "Teacher received " << payment.amount << '\n';
    }
};

// ------------------------------------------------------------
// Aggregation (students outlive the course)
// ------------------------------------------------------------
class LLDCourse {
    std::vector<Student*> students_;  // non-owning

public:
    void enroll(Student* student) { students_.push_back(student); }
    std::size_t size() const { return students_.size(); }
};

// ------------------------------------------------------------
// Composition (directory owns its children)
// ------------------------------------------------------------
struct IFileSystemNode {
    virtual ~IFileSystemNode() = default;
    virtual std::string name() const = 0;
};

class File : public IFileSystemNode {
    std::string name_;
    std::string meta_;

public:
    File(std::string name, std::string meta)
        : name_(std::move(name)), meta_(std::move(meta)) {}

    std::string name() const override { return name_; }
};

class Directory : public IFileSystemNode {
    std::string name_;
    std::vector<std::unique_ptr<IFileSystemNode>> children_;  // owned

public:
    explicit Directory(std::string name) : name_(std::move(name)) {}

    std::string name() const override { return name_; }

    void addChild(const std::string& name, const std::string& meta) {
        children_.push_back(std::make_unique<File>(name, meta));
    }

    const std::vector<std::unique_ptr<IFileSystemNode>>& children() const {
        return children_;
    }
};

int main() {
    Student neha("Neha");
    Student rahul("Rahul");
    std::vector<Student*> students{&neha, &rahul};

    Teacher teacher;
    teacher.teaches(students);
    teacher.getSalary(Payment(50000));

    LLDCourse course;
    for (auto* student : students) course.enroll(student);
    std::cout << "Course has " << course.size() << " students\n";

    Directory root("root");
    root.addChild("main.cpp", "text/x-c++");
    std::cout << root.name() << " contains:";
    for (const auto& child : root.children()) {
        std::cout << ' ' << child->name();
    }
    std::cout << '\n';

    return 0;
}
