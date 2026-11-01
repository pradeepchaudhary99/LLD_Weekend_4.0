#define main lesson_main
#include "../../C++/InterviewQuestions/InMemoryFileSystem.cpp"
#undef main
#include <atomic>
#include <thread>

int main() {
    FileSystem fs;
    for (const string path : {"relative", "/new//bad", "/new/../bad", "/new/", "/a\n"}) {
        rejected([&] {
            fs.mkdir(path);
        });
    }
    check(fs.list("/").empty());
    fs.mkdir("/a/child");
    fs.createFile("/a/note", "é🙂");
    check(fs.size("/a") == 6);
    rejected([&] {
        fs.move("/a", "/a/child/cycle");
    });
    fs.cd("/a/child");
    fs.rename("/a", "renamed");
    check(fs.pwd() == "/renamed/child");
    rejected([&] {
        fs.remove("/renamed", true);
    });
    fs.cd("/");
    fs.remove("/renamed", true);
    atomic<int> winners{0};
    vector<thread> threads;
    for (int index = 0; index < 8; index++) {
        threads.emplace_back([&] {
            try {
                fs.createFile("/shared", "");
                ++winners;
            } catch (const invalid_argument &) {
            }
        });
    }
    for (auto &worker : threads) {
        worker.join();
    }
    check(winners == 1);
    threads.clear();
    for (int index = 0; index < 8; index++) {
        threads.emplace_back([&] {
            for (int count = 0; count < 50; count++) {
                fs.append("/shared", "x");
            }
        });
    }
    for (auto &worker : threads) {
        worker.join();
    }
    check(fs.read("/shared") == string(400, 'x'));
    cout << "C++ file-system edge and concurrency checks passed\n";
}
