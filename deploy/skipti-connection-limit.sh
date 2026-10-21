#!/usr/bin/env bash
#
# ADR-0032 layer 1: rate limit NEW connections to 443 per source address, in
# the kernel, retaining nothing.
#
# Why here and not in the application: the kernel already reads the source
# address off every packet (it cannot not), so the component that has it by
# physical necessity is the one that uses it, and nothing above ever gains it.
# `hashlimit` keeps a fixed-size, in-memory, LRU-evicted table that expires on
# its own and is written nowhere.
#
#   Install (see DEPLOY-ORACLE.md §4.1):
#     sudo ./skipti-connection-limit.sh apply
#     sudo ./skipti-connection-limit.sh verify
#     sudo ./skipti-connection-limit.sh remove
#
# NEVER add `-j LOG` to these rules. A logging jump turns this from the layer
# that is compatible with THREAT_MODEL's published "No IP logging" into the
# thing that makes it false. That is why the script installs the rules itself
# instead of leaving an operator to hand-write them from a doc.
#
# HONEST LIMIT, and the reason ADR-0032 has a second layer: this counts
# CONNECTIONS, not requests. HTTP keep-alive carries many requests on one
# connection, so a single well-behaved connection still issues requests as
# fast as it likes. This blunts naive floods and costs nothing; the relay's
# own admission bucket is what bounds request volume.

set -euo pipefail

PORT=443
# Sustained new-connection rate per source address, and the burst allowed
# before it engages. A browser opening a page makes a handful of connections;
# a phone syncing makes one. 20/minute with a burst of 40 is far above any
# real client and far below a flood.
RATE="20/minute"
BURST=40
# Bounded kernel memory: at most this many source addresses are tracked, each
# expiring after EXPIRE_MS of silence. Full table => LRU eviction, never
# unbounded growth.
MAX_ENTRIES=8192
EXPIRE_MS=60000
NAME=skipti443

# The rule, as an array so `apply`, `remove` and `verify` cannot drift apart:
# a remove that does not exactly match the applied rule silently leaves it in
# place while reporting success.
rule=(
  -p tcp --dport "$PORT" -m state --state NEW
  -m hashlimit
  --hashlimit-name "$NAME"
  --hashlimit-mode srcip
  --hashlimit-above "$RATE"
  --hashlimit-burst "$BURST"
  --hashlimit-htable-max "$MAX_ENTRIES"
  --hashlimit-htable-expire "$EXPIRE_MS"
  -j DROP
)

need_root() {
  [ "$(id -u)" -eq 0 ] || { echo "must run as root (sudo)" >&2; exit 1; }
}

# This is netfilter, so it is Linux-only. Refuse clearly rather than dying on
# "iptables: command not found" three lines later: a reader who runs it on a
# Mac has misread WHERE it belongs, and the error should say so rather than
# look like a broken script. macOS uses pf and has no equivalent of these
# rules; the relay's own admission bucket is the layer that runs everywhere.
need_iptables() {
  command -v iptables >/dev/null 2>&1 || {
    echo "iptables not found: this script belongs on the RELAY HOST" >&2
    echo "(Ubuntu on Oracle Cloud), not on a workstation. macOS uses pf and" >&2
    echo "has no netfilter. See DEPLOY-ORACLE.md §4.1." >&2
    exit 1
  }
}

have_rule() { iptables -C INPUT "${rule[@]}" 2>/dev/null; }

case "${1:-}" in
  apply)
    need_root
    need_iptables
    if have_rule; then
      echo "already installed, nothing to do (idempotent)"
      exit 0
    fi
    # -I 1 so it is evaluated BEFORE the ACCEPT rules for 80/443 that
    # DEPLOY-ORACLE §4 inserts. iptables stops at the first match: appended
    # below an ACCEPT, this rule would never be reached and would look
    # installed while doing nothing.
    iptables -I INPUT 1 "${rule[@]}"
    echo "installed: >$RATE new connections/src to $PORT dropped (burst $BURST)"
    echo "NOT persisted across reboot: see DEPLOY-ORACLE.md §4.1"
    ;;

  remove)
    need_root
    need_iptables
    if have_rule; then
      iptables -D INPUT "${rule[@]}"
      echo "removed"
    else
      echo "not present, nothing to remove"
    fi
    ;;

  verify)
    need_root
    need_iptables
    echo "== rule present =="
    if have_rule; then
      echo "yes"
    else
      echo "NO. Run: sudo $0 apply" >&2
      exit 1
    fi

    echo
    echo "== position (must be ABOVE the ACCEPT for $PORT) =="
    iptables -L INPUT -n --line-numbers | awk -v p="dpt:$PORT" '
      NR<=2 { next }
      $0 ~ p { print }
    '

    echo
    echo "== the promise: these rules must log NOTHING =="
    # A check that cannot fail is not a check. Count LOG targets across the
    # whole chain: the correct answer is 0, and a non-zero answer names a
    # real THREAT_MODEL violation rather than a style problem.
    logs=$(iptables -S INPUT | grep -c -- '-j LOG' || true)
    if [ "$logs" -ne 0 ]; then
      echo "FAIL: $logs LOG target(s) in INPUT. THREAT_MODEL's \"No IP" >&2
      echo "logging\" is false while any of them is installed." >&2
      exit 1
    fi
    echo "ok: 0 LOG targets in INPUT"

    echo
    echo "== counters (packets dropped by this rule so far) =="
    # Match on what iptables actually PRINTS, not on $NAME. The hashlimit
    # name is the /proc table name and never appears in `iptables -L` output,
    # so the first version of this grep could not match on a working rule:
    # it reported "the rule has never matched" forever, whatever the counters
    # said. An unfalsifiable line in a verify command is worse than no line.
    if ! iptables -L INPUT -n -v --line-numbers \
         | grep -E "dpt:$PORT.*limit: above"; then
      echo "no counter line: the rule is NOT installed (verify above should" >&2
      echo "already have failed; if it passed and this did not, the parser" >&2
      echo "and the rule have drifted apart)" >&2
      exit 1
    fi

    # NOT dumped, deliberately: the live table at
    # /proc/net/ipt_hashlimit/$NAME lists the source addresses currently
    # being counted. It is ephemeral kernel memory that expires on its own
    # (which is what makes this layer compatible with THREAT_MODEL's "No IP
    # logging"), but printing it here would put client addresses on an
    # operator's terminal and into their scrollback, and anything that
    # scrapes it into a file breaks the promise outright. See ADR-0032.
    ;;

  *)
    echo "usage: $0 {apply|remove|verify}" >&2
    exit 2
    ;;
esac
