// Command power is the panel's power helper: the only part with Docker access, and all it can do is start, stop,
// restart and report on ONE container (mc). It listens only on a private network the website shares, and every
// request needs a shared secret. Keeping it apart means a bug in the website can't reach Docker (which would be root
// on the whole machine).
package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"
)

type helper struct {
	docker    *http.Client
	container string
	timeout   int // seconds Docker waits for a clean stop before killing
	token     string

	mu        sync.Mutex
	busy      string // the action running now, if any
	lastError string
}

func main() {
	token := os.Getenv("POWER_TOKEN")
	if len(token) < 32 {
		log.Fatal("POWER_TOKEN must be set (at least 32 characters)")
	}
	timeout, _ := strconv.Atoi(env("STOP_TIMEOUT", "120"))
	socket := env("DOCKER_SOCKET", "/var/run/docker.sock")
	h := &helper{
		docker: &http.Client{Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return (&net.Dialer{}).DialContext(ctx, "unix", socket)
			},
		}},
		container: env("CONTAINER", "mc"),
		timeout:   timeout,
		token:     token,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /status", h.auth(h.status))
	for _, action := range []string{"start", "stop", "restart"} {
		mux.HandleFunc("POST /"+action, h.auth(h.action(action)))
	}
	addr := env("LISTEN", ":9000")
	log.Printf("power helper for container %q on %s", h.container, addr)
	server := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	log.Fatal(server.ListenAndServe())
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func (h *helper) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Power-Token")), []byte(h.token)) != 1 {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next(w, r)
	}
}

type status struct {
	State     string `json:"state"`            // running, exited, restarting, ...
	Health    string `json:"health,omitempty"` // healthy, starting, unhealthy
	StartedAt string `json:"startedAt,omitempty"`
	Busy      string `json:"busy,omitempty"` // start/stop/restart in progress
	LastError string `json:"lastError,omitempty"`
}

func (h *helper) status(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://docker/containers/"+h.container+"/json", nil)
	resp, err := h.docker.Do(req)
	s := status{}
	if err != nil {
		s.State, s.LastError = "unknown", "can't reach Docker: "+err.Error()
	} else {
		defer resp.Body.Close()
		var info struct {
			State struct {
				Status    string
				StartedAt string
				Health    *struct{ Status string }
			}
		}
		if resp.StatusCode == http.StatusNotFound {
			s.State = "missing"
		} else if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
			s.State = "unknown"
		} else {
			s.State, s.StartedAt = info.State.Status, info.State.StartedAt
			if info.State.Health != nil {
				s.Health = info.State.Health.Status
			}
		}
	}
	h.mu.Lock()
	s.Busy = h.busy
	if s.LastError == "" {
		s.LastError = h.lastError
	}
	h.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(s)
}

// action starts one power action in the background (a stop can take up to two minutes while the server saves).
// Only one runs at a time.
func (h *helper) action(name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		if h.busy != "" {
			busy := h.busy
			h.mu.Unlock()
			http.Error(w, "busy: "+busy+" is still running", http.StatusConflict)
			return
		}
		h.busy, h.lastError = name, ""
		h.mu.Unlock()
		go func() {
			err := h.run(name)
			h.mu.Lock()
			h.busy = ""
			if err != nil {
				h.lastError = name + " failed: " + err.Error()
				log.Print(h.lastError)
			}
			h.mu.Unlock()
		}()
		w.WriteHeader(http.StatusAccepted)
	}
}

func (h *helper) run(name string) error {
	url := "http://docker/containers/" + h.container + "/" + name
	if name != "start" {
		url += "?t=" + strconv.Itoa(h.timeout)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(h.timeout+60)*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	resp, err := h.docker.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	// 204: done; 304: it was already in that state.
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusNotModified {
		return fmt.Errorf("docker said %d: %s", resp.StatusCode, body)
	}
	return nil
}
