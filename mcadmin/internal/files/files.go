// Package files is the panel's file manager: a few named folders (the server's data and its plugins), and nothing
// outside them. Every operation goes through os.Root, which refuses any path that would leave its folder, whether by
// "..", an absolute path or a symlink.
package files

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

var (
	ErrUnknownRoot = errors.New("unknown folder")
	ErrBadPath     = errors.New("that path isn't allowed")
	ErrIsRoot      = errors.New("the top folder itself can't be changed")
	ErrExists      = errors.New("something with that name is already there")
	ErrTooBig      = errors.New("too big to open in the editor (download it instead)")
	ErrBinary      = errors.New("that's not a text file (download it instead)")
	ErrNotDir      = errors.New("that's not a folder")
	ErrIsDir       = errors.New("that's a folder")
)

// Roots are the folders the panel may use, by name.
type Roots struct {
	roots map[string]*os.Root
	order []string
}

// RootSpec names a folder.
type RootSpec struct {
	Name  string `json:"name"`
	Label string `json:"label"`
	Dir   string `json:"-"`
}

// Open opens each folder. They must exist.
func Open(specs []RootSpec) (*Roots, error) {
	r := &Roots{roots: map[string]*os.Root{}}
	for _, spec := range specs {
		root, err := os.OpenRoot(spec.Dir)
		if err != nil {
			return nil, fmt.Errorf("folder %q (%s): %w", spec.Name, spec.Dir, err)
		}
		r.roots[spec.Name] = root
		r.order = append(r.order, spec.Name)
	}
	return r, nil
}

// Close closes every folder.
func (r *Roots) Close() {
	for _, root := range r.roots {
		root.Close()
	}
}

func (r *Roots) root(name string) (*os.Root, error) {
	root, ok := r.roots[name]
	if !ok {
		return nil, ErrUnknownRoot
	}
	return root, nil
}

// Clean turns a path from the browser ("plugins/FunCore/config.yml", "/", "") into one os.Root takes: relative,
// slash-separated, "." for the top folder. It refuses anything odd; os.Root still has the last word on escapes.
func Clean(p string) (string, error) {
	if strings.ContainsRune(p, 0) || strings.Contains(p, "\\") {
		return "", ErrBadPath
	}
	for _, part := range strings.Split(p, "/") {
		if part == ".." {
			return "", ErrBadPath
		}
	}
	c := path.Clean("/" + p)
	if c == "/" {
		return ".", nil
	}
	return c[1:], nil
}

// ValidName checks a single file or folder name (for new files, folders and renames).
func ValidName(name string) error {
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\\\x00") || len(name) > 255 {
		return errors.New("that's not a valid name")
	}
	return nil
}

// Entry is one thing in a folder.
type Entry struct {
	Name     string    `json:"name"`
	Dir      bool      `json:"dir"`
	Size     int64     `json:"size"`
	Modified time.Time `json:"modified"`
	Link     bool      `json:"link,omitempty"`
}

// List returns a folder's contents: folders first, then by name.
func (r *Roots) List(rootName, p string) ([]Entry, error) {
	root, err := r.root(rootName)
	if err != nil {
		return nil, err
	}
	p, err = Clean(p)
	if err != nil {
		return nil, err
	}
	dir, err := root.Open(p)
	if err != nil {
		return nil, err
	}
	defer dir.Close()
	info, err := dir.Stat()
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, ErrNotDir
	}
	items, err := dir.ReadDir(-1)
	if err != nil {
		return nil, err
	}
	entries := make([]Entry, 0, len(items))
	for _, item := range items {
		e := Entry{Name: item.Name(), Link: item.Type()&fs.ModeSymlink != 0}
		// Follow links inside the folder to show what they point at; one leading out shows as a plain entry.
		if st, err := root.Stat(path.Join(p, item.Name())); err == nil {
			e.Dir, e.Size, e.Modified = st.IsDir(), st.Size(), st.ModTime().UTC()
		} else if st, err := item.Info(); err == nil {
			e.Size, e.Modified = st.Size(), st.ModTime().UTC()
		}
		if e.Dir {
			e.Size = 0
		}
		entries = append(entries, e)
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].Dir != entries[j].Dir {
			return entries[i].Dir
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})
	return entries, nil
}

// ReadText returns a text file's contents, if it's at most max bytes and looks like text.
func (r *Roots) ReadText(rootName, p string, max int64) (string, error) {
	f, info, err := r.OpenRead(rootName, p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	if info.Size() > max {
		return "", ErrTooBig
	}
	data, err := io.ReadAll(io.LimitReader(f, max+1))
	if err != nil {
		return "", err
	}
	if int64(len(data)) > max {
		return "", ErrTooBig
	}
	if bytes.IndexByte(data, 0) >= 0 || !utf8.Valid(data) {
		return "", ErrBinary
	}
	return string(data), nil
}

// OpenRead opens a file for reading (a download, or the editor).
func (r *Roots) OpenRead(rootName, p string) (*os.File, fs.FileInfo, error) {
	root, err := r.root(rootName)
	if err != nil {
		return nil, nil, err
	}
	p, err = Clean(p)
	if err != nil {
		return nil, nil, err
	}
	f, err := root.Open(p)
	if err != nil {
		return nil, nil, err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, nil, err
	}
	if info.IsDir() {
		f.Close()
		return nil, nil, ErrIsDir
	}
	return f, info, nil
}

// Write saves a file from src (at most max bytes): into a temporary file next to it, then renamed over it, so the
// server never reads half a file. An existing file keeps its permissions.
func (r *Roots) Write(rootName, p string, src io.Reader, max int64) (int64, error) {
	root, err := r.root(rootName)
	if err != nil {
		return 0, err
	}
	p, err = Clean(p)
	if err != nil {
		return 0, err
	}
	if p == "." {
		return 0, ErrIsRoot
	}
	mode := os.FileMode(0o644)
	if st, err := root.Stat(p); err == nil {
		if st.IsDir() {
			return 0, ErrIsDir
		}
		mode = st.Mode().Perm()
	}
	tmp := path.Join(path.Dir(p), ".mcadmin-"+randomHex(6)+".tmp")
	f, err := root.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return 0, err
	}
	n, err := io.Copy(f, io.LimitReader(src, max+1))
	if err == nil && n > max {
		err = fmt.Errorf("the file is over the %d MB limit", max>>20)
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = root.Chmod(tmp, mode) // the umask may have trimmed it
	}
	if err == nil {
		err = root.Rename(tmp, p)
	}
	if err != nil {
		root.Remove(tmp)
		return 0, err
	}
	return n, nil
}

// Mkdir makes a folder (and any missing ones above it).
func (r *Roots) Mkdir(rootName, p string) error {
	root, err := r.root(rootName)
	if err != nil {
		return err
	}
	p, err = Clean(p)
	if err != nil {
		return err
	}
	if p == "." {
		return ErrIsRoot
	}
	if _, err := root.Lstat(p); err == nil {
		return ErrExists
	}
	return root.MkdirAll(p, 0o755)
}

// Rename moves a file or folder within one root. It won't replace something already at to.
func (r *Roots) Rename(rootName, from, to string) error {
	root, err := r.root(rootName)
	if err != nil {
		return err
	}
	from, err = Clean(from)
	if err != nil {
		return err
	}
	to, err = Clean(to)
	if err != nil {
		return err
	}
	if from == "." || to == "." {
		return ErrIsRoot
	}
	if _, err := root.Lstat(from); err != nil {
		return err
	}
	if _, err := root.Lstat(to); err == nil {
		return ErrExists
	}
	if to == from || strings.HasPrefix(to, from+"/") {
		return errors.New("a folder can't be moved into itself")
	}
	return root.Rename(from, to)
}

// Delete removes a file, or a folder and everything in it.
func (r *Roots) Delete(rootName, p string) error {
	root, err := r.root(rootName)
	if err != nil {
		return err
	}
	p, err = Clean(p)
	if err != nil {
		return err
	}
	if p == "." {
		return ErrIsRoot
	}
	if _, err := root.Lstat(p); err != nil {
		return err
	}
	return root.RemoveAll(p)
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
