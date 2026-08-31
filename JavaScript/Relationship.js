/*
 * Class relationships in JavaScript.
 *
 *   Association - "uses-a" (independent lifetimes)
 *   Aggregation - "has-a"  (whole references parts that outlive it)
 *   Composition - "owns-a" (whole creates and owns its parts)
 */

'use strict';

// ------------------------------------------------------------
// Association
// ------------------------------------------------------------
class Student {
  constructor(name) {
    this.name = name;
  }
}

class Payment {
  constructor(amount) {
    this.amount = amount;
  }
}

class Teacher {
  teaches(students) {
    for (const student of students) {
      console.log(`Teaching ${student.name}`);
    }
  }

  getSalary(payment) {
    console.log(`Teacher received ${payment.amount}`);
  }
}

// ------------------------------------------------------------
// Aggregation (students exist outside the course)
// ------------------------------------------------------------
class LLDCourse {
  constructor() {
    this.students = []; // references, not owned
  }

  enroll(student) {
    this.students.push(student);
  }
}

// ------------------------------------------------------------
// Composition (a directory owns its children)
// ------------------------------------------------------------
class FileSystemNode {
  get name() {
    throw new Error('not implemented');
  }
}

class File extends FileSystemNode {
  #name;
  #meta;

  constructor(name, meta) {
    super();
    this.#name = name;
    this.#meta = meta;
  }

  get name() {
    return this.#name;
  }
}

class Directory extends FileSystemNode {
  #name;
  #children = []; // owned by this directory

  constructor(name) {
    super();
    this.#name = name;
  }

  get name() {
    return this.#name;
  }

  addChild(name, meta) {
    this.#children.push(new File(name, meta));
  }

  get children() {
    return this.#children;
  }
}

function main() {
  const students = [new Student('Neha'), new Student('Rahul')];

  const teacher = new Teacher();
  teacher.teaches(students);
  teacher.getSalary(new Payment(50000));

  const course = new LLDCourse();
  students.forEach((s) => course.enroll(s));
  console.log(`Course has ${course.students.length} students`);

  const root = new Directory('root');
  root.addChild('Main.js', 'text/javascript');
  console.log(`${root.name} contains ${root.children.map((c) => c.name).join(', ')}`);
}

main();

module.exports = { Student, Payment, Teacher, LLDCourse, File, Directory };
