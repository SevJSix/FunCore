package auth

import (
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func TestAccounts(t *testing.T) {
	u := NewUsers(filepath.Join(t.TempDir(), "users.json"))
	if err := u.Add("Ian", "correct horse battery", Owner, false); err != nil {
		t.Fatal(err)
	}
	if err := u.Add("ian", "another password!", Admin, false); !errors.Is(err, ErrExists) {
		t.Fatalf("names are case-insensitive: %v", err)
	}
	if err := u.Add("x", "long enough password", Admin, false); !errors.Is(err, ErrBadName) {
		t.Fatalf("short name: %v", err)
	}
	if err := u.Add("bob", "short", Admin, false); err == nil {
		t.Fatal("short password")
	}
	if _, ok := u.Verify("IAN", "correct horse battery"); !ok {
		t.Fatal("login, any case")
	}
	if _, ok := u.Verify("ian", "wrong"); ok {
		t.Fatal("wrong password")
	}
	if _, ok := u.Verify("nobody", "correct horse battery"); ok {
		t.Fatal("no such account")
	}
	if err := u.Delete("ian"); !errors.Is(err, ErrLastOwner) {
		t.Fatalf("last owner: %v", err)
	}
	if err := u.SetRole("ian", Admin); !errors.Is(err, ErrLastOwner) {
		t.Fatalf("last owner demoted: %v", err)
	}
	u.Add("bob", "bobs password ok", Admin, true)
	if b, _ := u.Get("bob"); !b.MustChange {
		t.Fatal("generated passwords must be changed")
	}
	if err := u.ChangeOwn("bob", "wrong", "a new password!"); !errors.Is(err, ErrBadCurrent) {
		t.Fatal(err)
	}
	if err := u.ChangeOwn("bob", "bobs password ok", "a new password!"); err != nil {
		t.Fatal(err)
	}
	if b, _ := u.Get("bob"); b.MustChange {
		t.Fatal("changed")
	}
	if err := u.SetRole("bob", Owner); err != nil {
		t.Fatal(err)
	}
	if err := u.Delete("ian"); err != nil {
		t.Fatalf("not the last owner any more: %v", err)
	}
	if p := GeneratePassword(); CheckPassword(p) != nil {
		t.Fatalf("generated %q", p)
	}
}

func TestSessionsExpire(t *testing.T) {
	now := time.Now()
	s := NewSessions(time.Hour, 24*time.Hour)
	s.now = func() time.Time { return now }
	token := s.New("ian", "1.2.3.4")
	if _, ok := s.Get(token); !ok {
		t.Fatal("fresh")
	}
	now = now.Add(59 * time.Minute)
	if _, ok := s.Get(token); !ok {
		t.Fatal("still active")
	}
	now = now.Add(61 * time.Minute)
	if _, ok := s.Get(token); ok {
		t.Fatal("idle too long")
	}
	t2 := s.New("ian", "")
	t3 := s.New("ian", "")
	s.DeleteUser("IAN", t3)
	if _, ok := s.Get(t2); ok {
		t.Fatal("other sessions end")
	}
	if _, ok := s.Get(t3); !ok {
		t.Fatal("the kept one stays")
	}
}

func TestLimiter(t *testing.T) {
	now := time.Now()
	l := NewLimiter(3, 10*time.Minute)
	l.now = func() time.Time { return now }
	for i := 0; i < 3; i++ {
		if blocked, _ := l.Blocked("ip"); blocked {
			t.Fatal("not yet")
		}
		l.Fail("ip")
	}
	if blocked, wait := l.Blocked("ip"); !blocked || wait <= 0 {
		t.Fatal("blocked after 3")
	}
	now = now.Add(11 * time.Minute)
	if blocked, _ := l.Blocked("ip"); blocked {
		t.Fatal("the window passed")
	}
}
