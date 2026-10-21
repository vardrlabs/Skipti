#!/bin/bash
# cloud-audit.sh (D-201): re-verifies the live skipti-26 project against the
# repo, read-only, before every release (docs/CLOUD-SECURITY.md §8). It
# deploys nothing and needs no secret: the pinned CLI (logged in) and curl.
#
#   scripts/cloud-audit.sh            # every check; exit 1 if any fails
#
# What it cannot see (IAM, organization policy, enabled APIs, Auth providers,
# the budget, the Scheduler job, API key restrictions) is the founder's
# console list in CLOUD-SECURITY §8. Whether the deployed Firestore rules equal
# the repo's is shown by `firebase deploy --only firestore:rules` ("already up
# to date, skipping upload"): a deploy, so not run here.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
PROJECT=skipti-26
FB=firebase/node_modules/.bin/firebase
FN="https://us-central1-$PROJECT.cloudfunctions.net"
FS="https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"
SITE="${SKIPTI_SITE:-https://skipti.vardrlabs.com}"  # the domain moved to Hosting 2026-10-13; SKIPTI_SITE=https://skipti-26.web.app checks the raw site
RUNTIME="skipti-functions@$PROJECT.iam.gserviceaccount.com"
fails=0
ok() { printf 'ok    %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; fails=$((fails + 1)); }

# The Functions the source exports, by trigger kind.
callables=$(grep -oE '^export const [A-Za-z]+ = onCall' firebase/functions/index.js | awk '{print $3}')
triggered=$(grep -oE '^export const [A-Za-z]+ = on(DocumentCreated|Schedule)' firebase/functions/index.js | awk '{print $3}')
[ -n "$callables" ] && [ -n "$triggered" ] || { bad "could not read the Functions from index.js"; exit 1; }

# 1. Deployed Functions: exactly the source's, each running as skipti-functions.
list=$("$FB" functions:list --project "$PROJECT" --json 2>/dev/null)
report=$(printf '%s' "$list" | python3 -c '
import json, sys
want = set(sys.argv[1].split()); runtime = sys.argv[2]
try:
    d = json.load(sys.stdin)
except Exception:
    print("FAIL  functions:list unreadable (is the CLI logged in?)"); sys.exit()
fns = d.get("result", d)
have = {f["id"]: f.get("serviceAccount") or f.get("serviceAccountEmail") for f in fns}
missing, extra = sorted(want - set(have)), sorted(set(have) - want)
wrong = sorted(k for k, v in have.items() if v != runtime)
print("ok    deployed Functions are exactly the source'"'"'s (%d)" % len(want) if not (missing or extra)
      else "FAIL  deployed Functions differ: missing %s, extra %s" % (missing, extra))
print("ok    every Function runs as " + runtime if not wrong else "FAIL  not running as %s: %s" % (runtime, wrong))
' "$callables $triggered" "$RUNTIME")
printf '%s\n' "$report"
fails=$((fails + $(printf '%s\n' "$report" | grep -c '^FAIL')))

# 2. Every callable refuses a caller without a token (our code's 401).
for f in $callables; do
  body=$(curl -s -o - -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' -d '{"data":{}}' "$FN/$f")
  code=${body##*$'\n'}
  if [ "$code" = 401 ] && printf '%s' "$body" | grep -q UNAUTHENTICATED; then ok "callable $f: 401 without a token"
  else bad "callable $f answered $code without a token"; fi
done

# 3. Event and scheduled Functions are not public (Cloud Run's 403).
for f in $triggered; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$FN/$f")
  if [ "$code" = 403 ]; then ok "trigger $f: 403 from outside"; else bad "trigger $f answered $code from outside"; fi
done

# 4. Firestore refuses a caller with no account (the rules, not a missing document).
for path in users/audit-probe groups/audit-probe usernames/audit-probe invites/audit-probe limits/audit-probe; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$FS/$path")
  if [ "$code" = 403 ]; then ok "Firestore $path: 403 without an account"; else bad "Firestore $path answered $code without an account"; fi
done

# 5. Hosting serves the repo's files with their headers.
csp=$(curl -sI "$SITE/join" | tr -d '\r' | grep -i '^content-security-policy:')
hash=$(grep -o "sha256-[A-Za-z0-9+/=]*" firebase/firebase.json | head -1)
if printf '%s' "$csp" | grep -q "script-src '$hash'"; then ok "/join carries its pinned script hash"; else bad "/join CSP: $csp"; fi
csp404=$(curl -sI "$SITE/audit-probe" | tr -d '\r' | grep -i '^content-security-policy:')
if printf '%s' "$csp404" | grep -q "default-src 'none'" && ! printf '%s' "$csp404" | grep -q script-src; then
  ok "other paths: strict CSP, no script"; else bad "404 CSP: $csp404"; fi
for f in join/index.html privacy/index.html .well-known/apple-app-site-association .well-known/assetlinks.json 404.html; do
  url="$SITE/$f"; [ "$f" = join/index.html ] && url="$SITE/join"; [ "$f" = privacy/index.html ] && url="$SITE/privacy"; [ "$f" = 404.html ] && url="$SITE/audit-probe"
  if curl -s "$url" | cmp -s - "firebase/public/$f"; then ok "Hosting $f is the repo's, byte for byte"; else bad "Hosting $f differs from the repo"; fi
done
channels=$( (cd firebase && node_modules/.bin/firebase hosting:channel:list --project "$PROJECT" --json 2>/dev/null) | python3 -c '
import json, sys
d = json.load(sys.stdin); r = d.get("result", d)
print(" ".join(sorted(c["name"].split("/")[-1] for c in r.get("channels", r))))' 2>/dev/null)
if [ "$channels" = live ]; then ok "Hosting channels: live only"; else bad "Hosting channels: ${channels:-unreadable}"; fi

echo
if [ "$fails" -eq 0 ]; then echo "CLOUD AUDIT PASSED"; exit 0; fi
echo "CLOUD AUDIT FAILED ($fails)"; exit 1
