# Releasing 0.5.0 (the new look and the audit's fixes): step by step

Version 5 of this runbook (2026-10-20: 0.5.0 is out, iOS Build 7 on TestFlight and Android 5000 uploaded to Play; no
rebuild; the next release is 0.5.1 on both phones, iOS 0.5.1 Build 8 and Android 0.5.1 / 5001, see "The versions" and
the end of Phase F). Version 4 (the cloud deploy done; Phase F with the iPhone in full and every command a paste block),
drafted 2026-10-19 (D-236, release prep) after D-234 and D-235 merged (`main` at 78be530);
revised the same day by the Fable audit (D-237): Phase A done, its three fixes merged (a Functions change deploys with
E3; no new index, no rules change), the read-only production checks and the wider sweep done (all clean), your calls
of the evening folded in (decision 135: no 0.4.0 set-ups, the two testers update on the day), the safety notes in E3 to
E5 and G1. Everything up to Phase D is done or yours on paper; the day starts at Phase D.
`docs/RELEASE-INTERNAL-TESTING.md` stays the reference for one-time setup and earlier releases, and
`docs/RELEASE-0.4.0.md` for the Play forms; this file is the order to follow for 0.5.0, start to finish.

**What changed since 0.4.0, for you.** 0.4.0 had nothing to deploy. 0.5.0 has a lot: the cloud has been held back since
2026-10-14 ("nothing deploys until everything for 0.5.0 is done"), so two new Firestore indexes, eleven new Functions,
changes to all seventeen live ones and a rules change all go out together, in your order (decision 126): **indexes
first and wait until READY, then the Functions, then the rules, then the apps, and testers asked to update the same
day.** The Fable security audit came first (D-237, done). After the deploy, your checks on real phones.

**What you need:** the Mac with Xcode; your Android upload keystore and `android/keystore.properties`; an iPhone and an
Android phone on 0.4.0 with SKIPTI signed in (two accounts between them for the group checks); ideally a spare Android
phone that has never had a production build; one long day for Phases E to H (deploy, build, check, tell testers), and
the audit and your calls before it.

**Who runs what.** Steps marked **(you)** are yours alone: consoles, signing, store uploads, the Developer Tools
setting, anything on your phones. Steps marked **(Claude, after your yes)** are deploys: Claude runs each one only after
your yes in chat for THAT step (the command is printed so you can run it yourself instead). Steps marked **(read
only)** change nothing.

Each phase ends with a check. If a check fails, stop and tell Claude what you saw.

---

## The versions

| | 0.4.0 (shipped) | **0.5.0** |
|---|---|---|
| iOS `MARKETING_VERSION` (Version) | 0.4.0 | **0.5.0** |
| iOS `CURRENT_PROJECT_VERSION` (Build, one more per upload) | 6 | **7** |
| Android `versionName` | 0.4.0 | **0.5.0** |
| Android `versionCode` (derived: major x 1,000,000 + minor x 1,000 + patch) | 4000 | **5000** |

**0.5.0 shipped as above (the founder, 2026-10-20): Build 7 on TestFlight, 5000 uploaded to Play.** There is no 0.5.0
rebuild: D-239 (moments play under power saving) and the 0.5.1 plan's units ship in **0.5.1, the same name on both
phones: iOS Version 0.5.1, Build 8; Android versionName 0.5.1, versionCode 5001** (the founder's pop-up: both phones
read 0.5.1). Claude makes the version edits at 0.5.1's release prep, not before, so every unit until then builds as
0.5.0 (7) / 5000 and no debug build claims a version that is not yet released.

**The edits (D-236, made by `.drafts/release-050/apply.py`, which refuses if any anchor is missing):**

- `ios/Skipti.xcodeproj/project.pbxproj`, inside `XC0011 /* Debug */` AND inside `XC0012 /* Release */`:
  `CURRENT_PROJECT_VERSION = 6;` becomes `CURRENT_PROJECT_VERSION = 7;` and `MARKETING_VERSION = 0.4.0;` becomes
  `MARKETING_VERSION = 0.5.0;` (four lines; each old string is in the file exactly twice before, never after).
- `android/app/build.gradle.kts` (`defaultConfig`): `versionCode = 0 * 1_000_000 + 4 * 1_000 + 0` becomes
  `versionCode = 0 * 1_000_000 + 5 * 1_000 + 0`, and `versionName = "0.4.0"` becomes `versionName = "0.5.0"`.
- `docs/RELEASE-INTERNAL-TESTING.md` section 5: the sentence now ends "0.4.0 was Build 6 and 4000; **now 0.5.0: iOS
  Version 0.5.0, Build 7; Android versionName 0.5.0, versionCode 5000**". (Sections 5.1 and 5.2 still name 0.3.0 in
  their examples; this file replaces them for 0.5.0, as RELEASE-0.4.0.md did for 0.4.0.)

**Read-backs (Claude, after the edits, from a fresh build):**

```bash
grep -c 'CURRENT_PROJECT_VERSION = 7;' ios/Skipti.xcodeproj/project.pbxproj     # 2
grep -c 'MARKETING_VERSION = 0.5.0;' ios/Skipti.xcodeproj/project.pbxproj       # 2
grep -nE 'version(Code|Name) =' android/app/build.gradle.kts                    # 5 * 1_000 and "0.5.0"
plutil -p ios/build/Build/Products/Debug-iphonesimulator/Skipti.app/Info.plist | grep -E 'CFBundle(ShortVersionString|Version)"'
~/Library/Android/sdk/build-tools/*/aapt2 dump badging android/app/build/outputs/apk/play/debug/app-play-debug.apk | head -1
```

Expect `0.5.0` and `7`; `versionCode='5000' versionName='0.5.0'`; You → About reads "Version 0.5.0 (7)" on iPhone.

**If a store needs a second upload of 0.5.0** (a rejected build, or a fix found in Phase G): iOS goes to **Build 8**
with the version unchanged. Android cannot reuse 5000 once uploaded, so it becomes **0.5.1 / 5001**, and the planned
0.5.1 feature release takes the next free name. That is why Phase C3 checks Aurora on a spare Android BEFORE the upload.

---

## Phase 0. Claude: the release tree (no action from you)

1. D-235 (the Opus pre-release audit and its fixes) merged into `main`: full gate 23 of 23, records, merge, push, no
   Actions run.
2. D-236 (this release prep): the version edits above, this file into `docs/`, the notes, a full gate, records, merge,
   push, no Actions run. Claude names the merged `main` commit in chat: every later phase builds and deploys from it.

*Check:* Claude has named the commit, and `gh run list --branch main --limit 4` shows no new run.

---

## Phase A. The Fable security audit: DONE (D-237, 2026-10-19)

Report `.drafts/audit-050/findings.md`; review `.superpowers/sdd/d237/review.md`; decisions 131 to 134. Three fixes
merged (`main` 5b5aec7, gate 23 of 23): a place you joined as in one group no longer counts as you in another (core,
both phones), every record stamp takes a random step (core), and a participant id belongs to the account that
registered it first (Functions: deploys in E3, no index, no rules change). Everything else is recorded, nothing above
Low for internal testing. The read-only production checks found nothing wrong (CLOUD-SECURITY section 7, the two
paragraphs after the leads' answers): every joined member holds a used invite, no place has two holders, no unknown
author or id, every profile complete; the live rules equal the repo's at D-197; Google and Apple the only providers;
the 17 live Functions at 256 MiB as `skipti-functions`; no user Storage bucket; no composite index yet; the service
account's five roles only. One surface item for you, not a risk: Google enabled `analyticshub.googleapis.com` beside
BigQuery since D-201; nothing uses it; disable it in the console when you next pass (as Remote Config was).

*Stop condition met:* nothing Critical or High is open. Phase 0's commit is superseded by the one Claude names in D2.

---

## Phase B. Your calls on paper (30 minutes)

1. **The open list.** `docs/DECISIONS-TAKEN.md` → "Open for you before 0.5.0 deploys", items 1 to 13. Answer what needs
   no phone now (2, 3, 4, 6, 8's confirmations, 12's deploy timing and decision 115's two metadata refreshes); the phone
   items are answered in Phase G. Decisions 125 (the words of 83 and 84) are already yours and closed; 131 to 134 are
   the audit's (read them; 134 lists what waits for 0.5.1); 135 is yours (below).
2. **The store notes.** `.drafts/release-050/notes.md` (Claude puts the chosen text into this file's Phases F2 and F4
   before you start): pick variant A or B for each store, or change the words. They are public text.
3. **The privacy page (DECIDED, decision 130, 2026-10-19).** Since 0.4.0 your profile picture syncs to your phones and a
   small preview is shared with your groups (D-211). The page's line now reads "Your profile: the name you show others,
   a username if you choose one, and a picture if you add one, shown small to your groups." (committed in D-236, live
   only after the Hosting deploy in Phase E6 with your yes). In Play's Data safety the Photos entry's purpose stays App
   functionality (the type is already declared for a group's picture).

4. **The Auth record (DONE, 2026-10-19).** Read by Claude with your CLI access, field names only: Google gives a
   display name, a verified email and a picture URL through Android's sign-in, and a picture URL with no email at all
   through the iPhone's `openid`-only sign-in (two of the four accounts). CLOUD-SECURITY 4.3 says so. Nothing to do.

*Check:* the open items you can answer are answered in chat; the notes are chosen; the privacy line is decided.

---

## Phase C. Before the deploy: nothing on 0.4.0 phones (decision 135)

Your call (2026-10-19): the two testers on 0.4.0 are told to update on the day, and nothing is set up on their phones
first. Their usernames are server documents that neither the rules change nor the apps touch; they survive the update
as they are. G2 below is reworded for phones that are both on 0.5.0.

**C1. Aurora's scroll on a SPARE Android, before the upload (optional, recommended).** Why: if this phone says the
Android blur needs its bake (decision 79), the bake must go into the ONE upload; after the upload Android cannot reuse
0.5.0 (see The versions). Only on a spare phone that has never run a production build (incident 26): a debug build with
`--groups-proof` has no account and touches no cloud. **(you)**

```bash
cd ~/Documents/Skipti && make android-app
~/Library/Android/sdk/platform-tools/adb install -r android/app/build/outputs/apk/play/debug/app-play-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n com.vardrlabs.skipti/.MainActivity --es args --groups-proof
```

Then run check G10's measurement on it. No spare Android: skip C1; G10 runs on the Play build instead.

*Check:* C1 sent to Claude or skipped.

---

## Phase D. Prepare the Mac (10 minutes)

**D1. Turn the Developer Tools exemption OFF (you)** (CLAUDE.md 13: a signed or store build must be scanned by macOS).
System Settings → **Privacy & Security** → **Developer Tools** → switch off **Claude**, **Terminal** and **Xcode**. If
you once showed that pane with a Terminal command, hide it again:

```bash
sudo spctl developer-mode disable-terminal
```

Quit and reopen Claude, Terminal and Xcode. (Claude's gates now take 30 to 40 minutes; expected.)

**D2. Start from the exact tree Claude named (you).**

```bash
cd ~/Documents/Skipti && git checkout main && git pull --ff-only && git status --porcelain && git log --oneline -1
```

Nothing printed by the status; the last line is the commit Claude names in chat on the day (`main` after D-237; b2e53e1
when this version was written, or newer). Every later phase builds and deploys from it.

**D3. No user Storage bucket exists (read only).** The apps link no Storage SDK and the rules stay undeployed; a bucket
that appeared (a console click) would sit with Google's default rules. Confirmed 2026-10-19: only Cloud Functions' own
two buckets (`gcf-v2-sources-…`, `gcf-v2-uploads-…`) exist, which is right. Expect only those two:

```bash
gcloud storage buckets list --project skipti-26
```

**D4. The Firebase CLI is logged in (read only).**

```bash
cd ~/Documents/Skipti && firebase/node_modules/.bin/firebase projects:list --non-interactive | grep skipti-26
```

If it says the login expired, sign in again **(you)**, then paste the check above once more:

```bash
cd ~/Documents/Skipti/firebase && node_modules/.bin/firebase login --reauth
```

*Check:* clean tree, the expected commit, the CLI lists `skipti-26`.

---

## Phase E. Deploy the cloud, in order (an hour; each step with your yes)

**Status: PHASE E DONE (2026-10-19 evening, run by you from `main` f6ce22f).** E1 both indexes READY, E3 28 Functions
(512 MiB, 1 CPU, concurrency 6, 10 instances), E4 read back, E5 the live rules equal the repo's byte for byte, E6 the
privacy page live and `scripts/cloud-audit.sh` passed every check. CLOUD-SECURITY section 2 has a row for each.

Every command runs from `~/Documents/Skipti/firebase` with the repo's pinned CLI. Never pass `--force` (it would delete
anything in the project the repo does not list). After each step, Claude writes its dated row in
`docs/CLOUD-SECURITY.md` section 2 the same day (who, why, what it opens), from D-235's "pending at 0.5.0" paragraphs.

**E1. The two composite indexes (Claude, after your yes).** Records by (type, authorUid, createdAt) and invites by
(groupId, usedBy). Production has none today.

```bash
cd ~/Documents/Skipti/firebase && node_modules/.bin/firebase deploy --only firestore:indexes --project skipti-26 --non-interactive
```

**E2. Wait until both are READY (read only).** Until then every Function that reads a group's log fails in every group,
the invite Functions included. Run every few minutes:

```bash
gcloud firestore indexes composite list --project skipti-26
```

*Stop condition:* go on only when BOTH rows (collection group `records` and `invites`) read **READY**. If one reads
anything else after an hour, or the deploy printed an error, stop and tell Claude.

**E3. The Functions (Claude, after your yes).** The whole set in one deploy: the shared modules (`groups.js`,
`people.js`, `account.js`) are used by most Functions, so a partial deploy would run two versions of one rule. The
configuration is supported by the pinned CLI (checked 2026-10-19): a gen-2 Function at 512 MiB gets one CPU by
default, the minimum the CLI's validator wants for concurrency above 1, so the "concurrency refused for lack of CPU"
stop below is not expected; the runtime is Node 22, the CLI 15.31.0 and logged in.

```bash
cd ~/Documents/Skipti/firebase && node_modules/.bin/firebase deploy --only functions --project skipti-26 --non-interactive
```

What it does. Production has the 17 Functions of 2026-10-13 (`createInvite`, `peekInvite`, `acceptInvite`,
`onGroupRecord`, `setUsername`, `checkUsername`, `suggestUsernames`, `setFindable`, `findPeople`, `inviteUser`,
`inviteKnown`, `onDeviceCreated`, `signOutDevice`, `signOutEverywhere`, `requestDeletion`, `cancelDeletion`,
`purgeDeletedAccounts`); the source has 28. **Eleven are created**, all callables with a public invoker that check the
caller's sign-in themselves; **all seventeen are updated**, if only for D-235's runtime options (512 MiB, at most 6
calls an instance, at most 10 instances; the purge may run 30 minutes). By unit:

| Unit | New Functions | Changed Functions |
|---|---|---|
| D-210 roles and removal | `groupRoles`, `setAdmin`, `makeOwner`, `requestRemoval`, `declineRemoval`, `removeMember`, `deleteGroup`, `adoptRemovals` | `acceptInvite` (refuses a place another account holds; clears `former` on a rejoin), `purgeDeletedAccounts` (hands a deleted owner's groups on; deletes a destroyed group's picture) |
| D-211 profile pictures | `shareProfile` | `purgeDeletedAccounts` (deletes the account's marked previews) |
| D-218 held places | | `createInvite`, `inviteUser`, `inviteKnown` (refuse an invite for a held place) |
| D-227 the creator's place | | the hold rule in `groups.js`, used by `createInvite`, `inviteUser`, `inviteKnown`, `acceptInvite` and the role Functions; `groupRoles` answers places |
| D-229 one account, two phones | `shareAliases` (daily `aliases` quota 100) | `acceptInvite` (refuses an account already in the group) |
| D-230 confirmed places | `sharePlaces` (daily `places` quota 100) | `acceptInvite` (the join's `records`), `peekInvite` (answers `groupId` and `memberId`), the invite Functions (no invite for a removed place) |
| D-235 the audit's fixes | | every Function's runtime options; the log read per author, never refused (every log reader); `acceptInvite` (a removed member's old link refused; the place must be in the log); `createInvite`, `inviteUser`, `inviteKnown` and `peekInvite` (names from the server, decision 121); `removeMember` (voids the removed account's links; an account is always removable); `adoptRemovals` (only in a group nobody else joined); `purgeDeletedAccounts` (voids links, deletes names, pages); `setFindable`, `requestDeletion`, `cancelDeletion` (never create a profile); `shareProfile` (picture frame caps) |
| D-237 the Fable audit's fix | | `sharePlaces`, `acceptInvite` (the join's marks), `shareAliases` and `inviteKnown`: a participant id belongs to the account that registered it first (decision 133); a later profile with a copied id denies nothing and marks nothing |

0.4.0 phones keep working through this step: `acceptInvite` treats a missing `records` as none.

*Stop condition:* the CLI ends with "Deploy complete!". If it asks to delete a Function, refuses concurrency for lack of
CPU, or fails a build, stop. A failed deploy can leave some Functions new and some old: Claude reads the log, and the
same command is run again (with your yes) until E4's script says all 28 are current. Do not go on to E5 before that.

**E4. Read the Functions back (read only).**

```bash
cd ~/Documents/Skipti && scripts/cloud-audit.sh
```

*Expect:* "deployed Functions are exactly the source's (28)", every Function runs as `skipti-functions`, every callable
answers 401 without a token, the three event and scheduled Functions answer 403; every other check ok. Then one
Function's runtime options, read on the day (expect 512Mi, 6, 10, skipti-functions):

```bash
gcloud functions describe acceptInvite --region us-central1 --project skipti-26 --format='value(serviceConfig.availableMemory,serviceConfig.maxInstanceRequestConcurrency,serviceConfig.maxInstanceCount,serviceConfig.serviceAccountEmail)'
```

**E5. The Firestore rules (Claude, after your yes).** Storage rules stay undeployed (Cloud Storage is not enabled and
neither app uses it); the Fable audit agreed. Only after E4 passed, never before the Functions. The audit checked the
new rules against what a 0.4.0 phone writes (digit `type`, the three `hlc` keys, the five `payload` keys, bytes, version
1): every record of theirs is accepted, except the clock trap below.

```bash
cd ~/Documents/Skipti/firebase && node_modules/.bin/firebase deploy --only firestore:rules --project skipti-26 --non-interactive
```

What changes: D-210 (a removed member reads its group's records up to its removal; an account reads and deletes its own
`users/{uid}/notices`; a phone never writes `removal`) and D-235 (a record only in the exact shape the phones write,
`payload.version` 1, a blob of at most 192 KiB, a stamp at most 24 hours past the server's time; `departed` and
`removals` are written by Functions only).
**The trap you accepted (decision 126):** a 0.4.x phone whose stored clock was once dragged ahead now writes records
the rules refuse, and 0.4.0 cannot set them aside: that phone stops syncing at its first refused record until it
updates. So the apps go up and testers are told the same day.

**E6. The privacy page (you, or Claude after your yes).** Decision 130's clause ("...and a picture if you add one, shown
small to your groups.") is committed in `firebase/public/privacy/index.html` and goes live with this deploy. Hosting
serves only the repo's `firebase/public` folder; the other four files (`/join`, the two `.well-known` files, `404.html`)
are unchanged and are re-uploaded as they are.

```bash
cd ~/Documents/Skipti/firebase && node_modules/.bin/firebase deploy --only hosting --project skipti-26 --non-interactive
```

*Expect:* "Deploy complete!" and a Hosting URL. Then the full audit, which now compares the served privacy page byte
for byte:

```bash
cd ~/Documents/Skipti && scripts/cloud-audit.sh
```

*Stop condition:* every line reads `ok`, including "Hosting privacy/index.html is the repo's, byte for byte" and "/join
carries its pinned script hash". If a page differs right after the deploy, wait a minute and run the audit again (the
first reads after a release can come from Hosting's cache, as on 2026-10-12); if it still differs, stop and paste it.

*Check:* E2 READY, E3 complete, E4 all ok, E5 released, E6 audit all ok; a CLOUD-SECURITY row for each, committed
`[skip ci]`.

---

## Phase F. The apps (90 minutes, plus Apple's processing)

Both apps build from the same tree: `main` as Claude names it on the day (D2). Before anything here, the Developer
Tools exemption is OFF (D1): a signed build must be scanned by macOS. Every command below is one block to paste into
Terminal; wait for each to finish before the next. If one prints an error, stop and paste it to Claude.

### iPhone (F1 to F5)

**F1. Check the tree and the versions.** Expect a clean tree, Version 0.5.0 and Build 7, each twice (Debug and Release).

```bash
cd ~/Documents/Skipti && git status --porcelain && git log --oneline -1 && grep -c 'MARKETING_VERSION = 0.5.0;' ios/Skipti.xcodeproj/project.pbxproj && grep -c 'CURRENT_PROJECT_VERSION = 7;' ios/Skipti.xcodeproj/project.pbxproj
```

*Expect:* no file listed, the commit Claude named, then `2` and `2`.

**F2. Build the Rust core for iPhone** (a few minutes).

```bash
cd ~/Documents/Skipti && make ios-core
```

*Expect:* it ends with `Done: ios/Frameworks/SkiptiCore.xcframework`.

**F3. Archive** (signed by Xcode with your team, Vardr Labs LLC 27GK2T7UCT, automatic signing; about 5 minutes). The
archive goes to your Desktop so it is easy to find.

```bash
cd ~/Documents/Skipti && xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -configuration Release -destination 'generic/platform=iOS' -archivePath ~/Desktop/Skipti-0.5.0-7.xcarchive -allowProvisioningUpdates archive | tail -5
```

*Expect:* `** ARCHIVE SUCCEEDED **`. Then read the version back from the archive:

```bash
plutil -p ~/Desktop/Skipti-0.5.0-7.xcarchive/Products/Applications/Skipti.app/Info.plist | grep -E 'CFBundle(ShortVersionString|Version)"'
```

*Expect:* `"CFBundleShortVersionString" => "0.5.0"` and `"CFBundleVersion" => "7"`. If the archive fails on signing
("No Account for Team", "requires a provisioning profile"), open Xcode once (Settings → Accounts: your Apple ID is
listed under Vardr Labs LLC) and paste the command again.

**F4. Upload to App Store Connect.** Two ways; use the first, the second if it refuses.

*Terminal:* write the export settings once, then upload (it uses the Apple ID Xcode is signed in with):

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
xcodebuild -exportArchive -archivePath ~/Desktop/Skipti-0.5.0-7.xcarchive -exportOptionsPlist /tmp/SkiptiExport.plist -exportPath /tmp/SkiptiExport -allowProvisioningUpdates | tail -5
```

*Expect:* `Upload succeeded` (or `** EXPORT SUCCEEDED **`). A warning that symbols for Firebase's prebuilt libraries
could not be uploaded is expected.

*Xcode instead:* open the archive in the Organizer, then **Distribute App** → **App Store Connect** → **Upload** →
accept the defaults → **Upload**.

```bash
open ~/Desktop/Skipti-0.5.0-7.xcarchive
```

**F5. TestFlight (you, in the browser).** App Store Connect → SKIPTI → **TestFlight**. Build 7 appears after 10 to 30
minutes, marked **Missing Compliance** (the app does not declare it in Info.plist). Answer as with 0.4.0: it uses only
standard encryption (SQLCipher on the phone, HTTPS on the wire), exempt. Then Build 7 → **Test Details** → **What to
Test**, paste (Variant B; Variant A is in `.drafts/release-050/notes.md` if you prefer prose):

```
0.5.0
- A new look: Aurora, Norse or Vardr Labs under You > Appearance. The icon follows your theme.
- New installs open in Aurora dark.
- Less motion keeps the art and animations still.
- A changed amount now reaches everyone in a group; edits that went missing arrive after you update.
- If a change cannot sync, its group says so.
- Your place in a group is yours alone.
- A reinstalled iPhone starts signed out.
Update every phone in a group today.
```

Internal testing adds Build 7 to your group by itself (if not: **Internal Testing** → your group → **Builds → +**).
Do not message testers yet.

*Check:* TestFlight on your iPhone shows SKIPTI 0.5.0 (7); install it.

### Android (F6 to F9)

**F6. Check the signing file and the version.** Expect `keystore ok` and `versionCode = 0 * 1_000_000 + 5 * 1_000 + 0`
with `versionName = "0.5.0"`.

```bash
cd ~/Documents/Skipti && test -f android/keystore.properties && echo 'keystore ok' && grep -nE 'version(Code|Name) =' android/app/build.gradle.kts
```

**F7. Build the Rust core for Android, then the signed bundle** (about 5 minutes together).

```bash
cd ~/Documents/Skipti && make android-core
```

```bash
cd ~/Documents/Skipti/android && ./gradlew :app:bundlePlayRelease
```

*Expect:* `BUILD SUCCESSFUL`. A warning "Unable to strip ... libskipti_core.so" and Kotlin `w:` lines are expected. If
it fails with "Dependency verification failed", stop and paste it: a checksum is missing from
`android/gradle/verification-metadata.xml` (D-238 added the release lint tool's on 2026-10-19), and Claude adds it in
a unit. Then check the bundle is signed with your upload key:

```bash
keytool -printcert -jarfile ~/Documents/Skipti/android/app/build/outputs/bundle/playRelease/app-play-release.aab | grep SHA256
```

*Expect:* `41:84:18:7C:…` (your upload key). Anything else: stop.

**F8. Upload (you, in the browser).** Play Console → SKIPTI → **Test and release → Testing → Internal testing** →
**Create new release** → upload `android/app/build/outputs/bundle/playRelease/app-play-release.aab` (Finder opens the
folder with the next block). Play shows **0.5.0 (5000)**. Expected warnings: no deobfuscation file, no native debug
symbols, and a few devices filtered because SKIPTI now needs a portrait screen (decision 88).

```bash
open ~/Documents/Skipti/android/app/build/outputs/bundle/playRelease/
```

**F9. Play release notes.** Paste between `<en-US>` and `</en-US>` (Variant B; A is in the notes file), then **Next →
Save and publish**:

```
0.5.0
- A new look: Aurora, Norse or Vardr Labs under You > Appearance. The icon follows your theme; your home-screen icon may need placing again once.
- New installs open in Aurora dark.
- Less motion keeps the art and animations still.
- A changed amount now reaches everyone in a group; edits that went missing arrive after you update.
- If a change cannot sync, its group says so.
- Your place in a group is yours alone.
- SKIPTI stays upright on phones.
Update every phone in a group today.
```

*Check:* "Available to internal testers"; your Android phone updates from the Play Store.

**If Apple or Play refuses the upload:** iOS can take a second upload as Build 8 (Claude bumps it); Android cannot
reuse 5000, so it becomes 0.5.1 / 5001 (see The versions). Tell Claude before bumping anything.

### Phase F for 0.5.1 (the next release; same steps, new numbers)

**Before the 0.5.1 apps, a Hosting deploy (D-244, decision 158):** 0.5.1 syncs your app settings to the cloud, and the
privacy page saying so ("Your app settings: ...", Version 2, 20 October 2026) is only in the repo. Deploy Hosting with
your yes on the day (Claude prints the command and writes the CLOUD-SECURITY section 2 row), check the live page reads
Version 2, then build the apps.

0.5.1 follows F1 to F9 above with three changes: Version **0.5.1** and Build **8** in F1, the archive named
**Skipti-0.5.1-8** in F3 and F4, and Play showing **0.5.1 (5001)** in F8. The release notes are written at 0.5.1's
release prep. The Developer Tools exemption is OFF first (D1), as for 0.5.0. The changed commands, ready to paste:

```bash
cd ~/Documents/Skipti && git status --porcelain && git log --oneline -1 && grep -c 'MARKETING_VERSION = 0.5.1;' ios/Skipti.xcodeproj/project.pbxproj && grep -c 'CURRENT_PROJECT_VERSION = 8;' ios/Skipti.xcodeproj/project.pbxproj
```

*Expect:* no file listed, the commit Claude named, then `2` and `2`.

```bash
cd ~/Documents/Skipti && xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -configuration Release -destination 'generic/platform=iOS' -archivePath ~/Desktop/Skipti-0.5.1-8.xcarchive -allowProvisioningUpdates archive | tail -5
```

```bash
plutil -p ~/Desktop/Skipti-0.5.1-8.xcarchive/Products/Applications/Skipti.app/Info.plist | grep -E 'CFBundle(ShortVersionString|Version)"'
```

*Expect:* `"0.5.1"` and `"8"`.

```bash
xcodebuild -exportArchive -archivePath ~/Desktop/Skipti-0.5.1-8.xcarchive -exportOptionsPlist /tmp/SkiptiExport.plist -exportPath /tmp/SkiptiExport-051 -allowProvisioningUpdates | tail -5
```

(`/tmp/SkiptiExport.plist` is F4's file; write it again with F4's first block if the Mac has restarted since.)

```bash
cd ~/Documents/Skipti && test -f android/keystore.properties && echo 'keystore ok' && grep -nE 'version(Code|Name) =' android/app/build.gradle.kts
```

*Expect:* `keystore ok`, `versionCode = 0 * 1_000_000 + 5 * 1_000 + 1` and `versionName = "0.5.1"`. F2, F7's
builds, the keytool check and F8's Finder line are unchanged.

---

## Phase G. Your checks on real phones (2 to 3 hours; the most important phase)

Rules for every check: one step at a time; screenshot anything odd with the phone and theme named. **Stop at once** and
tell Claude if any check shows a wrong amount, a name on someone else's money, "Unknown member", a crash, the app
asking to erase anything, or the app opening without its lock after the timeout. Never run a debug build against
production on a signed-in phone (incident 26). Note each phone's model, OS version and screen refresh rate first.

**The go set, before you tell testers (about 45 minutes): G1, G2, G6 and G9.** The rest can follow after Phase H.

**G1. Sign-in, and a reinstall starts signed out (decision 107).** Update both phones; sign-in works on each (Apple and
Google on iPhone, Google on Android). Then on the iPhone: delete SKIPTI, install it again from TestFlight, open it.
*Expect:* no account (You shows the sign-in choice, nothing syncs) until you sign in again; then your ledger comes back.
(If an earlier build had signed a reinstall back in by itself, 0.5.0 signs it out once.)
*Stop if* the reinstalled app is signed in before you tapped anything.
Then, 30 seconds: on a phone signed in to account A, sign out and sign in with account B. *Expect:* "This phone's
ledger belongs to another account" and the app signed out again; the ledger untouched. (One account per phone, D-168;
the gate decision 107 relies on.)

**G2. A money edit reaches the other phone (the audit's Critical).** Both phones on 0.5.0, signed in to two accounts
sharing a group. On phone 1 add "Hotel" 90.00 split equally; wait until phone 2 shows it. On phone 1 edit Hotel's
amount to 120.00. *Expect:* phone 2 reads 120.00 within seconds with the same balances, nothing duplicated; now edit
on phone 2 to 150.00: phone 1 follows, and an open Hotel page on phone 1 updates by itself. (With decision 135 no
0.4.0 edit is waiting to be recovered; that recovery was proven on devices in D-235's look. If a tester's 0.4.0 phone
did make a money edit before updating, it arrives at their first launch of 0.5.0: ask them to look.)
*Stop if* the phones disagree on any amount after both have synced.

**G3. An update keeps your appearance (C2's phone).** After the update it still follows the phone (switch the phone
between light and dark: SKIPTI follows); Appearance reads System. Balances unchanged.

**G4. A fresh install opens in Aurora dark (decisions 73 to 78).** On a phone in LIGHT mode, a fresh install (G1's
iPhone counts). *Expect:* the launch screen is light for a moment (decision 77), then Aurora dark; Appearance reads
Aurora, Dark. On Android 12 or later the next launch's launch screen is dark too. **Your call (77):** is the light
flash acceptable?

**G5. No network without an account (decision 109).** iPhone: Settings → Privacy & Security → **App Privacy Report** on;
a fresh install of SKIPTI, used for five minutes WITHOUT signing in; the report's Network Activity for SKIPTI lists **no
domain**. Android has no such report: Settings → Network → Data usage for SKIPTI should read 0 B, but a fetch made by
Play services on SKIPTI's behalf is counted against Play services, so this is weak; the emulator measurement (D-235) is
the proof there.
*Stop if* the iPhone report lists any domain before you sign in.

**G6. The lock (decision 106, D-232's lock fix).** You → Security → App lock on, after 1 minute.
1. iPhone: open Add expense and type a note; leave the app. The app switcher shows a plain cover, no amounts. Wait over a
   minute, return: the lock covers everything, the open sheet included; after unlocking the sheet is still there with
   your note. Open an invite link while locked: it waits until you unlock.
2. iPhone with **VoiceOver** on (never checked on a device): at the lock, swipe through everything. *Expect:* VoiceOver
   reads only the lock screen, never an amount or a row under it.
3. Android: switch Light and Dark in Appearance, change the phone's dark mode, change the phone's font size: no lock
   prompt. Leave for over a minute and return: the lock asks, and the app returns to its main screen (the iPhone keeps
   the sheet; a known difference).
*Stop if* the app opens WITHOUT asking after the timeout, or VoiceOver reads anything under the lock.

**G7. The quiet line (decision 122).** Two accounts in the "Release check" group, B with a zero balance. Put B's phone in
airplane mode. On A: remove B from the group. On B (still offline): add an expense in that group. Take B out of airplane
mode and open the group. *Expect:* the expense stays on B's phone, and under the group's header a small dim line reads
"One change could not sync. It is still on this phone."; nothing else stops syncing on B.

**G8. Notifications stop after an offline sign-out.** Notifications on, both phones in a shared group. On the iPhone in
airplane mode: sign out (quick, no hang); airplane mode off; on the other phone add an expense: the iPhone gets **no**
notification. Repeat on Android: it stops once Android is back online (a minute after).
*Stop if* a signed-out phone keeps getting a group's notifications five minutes after it is online.

**G9. Groups (D-210 to D-230, D-235).** A owns a group, B is a member.
1. A invites a new name and sends the link to B. B taps it: "You're already in this group." and nothing changes.
2. A removes someone (or B asks to leave and A agrees). An OLD link for the removed place, or a removed member's own
   old link, refuses. **Your call (64, 66):** the words on an older link.
3. One account on two phones: the Owner label and the owner's choices on both; what A adds on either phone shows as A
   on B's phone, never "Unknown member".
4. An invitation's notification names the inviter and the group as SKIPTI's cloud knows them.
5. Anyone can be removed, owner and admins included (by the owner, or by leaving).
*Stop if* any expense reads "Unknown member" or under the wrong person.

**G10. Aurora's scroll on a real Android (decision 31; decision 79's overturn condition).** Skip if C1 ran on a spare
phone. USB debugging on; battery above 50%, battery saver off, Less motion off, room temperature; a Balance with enough
to scroll. `adb` is not on your PATH, so the first block defines it for this Terminal window and makes a folder for the
files; paste it once.

```bash
adb() { ~/Library/Android/sdk/platform-tools/adb "$@"; }; mkdir -p ~/Desktop/skipti-g10 && cd ~/Desktop/skipti-g10 && adb devices && adb shell dumpsys display | grep -i -E "refresh|fps" | head -3
```

*Expect:* your phone listed as `device`, and its refresh rate (note it).

Then, for each run: set the theme on the phone (the first run: Aurora, Dark), open Balance, wait 5 seconds, set `RUN`
on the first line to the run's name, and paste the block. It scrolls ten times and saves `RUN.txt`.

```bash
RUN=aurora-dark-1
adb shell dumpsys gfxinfo com.vardrlabs.skipti reset; W=$(adb shell wm size | awk -F'[ x]' '{print $3}' | tail -1); H=$(adb shell wm size | awk -F'[ x]' '{print $4}' | tail -1); for i in $(seq 10); do adb shell input swipe $((W/2)) $((H*7/10)) $((W/2)) $((H*3/8)) 400; adb shell input swipe $((W/2)) $((H*3/8)) $((W/2)) $((H*7/10)) 400; done; adb shell dumpsys gfxinfo com.vardrlabs.skipti framestats > "$RUN.txt" && echo "saved $RUN.txt"
```

The twelve runs, alternating: `aurora-dark-1`, `aurora-light-1`, `aurora-saver-1` (Aurora Dark with battery saver ON),
`vardr-light-1` (Vardr Labs Light: no art, the floor), then the same four ending in `-2`, then `-3`. Send Claude the
folder `~/Desktop/skipti-g10`.
**Overturn 79 (the bake becomes a unit, A/B on this phone first) if ANY of:** Aurora's janky frames are more than 2
points over Vardr Labs'; Aurora's 90th GPU percentile is over one refresh interval (16.7 ms at 60 Hz, 11.1 at 90, 8.3 at
120); you see it stutter. One phone proves only that phone.

**G11. Less motion (decisions 68 to 72).** On: the art holds still, bars stop springing, a done moment and the settle
celebration show without movement, and the + shows no press film on Android. On a phone sold with 3 GB or less, a fresh
install starts with it ON; turning it off sticks. **Your call (70).**

**G12. Android stays upright (decision 88).** With auto-rotate on, SKIPTI does not turn. A tablet or an unfolded
foldable on Android 16 or later still turns, and the lock (G6) holds there too.

**G13. Screen readers.** VoiceOver and TalkBack through Appearance (themes, Light, Dark and System, Text size, Haptics
"A tap when it is done", Less motion, the App icon row read as a collapsed button that expands), Security's switches, a
Settled card with a missing amount (the "-" is spoken "none", decision 43), Home's line, a group card, a Lately item.
*Expect:* the two phones say the same words in the same order.

**G14. Appearance details (D-234).** The text-size sample reads "Lunch with Luna, $48.00" on both phones, whatever
the default currency; it grows and shrinks with the slider. App icon is one row that opens to its four choices.

**G15. Haptics and the celebration (iPhone).** Taps on tab switches, on a save, and before something that cannot be
undone; none with Haptics off. Settling a group's last debt plays the celebration once, its words on a card, never
blocking a tap.

**G16. The app icon (D-216; decisions 33 and 34).** After the update, is the home-screen icon still there (Android: if
not, place it again and tell Claude; that decides the Play note's clause)? Switch to Norse and leave Appearance: the
icon follows; on iPhone the phone's own message appears once, after you leave. On a Samsung or Xiaomi phone, if you have
one, switch once.

**G17. Optional: vault recovery (D-225).** Only on a spare phone: "Try again" first; erasing is the last choice and
asks. Stop before tapping anything that erases.

**G18. Your look at the art.** `.drafts/d226/look/pairs/`, `.drafts/d226/relook/pairs/`, `.drafts/d216/look/pairs/`:
say yes, or name what to change.

*Check:* G1 to G16 pass (G17 optional); your calls on 77, 70, 64 and 66 given. Claude records the results and closes
decision 31 (or opens the bake unit).

---

## Phase H. Tell the testers (the same day as Phase E)

Paste this into your group chat first; it matters most:

```
SKIPTI 0.5.0 is up on TestFlight and the Play Store. Please update every phone today: from today a phone on the older version can show "Unknown member" or different balances in shared groups, and in rare cases stops syncing, until it updates. What to try is in the longer list I am sending next.
```

Then the longer checklist: the block under "The longer What to Test checklist" in `.drafts/release-050/notes.md`.
Open it with:

```bash
open -e ~/Documents/Skipti/.drafts/release-050/notes.md
```

---

## Phase I. Afterwards

**I1. The cloud's read-back (Claude; DONE 2026-10-19 after E6).** `scripts/cloud-audit.sh` passed every check; both
indexes READY; every Function 512 MiB, 1 CPU, concurrency 6, 10 instances, as `skipti-functions`; the live rules equal
the repo's byte for byte; CLOUD-SECURITY section 2 has a row for E1, E3, E5 and E6. To run it yourself any day:

```bash
cd ~/Documents/Skipti && scripts/cloud-audit.sh
```

```bash
gcloud firestore indexes composite list --project skipti-26
```

```bash
gcloud functions list --project skipti-26 --format='table(name,state,serviceConfig.availableMemory,serviceConfig.maxInstanceRequestConcurrency,serviceConfig.maxInstanceCount,serviceConfig.serviceAccountEmail)'
```

*Expect:* `CLOUD AUDIT PASSED`; two indexes READY; 28 Functions ACTIVE at 512Mi, 6, 10, `skipti-functions`.

**I2. The purge's next run** (it deletes accounts past their week; read only):

```bash
gcloud scheduler jobs describe firebase-schedule-purgeDeletedAccounts-us-central1 --location us-central1 --project skipti-26 --format='value(state,lastAttemptTime,status)'
```

*Expect:* `ENABLED`, a time in the last 24 hours, and an empty status (success).

**I3. The Developer Tools exemption** may go back on for Claude's gates ONLY while SKIPTI is still in internal
testing; never for an external beta, a store release or a signed build.

**I4. Before a public beta** (not this release): the Functions' capacity (60 calls at once; 512 MiB doubles the
GB-seconds a call costs), App Store privacy labels, App Check, and CLOUD-SECURITY 7.1's "public" rows.

---

## Phase J. Before the repo goes public (not this release; your call when)

1. **The whole-repo dash sweep:** `python3 scripts/check-em-dashes.py --scope whole-repo` until it passes. History files
   (BUILD-LOG, WORKLOG, ADRs, plans, specs) are swept or left out of the public repo: your choice.
2. **`docs/CLOUD-SECURITY.md` never goes public, its history included (decision 124).** It maps every control and
   every open gap of the live cloud. Its history also still holds the sign-in address D-235 removed from it and from
   WORKLOG. Recommended: keep this private repo as it is and publish a NEW public repo made from a filtered copy, so
   no clone of the private one breaks and no force-push is needed. Claude prepares the copy with git's own tools (no
   new tool) and you read it before it is pushed anywhere.
3. **The other public-sweep items (D-235's L5, decision 42):** the keystore path and alias in DEPLOY-ORACLE, the
   Oracle-era leftovers, private artifact links, absolute paths in plans and specs, ADR-0029's stale licence allowance;
   `.drafts/` and `.superpowers/` stay out (today they are excluded locally only).
4. **The Developer Tools exemption OFF for good.**

---

## If something goes wrong

| What you see | What it usually means | What to do |
|---|---|---|
| A Function fails with FAILED_PRECONDITION in the logs, or invites fail for everyone | An index is not READY yet | Wait for E2's READY; nothing to redeploy |
| E3 asks to delete a Function | The source and production disagree | Answer no (non-interactive refuses anyway); tell Claude |
| E3: "concurrency" refused for lack of CPU | Not expected (512 MiB gives one CPU on gen 2; checked against the pinned CLI) | Stop; Claude changes the options in a unit, gated, and you say yes again |
| A tester's phone on 0.4.0 stopped syncing after E5 | Its clock was once dragged ahead (decision 126's trap) | Update it to 0.5.0 |
| "One change could not sync" on a group | A record the cloud refused stays on that phone | Expected for G7; elsewhere, screenshot and tell Claude |
| A reinstalled iPhone is signed out | Decision 107, by design | Sign in again |
| After the update an edit made on 0.4.0 went back | A note over 1,000 characters, or a negative Adjustment share, is no longer accepted (decision 103) | Edit it again within the new limits; tell Claude |
| Invite links open the browser | Android has not re-checked the links | Reinstall once, or paste the link in Groups → Join |
| TestFlight build stuck in Processing | Apple is slow | Wait up to an hour |
| Play release blocked | A form in App content | The Dashboard names it |
