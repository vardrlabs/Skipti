# Internal testing: TestFlight and Google Play

The founder's runbook for the first internal-testing builds of SKIPTI 0.1.0.
Every step here is yours: Claude does not sign builds, hold the keystore or its
passwords, or act in App Store Connect or Play Console (CLAUDE.md §2, §11).
Written 2026-09-25 against the tree at D-169. Where a console's wording differs
from what is written here, the console is right; follow its meaning.

**What you are shipping:** iOS `com.vardrlabs.skipti` (team `27GK2T7UCT`) and
Android `com.vardrlabs.skipti` (the `play` flavour), version **0.1.0**, iOS build
1, Android versionCode 1000. Both apps talk to the production relay,
`https://skipti.vardrlabs.com`, by default. The relay is already deployed and
current: nothing from D-160 to D-169 changed it, so **no deploy is needed.**

---

> **For 0.4.0 and the Firebase switch, follow [RELEASE-0.4.0.md](RELEASE-0.4.0.md)** (version 2 of this runbook, step by
> step). This file stays the reference for one-time setup and earlier releases.

## 0. Before any signed build

1. **Turn the Developer Tools exemption off** (CLAUDE.md §13). It was allowed
   for internal development only, and a signed build is where it ends:
   System Settings → Privacy & Security → Developer Tools → switch off
   **Claude**, **Terminal** and **Xcode**. If the pane was shown with
   `sudo spctl developer-mode enable-terminal`, hide it again:

   ```bash
   sudo spctl developer-mode disable-terminal
   ```

   Quit and reopen the three apps. Gates take 30 to 40 minutes again from here;
   that is expected. Tell Claude when it is off, so the WORKLOG item closes.
2. **Start from a clean tree on `main`:**

   ```bash
   cd ~/Documents/Skipti && git status --porcelain && git log --oneline -1
   ```

   The first command should print nothing; the second should show the commit
   you mean to ship.

---

## 1. iOS → TestFlight (internal testers)

### 1.1 One-time: the App Store Connect record

1. App Store Connect → **Apps** → **+** → **New App**.
2. Platform **iOS**; Name: the name people will see (it must be unused on the
   App Store; "SKIPTI" may need a qualifier); Primary language; **Bundle ID:
   `com.vardrlabs.skipti`** from the list; SKU: any private string, for
   example `skipti-ios`; User access: Full.
3. In the Apple Developer site → Certificates, IDs & Profiles → Identifiers →
   `com.vardrlabs.skipti`, confirm **Sign in with Apple** and **Associated
   Domains** are ticked. Xcode's automatic signing makes the profiles.

### 1.2 Build and upload

1. Build the Rust core for iPhone and the simulator:

   ```bash
   cd ~/Documents/Skipti && make ios-core
   ```

2. Open `ios/Skipti.xcodeproj` in Xcode. Choose the **Skipti** scheme and the
   destination **Any iOS Device (arm64)**.
3. Target **Skipti** → Signing & Capabilities: Team **Vardr Labs LLC
   (27GK2T7UCT)**, "Automatically manage signing" on. General: Version
   **0.1.0**, Build **1**. Every later upload needs a **higher Build** (2, 3, …)
   for the same Version; ask Claude to bump it, or change it here.
4. **Product → Archive.** When the Organizer opens: **Distribute App → App
   Store Connect → Upload**, and accept the defaults.
5. App Store Connect → your app → **TestFlight**. The build appears after
   processing (often 10 to 30 minutes). It will ask the **export compliance**
   question: SKIPTI encrypts its ledger and its sync with standard
   algorithms (SQLCipher, AES-GCM, X25519). How to answer is your call, with
   counsel if you want one; the app does not pre-answer it in Info.plist.
6. **Internal Testing** → create a group → add testers. Internal testers must
   be users on your App Store Connect team (up to 100). They install the
   **TestFlight** app and accept the invitation.

### 1.3 Check on a real iPhone first

- First launch: straight into Balance.
- **Face ID**: Settings → App lock; leave the app for longer than the timeout
  and return. The first Face ID use shows iOS's permission prompt with
  "SKIPTI uses Face ID to unlock the app." (D-169, D-200; the simulator never
  shows this prompt.)
- Sign in with Apple, and Google, from Settings → Sign in: "You're signed in"
  (0.4.0: Firebase; no twelve words since D-192).
- Settings → Your phones lists this phone (D-198); a second phone signed in to
  the same account appears there and can be signed out.
- A push: another member adds an expense in a shared group; the notification
  arrives on this TestFlight build (production APNs, CLOUD-SECURITY G-16).
- An invite link opened from Messages opens the app (the `/join` app link;
  from 0.4.0 served by Firebase Hosting once DEPLOY-ORACLE §11 has moved the
  domain).

---

## 2. Android → Google Play (internal testing)

Internal testing is private: only the Google accounts you put on the testers
list can see or install the app, there is no Google review before testers get
it, and nothing appears on the public Play Store. Up to 100 testers.

### 2.1 One-time: the Play Console app

1. Play Console → **Create app** (done 2026-09-25): name, default language,
   **App**, **Free**, and the declarations.
2. The package name is fixed by the first upload and can never change: it will
   be **`com.vardrlabs.skipti`** (the `play` flavour). The `direct` flavour's
   `com.vardrlabs.skipti.direct` is never uploaded to Play.

### 2.2 Build the signed bundle (on the Mac)

The upload key is yours: the keystore that produced the upload fingerprint in
`assetlinks.json`. Its path and alias are not written in this public repo. The
build reads the passwords from a file you create and git ignores; never commit
it, and do not paste the passwords to Claude.

1. **Developer Tools exemption off** first (§0), then quit and reopen Terminal.
2. **Create `android/keystore.properties`** (the path is fixed: the build
   reads it from the `android/` folder; it is gitignored):

   ```bash
   cd ~/Documents/Skipti && nano android/keystore.properties
   ```

   with exactly these four lines, your values in place of the capitals:

   ```properties
   storeFile=/absolute/path/to/your-upload-keystore.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=YOUR_KEY_ALIAS
   keyPassword=YOUR_KEY_PASSWORD
   ```

   Save (Ctrl-O, Return, Ctrl-X), then confirm git ignores it (it must print
   `.gitignore:…:keystore.properties`):

   ```bash
   git check-ignore -v android/keystore.properties
   ```

3. **Build the Rust core for Android** (both ABIs; a few minutes):

   ```bash
   cd ~/Documents/Skipti && make android-core
   ```

   Do this every time before a release bundle: Gradle alone packages whatever
   Rust library was built last (D-166 shipped an emulator build with a stale
   one this way).
4. **Build the release bundle:**

   ```bash
   cd ~/Documents/Skipti/android && ./gradlew :app:bundlePlayRelease
   ```

   It should end with `BUILD SUCCESSFUL`. The file is
   `android/app/build/outputs/bundle/playRelease/app-play-release.aab`. If you
   find `app-play-release-unsigned.aab` instead, the build did not find the
   key file: check step 2.
5. **Check it is signed with your upload key.** The SHA-256 printed must equal
   the upload fingerprint (the one already in `assetlinks.json`):

   ```bash
   keytool -printcert -jarfile ~/Documents/Skipti/android/app/build/outputs/bundle/playRelease/app-play-release.aab | grep SHA256
   ```

6. **Check the version** Play will see: versionName 0.1.0, versionCode 1000.
   Every later upload needs a higher versionCode (0.1.1 is 1001; ask Claude to
   bump it). A versionCode, once uploaded, can never be reused, even if you
   delete that release.

### 2.3 Upload to internal testing (Play Console)

Play Console's menus move; where the words differ, follow the meaning.

1. Open the app → left menu **Test and release → Testing → Internal testing**.
2. **Testers tab first:** **Create email list** → name it (for example
   "SKIPTI internal") → add each tester's **Google account email** (the account
   signed in to Play Store on their phone, usually a Gmail address) → **Save
   changes** → tick the list → **Save**. Add yourself.
3. **Releases tab → Create new release.**
4. **App signing:** for a new app, Play asks you to use **Play App Signing**.
   Choose the default: **Google generates and protects the app signing key**.
   Your key stays the upload key; Google re-signs what testers install. (This
   is why §2.4 exists.)
5. **App bundles → Upload** → choose `app-play-release.aab`. Wait for it to
   process; it should show version code 1000, version 0.1.0.
6. **Release name:** leave the suggested one (`1000 (0.1.0)`) or write
   `0.1.0 internal 1`. **Release notes** (English):

   ```
   <en-US>
   First internal build. Everything works offline without an account. Please try: setup and the Recovery Kit, adding and splitting expenses, a group with people, settling up, import from Splitwise, app lock, and (optionally) signing in. Tell me anything confusing or broken.
   </en-US>
   ```

7. **Next.** Play reviews the release for errors and warnings.
   - **Errors** block the rollout. If it names unfinished setup (for example
     the privacy policy, app access, ads, content rating, target audience,
     data safety or financial features declarations), open the **Dashboard**:
     it lists each task. Answer them honestly; Claude can draft the data
     safety and financial-features answers from the code if you ask (SKIPTI
     is a ledger: no payments, no loans, no ads). A privacy policy URL is §4.
   - **Warnings** (for example "no deobfuscation file", because minification
     is off) do not block internal testing.
8. **Save and publish** (older wording: **Start rollout to Internal
   testing**). Internal releases are usually available within minutes.
9. **Testers tab → Copy link** ("Join on Android" / "Join on the web"). Send
   it to testers. Each tester opens it **signed in with the same Google
   account you listed**, taps **Accept invite**, then **Download it on Google
   Play**. It can take a little while after publishing before Play shows the
   app to them.

### 2.4 After the first upload: two things only you can do

Google's app signing key exists only after the first upload, and two services
must learn it, or Play-installed copies lose passkeys, invite links and Google
sign-in (your locally built copies keep working, which hides the problem).

1. **Read the app signing certificate.** Play Console → left menu **Protected
   with Play** → the **Play Store protection** card → **Play Store
   distribution** → **Go to Play app signing** (Google's help, 2026-09: "Protected
   with Play > Play Store distribution > Go to Play app signing"; the older
   menu, Test and release → App integrity, now redirects to Protected with
   Play). On that page, under **App signing key certificate**, copy both the
   **SHA-256** and the **SHA-1** certificate fingerprints. The **Upload key
   certificate** below it should match your own upload key.
2. **assetlinks.json on the relay host** (passkeys and invite links). Read
   `UPLOAD_FP` on the Mac from the keystore:

   ```bash
   keytool -list -v -keystore <keystore> -alias <alias> | grep SHA256
   ```

   Then on the host (`ssh-add` the key and `ssh` in as for a deploy), with
   both fingerprints, colons included:

   ```bash
   UPLOAD_FP="<the upload keystore's SHA-256>"
   PLAY_FP="<Play Console's app signing SHA-256>"
   printf '[{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti","sha256_cert_fingerprints":["%s","%s"]}},{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti.direct","sha256_cert_fingerprints":["%s"]}}]\n' "$UPLOAD_FP" "$PLAY_FP" "$UPLOAD_FP" \
     | sudo tee /var/www/skipti/.well-known/assetlinks.json >/dev/null
   ```

   Check it from anywhere (both packages listed, no errors):

   ```bash
   curl -sS -G 'https://digitalassetlinks.googleapis.com/v1/statements:list' --data-urlencode 'source.web.site=https://skipti.vardrlabs.com' --data-urlencode 'relation=delegate_permission/common.handle_all_urls'
   ```

   No fingerprint is written into this repo, so a rotated key cannot leave a
   runbook silently wrong (`docs/DEPLOY-ORACLE.md` §8.1).
3. **Google sign-in for Play installs.** Google Cloud Console (the project
   that holds SKIPTI's OAuth clients) → **APIs & Services → Credentials →
   Create credentials → OAuth client ID → Android**: package
   `com.vardrlabs.skipti`, **SHA-1** of the **app signing key** from step 1.
   The existing Android clients cover the upload key and the debug key only.
   It can take a few minutes to take effect.

Then tell Claude the app signing fingerprint is live, so WORKLOG closes the
item.

### 2.5 Check on a real Android phone first

Install from the Play link (not a locally built APK: only the Play copy proves
§2.4).

- First launch: straight into Balance.
- Set a screen lock on the phone; Settings → App lock; leave SKIPTI for longer
  than the timeout; the fingerprint or PIN prompt when you come back.
- Settings → Your phones lists this phone (D-198).
- A group, an expense, Settle; Settings → Currency.
- Import a Splitwise group export (Settings → Your data → Import).
- Sign in with Google (after §2.4 step 3), and an invite link opened from a
  message.

### 2.6 Later builds

1. Claude bumps the version (`versionName`, and `versionCode` +1 or more).
2. `make android-core`, then `./gradlew :app:bundlePlayRelease`, then the
   `keytool -printcert` check (§2.2 steps 3 to 5).
3. Internal testing → **Create new release** → upload → notes → **Save and
   publish**. Testers who already joined get the update through Play.

---

## 3. What testers should know

- **0.4.0 moves SKIPTI's cloud to Google Firebase** (ADR-0040). Your ledger on the phone stays as it was, encrypted.
  Coming from 0.3.x: the old sync service is retired, so sign in again (Apple or Google) and your phone uploads what
  it holds. Groups you shared in 0.3.x do not carry over as shared: invite people again from the group.
- SKIPTI works fully without an account. An account adds sync between your own phones, shared groups, invites,
  notifications and finding friends. Cloud data is protected by access rules, not end-to-end encryption
  (THREAT_MODEL.md says exactly what that means).
- **One account per phone** (D-168): signing in with a different account is refused; to switch, export, then
  Settings → Erase this phone.
- **Your phones and deleting your account** (D-198): Settings → Your phones lists them and signs any out; Settings →
  Delete account deletes the account a week after you ask, unless you sign in and tap Keep my account. The ledger on
  the phone stays.
- **Import** reads Splitwise group exports and Wallet by BudgetBakers exports. It always makes a new group, and cannot
  yet be undone as one step after it is applied (BUILD-LOG §4, D-166).
- Settings' Export card mentions "a JSON backup to restore", and neither app restores one yet (D-166 (h)).

---

## 4. Still open, and yours

- **Privacy policy and Play's forms (D-203).** The policy is hosted at `https://skipti-26.web.app/privacy`
  (`https://skipti.vardrlabs.com/privacy` once DEPLOY-ORACLE §11 has moved the domain; switch the URLs then).
  0.4.0 adds the contacts permission, so before the Android upload, in Play Console → App content:
  - **Privacy policy:** the URL above.
  - **Data safety:** the full answers, data type by data type, are in RELEASE-0.4.0.md step D2.
  - **Account deletion:** in the app (Settings → Delete account) and the web URL above (its "Deleting your
    account" section says how, including by email to skipti@vardrlabs.com).
  App Store Connect's privacy labels ask the same questions for an external release (TestFlight internal does not).

- **The export compliance answer** for App Store Connect (§1.2 step 5).
- **The Developer Tools exemption** stays off from §0 on.
- **Before 0.4.0 reaches testers:** the console checks in docs/CLOUD-SECURITY.md §8, steps 1 to 8 (including the
  alert on the account purge), and a tap on `https://skipti-26.web.app/join#test` (Copy link should copy).
- **With 0.4.0:** move the invite domain and shut Oracle down (docs/DEPLOY-ORACLE.md §11, in order), and send yourself
  a push from a TestFlight build (production APNs, CLOUD-SECURITY G-16).

---

## 5. Shipping an update to testers (0.2.0 and every build after)

TestFlight and Play internal testing both deliver updates like a normal app:
testers who already installed SKIPTI get the new build through the TestFlight
app and the Play Store. You upload a build with a **higher build number**;
nothing else about the tester groups changes.

Claude sets the version numbers in the tree before you build (0.2.0 was
iOS Build 2 and Android versionCode 2000, 0.2.1 was Build 3 and 2001; 0.3.0 was Build 4 and 3000; 0.3.1 was Build 5
and 3001; 0.4.0 was Build 6 and 4000; **now 0.5.0: iOS Version 0.5.0, Build 7; Android versionName 0.5.0, versionCode 5000**). Since D-176 the iOS Info.plist reads them from Xcode's build settings,
so what Xcode's General tab shows is what ships.

**The rule (founder, 2026-09-30; CLAUDE.md §10.3):** Android's versionCode is DERIVED from the version
(major × 1,000,000 + minor × 1,000 + patch: 0.3.1 → 3001), never matched to iOS; iOS's build number is an
upload counter that goes up by one for every upload (0.3.1 → Build 5, since 0.3.0 used 3 and 4).

### 5.0 Before you build (every time)

1. **The Developer Tools exemption** (§0 and CLAUDE.md §13): switch it
   **off** before you build a signed upload, so macOS scans what you ship.
   System Settings → Privacy & Security → Developer Tools → switch off Claude,
   Terminal and Xcode, then quit and reopen them. Switch it back on afterwards
   if you want Claude's gates fast again (it is allowed while SKIPTI is in
   internal testing, never for an external beta or store release).
2. Pull the latest tree and confirm it is the commit Claude named:

   ```bash
   cd ~/Documents/Skipti && git pull && git log --oneline -1
   ```

3. **0.3.0 needs the relay deployed first.** D-178 added `PUT
   /v1/session/device` (a second phone on your account gets your key); the
   0.3.0 apps call it, and the relay live before 0.3.0 does not have it.
   Deploy from the same commit, on the Mac (DEPLOY-ORACLE §7.1 as one
   command; `<PUBLIC_IP>` is the instance's address):

   ```bash
   ssh-add ~/.ssh/skipti_oracle
   ```

   ```bash
   cd ~/Documents/Skipti && deploy/ship-relay.sh ubuntu@<PUBLIC_IP>
   ```

   It ends by checking that `/version` answers with the commit you deployed.
   A build that changes no server code needs no deploy.

### 5.1 iOS: upload a new TestFlight build

1. Build the Rust core for iPhone:

   ```bash
   cd ~/Documents/Skipti && make ios-core
   ```

2. Open `ios/Skipti.xcodeproj` in Xcode. Scheme **Skipti**, destination
   **Any iOS Device (arm64)**.
3. Target **Skipti** → **General**: check **Version 0.3.0** and **Build 4**.
   (A build number can be uploaded only once per version. For another upload of
   a version, Build goes up by one, and so on. Claude can bump it, or you can here.)
4. **Product → Archive**. In the Organizer: select the new archive →
   **Distribute App → App Store Connect → Upload** → accept the defaults.
5. App Store Connect → **SKIPTI → TestFlight**. After processing (10 to 30
   minutes) the build shows **Missing Compliance**: answer as you did for
   0.1.0 ("standard encryption algorithms"; France as you decided).
6. **Internal Testing** → your group → the new build is added automatically
   for internal groups (if not: **Builds → +** and pick it). Testers get a
   TestFlight notification and tap **Update**.
7. Optional: **Test Details** → "What to Test" for this build, e.g. "New
   look: a new bar with a + in the middle, a Groups tab, Balance across all
   your groups, expenses grouped by group, Settle up for every group, a new
   Add sheet, categories in two steps. A second phone on your account gets
   your groups by itself. Pull down on any tab to sync."

### 5.2 Android: upload a new Play internal-testing release

1. Build the Rust core for Android, then the signed release bundle:

   ```bash
   cd ~/Documents/Skipti && make android-core
   ```

   ```bash
   cd ~/Documents/Skipti/android && ./gradlew :app:bundlePlayRelease
   ```

   It ends with `BUILD SUCCESSFUL`, and the file is
   `android/app/build/outputs/bundle/playRelease/app-play-release.aab`.
2. Check it is signed with your upload key (the SHA-256 printed must be the
   upload fingerprint, as in §2.2 step 5):

   ```bash
   keytool -printcert -jarfile ~/Documents/Skipti/android/app/build/outputs/bundle/playRelease/app-play-release.aab | grep SHA256
   ```

3. Play Console → **SKIPTI → Test and release → Testing → Internal testing**
   → **Create new release**.
4. **App bundles → Upload** → pick `app-play-release.aab`. Play shows
   **0.3.0 (3000)**. The two warnings from 0.1.0 (no deobfuscation file, no
   native debug symbols) are expected; neither blocks the release.
5. **Release notes** (en-US), e.g. "New look: a new bar, a Groups tab,
   Balance across all your groups, expenses by group, Settle up for every
   group, a new Add screen, categories in two steps. A second phone on your
   account gets your groups by itself. Pull down to sync."
6. **Next → Save and publish**. Testers get the update through the Play Store
   (automatic updates, or Play Store → Manage apps → Update).

### 5.3 Fix Google sign-in on Android (do this with 0.2.0)

Play moved SKIPTI to a new **quantum-ready** app-signing key on its own
(Protected with Play → App signing shows "Quantum-ready (beta)", a
**Classical key** and a **Post-quantum cryptography key**, and one **Previous
app signing key**, first used 26 Sep 2026). Phones that installed 0.1.0 hold
the build signed with the **previous** key, and Google checks the signing
certificate of the installed app against your OAuth clients, so it refused
every sign-in: only the two new keys were registered.

1. **Find the certificate your phone really has.** Install 0.2.0 from Play,
   open **Settings → About SKIPTI → App signature**: "Signing this install"
   shows its SHA-1 and SHA-256 (press and hold to copy). After a key change
   you may also see "Earlier key".
2. **Collect every SHA-1** from Play Console → Protected with Play → App
   signing: the **Classical key** SHA-1, the **Post-quantum** SHA-1, and the
   **Previous app signing key** (the download icon on its row gives its
   certificate; or copy it from the About screen on a phone still on 0.1.0).
3. **One Android OAuth client per SHA-1.** Google Cloud Console (the project
   whose client IDs start `403030719865-`) → **APIs & Services → Credentials →
   Create credentials → OAuth client ID → Android** → package
   `com.vardrlabs.skipti`, the SHA-1. Keep the two you made; add the one
   that is missing (the previous key's, or whatever About shows that is not
   registered). A client can hold one SHA-1, and Google refuses a SHA-1 +
   package pair already registered in any project.
4. **Test users**: Google Auth Platform → **Audience** is External, in
   Testing, with your testers' Google accounts listed. Leave it so. (Do not
   choose "Make internal": that limits sign-in to a Google Workspace domain.)
5. Wait a few minutes, force-stop SKIPTI, and try **Settings → Sign in →
   Sign in with Google**. If Google's sheet still closes, SKIPTI says so
   ("Google closed without signing you in."), and from 0.2.1 the line under
   it is Google's own reason (for example "Google's reason: [16] Account
   reauth failed."). On an older build, plug the phone in over USB (USB
   debugging on), try once more, then read the reason from **the phone**
   (`-d` picks the USB device; without it adb may read a running emulator
   instead, whose log has nothing from your phone):

   ```bash
   ~/Library/Android/sdk/platform-tools/adb devices -l
   ```

   ```bash
   ~/Library/Android/sdk/platform-tools/adb -d logcat -d -s GoogleCredential
   ```

6. **assetlinks.json** (invite links and passkeys) must list the new keys too.
   On the relay host, with every SHA-256 (the upload key, the previous app
   signing key, the Classical key and the Post-quantum key), colons included.
   `UPLOAD_FP` is the one `keytool -printcert -jarfile … app-play-release.aab`
   prints in §5.2 step 2 (your upload key, which also signs the direct
   build); it is NOT what the phone's About screen shows, which is a key Play
   signs with. Four different values:

   ```bash
   UPLOAD_FP="<upload keystore SHA-256>"; OLD_FP="<previous app signing SHA-256>"; NEW_FP="<Classical key SHA-256>"; PQC_FP="<Post-quantum key SHA-256>"
   ```

   ```bash
   printf '[{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti","sha256_cert_fingerprints":["%s","%s","%s","%s"]}},{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.vardrlabs.skipti.direct","sha256_cert_fingerprints":["%s"]}}]\n' "$UPLOAD_FP" "$OLD_FP" "$NEW_FP" "$PQC_FP" "$UPLOAD_FP" | sudo tee /var/www/skipti/.well-known/assetlinks.json >/dev/null
   ```

   Then the check from §2.4 step 2 (`statements:list`, no errors).

### 5.4 Check the update on real phones

**0.4.0 (the Firebase release; this list first).** The lines after this block describe 0.2.0 to 0.3.1: where
they mention the twelve words, the Recovery Kit or approving phones, 0.4.0 removed them (D-192).
- **Coming from 0.3.x:** the app opens signed out; Settings → Sign in (Apple or Google); "You're signed in"; your
  groups and expenses are still there and upload. Shared groups need inviting again.
- **Two phones, one account:** an expense added on one appears on the other while both are open.
- **Two accounts:** invite someone (QR or link, or Find on SKIPTI); they join; each sees the other's expenses; a
  notification arrives on the other phone (on iPhone, a TestFlight build: production APNs).
- **Settings → Your phones:** both phones; Sign it out on one signs the other out with a notice.
- **Settings → Delete account** on a test account: the date a week ahead; sign in again → Keep my account.

- **First launch** (a fresh install, or after Settings → Erase this phone):
  straight into SKIPTI, no twelve words.
- **Sign in** (Apple on iPhone; Google on Android after §5.3): after the
  provider, "One more step" and your twelve words, confirm three, then "You're
  signed in", and Settings shows how you signed in.
- **Look**: the Aurora glow behind screens, glass cards, violet buttons; dark
  mode follows the phone (Settings → Appearance → System).
- **Settings** order: You, Account, Money, Appearance, Privacy & security
  (App lock, recording, Recovery Kit), Your data, About, Danger zone.
- Phones that already had 0.1.0 keep their ledger, kit and account.
- **0.3.0's new look**: the bar reads Balance · Expenses · + · Groups · Settle
  up. Balance shows every group at once (one total per currency, never
  converted) and who owes you; a group opens its own page. Expenses come in
  a section per group with "you owe / you lent"; Settle up shows every group,
  with "Mark as paid" / "I paid …" and "… is all settled". The + opens the Add
  sheet: "How much?", the currency pill, "What was it for?", Category then
  Sub-category, the Group, and a live "You'll get back …" line.
- **Two phones, one account** (after the relay deploy in §5.0): sign in on a
  second phone with the same account; within about ten seconds of the first
  phone being open, the second has your groups, and the first says it shared
  them. Settings → Privacy & security → Maximum security (off by default)
  makes each new phone ask first, with six words to compare.
