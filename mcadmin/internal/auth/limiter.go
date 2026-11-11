package auth

import (
	"sync"
	"time"
)

// Limiter counts failed logins per key (an IP, or an account name) and blocks a key that fails too often within a
// window. It's what stops password guessing.
type Limiter struct {
	mu     sync.Mutex
	fails  map[string][]time.Time
	max    int
	window time.Duration
	now    func() time.Time
}

// NewLimiter blocks a key after max failures within window (until the oldest of them is older than window).
func NewLimiter(max int, window time.Duration) *Limiter {
	return &Limiter{fails: map[string][]time.Time{}, max: max, window: window, now: time.Now}
}

func (l *Limiter) recent(key string) []time.Time {
	cutoff := l.now().Add(-l.window)
	kept := l.fails[key][:0]
	for _, t := range l.fails[key] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) == 0 {
		delete(l.fails, key)
		return nil
	}
	l.fails[key] = kept
	return kept
}

// Blocked reports whether key has failed too often lately, and how long until it can try again.
func (l *Limiter) Blocked(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	fails := l.recent(key)
	if len(fails) < l.max {
		return false, 0
	}
	return true, fails[len(fails)-l.max].Add(l.window).Sub(l.now())
}

// Fail records a failure for key.
func (l *Limiter) Fail(key string) {
	l.mu.Lock()
	l.fails[key] = append(l.recent(key), l.now())
	l.mu.Unlock()
}

// Reset forgets key's failures (after a good login).
func (l *Limiter) Reset(key string) {
	l.mu.Lock()
	delete(l.fails, key)
	l.mu.Unlock()
}
