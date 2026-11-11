package console

import (
	"context"
	"io"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Hub follows the server's log file and hands each new line to everyone watching the console. It keeps the last
// lines, so someone opening the console sees what just happened. It notices when the server starts a new log (on
// every start the old one is archived and latest.log begins again).
type Hub struct {
	path  string
	keep  int
	mu    sync.Mutex
	lines []string
	subs  map[chan string]struct{}
}

// NewHub follows the log at path, keeping the last keep lines.
func NewHub(path string, keep int) *Hub {
	return &Hub{path: path, keep: keep, subs: map[chan string]struct{}{}}
}

// Subscribe returns the recent lines and a channel of new ones. Call the cancel func when done. A watcher that
// falls far behind misses lines rather than slowing everyone down.
func (h *Hub) Subscribe() ([]string, <-chan string, func()) {
	ch := make(chan string, 512)
	h.mu.Lock()
	backlog := append([]string(nil), h.lines...)
	h.subs[ch] = struct{}{}
	h.mu.Unlock()
	return backlog, ch, func() {
		h.mu.Lock()
		if _, ok := h.subs[ch]; ok {
			delete(h.subs, ch)
			close(ch)
		}
		h.mu.Unlock()
	}
}

// Publish adds a line (from the log, or a note like who ran a command).
func (h *Hub) Publish(line string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.lines = append(h.lines, line)
	if len(h.lines) > h.keep {
		h.lines = append([]string(nil), h.lines[len(h.lines)-h.keep:]...)
	}
	for ch := range h.subs {
		select {
		case ch <- line:
		default:
		}
	}
}

var ansi = regexp.MustCompile("\x1b\\[[0-9;]*[A-Za-z]")

// Run follows the log until ctx ends.
func (h *Hub) Run(ctx context.Context) {
	var f *os.File
	var info os.FileInfo
	var offset int64
	var partial string
	first := true
	tick := time.NewTicker(400 * time.Millisecond)
	defer tick.Stop()
	defer func() {
		if f != nil {
			f.Close()
		}
	}()
	for {
		// (Re)open: at the start, after the file was replaced, or if it shrank.
		if st, err := os.Stat(h.path); err == nil {
			if f == nil || !os.SameFile(st, info) || st.Size() < offset {
				if f != nil {
					f.Close()
					f = nil
				}
				if nf, err := os.Open(h.path); err == nil {
					f, info, offset, partial = nf, st, 0, ""
					if first && st.Size() > 64*1024 {
						offset = st.Size() - 64*1024 // just the end of a long log
						partial = "\x00skip"         // the first line read is cut off: drop it
					}
					if !first {
						h.Publish("──── the server started a new log ────")
					}
					first = false
				}
			}
		}
		if f != nil {
			if _, err := f.Seek(offset, io.SeekStart); err == nil {
				buf := make([]byte, 64*1024)
				for {
					n, err := f.Read(buf)
					if n > 0 {
						offset += int64(n)
						partial += string(buf[:n])
						for {
							i := strings.IndexByte(partial, '\n')
							if i < 0 {
								break
							}
							line := strings.TrimRight(partial[:i], "\r")
							skip := strings.HasPrefix(line, "\x00skip")
							partial = partial[i+1:]
							if !skip {
								h.Publish(ansi.ReplaceAllString(line, ""))
							}
						}
					}
					if err != nil || n == 0 {
						break
					}
				}
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
