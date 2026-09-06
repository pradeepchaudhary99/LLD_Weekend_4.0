#include <iostream>
#include <memory>
#include <string>
#include <vector>

struct FileSystemNode {
    std::string name;
    int size = 0;
    FileSystemNode* parent = nullptr;

    explicit FileSystemNode(std::string name) : name(std::move(name)) {}

    virtual void properties() = 0;
    virtual int getSize() = 0;
    virtual void rename(const std::string& newName) = 0;
    virtual void open() = 0;
    virtual ~FileSystemNode() = default;
};

class File : public FileSystemNode {
public:
    File(const std::string& name, const std::string& content = "")
        : FileSystemNode(name), content(content) {
        size = static_cast<int>(content.size());
    }

    void properties() override {
        std::cout << "File: " << name << ", size: " << size << std::endl;
    }

    int getSize() override { return size; }

    void rename(const std::string& newName) override { name = newName; }

    void open() override { std::cout << "Opening file " << name << std::endl; }

private:
    std::string content;
};

class Folder : public FileSystemNode {
public:
    explicit Folder(const std::string& name) : FileSystemNode(name) {}

    void addChild(std::unique_ptr<FileSystemNode> node) {
        node->parent = this;
        children.push_back(std::move(node));
    }

    void properties() override {
        std::cout << "Folder: " << name << ", size: " << getSize() << std::endl;
    }

    int getSize() override {
        int total = 0;
        for (const auto& child : children) {
            total += child->getSize();
        }
        return total;
    }

    void rename(const std::string& newName) override { name = newName; }

    void open() override { std::cout << "Opening folder " << name << std::endl; }

private:
    std::vector<std::unique_ptr<FileSystemNode>> children;
};

int main() {
    auto root = std::make_unique<Folder>("root");
    root->addChild(std::make_unique<File>("readme.txt", "hello"));
    root->addChild(std::make_unique<Folder>("subfolder"));
    return 0;
}
