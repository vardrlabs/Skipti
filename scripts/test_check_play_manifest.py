#!/usr/bin/env python3
"""Self-test of scripts/check-play-manifest.sh (D-216; `make android-app` runs it after the check).

Standard library only. The real merged manifest (the play debug variant `make android-app` assembles) must pass; then
each fixture, the real manifest with one rule broken through an asserted anchor, is written to a temp dir and must
fail with its own message:
  (a) a fourth LAUNCHER entry (an unknown alias)
  (b) two aliases enabled by default
  (c) LAUNCHER back on MainActivity
  (d) an alias whose targetActivity is not MainActivity
  (e) a permission not on the allowlist (the ADR-0010 rule, untested until D-216)
  (f) EmojiCompat's startup initializer back in androidx.startup's provider (D-235 wave E, GUARANTEES #6)
Usage: python3 -I scripts/test_check_play_manifest.py [merged-manifest]
"""
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHECK = os.path.join(ROOT, "scripts", "check-play-manifest.sh")
MERGED = os.path.join(
    ROOT, "android/app/build/intermediates/merged_manifests/playDebug/processPlayDebugManifest/AndroidManifest.xml")


def run(path):
    p = subprocess.run(["bash", CHECK, path], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def once(text, pattern, repl, label):
    """Replace exactly one match of the regex `pattern`; refuse otherwise (an anchor that misses is a broken test)."""
    found = re.findall(pattern, text, flags=re.S)
    if len(found) != 1:
        sys.exit("test_check_play_manifest: fixture %s: anchor matched %d times: %s" % (label, len(found), pattern))
    return re.sub(pattern, repl, text, count=1, flags=re.S)


def element(tag, name):
    """The regex of one element `<tag ... android:name="...name" ...>...</tag>` (name matched at its end)."""
    return r'(<%s\s[^>]*?android:name="[^"]*%s"[^>]*>)(.*?)(</%s>)' % (tag, re.escape(name), tag)


def start_tag(tag, name):
    """The regex of one element's start tag, `<tag ... android:name="...name" ...>`."""
    return r'<%s\s[^>]*?android:name="[^"]*%s"[^>]*>' % (tag, re.escape(name))


def swap(text, old, new, label):
    """Replace `old` once inside `text`; refuse unless it appears exactly once."""
    if text.count(old) != 1:
        sys.exit("test_check_play_manifest: fixture %s: %r appears %d times" % (label, old, text.count(old)))
    return text.replace(old, new)


LAUNCHER_FILTER = ('<intent-filter><action android:name="android.intent.action.MAIN" />'
                   '<category android:name="android.intent.category.LAUNCHER" /></intent-filter>')


def fixtures(real):
    out = {}
    # (a) a fourth launcher entry: an alias the contract does not name, disabled so only the count can catch it.
    out["a"] = (once(real, r"(</application>)",
                     '<activity-alias android:name="com.vardrlabs.skipti.IconExtra" android:enabled="false" '
                     'android:exported="true" android:targetActivity="com.vardrlabs.skipti.MainActivity">'
                     + LAUNCHER_FILTER + r"</activity-alias>\1", "a"),
                "launcher aliases are not exactly the three icons")
    # (b) Norse enabled by default too.
    out["b"] = (once(real, start_tag("activity-alias", "IconNorse"),
                     lambda m: swap(m.group(0), 'android:enabled="false"', 'android:enabled="true"', "b"), "b"),
                "exactly one launcher alias must be enabled by default, found 2")
    # (c) the MAIN/LAUNCHER filter back on MainActivity.
    out["c"] = (once(real, element("activity", "MainActivity"),
                     lambda m: m.group(1) + LAUNCHER_FILTER + m.group(2) + m.group(3), "c"),
                "LAUNCHER on <activity> com.vardrlabs.skipti.MainActivity")
    # (d) Vardr's alias opens another activity.
    out["d"] = (once(real, start_tag("activity-alias", "IconVardr"),
                     lambda m: swap(m.group(0), 'android:targetActivity="com.vardrlabs.skipti.MainActivity"',
                                    'android:targetActivity="com.vardrlabs.skipti.OtherActivity"', "d"), "d"),
                "launcher alias com.vardrlabs.skipti.IconVardr targets com.vardrlabs.skipti.OtherActivity")
    # (e) a permission nobody allowed.
    out["e"] = (once(real, r"(<application\b)",
                     r'<uses-permission android:name="android.permission.READ_CALENDAR" />\1', "e"),
                "uses-permission not on the allowlist: android.permission.READ_CALENDAR")
    # (f) a library brings EmojiCompat's initializer back: Play services would fetch its font at every launch.
    out["f"] = (once(real, r'(<provider\s[^>]*?android:name="androidx\.startup\.InitializationProvider"[^>]*>)',
                     r'\1<meta-data android:name="androidx.emoji2.text.EmojiCompatInitializer" '
                     r'android:value="androidx.startup" />', "f"),
                "androidx.emoji2.text.EmojiCompatInitializer is in the merged manifest")
    return out


def main():
    merged = sys.argv[1] if len(sys.argv) > 1 else MERGED
    if not os.path.isfile(merged):
        print("CHECK-PLAY-MANIFEST SELF-TEST FAILED: merged manifest not found (build the play flavour first): " + merged)
        return 1
    real = open(merged, encoding="utf-8").read()
    code, out = run(merged)
    if code != 0 or "PLAY MANIFEST CHECK OK" not in out:
        # The fixtures are made from it, so they would prove nothing.
        print("CHECK-PLAY-MANIFEST SELF-TEST FAILED: the real merged manifest did not pass:\n" + out)
        return 1
    failures = []
    cases = fixtures(real)
    messages = [m for _, m in cases.values()]
    if len(set(messages)) != len(messages):
        failures.append("two fixtures expect the same message")
    with tempfile.TemporaryDirectory() as tmp:
        for label, (text, want) in sorted(cases.items()):
            if text == real:
                failures.append("(%s) the fixture changed nothing" % label)
                continue
            path = os.path.join(tmp, "fixture-%s.xml" % label)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(text)
            code, out = run(path)
            if code == 0:
                failures.append("(%s) passed the check; want a failure with: %s" % (label, want))
            elif want not in out:
                failures.append("(%s) failed without its own message %r:\n%s" % (label, want, out))
            else:
                others = [m for m in messages if m != want and m in out]
                if others:
                    failures.append("(%s) also printed another fixture's message: %s" % (label, others))
    if failures:
        for f in failures:
            print("CHECK-PLAY-MANIFEST SELF-TEST FAILED: " + f)
        return 1
    print("CHECK-PLAY-MANIFEST SELF-TEST OK: the merged manifest passes; (a) to (f) each fail with their own message (D-216, D-235)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
