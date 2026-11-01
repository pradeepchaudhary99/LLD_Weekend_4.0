package InterviewQuestions;
import java.util.*;
import java.util.concurrent.*;

public class FileSystemChecks {
    static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("File-system check failed");
        }
    }
    static void rejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        var fs = new InMemoryFileSystem.FileSystem();
        for (String path : List.of("relative", "/new//bad", "/new/../bad", "/new/", "/a\n")) {
            rejected(() -> fs.mkdir(path));
        }
        check(fs.list("/").isEmpty());
        fs.mkdir("/a/child");
        fs.createFile("/a/note", "é🙂");
        check(fs.size("/a") == 6);
        rejected(() -> fs.mkdir("/a/note/child"));
        rejected(() -> fs.move("/a", "/a/child/cycle"));
        fs.cd("/a/child");
        fs.rename("/a", "renamed");
        check(fs.pwd().equals("/renamed/child"));
        rejected(() -> fs.delete("/renamed", true));
        rejected(() -> fs.delete("/", true));
        fs.cd("/");
        fs.delete("/renamed", true);
        check(fs.list("/").isEmpty());
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> creates = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                creates.add(pool.submit(() -> {
                    try {
                        fs.createFile("/shared", "");
                        return true;
                    } catch (IllegalArgumentException duplicate) {
                        return false;
                    }
                }));
            }
            int winners = 0;
            for (var result : creates) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            check(winners == 1);
            List<Future<?>> writes = new ArrayList<>();
            for (int index = 0; index < 500; index++) {
                writes.add(pool.submit(() -> fs.append("/shared", "x")));
            }
            for (var result : writes) {
                result.get(5, TimeUnit.SECONDS);
            }
            check(fs.read("/shared").equals("x".repeat(500)));
            List<String> snapshot = fs.list("/");
            snapshot.clear();
            check(fs.list("/").equals(List.of("shared")));
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        System.out.println("Java file-system edge and concurrency checks passed");
    }
}
