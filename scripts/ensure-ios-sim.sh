#!/usr/bin/env bash
#
# Resolve, boot if needed, and WAIT FOR LAUNCHABILITY of the iOS gate
# simulator, echoing its UDID for -destination 'platform=iOS Simulator,id=<UDID>'.
#
# MECHANISM (D-98, superseding D-96's stated cause): the variable is whether
# the simulator is ALREADY WARM, not destination form and not lifecycle
# ownership. `simctl bootstatus -b` reports boot FINISHED while SpringBoard
# still refuses app launches ("denied by service delegate (SBMainWorkspace)
# for reason: Busy (Application failed preflight checks)"), so the gate
# waits for what it actually needs: PROOF THAT AN APP CAN LAUNCH.
#
# The readiness probe deliberately launches a TRIVIAL SYSTEM APP (Settings)
# and retries until SpringBoard accepts it, bounded by a clear timeout with
# its own distinct failure message. Retrying a probe is legitimate; nothing
# here ever retries the tests. Boot-once reuse stays (D-96): one lifecycle
# instead of N per session is right on its own merits.
#
#   ./scripts/ensure-ios-sim.sh [SIMULATOR NAME]     # default: iPhone 17

set -euo pipefail

NAME="${1:-iPhone 17}"
PROBE_BUNDLE="com.apple.Preferences"
READINESS_TIMEOUT_SECS="${IOS_SIM_READINESS_TIMEOUT:-120}"
POLL_INTERVAL_SECS=2

# Several runtimes can each have a device with this name (Xcode 27 added an
# iOS 27.0 "iPhone 17" next to the iOS 26.5 one). By default the NEWEST iOS
# runtime wins, so the gate tests what the current SDK ships; SIM_OS pins one
# (e.g. SIM_OS=26.5 for the older runtime).
udid="$(xcrun simctl list devices | awk -v n="$NAME (" -v want="${SIM_OS:-}" '
  /^-- iOS [0-9.]+ --$/ { os = $3; next }
  /^-- / { os = ""; next }
  os != "" && index($0, n) && /Shutdown|Booted/ && (want == "" || os == want) {
    split($0, p, /[()]/); print os, p[2]
  }' | sort -V -k1,1 | tail -1 | cut -d" " -f2)"

[ -n "$udid" ] || { echo "no available simulator named \"$NAME\"${SIM_OS:+ on iOS $SIM_OS}" >&2; exit 1; }

state="$(xcrun simctl list devices | awk -F'[()]' -v u="$udid" 'index($0,u){for(i=1;i<=NF;i++) if($i=="Booted"||$i=="Shutdown") print $i}')"
if [ "$state" != "Booted" ]; then
  echo "==> cold start: booting $NAME ($udid)" >&2
  xcrun simctl boot "$udid"
else
  echo "==> $NAME ($udid) already booted (warm)" >&2
fi

# Phase 1 (coarse): CoreSimulator says the boot process finished.
(xcrun simctl bootstatus "$udid" -b >&2)

# Phase 2 (REAL readiness): poll until SpringBoard accepts an app launch.
# This is the part bootstatus does not know about (D-98).
echo "==> probing launchability ($PROBE_BUNDLE), bounded ${READINESS_TIMEOUT_SECS}s" >&2
deadline=$(( SECONDS + READINESS_TIMEOUT_SECS ))
while :; do
  if xcrun simctl launch "$udid" "$PROBE_BUNDLE" >/dev/null 2>&1; then
    xcrun simctl terminate "$udid" "$PROBE_BUNDLE" >/dev/null 2>&1 || true
    echo "==> simulator ready: system app launched cleanly" >&2
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "READYNESS-PROBE-TIMEOUT: simulator \"$NAME\" ($udid) booted but refused app launches for ${READINESS_TIMEOUT_SECS}s: SpringBoard never became ready; refusing to hand a half-ready device to the test leg" >&2
    exit 2
  fi
  sleep "$POLL_INTERVAL_SECS"
done

echo "$udid"
# (stdout contract: callers do `-destination id=$(...)`; every other byte must go to stderr)
