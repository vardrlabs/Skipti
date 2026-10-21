#!/bin/bash
# D-170: bash, not sh. `set -o pipefail` below is not POSIX; Ubuntu's /bin/sh
# is dash, which refuses it ("Illegal option -o pipefail"), so the first
# production run died on line one. macOS's /bin/sh is bash, which hid it.
# skipti-restore-drill.sh: ADR-0030's required restore rehearsal.
#
# A backup nobody has restored is a hypothesis (DEPLOY-ORACLE §10.4). This
# script is the drill, runnable any time WITHOUT touching the live relay:
# it verifies the newest snapshot structurally, byte-for-byte on a known
# ciphertext row, and by BOOTING A SECOND RELAY against the restored file
# on loopback. Every step needs no key material: everything sensitive in
# the file is ciphertext, so byte-fidelity of the copy is the whole proof.
#
# Usage:
#   skipti-restore-drill.sh [BACKUP_DB]          # default: newest snapshot
#   SKIPTI_SERVER_BIN=/usr/local/bin/skipti-server (default: cargo run)
#
# Exit 0 + a REPORT line means the backup is proven restorable.

set -euo pipefail

LIVE="${SKIPTI_RELAY_DB:-/var/lib/skipti/relay-vault.db}"
BACKUP="${1:-}"
SERVER_BIN="${SKIPTI_SERVER_BIN:-}"

die() {
    echo "skipti-restore-drill: FAIL: $*" >&2
    exit 1
}

command -v sqlite3 >/dev/null || die "sqlite3 not installed"
[ -f "$LIVE" ] || die "live vault not found at $LIVE"

if [ -z "$BACKUP" ]; then
    BACKUP="$(ls -1t "${SKIPTI_BACKUP_DIR:-/var/backups/skipti}"/relay-vault-*.db 2>/dev/null | head -1 || true)"
    [ -n "$BACKUP" ] || die "no snapshots found. Has layer 1 ever run?"
fi
[ -f "$BACKUP" ] || die "backup not found: $BACKUP"

repo_root() {
    d="$(cd "$(dirname "$0")/.." && pwd)"
    [ -f "$d/Cargo.toml" ] && echo "$d" || echo ""
}

WORK="$(mktemp -d /tmp/skipti-drill.XXXXXX)"
trap 'rm -rf "$WORK"; if [ -n "${SERVER_PID:-}" ]; then KILL_GROUP; fi' EXIT
RESTORED="$WORK/restore-check.db"
cp -- "$BACKUP" "$RESTORED"

# 1. Structural integrity of the restored copy:
CHECK="$(sqlite3 -bail "$RESTORED" 'PRAGMA integrity_check;')" \
    || die "integrity_check failed on restored copy (exit $?; corrupt backup?)"
[ "$CHECK" = "ok" ] || die "integrity_check on restored copy said: $CHECK"
echo "drill 1/4 ok: integrity_check = ok"

# 2. Row-count parity with the LIVE vault, table by table. A table that
#    exists in one and not the other is a schema drift alarm, not a pass.
TABLES="$(sqlite3 -bail "$LIVE" \
    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;")"
[ -n "$TABLES" ] || die "live vault reports no tables"
for t in $TABLES; do
    LIVE_N="$(sqlite3 -bail "$LIVE" "SELECT count(*) FROM [$t];")"
    BAK_N="$(sqlite3 -bail "$RESTORED" "SELECT count(*) FROM [$t];")"
    [ "$LIVE_N" = "$BAK_N" ] \
        || die "table $t: live=$LIVE_N backup=$BAK_N (backup predates writes? check age)"
done
echo "drill 2/4 ok: row counts match across all tables"

# 3. Byte-fidelity of the newest ciphertext row. Everything sensitive in
#    the vault is ciphertext (ADR-0018/D-36/D-66), so byte equality of a
#    known blob between live and backup IS the fidelity proof, no keys.
for t in $TABLES; do
    # Compare the LAST row of every table that has a blob-ish payload or
    # any columns at all: parity on full row text is the strictest cheap
    # check, and ciphertext columns compare byte-for-byte inside it.
    LIVE_ROW="$(sqlite3 -bail "$LIVE" "SELECT * FROM [$t] ORDER BY rowid DESC LIMIT 1;" 2>/dev/null || echo ABSENT)"
    BAK_ROW="$(sqlite3 -bail "$RESTORED" "SELECT * FROM [$t] ORDER BY rowid DESC LIMIT 1;" 2>/dev/null || echo ABSENT)"
    [ "$LIVE_ROW" = "$BAK_ROW" ] \
        || die "newest row of $t differs between live and backup"
done
echo "drill 3/4 ok: newest row of every table byte-identical"

# 4. The restored file RUNS: boot a second relay against it on loopback
#    and demand health + version answers. This is the step that separates
#    "the file opens" from "the service survives a restore".
#    The relay runs in its OWN process group (setsid) so the trap can kill
#    the whole group: a cargo-wrapped child holding stdout open kept this
#    script's callers hanging on the pipe until the orphan was shot (found
#    on the drill's first real run).
if [ -n "$SERVER_BIN" ]; then
    [ -x "$SERVER_BIN" ] || die "SKIPTI_SERVER_BIN=$SERVER_BIN is not executable"
fi
PORT="${SKIPTI_DRILL_PORT:-8081}"
# setsid detaches the server into its own process group so the trap can
# kill the whole group (a cargo-wrapped child holding stdout open kept
# this script's callers hanging on the pipe, found on the first run).
# macOS lacks setsid; there we fall back to plain child + pkill -P.
if command -v setsid >/dev/null 2>&1; then
    SETSID="setsid"
    KILL_GROUP() { kill -- "-$SERVER_PID" 2>/dev/null || kill "$SERVER_PID" 2>/dev/null || true; }
else
    SETSID=""
    KILL_GROUP() { kill "$SERVER_PID" 2>/dev/null || true; pkill -P "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; }
fi
if [ -n "$SERVER_BIN" ]; then
    $SETSID env SKIPTI_RELAY_DB="$RESTORED" SKIPTI_RP_ID=localhost \
        SKIPTI_ORIGIN="http://localhost:$PORT" SKIPTI_RELAY_PORT="$PORT" \
        "$SERVER_BIN" >/dev/null 2>&1 & SERVER_PID=$!
else
    [ -n "$(repo_root)" ] || die "cannot locate the repo to build the server; set SKIPTI_SERVER_BIN"
    $SETSID env SKIPTI_RELAY_DB="$RESTORED" SKIPTI_RP_ID=localhost \
        SKIPTI_ORIGIN="http://localhost:$PORT" SKIPTI_RELAY_PORT="$PORT" \
        cargo run --quiet --manifest-path "$(repo_root)/Cargo.toml" \
        -p skipti-server --bin skipti-server >/dev/null 2>&1 & SERVER_PID=$!
fi

# Wait for the loopback listener (max ~60 s incl. a cold cargo build),
# then probe:
READY=0
for _ in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then READY=1; break; fi
    sleep 0.5
done
if [ "$READY" != "1" ]; then
    die "restored-file relay never answered on :$PORT"
fi
HEALTH="$(curl -fsS "http://127.0.0.1:$PORT/")"
VERSION="$(curl -fsS "http://127.0.0.1:$PORT/version")"
echo "drill 4/4 ok: restored relay answered  / -> $HEALTH  /version -> $VERSION"

echo "REPORT: BACKUP RESTORABLE: $BACKUP (drill $(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "Record this line in the drill log (DEPLOY-ORACLE §10.4)."
