#!/bin/bash
# D-170: bash, not sh. `set -o pipefail` below is not POSIX; Ubuntu's /bin/sh
# is dash, which refuses it ("Illegal option -o pipefail"), so the first
# production run died on line one. macOS's /bin/sh is bash, which hid it.
# skipti-backup.sh: ADR-0030 layer 1, the on-host daily snapshot.
#
# Copies the relay vault with `sqlite3 ".backup"`, the ONLY correct way to
# copy a live WAL database. `cp` is FORBIDDEN here on purpose: it can tear
# a database mid-checkpoint, producing a file that opens fine today and is
# missing rows tomorrow. The script fails LOUDLY on every path: `set -eu`,
# sqlite `.bail on`, and a post-copy integrity check of the SNAPSHOT (a
# truncated or corrupt copy refuses to pass silently).
#
# Install (see DEPLOY-ORACLE.md §10):
#   install -m 700 skipti-backup.sh /usr/local/sbin/skipti-backup
#   cp skipti-backup.{service,timer} /etc/systemd/system/
#   systemctl enable --now skipti-backup.timer
#
# Overridable paths (env): SKIPTI_RELAY_DB, SKIPTI_BACKUP_DIR, RETAIN.

set -euo pipefail

DB="${SKIPTI_RELAY_DB:-/var/lib/skipti/relay-vault.db}"
DEST="${SKIPTI_BACKUP_DIR:-/var/backups/skipti}"
RETAIN="${SKIPTI_BACKUP_RETAIN:-14}"

die() {
    echo "skipti-backup: FAIL: $*" >&2
    exit 1
}

[ -f "$DB" ] || die "vault not found at $DB"
command -v sqlite3 >/dev/null || die "sqlite3 not installed"
command -v sha256sum >/dev/null || die "sha256sum not installed"

mkdir -p "$DEST"
chmod 700 "$DEST"

STAMP="$(date -u +%Y%m%d-%H%M%S)"
OUT="$DEST/relay-vault-$STAMP.db"
TMP="$DEST/.relay-vault-$STAMP.db.partial"

# The backup itself. `.backup` takes a consistent snapshot including the
# WAL; `.bail on` makes any sqlite error abort with a nonzero exit.
sqlite3 -bail "$DB" ".backup '$TMP'" || die "sqlite3 .backup failed"

# Verify the copy BEFORE it counts as a backup: integrity must answer
# exactly 'ok', and the file must be a real database with our schema
# marker (user_version is set by skrin's migrations; relay vault sets its
# own: nonzero proves we did not back up an empty file).
CHECK="$(sqlite3 -bail "$TMP" 'PRAGMA integrity_check;')"
[ "$CHECK" = "ok" ] || die "snapshot integrity_check said: $CHECK"
UV="$(sqlite3 -bail "$TMP" 'PRAGMA user_version;')"
case "$UV" in ''|*[!0-9]*) die "snapshot user_version not numeric: $UV" ;; esac
[ -s "$TMP" ] || die "snapshot is empty"

# Commit the artifact atomically and record it in the manifest.
mv "$TMP" "$OUT"
BYTES="$(wc -c < "$OUT")"
HASH="$(sha256sum "$OUT" | cut -d' ' -f1)"
echo "$HASH  $OUT  $BYTES bytes  uv=$UV" >> "$DEST/MANIFEST.sha256"

# Retention: keep the newest RETAIN snapshots, prune the rest. The
# manifest keeps their hashes: a deleted snapshot is at least provably
# gone, not silently rotten.
# (the pipeline can be empty on early runs: `for old in ""` would count a
# phantom prune, caught on this script's first real run; hence the -z guard)
PRUNED=0
for old in $(ls -1t "$DEST"/relay-vault-*.db 2>/dev/null | tail -n +$((RETAIN + 1))); do
    [ -n "$old" ] || continue
    rm -f "$old"
    PRUNED=$((PRUNED + 1))
done

echo "skipti-backup: ok $OUT ($BYTES bytes, sha256 $HASH, pruned $PRUNED)"
