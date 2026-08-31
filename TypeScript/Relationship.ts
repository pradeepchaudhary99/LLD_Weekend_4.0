/*
 * Class relationships in TypeScript.
 *
 *   Association - "uses-a" (independent lifetimes)
 *   Aggregation - "has-a"  (whole references parts that outlive it)
 *   Composition - "owns-a" (whole creates and owns its parts)
 */

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
class Student {
  constructor(public readonly name: string) {}
}

class Payment {
  constructor(public readonly amount: number) {}
}

class Teacher {
  teaches(students: Student[]): void {
    for (const student of students) {
      console.log(`Teaching ${student.name}`);
    }
  }

  getSalary(payment: Payment): void {
    console.log(`Teacher received ${payment.amount}`);
  }
}

// ------------------------------------------------------------
// Aggregation (students exist outside the course)
// ------------------------------------------------------------
class LLDCourse {
  private readonly students: Student[] = []; // references, not owned

  enroll(student: Student): void {
    this.students.push(student);
  }

  get size(): number {
    return this.students.length;
  }
}

// ------------------------------------------------------------
// Composition (a directory owns its children)
// ------------------------------------------------------------
interface FileSystemNode {
  readonly name: string;
}

class FileNode implements FileSystemNode {
  constructor(
    public readonly name: string,
    private readonly meta: string,
  ) {}
}

class Directory implements FileSystemNode {
  private readonly childNodes: FileSystemNode[] = []; // owned

  constructor(public readonly name: string) {}

  addChild(name: string, meta: string): void {
    this.childNodes.push(new FileNode(name, meta));
  }

  get children(): readonly FileSystemNode[] {
    return this.childNodes;
  }
}

function main(): void {
  const students = [new Student('Neha'), new Student('Rahul')];

  const teacher = new Teacher();
  teacher.teaches(students);
  teacher.getSalary(new Payment(50000));

  const course = new LLDCourse();
  students.forEach((s) => course.enroll(s));
  console.log(`Course has ${course.size} students`);

  const root = new Directory('root');
  root.addChild('Main.ts', 'text/typescript');
  console.log(`${root.name} contains ${root.children.map((c) => c.name).join(', ')}`);
}

main();

export {};
