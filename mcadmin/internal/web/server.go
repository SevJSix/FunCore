// Package web is the panel's website: the API and the page that uses it.
package web

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"mime"
	"net"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"

	"mcadmin/internal/audit"
	"mcadmin/internal/auth"
	"mcadmin/internal/console"
	"mcadmin/internal/files"
)

//go:embed static
var staticFiles embed.FS

const (
	cookieName  = "mcadmin_session"
	maxJSON     = 6 << 20   // a request body with JSON in it (the editor saves up to 5 MB)
	maxEditable = 5 << 20   // the biggest file the editor opens
	maxUpload   = 512 << 20 // the biggest upload (a world backup, a plugin)
)

// Power is the power helper's client (nil if there's none).
type Power interface {
	Status(ctx context.Context) (json.RawMessage, error)
	Do(ctx context.Context, action string) error
}

// Config is everything the server needs.
type Config struct {
	Origin   string // https://mcadmin.example.com: the only origin allowed to change things
	Insecure bool   // plain HTTP (local testing only): the cookie isn't marked Secure
	Users    *auth.Users
	Sessions *auth.Sessions
	Logins   *auth.Limiter // failures per IP
	Accounts *auth.Limiter // failures per account name
	Audit    *audit.Log
	Files    *files.Roots
	Roots    []files.RootSpec
	RCON     *console.RCON
	Hub      *console.Hub
	Power    Power
}

// Server serves the panel.
type Server struct {
	cfg Config
	mux *http.ServeMux
}

// New makes the server.
func New(cfg Config) *Server {
	s := &Server{cfg: cfg, mux: http.NewServeMux()}
	static, _ := fs.Sub(staticFiles, "static")
	s.mux.Handle("GET /static/", http.StripPrefix("/static/", http.FileServerFS(static)))
	s.mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		data, _ := fs.ReadFile(static, "index.html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(data)
	})

	s.mux.HandleFunc("POST /api/login", s.login)
	s.mux.HandleFunc("POST /api/logout", s.logout)
	s.handle("GET /api/me", s.me, anyone, allowMustChange)
	s.handle("POST /api/account/password", s.changeOwnPassword, anyone, allowMustChange)

	s.handle("GET /api/files/roots", s.listRoots, anyone, 0)
	s.handle("GET /api/files/list", s.listFiles, anyone, 0)
	s.handle("GET /api/files/read", s.readFile, anyone, 0)
	s.handle("GET /api/files/download", s.download, anyone, 0)
	s.handle("POST /api/files/write", s.writeFile, anyone, 0)
	s.handle("POST /api/files/upload", s.upload, anyone, 0)
	s.handle("POST /api/files/mkdir", s.mkdir, anyone, 0)
	s.handle("POST /api/files/rename", s.rename, anyone, 0)
	s.handle("POST /api/files/delete", s.deleteFile, anyone, 0)

	s.handle("GET /api/console/stream", s.consoleStream, anyone, 0)
	s.handle("POST /api/console/command", s.consoleCommand, anyone, 0)

	s.handle("GET /api/power", s.powerStatus, anyone, 0)
	s.handle("POST /api/power/{action}", s.powerAction, anyone, 0)

	s.handle("GET /api/users", s.listUsers, ownerOnly, 0)
	s.handle("POST /api/users", s.addUser, ownerOnly, 0)
	s.handle("POST /api/users/{name}/role", s.setRole, ownerOnly, 0)
	s.handle("POST /api/users/{name}/reset", s.resetPassword, ownerOnly, 0)
	s.handle("DELETE /api/users/{name}", s.deleteUser, ownerOnly, 0)
	s.handle("GET /api/audit", s.listAudit, ownerOnly, 0)
	return s
}

// ServeHTTP adds the security headers and refuses cross-site changes.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "+
		"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("X-Frame-Options", "DENY")
	h.Set("Referrer-Policy", "no-referrer")
	if r.Method != http.MethodGet && r.Method != http.MethodHead && !s.sameOrigin(r) {
		jsonError(w, http.StatusForbidden, "cross-site request refused")
		return
	}
	s.mux.ServeHTTP(w, r)
}

// sameOrigin reports whether a request that changes something came from the panel's own page. With the
// SameStrict cookie this is belt and braces against another site making an admin's browser act for them.
func (s *Server) sameOrigin(r *http.Request) bool {
	if origin := r.Header.Get("Origin"); origin != "" {
		return strings.EqualFold(origin, s.cfg.Origin)
	}
	return r.Header.Get("Sec-Fetch-Site") == "same-origin"
}

// ------------------------------------------------------------------------------------------------------------
// Sessions
// ------------------------------------------------------------------------------------------------------------

type access int

const (
	anyone access = iota
	ownerOnly
)

const allowMustChange = 1 // the route works before a generated password is replaced

type request struct {
	user  auth.User
	ip    string
	token string
}

type handler func(w http.ResponseWriter, r *http.Request, req request)

func (s *Server) handle(pattern string, h handler, who access, flags int) {
	s.mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(cookieName)
		if err != nil {
			jsonError(w, http.StatusUnauthorized, "log in first")
			return
		}
		sess, ok := s.cfg.Sessions.Get(cookie.Value)
		if !ok {
			jsonError(w, http.StatusUnauthorized, "your session ended: log in again")
			return
		}
		user, ok := s.cfg.Users.Get(sess.User)
		if !ok {
			s.cfg.Sessions.Delete(cookie.Value)
			jsonError(w, http.StatusUnauthorized, "your account was removed")
			return
		}
		if user.MustChange && flags&allowMustChange == 0 {
			jsonError(w, http.StatusForbidden, "choose a new password first")
			return
		}
		if who == ownerOnly && user.Role != auth.Owner {
			jsonError(w, http.StatusForbidden, "only owners can do that")
			return
		}
		h(w, r, request{user: user, ip: clientIP(r), token: cookie.Value})
	})
}

// clientIP is the visitor's address. The panel is only reachable through Caddy, which sets X-Forwarded-For to the
// address it saw (it doesn't pass on one sent by the visitor).
func clientIP(r *http.Request) string {
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		return strings.TrimSpace(strings.Split(fwd, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (s *Server) setCookie(w http.ResponseWriter, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: value, Path: "/", MaxAge: maxAge,
		HttpOnly: true, Secure: !s.cfg.Insecure, SameSite: http.SameSiteStrictMode,
	})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var body struct{ User, Password string }
	if !readJSON(w, r, &body) {
		return
	}
	ip := clientIP(r)
	name := strings.ToLower(strings.TrimSpace(body.User))
	if blocked, wait := s.cfg.Logins.Blocked(ip); blocked {
		jsonError(w, http.StatusTooManyRequests, fmt.Sprintf("too many failed logins: try again in %d minutes", minutes(wait)))
		return
	}
	if blocked, wait := s.cfg.Accounts.Blocked(name); blocked {
		jsonError(w, http.StatusTooManyRequests, fmt.Sprintf("too many failed logins for that account: try again in %d minutes", minutes(wait)))
		return
	}
	user, ok := s.cfg.Users.Verify(name, body.Password)
	if !ok {
		s.cfg.Logins.Fail(ip)
		s.cfg.Accounts.Fail(name)
		s.cfg.Audit.Write(name, ip, "login failed", "")
		time.Sleep(300 * time.Millisecond)
		jsonError(w, http.StatusUnauthorized, "wrong name or password")
		return
	}
	s.cfg.Logins.Reset(ip)
	s.cfg.Accounts.Reset(name)
	s.setCookie(w, s.cfg.Sessions.New(user.Name, ip), 0) // a browser-session cookie; the server ends it too
	s.cfg.Audit.Write(user.Name, ip, "login", "")
	writeJSON(w, user.Public())
}

func minutes(d time.Duration) int {
	m := int(d.Minutes() + 0.999)
	if m < 1 {
		return 1
	}
	return m
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie(cookieName); err == nil {
		if sess, ok := s.cfg.Sessions.Get(cookie.Value); ok {
			s.cfg.Audit.Write(sess.User, clientIP(r), "logout", "")
		}
		s.cfg.Sessions.Delete(cookie.Value)
	}
	s.setCookie(w, "", -1)
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) me(w http.ResponseWriter, r *http.Request, req request) {
	writeJSON(w, req.user.Public())
}

func (s *Server) changeOwnPassword(w http.ResponseWriter, r *http.Request, req request) {
	var body struct{ Current, Next string }
	if !readJSON(w, r, &body) {
		return
	}
	if err := s.cfg.Users.ChangeOwn(req.user.Name, body.Current, body.Next); err != nil {
		jsonError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.cfg.Sessions.DeleteUser(req.user.Name, req.token) // other browsers log out
	s.cfg.Audit.Write(req.user.Name, req.ip, "changed own password", "")
	writeJSON(w, map[string]bool{"ok": true})
}

// ------------------------------------------------------------------------------------------------------------
// Files
// ------------------------------------------------------------------------------------------------------------

func (s *Server) listRoots(w http.ResponseWriter, r *http.Request, req request) {
	writeJSON(w, s.cfg.Roots)
}

func (s *Server) listFiles(w http.ResponseWriter, r *http.Request, req request) {
	q := r.URL.Query()
	entries, err := s.cfg.Files.List(q.Get("root"), q.Get("path"))
	if err != nil {
		fileError(w, err)
		return
	}
	writeJSON(w, entries)
}

func (s *Server) readFile(w http.ResponseWriter, r *http.Request, req request) {
	q := r.URL.Query()
	text, err := s.cfg.Files.ReadText(q.Get("root"), q.Get("path"), maxEditable)
	if err != nil {
		fileError(w, err)
		return
	}
	writeJSON(w, map[string]string{"content": text})
}

func (s *Server) download(w http.ResponseWriter, r *http.Request, req request) {
	q := r.URL.Query()
	f, info, err := s.cfg.Files.OpenRead(q.Get("root"), q.Get("path"))
	if err != nil {
		fileError(w, err)
		return
	}
	defer f.Close()
	s.cfg.Audit.Write(req.user.Name, req.ip, "downloaded", q.Get("root")+":"+q.Get("path"))
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": info.Name()}))
	http.ServeContent(w, r, info.Name(), info.ModTime(), f)
}

func (s *Server) writeFile(w http.ResponseWriter, r *http.Request, req request) {
	var body struct{ Root, Path, Content string }
	if !readJSON(w, r, &body) {
		return
	}
	n, err := s.cfg.Files.Write(body.Root, body.Path, strings.NewReader(body.Content), maxEditable)
	if err != nil {
		fileError(w, err)
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "saved", fmt.Sprintf("%s:%s (%d bytes)", body.Root, body.Path, n))
	writeJSON(w, map[string]bool{"ok": true})
}

// upload takes files from a multipart form, streamed straight to disk (never all in memory).
func (s *Server) upload(w http.ResponseWriter, r *http.Request, req request) {
	q := r.URL.Query()
	root, dir := q.Get("root"), q.Get("path")
	r.Body = http.MaxBytesReader(w, r.Body, maxUpload+1<<20)
	reader, err := r.MultipartReader()
	if err != nil {
		jsonError(w, http.StatusBadRequest, "expected a file upload")
		return
	}
	var saved []string
	for {
		part, err := reader.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			jsonError(w, http.StatusBadRequest, "the upload was cut off: "+err.Error())
			return
		}
		name := part.FileName()
		if name == "" {
			part.Close()
			continue
		}
		name = path.Base(strings.ReplaceAll(name, "\\", "/"))
		if err := files.ValidName(name); err != nil {
			part.Close()
			jsonError(w, http.StatusBadRequest, err.Error())
			return
		}
		target := path.Join(dir, name)
		n, err := s.cfg.Files.Write(root, target, part, maxUpload)
		part.Close()
		if err != nil {
			fileError(w, err)
			return
		}
		saved = append(saved, name)
		s.cfg.Audit.Write(req.user.Name, req.ip, "uploaded", fmt.Sprintf("%s:%s (%d bytes)", root, target, n))
	}
	writeJSON(w, map[string]any{"saved": saved})
}

func (s *Server) mkdir(w http.ResponseWriter, r *http.Request, req request) {
	var body struct{ Root, Path string }
	if !readJSON(w, r, &body) {
		return
	}
	if err := files.ValidName(path.Base(body.Path)); err != nil {
		jsonError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := s.cfg.Files.Mkdir(body.Root, body.Path); err != nil {
		fileError(w, err)
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "made folder", body.Root+":"+body.Path)
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) rename(w http.ResponseWriter, r *http.Request, req request) {
	var body struct{ Root, From, To string }
	if !readJSON(w, r, &body) {
		return
	}
	if err := files.ValidName(path.Base(body.To)); err != nil {
		jsonError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := s.cfg.Files.Rename(body.Root, body.From, body.To); err != nil {
		fileError(w, err)
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "renamed", fmt.Sprintf("%s:%s -> %s", body.Root, body.From, body.To))
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) deleteFile(w http.ResponseWriter, r *http.Request, req request) {
	var body struct{ Root, Path string }
	if !readJSON(w, r, &body) {
		return
	}
	if err := s.cfg.Files.Delete(body.Root, body.Path); err != nil {
		fileError(w, err)
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "deleted", body.Root+":"+body.Path)
	writeJSON(w, map[string]bool{"ok": true})
}

func fileError(w http.ResponseWriter, err error) {
	var maxErr *http.MaxBytesError
	switch {
	case errors.As(err, &maxErr):
		jsonError(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("uploads are at most %d MB", maxUpload>>20))
	case errors.Is(err, fs.ErrNotExist):
		jsonError(w, http.StatusNotFound, "it isn't there (anymore?)")
	case errors.Is(err, fs.ErrPermission):
		jsonError(w, http.StatusForbidden, "the panel isn't allowed to change that file")
	case errors.Is(err, files.ErrUnknownRoot), errors.Is(err, files.ErrBadPath), errors.Is(err, files.ErrIsRoot),
		errors.Is(err, files.ErrExists), errors.Is(err, files.ErrTooBig), errors.Is(err, files.ErrBinary),
		errors.Is(err, files.ErrNotDir), errors.Is(err, files.ErrIsDir):
		jsonError(w, http.StatusBadRequest, err.Error())
	default:
		if strings.Contains(err.Error(), "path escapes") {
			jsonError(w, http.StatusBadRequest, files.ErrBadPath.Error())
			return
		}
		log.Printf("file error: %v", err)
		jsonError(w, http.StatusInternalServerError, err.Error())
	}
}

// ------------------------------------------------------------------------------------------------------------
// Console
// ------------------------------------------------------------------------------------------------------------

// consoleStream sends the log, live, as server-sent events: the recent lines, then each new one.
func (s *Server) consoleStream(w http.ResponseWriter, r *http.Request, req request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		jsonError(w, http.StatusInternalServerError, "streaming isn't supported")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	backlog, lines, cancel := s.cfg.Hub.Subscribe()
	defer cancel()
	send := func(line string) {
		data, _ := json.Marshal(line)
		fmt.Fprintf(w, "data: %s\n\n", data)
	}
	for _, line := range backlog {
		send(line)
	}
	flusher.Flush()
	heartbeat := time.NewTicker(20 * time.Second)
	defer heartbeat.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case line, ok := <-lines:
			if !ok {
				return
			}
			send(line)
			// Send whatever else is waiting in one go.
			for more := true; more; {
				select {
				case l, ok := <-lines:
					if !ok {
						return
					}
					send(l)
				default:
					more = false
				}
			}
			flusher.Flush()
		case <-heartbeat.C:
			// Still allowed? (Removed accounts and ended sessions stop watching.)
			if _, ok := s.cfg.Sessions.Get(req.token); !ok {
				return
			}
			fmt.Fprint(w, ": keep-alive\n\n")
			flusher.Flush()
		}
	}
}

func (s *Server) consoleCommand(w http.ResponseWriter, r *http.Request, req request) {
	var body struct{ Command string }
	if !readJSON(w, r, &body) {
		return
	}
	cmd := strings.TrimPrefix(strings.TrimSpace(body.Command), "/")
	if cmd == "" || len(cmd) > 1400 || strings.ContainsAny(cmd, "\r\n") {
		jsonError(w, http.StatusBadRequest, "enter one command (no new lines)")
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "command", cmd)
	s.cfg.Hub.Publish(fmt.Sprintf("» %s: /%s", req.user.Name, cmd))
	reply, err := s.cfg.RCON.Command(cmd)
	if err != nil {
		s.cfg.Hub.Publish("» (failed: " + err.Error() + ")")
		jsonError(w, http.StatusBadGateway, err.Error())
		return
	}
	for _, line := range strings.Split(strings.TrimRight(reply, "\n"), "\n") {
		if strings.TrimSpace(line) != "" {
			s.cfg.Hub.Publish("» " + line)
		}
	}
	writeJSON(w, map[string]string{"reply": reply})
}

// ------------------------------------------------------------------------------------------------------------
// Power
// ------------------------------------------------------------------------------------------------------------

func (s *Server) powerStatus(w http.ResponseWriter, r *http.Request, req request) {
	if s.cfg.Power == nil {
		writeJSON(w, map[string]string{"state": "unknown", "lastError": "power controls aren't set up"})
		return
	}
	status, err := s.cfg.Power.Status(r.Context())
	if err != nil {
		writeJSON(w, map[string]string{"state": "unknown", "lastError": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(status)
}

func (s *Server) powerAction(w http.ResponseWriter, r *http.Request, req request) {
	action := r.PathValue("action")
	if action != "start" && action != "stop" && action != "restart" {
		jsonError(w, http.StatusNotFound, "unknown action")
		return
	}
	if s.cfg.Power == nil {
		jsonError(w, http.StatusServiceUnavailable, "power controls aren't set up")
		return
	}
	if err := s.cfg.Power.Do(r.Context(), action); err != nil {
		jsonError(w, http.StatusConflict, err.Error())
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "power", action)
	s.cfg.Hub.Publish(fmt.Sprintf("» %s: %s the server", req.user.Name, map[string]string{
		"start": "started", "stop": "stopped", "restart": "restarted"}[action]))
	writeJSON(w, map[string]bool{"ok": true})
}

// ------------------------------------------------------------------------------------------------------------
// Accounts (owners)
// ------------------------------------------------------------------------------------------------------------

func (s *Server) listUsers(w http.ResponseWriter, r *http.Request, req request) {
	users, err := s.cfg.Users.List()
	if err != nil {
		jsonError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, users)
}

// addUser makes an account with a generated password, shown once; they choose their own on first login.
func (s *Server) addUser(w http.ResponseWriter, r *http.Request, req request) {
	var body struct {
		Name string
		Role auth.Role
	}
	if !readJSON(w, r, &body) {
		return
	}
	password := auth.GeneratePassword()
	if err := s.cfg.Users.Add(strings.TrimSpace(body.Name), password, body.Role, true); err != nil {
		jsonError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "added account", fmt.Sprintf("%s (%s)", body.Name, body.Role))
	writeJSON(w, map[string]string{"password": password})
}

func (s *Server) setRole(w http.ResponseWriter, r *http.Request, req request) {
	var body struct{ Role auth.Role }
	if !readJSON(w, r, &body) {
		return
	}
	name := r.PathValue("name")
	if err := s.cfg.Users.SetRole(name, body.Role); err != nil {
		jsonError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.cfg.Audit.Write(req.user.Name, req.ip, "changed role", fmt.Sprintf("%s -> %s", name, body.Role))
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) resetPassword(w http.ResponseWriter, r *http.Request, req request) {
	name := r.PathValue("name")
	password := auth.GeneratePassword()
	if err := s.cfg.Users.SetPassword(name, password, true); err != nil {
		jsonError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.cfg.Sessions.DeleteUser(name, "")
	s.cfg.Audit.Write(req.user.Name, req.ip, "reset password", name)
	writeJSON(w, map[string]string{"password": password})
}

func (s *Server) deleteUser(w http.ResponseWriter, r *http.Request, req request) {
	name := r.PathValue("name")
	if strings.EqualFold(name, req.user.Name) {
		jsonError(w, http.StatusBadRequest, "you can't remove your own account")
		return
	}
	if err := s.cfg.Users.Delete(name); err != nil {
		jsonError(w, http.StatusBadRequest, err.Error())
		return
	}
	s.cfg.Sessions.DeleteUser(name, "")
	s.cfg.Audit.Write(req.user.Name, req.ip, "removed account", name)
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) listAudit(w http.ResponseWriter, r *http.Request, req request) {
	entries, err := s.cfg.Audit.Recent(500)
	if err != nil {
		jsonError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, entries)
}

// ------------------------------------------------------------------------------------------------------------
// JSON
// ------------------------------------------------------------------------------------------------------------

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if ct, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); ct != "application/json" {
		jsonError(w, http.StatusUnsupportedMediaType, "expected JSON")
		return false
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxJSON))
	if err := dec.Decode(v); err != nil {
		jsonError(w, http.StatusBadRequest, "bad request: "+err.Error())
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

func jsonError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": message})
}

// PowerClient talks to the power helper.
type PowerClient struct {
	URL   string
	Token string
	HTTP  *http.Client
}

func (p *PowerClient) request(ctx context.Context, method, action string) (*http.Response, error) {
	u, err := url.JoinPath(p.URL, action)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, method, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Power-Token", p.Token)
	resp, err := p.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("can't reach the power helper: %w", err)
	}
	return resp, nil
}

// Status returns the helper's report on the container.
func (p *PowerClient) Status(ctx context.Context) (json.RawMessage, error) {
	resp, err := p.request(ctx, http.MethodGet, "status")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("power helper: %s", strings.TrimSpace(string(data)))
	}
	return data, nil
}

// Do starts an action (start, stop, restart).
func (p *PowerClient) Do(ctx context.Context, action string) error {
	resp, err := p.request(ctx, http.MethodPost, action)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusAccepted {
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("%s", strings.TrimSpace(string(data)))
	}
	return nil
}
