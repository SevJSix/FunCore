package web

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"mcadmin/internal/audit"
	"mcadmin/internal/auth"
	"mcadmin/internal/console"
	"mcadmin/internal/files"
)

const origin = "https://mcadmin.test"

type harness struct {
	t      *testing.T
	srv    *httptest.Server
	users  *auth.Users
	data   string
	secret string
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	base := t.TempDir()
	data := filepath.Join(base, "data")
	plugins := filepath.Join(base, "plugins")
	secret := filepath.Join(base, "secret")
	for _, d := range []string{data, plugins, secret, filepath.Join(data, "logs")} {
		os.MkdirAll(d, 0o755)
	}
	os.WriteFile(filepath.Join(secret, "key"), []byte("top secret"), 0o600)
	roots := []files.RootSpec{{Name: "data", Label: "Server", Dir: data}, {Name: "plugins", Label: "Plugins", Dir: plugins}}
	fr, err := files.Open(roots)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(fr.Close)
	users := auth.NewUsers(filepath.Join(base, "users.json"))
	users.Add("owner", "owner password 1", auth.Owner, false)
	users.Add("admin", "admin password 1", auth.Admin, false)
	users.Add("newbie", "generated pass 1", auth.Admin, true)
	s := New(Config{
		Origin: origin, Insecure: true, Users: users,
		Sessions: auth.NewSessions(time.Hour, 24*time.Hour),
		Logins:   auth.NewLimiter(20, time.Minute), Accounts: auth.NewLimiter(3, time.Minute),
		Audit: audit.New(filepath.Join(base, "audit.log")), Files: fr, Roots: roots,
		RCON: console.NewRCON("127.0.0.1:1", filepath.Join(data, ".rcon-cli.env")),
		Hub:  console.NewHub(filepath.Join(data, "logs", "latest.log"), 10),
	})
	srv := httptest.NewServer(s)
	t.Cleanup(srv.Close)
	return &harness{t: t, srv: srv, users: users, data: data, secret: secret}
}

// do sends a request as the panel's own page would (same origin), with the session cookie if given.
func (h *harness) do(method, path, cookie string, body any) (int, string) {
	h.t.Helper()
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, h.srv.URL+path, r)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if method != http.MethodGet {
		req.Header.Set("Origin", origin)
	}
	if cookie != "" {
		req.AddCookie(&http.Cookie{Name: cookieName, Value: cookie})
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(data)
}

func (h *harness) login(name, password string) string {
	h.t.Helper()
	b, _ := json.Marshal(map[string]string{"user": name, "password": password})
	req, _ := http.NewRequest(http.MethodPost, h.srv.URL+"/api/login", bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", origin)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	resp.Body.Close()
	for _, c := range resp.Cookies() {
		if c.Name == cookieName {
			if !c.HttpOnly || c.SameSite != http.SameSiteStrictMode {
				h.t.Fatal("the session cookie must be HttpOnly and SameSite=Strict")
			}
			return c.Value
		}
	}
	return ""
}

func TestNeedsLogin(t *testing.T) {
	h := newHarness(t)
	for _, p := range []string{"/api/me", "/api/files/list?root=data", "/api/console/stream", "/api/users", "/api/power"} {
		if code, _ := h.do(http.MethodGet, p, "", nil); code != http.StatusUnauthorized {
			t.Errorf("%s without a login: %d", p, code)
		}
	}
	if code, _ := h.do(http.MethodGet, "/api/me", "made-up-token", nil); code != http.StatusUnauthorized {
		t.Error("a made-up session")
	}
	if code, body := h.do(http.MethodGet, "/", "", nil); code != http.StatusOK || !strings.Contains(body, "<") {
		t.Error("the page itself loads")
	}
}

func TestLoginAndThrottle(t *testing.T) {
	h := newHarness(t)
	if h.login("admin", "wrong") != "" {
		t.Fatal("wrong password logged in")
	}
	if c := h.login("ADMIN", "admin password 1"); c == "" {
		t.Fatal("right password, any case")
	}
	for i := 0; i < 3; i++ {
		h.login("owner", "guess")
	}
	if h.login("owner", "owner password 1") != "" {
		t.Fatal("after 3 failures the account is locked for a while, even with the right password")
	}
}

func TestCrossSiteChangesRefused(t *testing.T) {
	h := newHarness(t)
	cookie := h.login("owner", "owner password 1")
	b, _ := json.Marshal(map[string]string{"root": "data", "path": "evil"})
	for _, from := range []string{"https://evil.example", ""} {
		req, _ := http.NewRequest(http.MethodPost, h.srv.URL+"/api/files/mkdir", bytes.NewReader(b))
		req.Header.Set("Content-Type", "application/json")
		if from != "" {
			req.Header.Set("Origin", from)
		}
		req.AddCookie(&http.Cookie{Name: cookieName, Value: cookie})
		resp, _ := http.DefaultClient.Do(req)
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("origin %q: %d", from, resp.StatusCode)
		}
	}
	if _, err := os.Stat(filepath.Join(h.data, "evil")); err == nil {
		t.Fatal("a cross-site request made a folder")
	}
}

func TestMustChangePasswordFirst(t *testing.T) {
	h := newHarness(t)
	cookie := h.login("newbie", "generated pass 1")
	if code, _ := h.do(http.MethodGet, "/api/files/list?root=data", cookie, nil); code != http.StatusForbidden {
		t.Fatal("nothing else until the generated password is changed")
	}
	if code, _ := h.do(http.MethodPost, "/api/account/password", cookie, map[string]string{"current": "generated pass 1", "next": "my own password"}); code != 200 {
		t.Fatal("change it")
	}
	if code, _ := h.do(http.MethodGet, "/api/files/list?root=data", cookie, nil); code != 200 {
		t.Fatal("then the panel works")
	}
}

func TestOwnersOnly(t *testing.T) {
	h := newHarness(t)
	admin := h.login("admin", "admin password 1")
	if code, _ := h.do(http.MethodGet, "/api/users", admin, nil); code != http.StatusForbidden {
		t.Fatal("admins can't list accounts")
	}
	if code, _ := h.do(http.MethodPost, "/api/users", admin, map[string]string{"name": "sneaky", "role": "owner"}); code != http.StatusForbidden {
		t.Fatal("admins can't add accounts")
	}
	owner := h.login("owner", "owner password 1")
	code, body := h.do(http.MethodPost, "/api/users", owner, map[string]string{"name": "friend", "role": "admin"})
	if code != 200 || !strings.Contains(body, "password") {
		t.Fatalf("owner adds an account: %d %s", code, body)
	}
	if code, _ := h.do(http.MethodPost, "/api/users/admin/reset", owner, map[string]string{}); code != 200 {
		t.Fatal("reset")
	}
	if code, _ := h.do(http.MethodGet, "/api/me", admin, nil); code != http.StatusUnauthorized {
		t.Fatal("a reset logs the account out everywhere")
	}
	if code, _ := h.do(http.MethodDelete, "/api/users/owner", owner, nil); code != http.StatusBadRequest {
		t.Fatal("can't remove yourself")
	}
}

func TestFilesThroughTheAPI(t *testing.T) {
	h := newHarness(t)
	c := h.login("admin", "admin password 1")
	if code, _ := h.do(http.MethodPost, "/api/files/write", c, map[string]string{"root": "data", "path": "server.properties", "content": "motd=hi\n"}); code != 200 {
		t.Fatal("save")
	}
	if code, body := h.do(http.MethodGet, "/api/files/read?root=data&path=server.properties", c, nil); code != 200 || !strings.Contains(body, "motd=hi") {
		t.Fatalf("read back: %d %s", code, body)
	}
	for _, p := range []string{"../secret/key", "..%2Fsecret%2Fkey", "%2e%2e/secret/key", "/../../secret/key"} {
		code, body := h.do(http.MethodGet, "/api/files/read?root=data&path="+p, c, nil)
		if code == 200 || strings.Contains(body, "top secret") {
			t.Errorf("escaped with %q: %d %s", p, code, body)
		}
	}
	if code, _ := h.do(http.MethodGet, "/api/files/list?root=etc&path=/", c, nil); code != http.StatusBadRequest {
		t.Error("unknown root")
	}
	if err := os.Symlink(h.secret, filepath.Join(h.data, "out")); err == nil {
		if code, body := h.do(http.MethodGet, "/api/files/read?root=data&path=out/key", c, nil); code == 200 || strings.Contains(body, "top secret") {
			t.Errorf("escaped through a symlink: %d %s", code, body)
		}
	}
	// Upload.
	var buf bytes.Buffer
	buf.WriteString("--X\r\nContent-Disposition: form-data; name=\"file\"; filename=\"..\\..\\Plugin.jar\"\r\n\r\nJARDATA\r\n--X--\r\n")
	req, _ := http.NewRequest(http.MethodPost, h.srv.URL+"/api/files/upload?root=plugins&path=", &buf)
	req.Header.Set("Content-Type", "multipart/form-data; boundary=X")
	req.Header.Set("Origin", origin)
	req.AddCookie(&http.Cookie{Name: cookieName, Value: c})
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("upload: %d", resp.StatusCode)
	}
	if code, body := h.do(http.MethodGet, "/api/files/list?root=plugins", c, nil); code != 200 || !strings.Contains(body, "Plugin.jar") {
		t.Fatalf("an uploaded file's folder parts are dropped: %s", body)
	}
	if code, _ := h.do(http.MethodPost, "/api/files/delete", c, map[string]string{"root": "data", "path": "/"}); code != http.StatusBadRequest {
		t.Fatal("the top folder can't be deleted")
	}
}

func TestConsoleCommandWithoutAServer(t *testing.T) {
	h := newHarness(t)
	c := h.login("admin", "admin password 1")
	code, body := h.do(http.MethodPost, "/api/console/command", c, map[string]string{"command": "list"})
	if code != http.StatusBadGateway || !strings.Contains(body, "error") {
		t.Fatalf("a clear error when the server's console can't be reached: %d %s", code, body)
	}
	if code, _ := h.do(http.MethodPost, "/api/console/command", c, map[string]string{"command": "say a\nop me"}); code != http.StatusBadRequest {
		t.Fatal("one command at a time")
	}
}
