# Releasing 0.4.0 (the Firebase release): step by step

Version 2 of the release runbook, written 2026-10-12 for the switch from the Oracle relay to Google Firebase
(ADR-0040). `docs/RELEASE-INTERNAL-TESTING.md` stays the reference for one-time setup (§1.1, §2.1) and for the
history of earlier releases; this file is the order to follow for 0.4.0, start to finish.

**What changed since 0.3.x, for you.** There is no relay to deploy. The cloud is the Firebase project `skipti-26`:
its rules, Functions, Hosting and alert are already live (Claude deployed and checked them, CLOUD-SECURITY §2). Your
work is: one budget look, the signing fingerprints, building and uploading both apps, Play's forms, testing on real
phones, then moving the domain off Oracle and shutting Oracle down.

**What you need:** the Mac with Xcode; your Android upload keystore and `android/keystore.properties`; two phones
(an iPhone and an Android phone are ideal), and two Google or Apple accounts for the two-account test; about three
hours in total, split across Phases A to F, plus waiting for Apple and Google to process the builds. Phase G (the
domain) can be another day.

**The versions (already set in the tree, D-202):** iOS **Version 0.4.0, Build 6**; Android **versionName 0.4.0,
versionCode 4000**.

Each phase ends with a check. If a check fails, stop and tell Claude what you saw.

---

## Phase A. Before anything: two looks (10 minutes)

**A1. The budget alert.** Google Cloud console → **Billing** → **Budgets & alerts**. There should be a budget for
the billing account that pays for `skipti-26`, emailing you. If there is none: **Create budget** → scope: project
`skipti-26` → amount: the monthly figure you are comfortable with (the free tier covers testing; a small amount such
as 5 to 10 USD catches surprises) → thresholds 50%, 90%, 100% → email to you → **Finish**.
*Check:* the budget is listed.

**A2. Play's signing certificates (for invite links and the README).** Play Console → SKIPTI → **Protected with
Play** → **Play Store distribution** → **Go to Play app signing** (the menu that used to be App integrity). Write down
every **SHA-256** shown under **App signing key certificate**: the classical key, the post-quantum key if shown, and
the **previous app signing key** if listed. Also the **Upload key certificate** SHA-256.
Send them to Claude in chat (they are public by design; they are served to anyone at `/.well-known/assetlinks.json`).
Claude checks them against `firebase/public/.well-known/assetlinks.json` and the README, which today name
`ED:82:04:58…` as Play's key and `41:84:18:7C…` as the upload key, and updates both if Play now signs with another
key.
*Why:* if `assetlinks.json` lacks the key Play signs with, Android opens invite links in the browser instead of the
app. (The file Oracle serves today is broken: its body is "…", not JSON. The domain move in Phase G replaces it.)
*Check:* Claude confirms the files match Play.

---

## Phase B. Prepare the Mac (10 minutes)

**B1. Turn the Developer Tools exemption OFF** (CLAUDE.md §13: a signed build must be scanned by macOS).
System Settings → **Privacy & Security** → **Developer Tools** → switch off **Claude**, **Terminal** and **Xcode**.
If you once showed that pane with a Terminal command, hide it again:

```bash
sudo spctl developer-mode disable-terminal
```

Quit and reopen Claude, Terminal and Xcode. (Claude's gates now take 30 to 40 minutes; that is expected.)

**B2. Start from the exact tree Claude named.**

```bash
cd ~/Documents/Skipti && git pull --ff-only && git status --porcelain && git log --oneline -1
```

The status line prints nothing, and the last line is the newest commit on `main` (Claude names it in chat).
*Check:* clean tree, the commit you expect.

---

## Phase C. iPhone: build and upload to TestFlight (30 minutes, plus processing)

**C1. Build the Rust core for iPhone.**

```bash
cd ~/Documents/Skipti && make ios-core
```

It ends with `Done: ios/Frameworks/SkiptiCore.xcframework`.

**C2. Open the project.** Open `ios/Skipti.xcodeproj` in Xcode. Scheme **Skipti**; destination **Any iOS Device
(arm64)**.

**C3. Check signing and versions.** Target **Skipti**:
- **General**: Version **0.4.0**, Build **6**.
- **Signing & Capabilities**: Team **Vardr Labs LLC (27GK2T7UCT)**, "Automatically manage signing" on. The list
  should show **Sign in with Apple**, **Associated Domains** (`applinks:skipti.vardrlabs.com`) and **Push
  Notifications**. If Xcode shows a red error for any of them, stop and tell Claude.

**C4. Archive and upload.** **Product → Archive**. In the Organizer: the new archive (0.4.0, 6) → **Distribute
App** → **App Store Connect** → **Upload** → accept the defaults → **Upload**.
(Xcode switches push to the production environment for the upload by itself.)

**C5. Wait for processing.** App Store Connect → SKIPTI → **TestFlight**. Build 6 appears after 10 to 30 minutes,
marked **Missing Compliance**. Answer it:
- SKIPTI's own encryption is now only the standard kind: the ledger on the phone is encrypted with SQLCipher
  (AES-256), and everything sent travels over HTTPS. The relay-era encryption (AES-GCM sealing, X25519) was removed
  in D-200. Answer as you did before ("uses standard encryption algorithms" / exempt), unless your counsel says
  otherwise.

**C6. Test details.** Build 6 → **Test Details** → **What to Test**, paste:

```
0.4.0: SKIPTI's cloud is now Google Firebase.
- Coming from 0.3.x: sign in again (Settings > Sign in). Your expenses stay on the phone and upload; invite people to shared groups again.
- Your expenses sync between your own phones while the app is open.
- Invite people by QR, link, @username, email or your contacts; they join on their own account.
- Notifications when someone in a group adds, changes or deletes an expense; Recent activity on each group's page.
- Settings > Your phones: see your phones and sign any of them out.
- Settings > Delete account, with a week to change your mind.
- Gone: the Recovery Kit, safety phrases and phone approvals.
Please try two phones and two accounts, and tell me anything confusing or broken.
```

**C7. Testers.** **Internal Testing** → your group → Build 6 is added automatically for internal groups (if not:
**Builds → +**). Testers get a TestFlight notification and tap **Update**.
*Check:* on your own iPhone, TestFlight shows SKIPTI 0.4.0 (6); install it.

---

## Phase D. Android: Play's forms first, then build and upload (45 minutes)

0.4.0 adds the contacts permission, so Play needs three answers before it accepts the release.

**D1. Privacy policy.** Play Console → SKIPTI → **Policy and programs** (or **App content**) → **Privacy policy** →
`https://skipti-26.web.app/privacy` → **Save**. (After Phase G, change it to `https://skipti.vardrlabs.com/privacy`.)

**D2. Data safety.** **App content → Data safety → Manage / Start**. Answer as the code behaves:
- Does your app collect or share any of the required user data types? **Yes**.
- Is all of the user data collected by your app encrypted in transit? **Yes**.
- Do you provide a way for users to request that their data is deleted? **Yes**.
- Data types, each **collected**, **not shared**, **optional** (only with an account), processed for **App
  functionality** (and **Account management** for name, email and user IDs). "Processed ephemerally?" is **No**
  for every type except Contacts:
  - Personal info → **Name** (the name you show others), **Email address** (from the sign-in provider) and
    **User IDs** (the account id). Ephemeral: No.
  - Financial info → **Other financial info** (the expenses you record). Ephemeral: No.
  - Photos and videos → **Photos** (a group's picture, if someone adds one; D-187). Ephemeral: No.
  - Contacts → **Contacts**. Ephemeral: **Yes** (only email fingerprints leave the phone; the Function compares them
    and keeps and logs none).
  - Device or other IDs → **Device or other IDs** (the address for notifications). Ephemeral: No.
  - Nothing else: no location, no app activity, no app info and performance, no messages, no files, no audio.
- **Save**, then **Submit** at the end.

**D3. Account creation and deletion** (in the Data safety flow):
- "Which methods of account creation does your app support?": tick **OAuth** only (Sign in with Apple or Google;
  there are no passwords, and a username is only for being found).
- **Delete account URL:** `https://skipti.vardrlabs.com/privacy#delete` (the "Deleting your account" section: the steps
  in the app and by email to skipti@vardrlabs.com, what is deleted, what is kept, the 7 days).
- "Do you provide a way to delete some or all data without deleting the account?" (optional): **No**. Synced records
  are kept as history in the cloud; deleting an expense in the app hides it but does not erase it there.

**D4. The keystore file** (as for earlier releases; skip if `android/keystore.properties` is still there):

```bash
cd ~/Documents/Skipti && git check-ignore -v android/keystore.properties
```

It must print `.gitignore:…:keystore.properties`. If the file is missing, create it as in
RELEASE-INTERNAL-TESTING §2.2 step 2 (four lines; never commit it; never paste the passwords to Claude).

**D5. Build the Rust core for Android, then the signed bundle.**

```bash
cd ~/Documents/Skipti && make android-core
```

```bash
cd ~/Documents/Skipti/android && ./gradlew :app:bundlePlayRelease
```

It ends with `BUILD SUCCESSFUL`; the file is `android/app/build/outputs/bundle/playRelease/app-play-release.aab`.
(If you find `app-play-release-unsigned.aab` instead, the build did not find `keystore.properties`: check D4.)

**D6. Check the signature.**

```bash
keytool -printcert -jarfile ~/Documents/Skipti/android/app/build/outputs/bundle/playRelease/app-play-release.aab | grep SHA256
```

It must print your **upload key**'s SHA-256 (`41:84:18:7C:…:3A:0E`, the same as in A2).

**D7. Upload.** Play Console → SKIPTI → **Test and release → Testing → Internal testing** → **Create new release** →
**App bundles → Upload** → `app-play-release.aab`. Play shows **0.4.0 (4000)**. The warnings about a deobfuscation
file and native debug symbols are expected and do not block internal testing.

**D8. Release notes** (paste as is):

```
<en-US>
0.4.0: SKIPTI's cloud is now Google Firebase. From 0.3.x: sign in again; your expenses stay and upload, and shared groups need inviting again. New: sync between your phones; invite by QR, link, @username, email or contacts; notifications and Recent activity; Settings > Your phones; Delete account (a week to change your mind). Gone: Recovery Kit, safety phrases, phone approvals. Tell me anything confusing or broken.
</en-US>
```

**D9. Next → Save and publish.** If Play shows **errors**, it names the unfinished form; open the **Dashboard**, finish
it, and come back. *Check:* the release is **Available to internal testers**; on your Android phone, Play Store →
SKIPTI → **Update** (or install).

---

## Phase E. Test on real phones (45 minutes; the most important phase)

Do these in order; each one depends on the one before. Note anything odd, with a screenshot.

**E1. Sign-in (never tried on production before this release).**
- iPhone: Settings → **Sign in** → **Sign in with Apple** → "You're signed in". Sign out, then **Sign in with
  Google** → "You're signed in".
- Android: Settings → **Sign in** → **Sign in with Google** → "You're signed in".
- If Google sign-in fails on Android with a message about the app's signature: Settings → **About SKIPTI → App
  signature** shows "Signing this install"; send Claude that SHA-1. Every SHA-1 Play signs with needs an Android
  OAuth client in Google Cloud (RELEASE-INTERNAL-TESTING §5.3 steps 2 and 3).

**E2. Two phones, one account.** Sign both phones in with the SAME account. Add an expense on one; it appears on the
other within seconds while both are open.

**E3. Your phones.** Settings → **Your phones** shows both. On one phone, **Sign it out** on the other: the other
shows "Signed out", keeps its ledger, and its Settings reads "No account". Sign it back in.

**E4. Two accounts.** Sign the second phone into a DIFFERENT account. On phone 1: a group → **Invite** → show the QR
(or send the link). Phone 2 scans or opens it → **Join**. Each adds an expense; each sees the other's.

**E5. Notifications.** On both phones, Settings → Notifications → **Turn on notifications** → allow. Phone 2 adds an
expense in the shared group with phone 1's app closed: phone 1 gets "… added …" within a minute; tapping it opens the
expense. Do it the other way round too. (The iPhone build is from TestFlight, so this proves production push.)

**E6. Find people.** Phone 1: Settings → **Choose a username**. Phone 2: a group → Invite → **Find … on SKIPTI** →
type phone 1's `@username` → **Invite**. Phone 1 gets a notification and the invitation in Groups.

**E7. Contacts (optional).** **Find friends in contacts**: the disclosure shows first, then the system prompt.

**E8. Deleting an account** (use a spare test account, not your own): Settings → **Delete account** → **Delete my
account** → one notice with a date a week ahead; the phone is signed out. Sign in again → Settings shows "Your
account will be deleted on …" → **Keep my account** → the notice goes.

**E9. Coming from 0.3.x** (only if a tester still has 0.3.1): update in place, open the app, sign in. Their expenses
are still there; shared groups need inviting again.

*Check:* E1 to E6 all pass. Tell Claude the results either way; E9 is the one Claude could not prove beforehand.

---

## Phase F. Tell the testers (10 minutes)

Message your testers: update from TestFlight or the Play Store; sign in again (Settings → Sign in); invite the
people in shared groups again; turn notifications on to hear about group expenses; send anything odd with a
screenshot.

---

## Phase G. Move the domain to Firebase and shut Oracle down (an hour; any day after E passes)

Until this phase, installed 0.3.x builds still talk to Oracle; after it, they cannot. Do it once your testers have
0.4.0. The full reasoning is DEPLOY-ORACLE.md §11; these are the same steps, in order. Claude gives you the Oracle
instance's IP address in chat when you start (it is kept out of this public repo).

**G1. Add the custom domain in Firebase.** Firebase console → project **skipti-26** → **Hosting** → **Add custom
domain** → `skipti.vardrlabs.com` → **Continue**. Firebase shows the DNS records to add: a **TXT** record (proves you
own the domain) and one or more **A** records. Leave that page open.

**G2. Change DNS in Cloudflare.** Cloudflare → `vardrlabs.com` → **DNS → Records**:
1. **Add** the TXT record exactly as Firebase shows it.
2. **Edit** the existing `skipti` **A** record (today: the Oracle IP) and **replace** its value with Firebase's A
   address. If Firebase gave two A addresses, add the second as another `skipti` A record. Remove any `skipti` record
   still pointing at Oracle.
3. Keep **Proxy status: DNS only** (grey cloud) on every `skipti` record; Firebase must reach the domain directly to
   issue its certificate.
4. If the zone has **CAA** records, add one allowing `pki.goog`.

Back in Firebase, wait until the domain shows **Connected** (minutes to a few hours; the certificate is part of it).

**G3. Check the domain answers from Firebase** (on the Mac):

```bash
curl -sI https://skipti.vardrlabs.com/join | grep -iE '^HTTP|content-security'
```

```bash
curl -s https://skipti.vardrlabs.com/.well-known/assetlinks.json
```

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://skipti.vardrlabs.com/version
```

Expect `HTTP/2 200` with `script-src 'sha256-EFFNKyFe…'`; JSON (not "…") naming `com.vardrlabs.skipti`; and **404**
for `/version` (the relay no longer answers). Then ask Claude to run `SKIPTI_SITE=https://skipti.vardrlabs.com scripts/cloud-audit.sh`.
On an Android phone, an invite link from Messages should now open SKIPTI directly (Android re-checks the links within
about a day; reinstalling the app makes it check at once).

**G4. A last backup on Oracle, then stop it.** In Terminal (Claude gives the IP):

```bash
ssh-add ~/.ssh/skipti_oracle
```

```bash
ssh ubuntu@<instance IP>
```

then on the instance:

```bash
sudo systemctl start skipti-backup.service && sudo ls -l /var/backups/skipti | tail -3
```

```bash
sudo systemctl stop skipti-relay caddy && sudo systemctl disable skipti-relay caddy skipti-backup.timer
```

```bash
exit
```

**G5. Copy everything off, and prove the copy opens** (on the Mac):

```bash
mkdir -p ~/SkiptiOracleFinal && chmod 700 ~/SkiptiOracleFinal
```

```bash
ssh ubuntu@<instance IP> 'sudo tar -C /var -czf - backups/skipti lib/skipti' > ~/SkiptiOracleFinal/oracle-final-$(date -u +%Y%m%d).tar.gz
```

```bash
tar -tzf ~/SkiptiOracleFinal/oracle-final-*.tar.gz | head
```

```bash
shasum -a 256 ~/SkiptiOracleFinal/oracle-final-*.tar.gz
```

The list shows `backups/skipti/…` and `lib/skipti/…`. Keep the archive on the Mac's encrypted disk only; decide how
long, and tell Claude the date you delete it (it holds testers' old accounts and sealed records).

**G6. Terminate the instance.** Oracle Cloud console → **Compute → Instances** → the SKIPTI instance → **Terminate** →
tick **Permanently delete the attached boot volume** → confirm. Then **Storage → Block Storage → Boot Volume
Backups**: delete the instance's backups. Irreversible: only after G5's archive listed its files.

**G7. Point the stores at the domain.** Play Console → Privacy policy and the account deletion URL →
`https://skipti.vardrlabs.com/privacy`. Tell Claude, who updates the docs, the README's links and the audit script.

---

## Phase H. Afterwards

- **The Developer Tools exemption** may go back on for Claude's gates (Claude, Terminal and Xcode), while SKIPTI is
  in internal testing only, never for an external beta or a store release.
- **The account purge** runs once a day. If it fails, you get an email ("SKIPTI: the account purge failed (G-27)").
  Claude reads its first run next session.
- **Before any public release:** CLOUD-SECURITY §7.1 lists what is still open ("public" rows), App Store privacy
  labels, and the API key restrictions (§8 step 9).

## If something goes wrong

| What you see | What it usually means | What to do |
|---|---|---|
| Android: Google sign-in refused after install from Play | Play signs with a key whose SHA-1 has no Android OAuth client | Settings → About → App signature → send Claude the SHA-1; RELEASE-INTERNAL-TESTING §5.3 |
| iPhone: no notifications on the TestFlight build | Notifications off for SKIPTI, or the APNs key | iPhone Settings → Notifications → SKIPTI; then tell Claude (Firebase holds the key in both slots) |
| Invite links open the browser, not the app | Phase G not done yet, or Android has not re-checked | Paste the link into Groups → Join a group meanwhile; after G, reinstall once |
| "Couldn't reach SKIPTI's cloud" everywhere | No network, or a Function outage | Try again; tell Claude, who reads the Functions' logs |
| TestFlight build stuck in "Processing" | Apple is slow | Wait up to an hour; nothing to do |
| Validate or Upload: "Upload Symbols Failed … did not include a dSYM for FirebaseFirestoreInternal / absl / grpc / grpcpp / openssl_grpc" | Firebase ships these as prebuilt binaries without debug symbols | A warning, not an error: continue the upload. Only crash reports inside Firestore's own code lose function names |
| Play release blocked with an error | A form in App content is unfinished | Dashboard lists it; D1 to D3 |
| An email "SKIPTI: the account purge failed" | The daily deletion job hit an error | Forward it to Claude |
