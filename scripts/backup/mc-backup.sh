#!/bin/bash
# Nightly backup of the Minecraft server (and the admin panel's accounts), run by mc-backup.timer.
# Installed at /usr/local/bin/mc-backup.sh on vps02.ianlabs.xyz. Run it by hand any time: `mc-backup.sh`.
#
# What's saved: /opt/minecraft/data (worlds, every plugin's data and config, server settings, logs), the plugin jars,
# the compose file, and /opt/mcadmin (panel accounts, activity log, settings). What isn't: Paper's jar, libraries and
# caches, which the server image downloads again by itself.
#
# While it copies, world saving is paused (save-off) after a full save, so the world on disk can't change halfway
# through; players notice nothing. Saving is always switched back on, even if the backup fails.
#
# Backups: /opt/backups/minecraft/mc-<date>.tar.zst, the last KEEP_DAYS days. Restoring: see RESTORE.txt there.
set -euo pipefail

DEST=/opt/backups/minecraft
KEEP_DAYS=14
STAMP=$(TZ=America/Toronto date +%Y-%m-%d_%H-%M)   # Toronto time, like the timer
FILE="$DEST/mc-$STAMP.tar.zst"

mkdir -p "$DEST"
chmod 700 /opt/backups "$DEST"

rcon() { docker exec mc rcon-cli "$@" >/dev/null; }

RUNNING=0
if [ "$(docker inspect -f '{{.State.Running}}' mc 2>/dev/null || true)" = true ]; then RUNNING=1; fi

resume() {
    if [ "$RUNNING" = 1 ]; then rcon save-on || echo "WARNING: couldn't switch world saving back on: run 'docker exec mc rcon-cli save-on'" >&2; fi
}
trap resume EXIT

if [ "$RUNNING" = 1 ]; then
    rcon save-off
    rcon "save-all flush"
    sleep 5
else
    echo "The server isn't running: backing up the files as they are."
fi

# tar exits 1 when a file changed while it was read (a plugin writing its data): the copy is still good.
set +e
tar -C /opt --zstd -cf "$FILE.part" \
    --exclude=minecraft/data/libraries --exclude=minecraft/data/versions --exclude=minecraft/data/cache \
    --exclude=minecraft/data/.cache --exclude='minecraft/data/paper-*.jar' \
    --exclude=mcadmin/panel --exclude=mcadmin/power \
    minecraft/data minecraft/plugins minecraft/docker-compose.yml mcadmin
STATUS=$?
set -e
resume
trap - EXIT
if [ "$STATUS" -gt 1 ]; then
    rm -f "$FILE.part"
    echo "ERROR: tar failed ($STATUS); no backup made" >&2
    exit 1
fi

zstd -tq "$FILE.part"                     # the archive reads back cleanly
mv "$FILE.part" "$FILE"
chmod 600 "$FILE"
ln -sfn "$(basename "$FILE")" "$DEST/latest.tar.zst"

# Keep the last KEEP_DAYS days.
find "$DEST" -maxdepth 1 -name 'mc-*.tar.zst' -mtime +$((KEEP_DAYS - 1)) -delete

echo "Backed up to $FILE ($(du -h "$FILE" | cut -f1)); $(ls "$DEST"/mc-*.tar.zst | wc -l) backups kept, $(du -sh "$DEST" | cut -f1) in total."
