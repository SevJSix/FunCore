package console

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeServer answers like Minecraft: auth (type 3) gets a type 2 reply (id -1 if wrong), a command gets its reply
// split into 4096-byte packets, anything else gets "Unknown request". Like Minecraft, it reads the socket into a
// 1460-byte buffer and handles only the first packet in each read: anything sent along with it is lost.
// It returns the address, a count of commands run, and a func that drops every open connection (a restart).
func fakeServer(t *testing.T, password string, reply func(cmd string) string) (string, *int32, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	var ran int32
	var mu sync.Mutex
	var conns []net.Conn
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			conns = append(conns, conn)
			mu.Unlock()
			go func(c net.Conn) {
				defer c.Close()
				authed := false
				buf := make([]byte, 1460)
				for {
					n, err := c.Read(buf)
					if err != nil {
						return
					}
					id, ptype, body, err := readPacket(bytes.NewReader(buf[:n]))
					if err != nil {
						return
					}
					switch ptype {
					case typeAuth:
						if body == password {
							authed = true
							writePacket(c, id, typeCommand, "")
						} else {
							writePacket(c, -1, typeCommand, "")
						}
					case typeCommand:
						if !authed {
							return
						}
						atomic.AddInt32(&ran, 1)
						out := reply(body)
						for len(out) > 4096 {
							writePacket(c, id, typeResponse, out[:4096])
							out = out[4096:]
						}
						writePacket(c, id, typeResponse, out)
					default:
						writePacket(c, id, typeResponse, fmt.Sprintf("Unknown request %x", ptype))
					}
				}
			}(conn)
		}
	}()
	restart := func() {
		mu.Lock()
		for _, c := range conns {
			c.Close()
		}
		conns = nil
		mu.Unlock()
	}
	return ln.Addr().String(), &ran, restart
}

func TestRCON(t *testing.T) {
	long := strings.Repeat("x", 9000)
	addr, ran, restart := fakeServer(t, "s3cret", func(cmd string) string {
		if cmd == "long" {
			return long
		}
		return "§aThere are 0 of a max of 20 players online"
	})
	file := filepath.Join(t.TempDir(), ".rcon-cli.env")
	os.WriteFile(file, []byte("password=s3cret\nport=25575\n"), 0o600)
	r := NewRCON(addr, file)
	defer r.Close()
	got, err := r.Command("list")
	if err != nil || got != "There are 0 of a max of 20 players online" {
		t.Fatalf("%q %v", got, err)
	}
	if got, err := r.Command("long"); err != nil || got != long {
		t.Fatalf("a long reply comes back whole: %d bytes, %v", len(got), err)
	}
	if got, err := r.Command("say"); err != nil || got == "" {
		t.Fatalf("an empty-ish reply still ends: %q %v", got, err)
	}

	// The server restarts: the kept connection is dead. The next command reconnects and runs exactly once.
	before := atomic.LoadInt32(ran)
	restart()
	time.Sleep(50 * time.Millisecond)
	if _, err := r.Command("list"); err != nil {
		t.Fatalf("after a restart: %v", err)
	}
	if n := atomic.LoadInt32(ran) - before; n != 1 {
		t.Fatalf("the command ran %d times", n)
	}

	os.WriteFile(file, []byte("password=wrong\n"), 0o600)
	bad := NewRCON(addr, file)
	if _, err := bad.Command("list"); err == nil {
		t.Fatal("wrong password")
	}
}

func TestHubFollowsTheLogAndANewOne(t *testing.T) {
	file := filepath.Join(t.TempDir(), "latest.log")
	os.WriteFile(file, []byte("old line\n"), 0o644)
	h := NewHub(file, 100)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go h.Run(ctx)
	backlogHas := func(want string) bool {
		deadline := time.Now().Add(3 * time.Second)
		for time.Now().Before(deadline) {
			lines, _, done := h.Subscribe()
			done()
			for _, l := range lines {
				if l == want {
					return true
				}
			}
			time.Sleep(50 * time.Millisecond)
		}
		return false
	}
	if !backlogHas("old line") {
		t.Fatal("the existing log is read")
	}
	f, _ := os.OpenFile(file, os.O_APPEND|os.O_WRONLY, 0o644)
	f.WriteString("joined the game\npartial")
	f.Close()
	if !backlogHas("joined the game") {
		t.Fatal("new lines appear")
	}
	// The server archives the log and starts a new one.
	os.Rename(file, file+".1")
	os.WriteFile(file, []byte("Starting minecraft server\n"), 0o644)
	if !backlogHas("Starting minecraft server") {
		t.Fatal("the new log is followed")
	}
}
