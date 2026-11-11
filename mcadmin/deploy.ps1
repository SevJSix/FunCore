<#
.SYNOPSIS
    Builds the mcadmin panel and deploys it to the server, at https://mcadmin.ianlabs.xyz.

.DESCRIPTION
    1. Runs the tests, then builds the panel and the power helper for Linux, here (the VPS has no memory to spare
       for compiling).
    2. Uploads them with the Docker files to /opt/mcadmin on the server.
    3. The first time: makes /opt/mcadmin/.env (the domain, and a random secret shared by the panel and the power
       helper), the state folder, and lets the panel write to /opt/minecraft/plugins.
    4. Checks the domain points at the server (so Caddy can get its HTTPS certificate), then starts or updates the
       containers. The Minecraft server isn't touched.

    Needs Go (go.mod's version is fetched automatically) and SSH key login to the server.

.EXAMPLE
    .\mcadmin\deploy.ps1
.EXAMPLE
    .\mcadmin\deploy.ps1 -SkipTests
#>
param(
    [string]$Server = "root@vps02.ianlabs.xyz",
    [string]$Domain = "mcadmin.ianlabs.xyz",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Build = Join-Path $Root "build"

function Step($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Fail($message) { Write-Host "ERROR: $message" -ForegroundColor Red; exit 1 }

# Strip a byte order mark and CRLFs before bash reads the piped script (see scripts/deploy.ps1).
$RemoteShell = "sed -e '1s/^\xEF\xBB\xBF//' -e 's/\r`$//' | bash -s --"

$RemoteScript = @'
set -euo pipefail
DOMAIN="$1"
DIR=/opt/mcadmin
UPLOAD=/tmp/mcadmin.upload

mkdir -p "$DIR/state"
install -m 755 "$UPLOAD/panel" "$UPLOAD/power" "$DIR/"
install -m 644 "$UPLOAD/Dockerfile" "$UPLOAD/docker-compose.yml" "$UPLOAD/Caddyfile" "$DIR/"
rm -rf "$UPLOAD"
chown 1000:1000 "$DIR/state"
chmod 700 "$DIR/state"

if [ ! -f "$DIR/.env" ]; then
    TOKEN=$(head -c 48 /dev/urandom | base64 | tr -d '/+=\n' | head -c 48)
    umask 077
    printf 'DOMAIN=%s\nPOWER_TOKEN=%s\n' "$DOMAIN" "$TOKEN" > "$DIR/.env"
    echo "Made $DIR/.env (domain and the power helper's secret)."
fi
DOMAIN=$(grep '^DOMAIN=' "$DIR/.env" | cut -d= -f2)

# The panel runs as the Minecraft server's user (1000): let it add and replace plugin jars.
if [ "$(stat -c %u /opt/minecraft/plugins)" != 1000 ]; then
    chown 1000:1000 /opt/minecraft/plugins
    echo "/opt/minecraft/plugins now belongs to the server's user (1000), so the panel can upload plugins."
fi

# Caddy asks Let's Encrypt for a certificate as soon as it starts; if the domain doesn't point here yet that fails,
# and too many failures get rate-limited. So don't start until it does.
HERE=$(curl -s -4 --max-time 10 https://ifconfig.me || true)
THERE=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)
if [ -z "$THERE" ] || [ "$THERE" != "$HERE" ]; then
    echo "$DOMAIN points at '${THERE:-nothing}', not this server ($HERE)." >&2
    echo "Add an A record: $DOMAIN -> $HERE, wait a few minutes, then deploy again. The files are in place; nothing was started." >&2
    exit 3
fi

cd "$DIR"
docker compose up -d --build --remove-orphans
sleep 3
docker compose ps
echo "--- panel log ---"
docker compose logs --tail 10 panel
if [ ! -s "$DIR/state/users.json" ]; then
    echo
    echo "NO ACCOUNTS YET. Make yours (it prints a one-time password):"
    echo "  ssh $(whoami)@$(hostname -f 2>/dev/null || hostname) \"cd $DIR && docker compose exec panel /panel create-owner <your-name>\""
fi
'@

Push-Location $Root
try {
    if (-not $SkipTests) {
        Step "Testing"
        go test ./...
        if ($LASTEXITCODE -ne 0) { Fail "tests failed; nothing was deployed" }
    }

    Step "Building for Linux"
    New-Item -ItemType Directory -Force $Build | Out-Null
    $env:GOOS = "linux"; $env:GOARCH = "amd64"; $env:CGO_ENABLED = "0"
    try {
        go build -trimpath -ldflags "-s -w" -o (Join-Path $Build "panel") ./cmd/panel
        if ($LASTEXITCODE -ne 0) { Fail "building the panel failed" }
        go build -trimpath -ldflags "-s -w" -o (Join-Path $Build "power") ./cmd/power
        if ($LASTEXITCODE -ne 0) { Fail "building the power helper failed" }
    } finally {
        Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
    }
    Copy-Item (Join-Path $Root "deploy\*") $Build -Force

    Step "Uploading to $Server"
    ssh $Server "rm -rf /tmp/mcadmin.upload && mkdir -p /tmp/mcadmin.upload"
    if ($LASTEXITCODE -ne 0) { Fail "can't reach $Server" }
    $files = "panel", "power", "Dockerfile", "docker-compose.yml", "Caddyfile" | ForEach-Object { Join-Path $Build $_ }
    scp -q @files "${Server}:/tmp/mcadmin.upload/"
    if ($LASTEXITCODE -ne 0) { Fail "upload failed" }

    Step "Installing and starting"
    $RemoteScript | ssh $Server "$RemoteShell $Domain"
    if ($LASTEXITCODE -eq 3) { Fail "the domain doesn't point at the server yet (see above)" }
    if ($LASTEXITCODE -ne 0) { Fail "the install on the server failed (see above)" }
    Write-Host "Deployed: https://$Domain" -ForegroundColor Green
} finally {
    Pop-Location
}
