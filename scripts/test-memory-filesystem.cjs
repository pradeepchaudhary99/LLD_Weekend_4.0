const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const source = fs.readFileSync(
    path.join(__dirname, "../JavaScript/InterviewQuestions/InMemoryFileSystem.js"),
    "utf8",
);
const context = { exports: {}, Buffer, console };
vm.createContext(context);
vm.runInContext(
    source.slice(0, source.lastIndexOf("main();")) + "\nthis.FileSystem = FileSystem;",
    context,
);
const tree = new context.FileSystem();
for (const value of ["relative", "/new//bad", "/new/../bad", "/new/", "/a\n"]) {
    assert.throws(() => tree.mkdir(value));
}
assert.equal(tree.list("/").length, 0);
tree.mkdir("/a/child");
tree.createFile("/a/note", "é🙂");
assert.equal(tree.size("/a"), 6);
assert.throws(() => tree.mkdir("/a/note/child"));
assert.throws(() => tree.move("/a", "/a/child/cycle"));
assert.throws(() => tree.createFile("/a/note", "overwrite"));
assert.equal(tree.read("/a/note"), "é🙂");
tree.cd("/a/child");
tree.rename("/a", "renamed");
assert.equal(tree.pwd(), "/renamed/child");
assert.throws(() => tree.delete("/renamed", true));
assert.throws(() => tree.delete("/", true));
const listing = tree.list("/renamed");
listing.length = 0;
assert.equal(tree.list("/renamed").length, 2);
tree.cd("/");
tree.delete("/renamed", true);
assert.equal(tree.list("/").length, 0);
console.log("JavaScript file-system edge checks passed");
