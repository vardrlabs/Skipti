# Releasing 0.5.1 to internal testing: step by step

Written 2026-10-24, the 0.5.1 deploy day. The cloud side is already done; what is left is yours: build both apps,
upload them, and tell the testers. Every command below is one block to paste into Terminal; wait for each to finish
before the next. If one prints an error, stop and paste it to Claude.

**Versions:** iOS Version **0.5.1**, Build **8**; Android versionName **0.5.1**, versionCode **5001** (decision 138).

## Already done today (nothing to do)

- The cloud: the approvals index, `adminApprovals` with your company Google account as the admin, the admin page
  `https://skipti-26-admin.web.app`, every Cloud Function, the Firestore rules, the privacy page Version 4 and the Data
  Access audit logs. `scripts/cloud-audit.sh` passes 51 of 51. Your 3 existing testers are approved.
- Two security fixes found by the day's CVE check: the Cloud Functions' body-parser (D-261, deployed) and the iPhone
  app's Firebase SDK 12.19.3 (D-262, in this build).
- The Developer Tools exemption is OFF (a signed build must be scanned by macOS).

## 1. Start from main (you)

```bash
cd ~/Documents/Skipti && git checkout main && git pull --ff-only && git status --porcelain && git log --oneline -1
```

*Expect:* nothing listed by the status, and a commit at or after `b988a557` (every commit since changes only
documents, so the apps are the same).

## 2. iPhone

**2.1 Check the versions.**

```bash
cd ~/Documents/Skipti && grep -c 'MARKETING_VERSION = 0.5.1;' ios/Skipti.xcodeproj/project.pbxproj && grep -c 'CURRENT_PROJECT_VERSION = 8;' ios/Skipti.xcodeproj/project.pbxproj && grep -c 'version = 12.19.3;' ios/Skipti.xcodeproj/project.pbxproj
```

*Expect:* `2`, `2`, then `1` (the Firebase SDK with the certificate fix).

**2.2 Build the Rust core for iPhone** (several minutes; slower than before now that macOS scans every build).

```bash
cd ~/Documents/Skipti && make ios-core
```

*Expect:* it ends with `Done: ios/Frameworks/SkiptiCore.xcframework`.

**2.3 Archive** (signed with your team, Vardr Labs LLC 27GK2T7UCT; the archive lands on your Desktop).

```bash
cd ~/Documents/Skipti && xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -configuration Release -destination 'generic/platform=iOS' -archivePath ~/Desktop/Skipti-0.5.1-8.xcarchive -allowProvisioningUpdates archive | tail -5
```

*Expect:* `** ARCHIVE SUCCEEDED **`. Then read the version back:

```bash
plutil -p ~/Desktop/Skipti-0.5.1-8.xcarchive/Products/Applications/Skipti.app/Info.plist | grep -E 'CFBundle(ShortVersionString|Version)"'
```

*Expect:* `"0.5.1"` and `"8"`. If signing fails ("No Account for Team"), open Xcode once (Settings, Accounts: your
Apple ID under Vardr Labs LLC) and paste the archive command again.

**2.4 Upload to App Store Connect.** Write the export settings (once per restart of the Mac):

```bash
cat > /tmp/SkiptiExport.plist <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>teamID</key><string>27GK2T7UCT</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
EOF
```

```bash
xcodebuild -exportArchive -archivePath ~/Desktop/Skipti-0.5.1-8.xcarchive -exportOptionsPlist /tmp/SkiptiExport.plist -exportPath /tmp/SkiptiExport-051 -allowProvisioningUpdates | tail -5
```

*Expect:* `Upload succeeded` (or `** EXPORT SUCCEEDED **`). A warning about symbols for Firebase's prebuilt libraries is
expected. If Terminal refuses, use Xcode instead: `open ~/Desktop/Skipti-0.5.1-8.xcarchive`, then **Distribute App**,
**App Store Connect**, **Upload**, accept the defaults.

**2.5 TestFlight (browser).** App Store Connect, SKIPTI, **TestFlight**. Build 8 appears after 10 to 30 minutes marked
**Missing Compliance**: answer as before (only standard encryption: SQLCipher on the phone, HTTPS on the wire; exempt).
Then Build 8, **Test Details**, **What to Test**, paste:

```
0.5.1
- A welcome on a new install: pick a look, add your name and picture. See it again under You > About SKIPTI.
- Just me: add an expense to Just me from the +. Your spending sits under your ledgers on Home.
- Settle up: pay part of a debt with Another amount; every settle waits a few seconds with Undo.
- Deleting an expense offers Undo right there.
- Amounts take a decimal comma where your phone uses one.
- Norse is more vibrant; its runes glow.
- Your settings follow you to your other phones when signed in.
- A group deleted on one phone now ends on every phone, with who deleted it.
- New accounts wait for approval before anything syncs; the phone works as usual meanwhile.
- Security updates in the app and the cloud.
Please update every phone today.
```

Internal testing adds Build 8 to your group by itself (if not: **Internal Testing**, your group, **Builds**, **+**).

*Check:* TestFlight on your iPhone shows SKIPTI 0.5.1 (8); install it.

## 3. Android

**3.1 Check the signing file and the version.**

```bash
cd ~/Documents/Skipti && test -f android/keystore.properties && echo 'keystore ok' && grep -nE 'version(Code|Name) =' android/app/build.gradle.kts
```

*Expect:* `keystore ok`, `versionCode = 0 * 1_000_000 + 5 * 1_000 + 1` and `versionName = "0.5.1"`.

**3.2 Build the Rust core for Android, then the signed bundle.**

```bash
cd ~/Documents/Skipti && make android-core
```

```bash
cd ~/Documents/Skipti/android && ./gradlew :app:bundlePlayRelease
```

*Expect:* `BUILD SUCCESSFUL`. A warning "Unable to strip ... libskipti_core.so" and Kotlin `w:` lines are expected. If
it fails with "Dependency verification failed", stop and paste it to Claude. Then check the bundle's signature:

```bash
keytool -printcert -jarfile ~/Documents/Skipti/android/app/build/outputs/bundle/playRelease/app-play-release.aab | grep SHA256
```

*Expect:* `41:84:18:7C:...` (your upload key). Anything else: stop.

**3.3 Upload (browser).** Play Console, SKIPTI, **Test and release**, **Testing**, **Internal testing**, **Create new
release**, upload `app-play-release.aab` from the folder this opens. Play shows **0.5.1 (5001)**. Expected warnings:
no deobfuscation file, no native debug symbols.

```bash
open ~/Documents/Skipti/android/app/build/outputs/bundle/playRelease/
```

**3.4 Play release notes.** Paste between `<en-US>` and `</en-US>`, then **Next**, **Save and publish**:

```
0.5.1
- Welcome guide for new installs.
- Just me: track what you spend on your own, and see All my spending with your share of your groups.
- Settle part of a debt, with Undo. Undo right after deleting an expense.
- Decimal comma support. A more vibrant Norse theme.
- Settings follow you across phones when signed in.
- A deleted group now ends on all your phones.
- New accounts wait for approval before syncing.
- Security updates.
Please update today.
```

*Check:* "Available to internal testers"; your Android phone updates from the Play Store.

## 4. Tell the testers (the same day)

Paste into your group chat:

```
SKIPTI 0.5.1 is up on TestFlight and the Play Store. Please update every phone today. Until you do, notifications from your phone read "<your name> changed an expense", and a group you delete shows your own name instead of "You". New in this one: a welcome guide, Just me for your own spending, part payments with Undo, and your settings follow you across phones. If you sign in with a new account, it waits for my approval before it syncs; your phone works as usual meanwhile.
```

## 5. Your checks once a tester has updated

- The first notification after the update names its author the way the cloud knows them ("Sam added Dinner ·
  $65.00"), not "Someone".
- Home looks the same as before on an approved tester's phone (nothing changed for approved accounts).
- A new sign-up (if anyone joins): it shows on the admin page under **Waiting**; approve only names you know.

## If something goes wrong

- **Apple refuses the upload:** a second upload must be Build 9: tell Claude, who bumps it (iOS builds count every
  upload).
- **Play refuses 5001:** a version code cannot be reused; it becomes 0.5.2 / 5002. Tell Claude before bumping.
- **A tester cannot sync after updating:** their account may be waiting or denied; check the admin page. Anything else:
  paste the exact screen or error to Claude.

## After the apps

Going public (Claude prepares, you approve each step): the full gate on the prep branch, then vardrlabs/Skipti public,
private vulnerability reporting on, the three advisories published (your yes each) and their CVEs requested.
