// TypeScript's emitter removes blank lines between declarations.
// Restore readable spacing in generated teaching files before running Prettier.
const fs = require("node:fs");
const ts = require("typescript");

for (const filename of process.argv.slice(2)) {
    const text = fs.readFileSync(filename, "utf8");
    const source = ts.createSourceFile(filename, text, ts.ScriptTarget.ES2022, true);
    const positions = new Set();

    function visit(node) {
        if (
            ts.isClassDeclaration(node) ||
            ts.isFunctionDeclaration(node) ||
            ts.isMethodDeclaration(node) ||
            ts.isConstructorDeclaration(node)
        ) {
            const start = node.getStart(source);
            const lineStart = text.lastIndexOf("\n", start - 1) + 1;
            if (lineStart > 0 && text[lineStart - 2] !== "\n") {
                positions.add(lineStart);
            }
        }
        ts.forEachChild(node, visit);
    }

    visit(source);
    let result = text;
    for (const position of [...positions].sort((left, right) => right - left)) {
        result = result.slice(0, position) + "\n" + result.slice(position);
    }
    fs.writeFileSync(filename, result);
}
