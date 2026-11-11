// Command panel is the mcadmin website: files and console for the Minecraft server, behind a login.
//
//	panel serve                      run the website (what the container does)
//	panel create-owner <name>        make an owner account (the first one), printing its password once
//	panel reset-password <name>      give an account a new generated password, printed once
//	panel users                      list the accounts
package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"mcadmin/internal/audit"
	"mcadmin/internal/auth"
	"mcadmin/internal/console"
	"mcadmin/internal/files"
	"mcadmin/internal/web"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	state := env("STATE_DIR", "/state")
	users := auth.NewUsers(filepath.Join(state, "users.json"))
	switch os.Args[1] {
	case "serve":
		serve(state, users)
	case "create-owner":
		if len(os.Args) != 3 {
			usage()
		}
		password := auth.GeneratePassword()
		if err := users.Add(os.Args[2], password, auth.Owner, true); err != nil {
			fail(err)
		}
		audit.New(filepath.Join(state, "audit.log")).Write("(command line)", "-", "added account", os.Args[2]+" (owner)")
		fmt.Printf("Owner %q created.\nPassword (shown once; you'll choose your own after logging in): %s\n", os.Args[2], password)
	case "reset-password":
		if len(os.Args) != 3 {
			usage()
		}
		password := auth.GeneratePassword()
		if err := users.SetPassword(os.Args[2], password, true); err != nil {
			fail(err)
		}
		audit.New(filepath.Join(state, "audit.log")).Write("(command line)", "-", "reset password", os.Args[2])
		fmt.Printf("New password for %q (shown once; they choose their own after logging in): %s\n", os.Args[2], password)
		fmt.Println("Sessions already logged in end the next time the panel restarts, or straight away if reset from the Users page.")
	case "users":
		list, err := users.List()
		if err != nil {
			fail(err)
		}
		for _, u := range list {
			fmt.Printf("%-20s %-6s created %s\n", u.Name, u.Role, u.Created.Format("2006-01-02"))
		}
	default:
		usage()
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: panel serve | create-owner <name> | reset-password <name> | users")
	os.Exit(2)
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "error:", err)
	os.Exit(1)
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func serve(state string, users *auth.Users) {
	origin := os.Getenv("PUBLIC_ORIGIN")
	insecure := os.Getenv("INSECURE_HTTP") == "1"
	if origin == "" {
		log.Fatal("PUBLIC_ORIGIN must be set (e.g. https://mcadmin.example.com)")
	}
	if n, err := users.Count(); err != nil {
		log.Fatalf("can't read the accounts: %v", err)
	} else if n == 0 {
		log.Print("There are no accounts yet: make the first with `docker compose exec panel /panel create-owner <name>`")
	}

	roots := []files.RootSpec{
		{Name: "data", Label: "Server", Dir: env("DATA_DIR", "/srv/data")},
		{Name: "plugins", Label: "Plugins", Dir: env("PLUGINS_DIR", "/srv/plugins")},
	}
	fileRoots, err := files.Open(roots)
	if err != nil {
		log.Fatal(err)
	}
	defer fileRoots.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	hub := console.NewHub(env("LOG_FILE", "/srv/data/logs/latest.log"), 1000)
	go hub.Run(ctx)
	rcon := console.NewRCON(env("RCON_ADDR", "mc:25575"), env("RCON_PASSWORD_FILE", "/srv/data/.rcon-cli.env"))
	defer rcon.Close()

	var power web.Power
	if url := os.Getenv("POWER_URL"); url != "" {
		power = &web.PowerClient{URL: url, Token: os.Getenv("POWER_TOKEN"), HTTP: &http.Client{Timeout: 10 * time.Second}}
	}

	sessions := auth.NewSessions(8*time.Hour, 7*24*time.Hour)
	go func() {
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				sessions.Sweep()
			}
		}
	}()

	handler := web.New(web.Config{
		Origin:   origin,
		Insecure: insecure,
		Users:    users,
		Sessions: sessions,
		Logins:   auth.NewLimiter(20, 15*time.Minute), // per IP
		Accounts: auth.NewLimiter(5, 15*time.Minute),  // per account
		Audit:    audit.New(filepath.Join(state, "audit.log")),
		Files:    fileRoots,
		Roots:    roots,
		RCON:     rcon,
		Hub:      hub,
		Power:    power,
	})
	server := &http.Server{
		Addr:              env("LISTEN", ":8080"),
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		// No write timeout: downloads and the console stream run long.
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		server.Shutdown(shutdown)
	}()
	log.Printf("mcadmin panel on %s for %s", server.Addr, origin)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
