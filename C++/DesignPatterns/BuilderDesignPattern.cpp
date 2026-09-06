#include <string>

class Student {
public:
    class StudentBuilder {
    public:
        explicit StudentBuilder(std::string name) : name(std::move(name)) {}

        StudentBuilder& setAge(int age) {
            this->age = age;
            return *this;
        }

        StudentBuilder& setAddress(const std::string& address) {
            this->address = address;
            return *this;
        }

        StudentBuilder& setWallet(float wallet) {
            this->wallet = wallet;
            return *this;
        }

        Student build() const {
            return Student(*this);
        }

        std::string name;  // mandatory param
        // optional
        int age = 0;
        std::string address;
        float wallet = 0.0f;
    };

    explicit Student(const StudentBuilder& builder)
        : name(builder.name),
          age(builder.age),
          address(builder.address),
          wallet(builder.wallet) {}

private:
    std::string name;
    int age;
    std::string address;
    float wallet;
};

int main() {
    Student student = Student::StudentBuilder("pradeep")
                           .setAge(123)
                           .setAddress("dasd")
                           .setWallet(12321)
                           .build();
    return 0;
}
