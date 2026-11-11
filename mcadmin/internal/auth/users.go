// Package auth holds the panel's accounts, sessions and login throttling.
package auth

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"

	"mcadmin/internal/store"
)

// Role is what an account may do. Owners also manage accounts and read the audit log.
type Role string

const (
	Owner Role = "owner"
	Admin Role = "admin"
)

// ValidRole reports whether r is a known role.
func ValidRole(r Role) bool { return r == Owner || r == Admin }

// User is one account. Hash is a bcrypt hash; it never leaves the server.
type User struct {
	Name       string    `json:"name"`
	Hash       string    `json:"hash"`
	Role       Role      `json:"role"`
	Created    time.Time `json:"created"`
	MustChange bool      `json:"mustChange"` // given a generated password: has to pick their own first
}

// Public is what the panel shows about an account.
type Public struct {
	Name       string    `json:"name"`
	Role       Role      `json:"role"`
	Created    time.Time `json:"created"`
	MustChange bool      `json:"mustChange"`
}

func (u User) Public() Public {
	return Public{Name: u.Name, Role: u.Role, Created: u.Created, MustChange: u.MustChange}
}

const (
	MinPassword = 10
	MaxPassword = 72 // bcrypt's limit, in bytes
	bcryptCost  = 12
)

var (
	ErrExists     = errors.New("that name is taken")
	ErrNotFound   = errors.New("no such account")
	ErrLastOwner  = errors.New("there has to be at least one owner")
	ErrBadName    = errors.New("names are 3-32 letters, digits, '_', '-' or '.'")
	ErrBadRole    = errors.New("the role is owner or admin")
	ErrBadCurrent = errors.New("the current password is wrong")
	namePattern   = regexp.MustCompile(`^[A-Za-z0-9_.-]{3,32}$`)
	// A hash to compare against when the account doesn't exist, so a wrong name takes as long as a wrong password.
	dummyHash, _ = bcrypt.GenerateFromPassword([]byte("not a real password"), bcryptCost)
)

// CheckPassword returns why a new password isn't allowed, or nil.
func CheckPassword(password string) error {
	if len([]rune(password)) < MinPassword {
		return fmt.Errorf("passwords are at least %d characters", MinPassword)
	}
	if len(password) > MaxPassword {
		return fmt.Errorf("passwords are at most %d bytes", MaxPassword)
	}
	return nil
}

// Users is the account list, kept in a JSON file. It's read from disk for every operation (it's tiny), so the
// command-line tools and the running panel never overwrite each other's changes.
type Users struct {
	mu   sync.Mutex
	path string
}

// NewUsers uses the account file at path (created on the first change).
func NewUsers(path string) *Users { return &Users{path: path} }

type file struct {
	Users []User `json:"users"`
}

func (s *Users) load() (map[string]User, error) {
	users := map[string]User{}
	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return users, nil
	}
	if err != nil {
		return nil, err
	}
	var f file
	if err := json.Unmarshal(data, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", s.path, err)
	}
	for _, u := range f.Users {
		users[strings.ToLower(u.Name)] = u
	}
	return users, nil
}

func (s *Users) save(users map[string]User) error {
	f := file{Users: make([]User, 0, len(users))}
	for _, u := range users {
		f.Users = append(f.Users, u)
	}
	sort.Slice(f.Users, func(i, j int) bool { return strings.ToLower(f.Users[i].Name) < strings.ToLower(f.Users[j].Name) })
	return store.WriteJSON(s.path, f)
}

// Verify checks a login. It takes about as long whether or not the account exists.
func (s *Users) Verify(name, password string) (User, bool) {
	s.mu.Lock()
	users, err := s.load()
	s.mu.Unlock()
	if err != nil {
		return User{}, false
	}
	u, ok := users[strings.ToLower(name)]
	hash := dummyHash
	if ok {
		hash = []byte(u.Hash)
	}
	if len(password) > MaxPassword {
		password = password[:MaxPassword]
	}
	match := bcrypt.CompareHashAndPassword(hash, []byte(password)) == nil
	return u, ok && match
}

// Get returns an account by name (any case).
func (s *Users) Get(name string) (User, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	users, err := s.load()
	if err != nil {
		return User{}, false
	}
	u, ok := users[strings.ToLower(name)]
	return u, ok
}

// List returns every account, by name.
func (s *Users) List() ([]Public, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	users, err := s.load()
	if err != nil {
		return nil, err
	}
	list := make([]Public, 0, len(users))
	for _, u := range users {
		list = append(list, u.Public())
	}
	sort.Slice(list, func(i, j int) bool { return strings.ToLower(list[i].Name) < strings.ToLower(list[j].Name) })
	return list, nil
}

// Count returns how many accounts there are.
func (s *Users) Count() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	users, err := s.load()
	return len(users), err
}

func hash(password string) (string, error) {
	h, err := bcrypt.GenerateFromPassword([]byte(password), bcryptCost)
	return string(h), err
}

// Add creates an account. mustChange makes them pick their own password on first login.
func (s *Users) Add(name, password string, role Role, mustChange bool) error {
	if !namePattern.MatchString(name) {
		return ErrBadName
	}
	if !ValidRole(role) {
		return ErrBadRole
	}
	if err := CheckPassword(password); err != nil {
		return err
	}
	h, err := hash(password)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	users, err := s.load()
	if err != nil {
		return err
	}
	if _, taken := users[strings.ToLower(name)]; taken {
		return ErrExists
	}
	users[strings.ToLower(name)] = User{Name: name, Hash: h, Role: role, Created: time.Now().UTC(), MustChange: mustChange}
	return s.save(users)
}

// SetPassword gives an account a new password. mustChange makes them pick their own on next login.
func (s *Users) SetPassword(name, password string, mustChange bool) error {
	if err := CheckPassword(password); err != nil {
		return err
	}
	h, err := hash(password)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	users, err := s.load()
	if err != nil {
		return err
	}
	u, ok := users[strings.ToLower(name)]
	if !ok {
		return ErrNotFound
	}
	u.Hash, u.MustChange = h, mustChange
	users[strings.ToLower(name)] = u
	return s.save(users)
}

// ChangeOwn changes a user's own password, checking the current one.
func (s *Users) ChangeOwn(name, current, next string) error {
	if _, ok := s.Verify(name, current); !ok {
		return ErrBadCurrent
	}
	return s.SetPassword(name, next, false)
}

func owners(users map[string]User) int {
	n := 0
	for _, u := range users {
		if u.Role == Owner {
			n++
		}
	}
	return n
}

// SetRole changes an account's role. The last owner can't be made an admin.
func (s *Users) SetRole(name string, role Role) error {
	if !ValidRole(role) {
		return ErrBadRole
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	users, err := s.load()
	if err != nil {
		return err
	}
	u, ok := users[strings.ToLower(name)]
	if !ok {
		return ErrNotFound
	}
	if u.Role == Owner && role != Owner && owners(users) <= 1 {
		return ErrLastOwner
	}
	u.Role = role
	users[strings.ToLower(name)] = u
	return s.save(users)
}

// Delete removes an account. The last owner can't be removed.
func (s *Users) Delete(name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	users, err := s.load()
	if err != nil {
		return err
	}
	u, ok := users[strings.ToLower(name)]
	if !ok {
		return ErrNotFound
	}
	if u.Role == Owner && owners(users) <= 1 {
		return ErrLastOwner
	}
	delete(users, strings.ToLower(name))
	return s.save(users)
}

// GeneratePassword makes a random password that's easy to type: 20 letters and digits (no 0/O/1/l/I).
func GeneratePassword() string {
	const alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 20)
	for i := range b {
		b[i] = alphabet[randIndex(len(alphabet))]
	}
	return string(b)
}

func randIndex(n int) int {
	var b [1]byte
	for {
		if _, err := rand.Read(b[:]); err != nil {
			panic(err)
		}
		if int(b[0]) < 256-256%n { // no bias
			return int(b[0]) % n
		}
	}
}
