// Package audit keeps a record of what every admin did: logins, commands, file changes, power.
package audit

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"sync"
	"time"
)

// Entry is one thing someone did.
type Entry struct {
	Time   time.Time `json:"time"`
	User   string    `json:"user"`
	IP     string    `json:"ip"`
	Action string    `json:"action"`
	Detail string    `json:"detail,omitempty"`
}

// Log appends entries to a JSON-lines file.
type Log struct {
	mu   sync.Mutex
	path string
}

// New writes to the file at path.
func New(path string) *Log { return &Log{path: path} }

// Write records an entry. Failures are returned but shouldn't stop what was done.
func (l *Log) Write(user, ip, action, detail string) error {
	line, err := json.Marshal(Entry{Time: time.Now().UTC(), User: user, IP: ip, Action: action, Detail: detail})
	if err != nil {
		return err
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	f, err := os.OpenFile(l.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.Write(append(line, '\n'))
	return err
}

// Recent returns up to n of the newest entries, newest first.
func (l *Log) Recent(n int) ([]Entry, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	f, err := os.Open(l.path)
	if os.IsNotExist(err) {
		return []Entry{}, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	// Only the end of the file: the log keeps growing.
	const tail = 1 << 20
	if info, err := f.Stat(); err == nil && info.Size() > tail {
		if _, err := f.Seek(info.Size()-tail, io.SeekStart); err != nil {
			return nil, err
		}
	}
	data, err := io.ReadAll(f)
	if err != nil {
		return nil, err
	}
	var entries []Entry
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 64*1024), 1<<20)
	for scanner.Scan() {
		var e Entry
		if json.Unmarshal(scanner.Bytes(), &e) == nil && !e.Time.IsZero() {
			entries = append(entries, e)
		}
	}
	out := make([]Entry, 0, n)
	for i := len(entries) - 1; i >= 0 && len(out) < n; i-- {
		out = append(out, entries[i])
	}
	return out, nil
}
