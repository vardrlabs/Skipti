#!/usr/bin/env bash
#
# The play flavour's manifest contract (ADR-0010), asserted against the
# MERGED manifest: merging is where a permission would arrive from a
# library, so the source manifest proves nothing.
#
# v1 ships with NO sensitive permission in either flavour. The merged
# manifest's uses-permission entries are checked against an ALLOWLIST of
# exactly one entry: the app-private dynamic-receiver permission the
# toolchain auto-generates (signature-level, invisible to Play's user
# permission review). Any OTHER permission (SMS-shaped or otherwise)
# fails the check. A permission added later (v1.2-at-the-earliest per
# ADR-0010) must come with this allowlist being narrowed DELIBERATELY,
# never silently.
#
# Usage: check-play-manifest.sh [path-to-merged-manifest]
# Exits non-zero on any non-allowlisted permission, on any SMS-shaped
# string, or if the manifest is missing (a check that cannot find its
# subject is a failure, not a skip).

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

MANIFEST="${1:-}"

if [ -z "$MANIFEST" ]; then
  # D-133: pinned to the variant `make android-app` assembles. The old lookup
  # was an `ls -t merged_manifests/*/play*/` glob that could never match (the
  # variant directory is processPlayDebugManifest, not play*), then an
  # unordered `find | head -1` over EVERY play variant. That found playDebug
  # by luck; a playRelease manifest from another day was equally eligible.
  # (Checked when found: the manifest packaged in the APK matched the one
  # read, so nothing false had been approved.)
  MANIFEST=android/app/build/intermediates/merged_manifests/playDebug/processPlayDebugManifest/AndroidManifest.xml
fi

if [ -z "$MANIFEST" ] || [ ! -f "$MANIFEST" ]; then
  echo "PLAY MANIFEST CHECK FAILED: merged manifest not found (build the play flavour first)"
  exit 1
fi

echo "checking: $MANIFEST"

# The one auto-generated, app-private, signature-level permission, plus
# INTERNET (D-117): the app speaks to its relay (sign-in §4.2/§4.3, sync
# §10.3) and Android refuses every socket without it. INTERNET is a
# NORMAL, install-time permission: no user approval, invisible to the
# runtime permission model, not sensitive in ADR-0016's sense (that rule
# confines SENSITIVE permissions to updates). It was absent until D-117
# because until then nothing in the app ever opened a socket. The moment
# it does, the permission is the honest declaration of that.
#
# D-118 adds the two the passkey dependency brings: androidx.credentials'
# own manifest declares USE_BIOMETRIC + USE_FINGERPRINT (the pre-28
# alias) because the Credential Manager consent step is a BIOMETRIC
# PROMPT: the ceremony cannot ask the user to consent without it. Both
# are NORMAL-level gatekeepers for a platform UI; neither reads biometric
# DATA, which never leaves the provider. The merged-manifest check
# CAUGHT these arriving from a library. This allowlist extension is
# that check working, taken deliberately in the open. Any OTHER
# permission still fails the check.
#
# D-190 adds POST_NOTIFICATIONS, deliberately and in the open (the founder,
# 2026-09-30, asked for notifications and left the design to Claude): the
# opt-in background check's "New activity in Goa trip". On Android 13+ it is
# a RUNTIME permission the app asks for only when the person turns
# Settings → Notifications on, never at launch; it reads nothing, and it is
# not one of Play's sensitive or restricted permissions (SMS, call log,
# location, accessibility, notification access). Every other permission
# still fails.
#
# D-191 adds ACCESS_NETWORK_STATE, deliberately and in the open: Firebase Auth
# and its reCAPTCHA library declare it (read off the merged manifest's report).
# NORMAL install-time level: it says whether the phone is online, nothing
# more. The same library's com.google.android.providers.gsf READ_GSERVICES is
# NOT taken: the app manifest removes it (tools:node="remove"); reCAPTCHA only
# serves email and phone sign-in, which SKIPTI does not offer.
#
# D-196 adds two, deliberately and in the open, both declared by
# firebase-messaging (read off the merged manifest's report): FCM's
# com.google.android.c2dm.permission.RECEIVE (lets Google Play services hand
# this app its own pushes; without it no push arrives) and WAKE_LOCK (keeps
# the phone awake for the moment a push is handled). Both are NORMAL,
# install-time permissions with no user prompt; neither reads anything. FCM
# starts only with an account and sync on (Push.kt). Every other permission
# still fails.
#
# D-197 adds READ_CONTACTS, deliberately and in the open (the founder,
# 2026-10-02, chose "match all contacts" for finding friends). A DANGEROUS,
# runtime permission: asked only when the person taps "Find friends in
# contacts", never at launch. The app reads contacts' email addresses, turns
# each into a SHA-256 fingerprint on the phone and sends only those; the cloud
# keeps none (CLOUD-SECURITY.md). Play's User Data policy applies: a prominent
# disclosure (the text under the button) and the privacy policy and Data
# safety form must say so before release. Every other permission still fails.
ALLOWED="com.vardrlabs.skipti.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
android.permission.INTERNET
android.permission.USE_BIOMETRIC
android.permission.USE_FINGERPRINT
android.permission.POST_NOTIFICATIONS
android.permission.ACCESS_NETWORK_STATE
android.permission.WAKE_LOCK
com.google.android.c2dm.permission.RECEIVE
android.permission.READ_CONTACTS"

FAILED=0
while IFS= read -r name; do
  [ -z "$name" ] && continue
  if ! printf '%s' "$ALLOWED" | grep -qx "$name"; then
    echo "PLAY MANIFEST CHECK FAILED: uses-permission not on the allowlist: $name"
    FAILED=1
  fi
done < <(grep -o 'uses-permission android:name="[^"]*"' "$MANIFEST" |
         sed 's/uses-permission android:name="//; s/"$//' | sort -u)

if grep -qi 'READ_SMS\|RECEIVE_SMS\|SEND_SMS\|RECEIVE_MMS\|RECEIVE_WAP_PUSH' "$MANIFEST"; then
  echo "PLAY MANIFEST CHECK FAILED: SMS-shaped string present"
  FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  echo "ADR-0010: v1 ships with NO sensitive permission. If a permission is now"
  echo "intended, this allowlist must be narrowed DELIBERATELY, in the open."
  exit 1
fi

# D-216: the launcher contract. The app's launcher entries are exactly three
# activity-aliases of MainActivity, one per app icon (IconAurora, IconNorse,
# IconVardr: PERMANENT names, a launcher stores the component name), and the
# manifest enables exactly one of them (a missing android:enabled counts as
# enabled). MainActivity itself is no launcher entry: it cannot be disabled,
# so only the aliases can switch. Read from the merged manifest with the
# standard library (python3 -I, xml.etree); each broken rule prints its own line.
if ! python3 -I - "$MANIFEST" <<'PY'
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
LAUNCHER = "android.intent.category.LAUNCHER"
MAIN = "com.vardrlabs.skipti.MainActivity"
ALIASES = ["com.vardrlabs.skipti.IconAurora", "com.vardrlabs.skipti.IconNorse", "com.vardrlabs.skipti.IconVardr"]

root = ET.parse(sys.argv[1]).getroot()
package = root.get("package") or "com.vardrlabs.skipti"
app = root.find("application")
if app is None:
    print("PLAY MANIFEST CHECK FAILED: no <application> in the merged manifest")
    sys.exit(1)


def full(name):
    name = name or ""
    return package + name if name.startswith(".") else name


def launcher(el):
    return any(c.get(A + "name") == LAUNCHER for f in el.findall("intent-filter") for c in f.findall("category"))


aliases = app.findall("activity-alias")
problems = []


def counts():
    """The count check: exactly the three aliases carry LAUNCHER, exactly one is enabled by default."""
    out = []
    entries = sorted(full(a.get(A + "name")) for a in aliases if launcher(a))
    if entries != sorted(ALIASES):
        out.append("launcher aliases are not exactly the three icons: %s" % entries)
    enabled = [full(a.get(A + "name")) for a in aliases if launcher(a) and a.get(A + "enabled", "true") != "false"]
    if len(enabled) != 1:
        out.append("exactly one launcher alias must be enabled by default, found %d: %s" % (len(enabled), enabled))
    return out


problems += counts()
# D-235 wave E (decision 109, GUARANTEES #6): EmojiCompat's startup initializer has Google Play services fetch the
# "Noto Color Emoji Compat" font from fonts.gstatic.com at every launch, with no account. The app manifest removes it
# (tools:node="remove"); a library that brings it back fails here.
EMOJI = "androidx.emoji2.text.EmojiCompatInitializer"
for el in root.iter():
    if el.get(A + "name") == EMOJI:
        problems.append("%s is in the merged manifest: it fetches an emoji font from Google at every launch" % EMOJI)
        break
for el in app:
    if el.tag != "activity-alias" and launcher(el):
        problems.append("LAUNCHER on <%s> %s: only the icon aliases are launcher entries" % (el.tag, full(el.get(A + "name"))))
for a in aliases:
    if launcher(a) and full(a.get(A + "targetActivity")) != MAIN:
        problems.append("launcher alias %s targets %s, not %s" % (full(a.get(A + "name")), full(a.get(A + "targetActivity")), MAIN))

for p in problems:
    print("PLAY MANIFEST CHECK FAILED: " + p)
sys.exit(1 if problems else 0)
PY
then
  echo "D-216: the launcher entries are the three icon aliases of MainActivity, one enabled; D-235: no EmojiCompat initializer."
  exit 1
fi

echo "PLAY MANIFEST CHECK OK: D-216 launcher contract (three icon aliases of MainActivity, one enabled) + no EmojiCompat startup font fetch (D-235) + INTERNET (D-117) + the biometric-prompt pair from androidx.credentials (D-118, normal-level) + POST_NOTIFICATIONS (D-190, opt-in) + ACCESS_NETWORK_STATE (D-191, Firebase Auth) + WAKE_LOCK and c2dm RECEIVE (D-196, FCM) + READ_CONTACTS (D-197, find friends, asked on tap) + the toolchain's dynamic-receiver permission (ADR-0010 v1)"
