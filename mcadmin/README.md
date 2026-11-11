# mcadmin

A small web panel for the Minecraft server at **https://mcadmin.ianlabs.xyz**, so admins can manage it without SSH:

- **Console**: the live server log, and a box to run console commands.
- **Files**: browse, edit, upload, download, rename and delete files in the server folder (`/opt/minecraft/data`)
  and the plugins folder (`/opt/minecraft/plugins`). Nothing else on the VPS is reachable.
- **Start / Restart / Stop** buttons. Players get the same 10-second warning and save as a normal restart.
- **Accounts**: owners add admins, reset passwords and see an activity log of everything anyone did.

## How it's built

Three containers in `/opt/mcadmin`, separate from `/opt/minecraft` (changing the panel never touches the `mc`
container):

| Container | What it does | What it can reach |
|-----------|--------------|-------------------|
| `caddy` | HTTPS on ports 80/443, with a free Let's Encrypt certificate it renews itself | the panel |
| `panel` | the website (Go, ~15 MB of memory, capped at 128 MB) | the two folders; the server's RCON (`mc:25575`) and log; the power helper |
| `power` | start/stop/restart the `mc` container, nothing else | the Docker socket; only the panel can reach it |

The website itself never gets Docker access (that would be root on the whole VPS): only the tiny power helper has it,
and it only knows three actions on one container.

## Security

- Passwords are stored as bcrypt hashes in `/opt/mcadmin/state/users.json`.
- A login lasts until the browser closes, 8 hours idle, or 7 days at most.
- Too many wrong passwords lock that account for 15 minutes (5 tries), and that address too (20 tries).
- New accounts and password resets get a one-time password; the person must choose their own before doing anything.
- The session cookie is `HttpOnly`, `Secure` and `SameSite=Strict`. Every change is also checked to come from the
  panel's own page, so another website can't act for a logged-in admin.
- File paths can't leave the two folders: every operation goes through Go's `os.Root`, which refuses `..`, absolute
  paths and symlinks that lead out.
- Every login, command, file change, power action and account change goes in `/opt/mcadmin/state/audit.log`
  (the **Activity** tab).

**Admins are trusted with the server.** Console commands and plugin uploads can do anything the Minecraft server can
(a plugin is code). Only give accounts to people you'd give the console to.

## Running it

Deploy (from the FunCore folder, on the `mcadmin` branch): runs the tests, builds on your PC, uploads, starts.

```powershell
.\mcadmin\deploy.ps1
```

The first deploy also:

- creates `/opt/mcadmin/.env` (the domain, and a random secret between the panel and the power helper);
- gives `/opt/minecraft/plugins` to the server's user (1000), so the panel can upload plugins
  (`scripts/deploy.ps1` still works: it runs as root).

It refuses to start until `mcadmin.ianlabs.xyz` points at the server: Caddy's certificate request would fail, and
repeated failures get rate-limited.

### Accounts from the command line

```bash
cd /opt/mcadmin
docker compose exec panel /panel create-owner <name>     # the first account (prints a one-time password)
docker compose exec panel /panel reset-password <name>   # if an owner is locked out
docker compose exec panel /panel users                   # list them
```

Everything else is on the **Users** page.

### Day to day

```bash
cd /opt/mcadmin
docker compose ps                  # what's running
docker compose logs -f panel       # the panel's own log
docker compose restart panel       # logs everyone out
docker compose down                # turn the panel off (the Minecraft server keeps running)
```

## Developing

```bash
cd mcadmin
go test ./...
```

Run it locally over plain HTTP (no Caddy) against throwaway folders:

```bash
STATE_DIR=/tmp/mca/state DATA_DIR=/tmp/mca/data PLUGINS_DIR=/tmp/mca/plugins \
LOG_FILE=/tmp/mca/data/logs/latest.log RCON_PASSWORD_FILE=/tmp/mca/data/.rcon-cli.env \
PUBLIC_ORIGIN=http://127.0.0.1:8099 INSECURE_HTTP=1 LISTEN=127.0.0.1:8099 go run ./cmd/panel serve
```

The page is plain HTML/CSS/JS in `internal/web/static` (no build step), built into the binary.
