// Package console runs commands on the Minecraft server (over RCON) and follows its log.
package console

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	typeResponse = 0
	typeCommand  = 2
	typeAuth     = 3
	maxPacket    = 4096 + 14 // Minecraft splits longer replies into packets of 4096 bytes of text
)

// RCON is a connection to the server's remote console, opened when first needed and again after any error. The
// password is read from a file each time it connects (the server image writes it there, and may change it).
type RCON struct {
	mu           sync.Mutex
	addr         string
	passwordFile string
	conn         net.Conn
	reader       *bufio.Reader
	nextID       int32
	timeout      time.Duration
}

// NewRCON talks to addr ("mc:25575"), with the password from passwordFile (a line "password=...").
func NewRCON(addr, passwordFile string) *RCON {
	return &RCON{addr: addr, passwordFile: passwordFile, timeout: 10 * time.Second}
}

var errAuth = errors.New("the server refused the RCON password")

// errStale means a kept connection had died before the command got through (the server restarted since): the
// command never ran, so it's safe to send again on a fresh connection.
var errStale = errors.New("stale connection")

// Command runs a console command and returns what the server replied (colour codes removed). A command is only ever
// sent twice if the first try certainly didn't reach the server: never after a timeout, when it may have run.
func (r *RCON) Command(cmd string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	reply, err := r.try(cmd)
	if errors.Is(err, errStale) {
		r.close()
		reply, err = r.try(cmd)
	}
	if err != nil {
		r.close()
		if errors.Is(err, errStale) {
			err = errors.New("lost the connection to the server's console")
		}
	}
	return reply, err
}

func (r *RCON) try(cmd string) (string, error) {
	reused := r.conn != nil
	if !reused {
		if err := r.connect(); err != nil {
			return "", err
		}
	}
	r.conn.SetDeadline(time.Now().Add(r.timeout))
	id := r.id()
	end := r.id()
	if err := writePacket(r.conn, id, typeCommand, cmd); err != nil {
		if reused {
			return "", errStale
		}
		return "", err
	}
	// The server reads one packet at a time (anything else in the same read is dropped), so the end marker only
	// goes once the reply has started: a second, unknown request, which it answers after the whole reply (long
	// replies come in several packets).
	var out strings.Builder
	sentEnd := false
	for {
		pid, _, body, err := readPacket(r.reader)
		if err != nil {
			// A connection that was closed under us, before any reply: the command never arrived.
			if reused && !sentEnd && out.Len() == 0 && !isTimeout(err) && (errors.Is(err, io.EOF) || isReset(err)) {
				return "", errStale
			}
			if isTimeout(err) {
				return "", errors.New("the server didn't answer in time (the command may still have run)")
			}
			return "", err
		}
		if pid == end {
			return stripColours(out.String()), nil
		}
		if pid == id {
			out.WriteString(body)
			if !sentEnd {
				if err := writePacket(r.conn, end, typeResponse, ""); err != nil {
					return stripColours(out.String()), nil // the command ran; return what came back
				}
				sentEnd = true
			}
		}
	}
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// isReset reports whether the other end dropped the connection.
func isReset(err error) bool {
	if errors.Is(err, syscall.ECONNRESET) || errors.Is(err, syscall.ECONNABORTED) || errors.Is(err, syscall.EPIPE) ||
		errors.Is(err, net.ErrClosed) {
		return true
	}
	msg := err.Error()
	return strings.Contains(msg, "connection reset") || strings.Contains(msg, "forcibly closed") ||
		strings.Contains(msg, "aborted") || strings.Contains(msg, "broken pipe")
}

func (r *RCON) id() int32 {
	r.nextID++
	if r.nextID <= 0 {
		r.nextID = 1
	}
	return r.nextID
}

func (r *RCON) connect() error {
	password, err := readPassword(r.passwordFile)
	if err != nil {
		return err
	}
	conn, err := net.DialTimeout("tcp", r.addr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("can't reach the server's console (is it running?): %w", err)
	}
	conn.SetDeadline(time.Now().Add(r.timeout))
	reader := bufio.NewReader(conn)
	id := r.id()
	if err := writePacket(conn, id, typeAuth, password); err != nil {
		conn.Close()
		return err
	}
	for {
		pid, ptype, _, err := readPacket(reader)
		if err != nil {
			conn.Close()
			return err
		}
		if ptype != typeCommand { // the auth reply has type 2
			continue
		}
		if pid == -1 {
			conn.Close()
			return errAuth
		}
		break
	}
	r.conn, r.reader = conn, reader
	return nil
}

func (r *RCON) close() {
	if r.conn != nil {
		r.conn.Close()
		r.conn, r.reader = nil, nil
	}
}

// Close ends the connection.
func (r *RCON) Close() {
	r.mu.Lock()
	r.close()
	r.mu.Unlock()
}

func readPassword(file string) (string, error) {
	data, err := os.ReadFile(file)
	if err != nil {
		return "", fmt.Errorf("can't read the RCON password: %w", err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if v, ok := strings.CutPrefix(line, "password="); ok {
			return strings.Trim(v, `"'`), nil
		}
		if v, ok := strings.CutPrefix(line, "password:"); ok {
			return strings.Trim(strings.TrimSpace(v), `"'`), nil
		}
	}
	return "", fmt.Errorf("no password in %s", file)
}

func writePacket(w io.Writer, id, ptype int32, body string) error {
	var buf bytes.Buffer
	binary.Write(&buf, binary.LittleEndian, int32(len(body)+10))
	binary.Write(&buf, binary.LittleEndian, id)
	binary.Write(&buf, binary.LittleEndian, ptype)
	buf.WriteString(body)
	buf.Write([]byte{0, 0})
	_, err := w.Write(buf.Bytes())
	return err
}

func readPacket(r io.Reader) (id, ptype int32, body string, err error) {
	var length int32
	if err = binary.Read(r, binary.LittleEndian, &length); err != nil {
		return
	}
	if length < 10 || length > maxPacket {
		err = fmt.Errorf("bad RCON packet length %d", length)
		return
	}
	data := make([]byte, length)
	if _, err = io.ReadFull(r, data); err != nil {
		return
	}
	id = int32(binary.LittleEndian.Uint32(data[0:4]))
	ptype = int32(binary.LittleEndian.Uint32(data[4:8]))
	body = string(bytes.TrimRight(data[8:], "\x00"))
	return
}

var colourCode = regexp.MustCompile("§[0-9a-fk-orx]")

func stripColours(s string) string { return colourCode.ReplaceAllString(s, "") }
