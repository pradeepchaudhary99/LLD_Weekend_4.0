package main

import (
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestConcurrentCreateAppendMove(t *testing.T) {
	fs := NewFileSystem()
	var workers sync.WaitGroup
	var winners atomic.Int32
	for index := 0; index < 50; index++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if fs.CreateFile("/shared", "") == nil {
				winners.Add(1)
			}
		}()
	}
	workers.Wait()
	if winners.Load() != 1 {
		t.Fatal("duplicate creation was not atomic")
	}
	for index := 0; index < 500; index++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if err := fs.Append("/shared", "x"); err != nil {
				t.Error(err)
			}
		}()
	}
	workers.Wait()
	content, err := fs.Read("/shared")
	if err != nil || content != strings.Repeat("x", 500) {
		t.Fatal("lost append")
	}
	winners.Store(0)
	for index := 0; index < 20; index++ {
		workers.Add(1)
		go func(index int) {
			defer workers.Done()
			if fs.Move("/shared", "/target"+string(rune('a'+index))) == nil {
				winners.Add(1)
			}
		}(index)
	}
	workers.Wait()
	names, err := fs.List("/")
	if err != nil || len(names) != 1 || winners.Load() != 1 {
		t.Fatal("move ownership corrupted")
	}
}

func TestStructuralFailuresAndSize(t *testing.T) {
	fs := NewFileSystem()
	for _, path := range []string{"relative", "/new//bad", "/new/../bad", "/new/", "/a\n"} {
		if fs.Mkdir(path) == nil {
			t.Fatal("accepted invalid path", path)
		}
	}
	names, err := fs.List("/")
	if err != nil || len(names) != 0 {
		t.Fatal("failed mkdir changed tree")
	}
	if err := fs.Mkdir("/a/child"); err != nil {
		t.Fatal(err)
	}
	if err := fs.CreateFile("/a/note", "é🙂"); err != nil {
		t.Fatal(err)
	}
	size, err := fs.Size("/a")
	if err != nil || size != 6 {
		t.Fatal("wrong UTF-8 size")
	}
	if fs.Move("/a", "/a/child/cycle") == nil {
		t.Fatal("cycle accepted")
	}
	if err := fs.Cd("/a/child"); err != nil {
		t.Fatal(err)
	}
	if err := fs.Rename("/a", "renamed"); err != nil {
		t.Fatal(err)
	}
	if fs.Pwd() != "/renamed/child" {
		t.Fatal("stale working directory")
	}
	if fs.Delete("/renamed", true) == nil || fs.Delete("/", true) == nil {
		t.Fatal("deleted protected directory")
	}
	if err := fs.Cd("/"); err != nil {
		t.Fatal(err)
	}
	if err := fs.Delete("/renamed", true); err != nil {
		t.Fatal(err)
	}
	names, err = fs.List("/")
	if err != nil || len(names) != 0 {
		t.Fatal("recursive deletion failed")
	}
}
