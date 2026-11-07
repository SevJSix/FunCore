<#
.SYNOPSIS
    Builds FunCore and deploys it to the production server.

.DESCRIPTION
    1. Builds the plugin with Gradle (skip with -SkipBuild).
    2. Uploads the jar with scp and checks its SHA-256 on the server.
    3. Keeps the previous jar as FunCore.jar.prev, so -Rollback can swap back to it.
    4. Restarts the server cleanly (players get a 10 second warning) and waits until it's back up
       (skip with -NoRestart; the new jar then loads on the next restart).

    Needs the OpenSSH client (built into Windows 10/11) and SSH key login to the server. Run ssh-agent so you're only
    asked for your key passphrase once.

.EXAMPLE
    .\scripts\deploy.ps1
.EXAMPLE
    .\scripts\deploy.ps1 -SkipBuild -NoRestart
.EXAMPLE
    .\scripts\deploy.ps1 -Rollback
#>
param(
    [string]$Server = "root@vps02.ianlabs.xyz",
    [switch]$SkipBuild,
    [switch]$NoRestart,
    [switch]$Rollback
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$UploadPath = "/tmp/FunCore.jar.upload"

function Step($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Fail($message) { Write-Host "ERROR: $message" -ForegroundColor Red; exit 1 }

# Windows PowerShell can start piped text with a byte order mark (and the script may have CRLF line endings). This strips
# both on the server before bash reads the script; otherwise the first line (set -euo pipefail) fails and strict mode is
# silently off.
$RemoteShell = "sed -e '1s/^\xEF\xBB\xBF//' -e 's/\r`$//' | bash -s --"

# Runs on the server via `bash -s`. Arguments: <deploy|rollback> <restart: 1|0> [expected sha256]
$RemoteScript = @'
set -euo pipefail
MODE="$1"; RESTART="$2"; EXPECTED_SHA="${3:-}"
DIR=/opt/minecraft
JAR="$DIR/plugins/FunCore.jar"
UPLOAD=/tmp/FunCore.jar.upload

cd "$DIR"
mkdir -p "$DIR/plugins"

if [ "$MODE" = deploy ]; then
    ACTUAL_SHA=$(sha256sum "$UPLOAD" | cut -d' ' -f1)
    if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
        rm -f "$UPLOAD"
        echo "Checksum mismatch: the upload was corrupted. Nothing was changed." >&2
        exit 1
    fi
    if [ -f "$JAR" ]; then cp -p "$JAR" "$JAR.prev"; fi
    install -m 644 "$UPLOAD" "$JAR.new"
    mv -f "$JAR.new" "$JAR"
    rm -f "$UPLOAD"
    echo "Installed new FunCore.jar (previous kept as FunCore.jar.prev)."
else
    if [ ! -f "$JAR.prev" ]; then echo "There is no FunCore.jar.prev to roll back to." >&2; exit 1; fi
    # Swap, so running -Rollback again rolls forward.
    mv -f "$JAR" "$JAR.swap"
    mv -f "$JAR.prev" "$JAR"
    mv -f "$JAR.swap" "$JAR.prev"
    echo "Rolled back to the previous FunCore.jar."
fi
# The image only copies plugins that are newer than the copy in /data, so mark this one as new.
touch "$JAR"

if [ "$RESTART" != 1 ]; then
    echo "Not restarting. The new jar loads on the next restart (docker compose restart mc)."
    exit 0
fi

START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "Restarting the server (players get a short warning first)..."
docker compose restart mc

for _ in $(seq 1 90); do
    STATUS=$(docker inspect -f '{{.State.Health.Status}}' mc 2>/dev/null || echo unknown)
    if [ "$STATUS" = healthy ]; then
        echo "Server is back up."
        docker logs --since "$START" mc 2>&1 | grep -iE "FunCore" | tail -n 8 || true
        if docker logs --since "$START" mc 2>&1 | grep -qiE "Error occurred while enabling FunCore|Could not load 'plugins/FunCore"; then
            echo "WARNING: FunCore failed to load. Check: docker logs mc   (or roll back with -Rollback)" >&2
            exit 2
        fi
        exit 0
    fi
    sleep 2
done
echo "The server didn't report healthy within 3 minutes. Last log lines:" >&2
docker logs --tail 30 mc >&2
exit 1
'@ -replace "`r", ""   # this file may be saved with Windows line endings; bash needs LF

if ($Rollback) {
    Step "Rolling back on $Server"
    $restartFlag = if ($NoRestart) { "0" } else { "1" }
    $RemoteScript | ssh $Server "$RemoteShell rollback $restartFlag"
    if ($LASTEXITCODE -ne 0) { Fail "Rollback failed (exit code $LASTEXITCODE)." }
    exit 0
}

if (-not $SkipBuild) {
    Step "Building FunCore"
    Push-Location $ProjectRoot
    try {
        & .\gradlew.bat build --console=plain
        if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed." }
    } finally {
        Pop-Location
    }
}

$jar = Get-ChildItem (Join-Path $ProjectRoot "build\libs") -Filter "FunCore-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "-(sources|javadoc|dev)\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
if ($null -eq $jar) { Fail "No jar found in build\libs. Run without -SkipBuild." }

$sha = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower()
Step "Uploading $($jar.Name) ($([math]::Round($jar.Length / 1KB)) KB) to $Server"
scp -q $jar.FullName "${Server}:$UploadPath"
if ($LASTEXITCODE -ne 0) { Fail "Upload failed." }

Step "Installing on the server"
$restart = if ($NoRestart) { "0" } else { "1" }
$RemoteScript | ssh $Server "$RemoteShell deploy $restart $sha"
switch ($LASTEXITCODE) {
    0 { Write-Host "Deployed $($jar.Name)." -ForegroundColor Green }
    2 { Fail "Deployed, but FunCore didn't enable. Run '.\scripts\deploy.ps1 -Rollback' to go back." }
    default { Fail "Deploy failed (exit code $LASTEXITCODE)." }
}
