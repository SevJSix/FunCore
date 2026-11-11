package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"sync"
	"time"
)

// Session is one logged-in browser.
type Session struct {
	User     string
	Created  time.Time
	LastSeen time.Time
	IP       string
}

// Sessions are kept in memory, by a hash of their token (so a memory dump doesn't hand out live tokens). A restart
// logs everyone out, which is fine for a handful of admins.
type Sessions struct {
	mu   sync.Mutex
	m    map[string]*Session
	idle time.Duration // ends after this long without a request
	max  time.Duration // ends this long after login, however active
	now  func() time.Time
}

// NewSessions makes a session store.
func NewSessions(idle, max time.Duration) *Sessions {
	return &Sessions{m: map[string]*Session{}, idle: idle, max: max, now: time.Now}
}

func key(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// New starts a session and returns its token (for the cookie).
func (s *Sessions) New(user, ip string) string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	token := hex.EncodeToString(b)
	now := s.now()
	s.mu.Lock()
	s.m[key(token)] = &Session{User: user, Created: now, LastSeen: now, IP: ip}
	s.mu.Unlock()
	return token
}

// Get returns the session for a token and marks it used, or false if there's none (or it's expired).
func (s *Sessions) Get(token string) (Session, bool) {
	if token == "" {
		return Session{}, false
	}
	k := key(token)
	now := s.now()
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.m[k]
	if !ok {
		return Session{}, false
	}
	if now.Sub(sess.LastSeen) > s.idle || now.Sub(sess.Created) > s.max {
		delete(s.m, k)
		return Session{}, false
	}
	sess.LastSeen = now
	return *sess, true
}

// Delete ends a session.
func (s *Sessions) Delete(token string) {
	s.mu.Lock()
	delete(s.m, key(token))
	s.mu.Unlock()
}

// DeleteUser ends every session of an account (after a password reset or removal), except the one with token
// keep (may be empty).
func (s *Sessions) DeleteUser(user, keep string) {
	keepKey := ""
	if keep != "" {
		keepKey = key(keep)
	}
	s.mu.Lock()
	for k, sess := range s.m {
		if strings.EqualFold(sess.User, user) && k != keepKey {
			delete(s.m, k)
		}
	}
	s.mu.Unlock()
}

// Sweep drops expired sessions.
func (s *Sessions) Sweep() {
	now := s.now()
	s.mu.Lock()
	for k, sess := range s.m {
		if now.Sub(sess.LastSeen) > s.idle || now.Sub(sess.Created) > s.max {
			delete(s.m, k)
		}
	}
	s.mu.Unlock()
}
