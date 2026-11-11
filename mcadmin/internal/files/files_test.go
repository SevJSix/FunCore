package files

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func setup(t *testing.T) (*Roots, string, string) {
	t.Helper()
	base := t.TempDir()
	data := filepath.Join(base, "data")
	outside := filepath.Join(base, "secret")
	for _, d := range []string{data, outside} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	os.WriteFile(filepath.Join(outside, "passwd"), []byte("root:x"), 0o644)
	roots, err := Open([]RootSpec{{Name: "data", Dir: data}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(roots.Close)
	return roots, data, outside
}

func TestClean(t *testing.T) {
	good := map[string]string{"": ".", "/": ".", "a/b": "a/b", "/a//b/": "a/b", "./a": "a"}
	for in, want := range good {
		if got, err := Clean(in); err != nil || got != want {
			t.Errorf("Clean(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"..", "../x", "a/../../x", "a/..", "a\\b", "a\x00b"} {
		if _, err := Clean(bad); err == nil {
			t.Errorf("Clean(%q) should be refused", bad)
		}
	}
}

func TestWriteReadListRenameDelete(t *testing.T) {
	r, data, _ := setup(t)
	if _, err := r.Write("data", "config/server.yml", strings.NewReader("a: 1\n"), 1<<20); err == nil {
		t.Fatal("writing into a missing folder should fail")
	}
	if err := r.Mkdir("data", "config"); err != nil {
		t.Fatal(err)
	}
	if _, err := r.Write("data", "config/server.yml", strings.NewReader("a: 1\n"), 1<<20); err != nil {
		t.Fatal(err)
	}
	text, err := r.ReadText("data", "/config/server.yml", 1<<20)
	if err != nil || text != "a: 1\n" {
		t.Fatalf("read %q, %v", text, err)
	}
	entries, _ := r.List("data", "config")
	if len(entries) != 1 || entries[0].Name != "server.yml" {
		t.Fatalf("no temp files left behind: %+v", entries)
	}
	if err := r.Rename("data", "config/server.yml", "config/other.yml"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(data, "config", "other.yml")); err != nil {
		t.Fatal(err)
	}
	if err := r.Rename("data", "config", "config/inside"); err == nil {
		t.Fatal("a folder can't move into itself")
	}
	if err := r.Delete("data", "config"); err != nil {
		t.Fatal(err)
	}
	if err := r.Delete("data", "/"); !errors.Is(err, ErrIsRoot) {
		t.Fatalf("deleting the top folder: %v", err)
	}
}

func TestTooBigAndBinary(t *testing.T) {
	r, data, _ := setup(t)
	os.WriteFile(filepath.Join(data, "big.txt"), make([]byte, 100), 0o644)
	if _, err := r.ReadText("data", "big.txt", 10); !errors.Is(err, ErrTooBig) {
		t.Fatalf("too big: %v", err)
	}
	if _, err := r.ReadText("data", "big.txt", 1000); !errors.Is(err, ErrBinary) {
		t.Fatalf("zero bytes are binary: %v", err)
	}
	if _, err := r.Write("data", "up.bin", strings.NewReader(strings.Repeat("x", 11)), 10); err == nil {
		t.Fatal("over the limit")
	}
	if _, err := os.Stat(filepath.Join(data, "up.bin")); err == nil {
		t.Fatal("a failed write leaves nothing")
	}
}

func TestCantEscape(t *testing.T) {
	r, data, outside := setup(t)
	if err := os.Symlink(outside, filepath.Join(data, "link")); err != nil {
		t.Skip("can't make symlinks here:", err)
	}
	if _, err := r.ReadText("data", "link/passwd", 1<<20); err == nil {
		t.Fatal("read through a symlink leading out")
	}
	if _, err := r.Write("data", "link/evil", strings.NewReader("x"), 10); err == nil {
		t.Fatal("wrote through a symlink leading out")
	}
	if _, err := r.List("data", "link"); err == nil {
		t.Fatal("listed through a symlink leading out")
	}
	if err := r.Delete("data", "link"); err != nil {
		t.Fatalf("deleting the link itself is fine: %v", err)
	}
	if _, err := os.Stat(filepath.Join(outside, "passwd")); err != nil {
		t.Fatal("deleting the link must not touch what it pointed at")
	}
}
