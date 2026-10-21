# Firebase backend — implementation plan

> **For agentic workers:** executed inline by Claude in this repo (the founder asked for no subagents by default).
> Steps use checkbox (`- [ ]`) syntax. Every unit still follows CLAUDE.md §8 (WORKLOG, asserted-anchor edits,
> twin tests, sabotage, look at it running, full gate, BUILD-LOG through `scripts/buildlog_edit.py`, `[skip ci]`,
> explicit-path staging, no attribution).

**Goal:** replace the relay's end-to-end-encrypted sync with Firebase (Auth, Firestore, Cloud Functions, FCM,
Storage, Hosting) per `docs/superpowers/specs/2026-10-01-firebase-backend-design.md`, shipping as 0.4.0.

**Architecture:** the vault stays each phone's database and the screens' only data source. Core's immutable
records become Firestore documents (outbox up, snapshot listeners down, core's merge applies them). Firebase Auth
replaces the relay's sessions; Cloud Functions own membership, invites, lookups and pushes.

**Tech stack:** Firebase iOS SDK **12.19.2** (SwiftPM, published 2026-09-15), Firebase Android BoM **34.19.0**
(published 2026-09-09), firebase-tools **15.31.0** (pinned in `firebase/`), Cloud Functions in TypeScript on
Node 22, Firestore `(default)` in **nam5**, project **`skipti-26`** (GCP 403030719865).

## Global constraints

- Project `skipti-26`. iOS app `1:403030719865:ios:26a313b36c35afcdcd43a8`; Android app
  `1:403030719865:android:b8e41683ad5cacfacd43a8`. The Android SHAs live in Firebase and private memory, never in git.
- `ios/Skipti/GoogleService-Info.plist` and `android/app/google-services.json` are gitignored. Both shells
  configure Firebase **in code** (`FirebaseConfig`), so a clean clone builds without them. Values in code are
  public identifiers (API key, app id, sender id, project id, bucket), not secrets.
- No Firebase Analytics, Crashlytics, Performance, Remote Config or In-App Messaging product, ever (GUARANTEES #1).
- Every money string comes from core. Money is integer minor units.
- New dependencies: CONTRIBUTING rule 4's written justification in the unit's D-row; `scripts/licences.py`
  regenerated (both shells' Licences pages); `scripts/check-play-manifest.sh`'s allowlist updated only for
  permissions read off the merged manifest.
- Run the Firebase CLI as `firebase/node_modules/.bin/firebase … --project skipti-26 --non-interactive`. Never
  the Firebase MCP (it hung, 2026-10-01).
- Emulator ports: auth 9099, firestore 8180 (8080 is the old local relay's), functions 5001, storage 9199.
- Release: 0.4.0 on both (iOS 0.4.0 build 6; Android 0.4.0 / 4000).

## Units (D-numbers assigned at start)

Changed from the spec's order (2026-10-01): the removals come second, not eighth. About 35 call sites per shell
ask the relay for a session; moving them to one account check first, then deleting the dormant relay layer,
means no later unit builds next to dead code.

| # | Unit | Done when |
|---|---|---|
| 1 | **Firebase sign-in** (detailed below) — **DONE: D-191** (4d99a33, 4e01834) | Both apps sign in and out with Firebase Auth; every "has an account" check reads the Firebase user; the relay's sign-in paths are gone. |
| 2 | **Removals + guarantees** | Relay sync clients, passkeys, email code, Recovery Kit, safety phrase/UNVERIFIED, Maximum security + approvals, bell, background check, `direct` flavour, `server/` crate, sync-only core crypto deleted; GUARANTEES #5/#6/#7, ADR-0040, THREAT_MODEL, SPEC, README, privacy text updated. The app is local-only plus a Firebase account. |
| 3 | **Rules + emulator leg + profile** | `firebase/firestore.rules`, `firebase/storage.rules`, rules tests in the emulator as a gate leg (`npm audit` for the Functions' deps); `users/{uid}` and `users/{uid}/devices/{id}` written at sign-in. |
| 4 | **Personal sync** | Core's unsealed record export/import FFI; outbox + listener for `users/{uid}/records`; two phones of one account converge (emulator test + look). |
| 5 | **Groups** | Groups need an account; local groups upload at first sign-in; `groups/{g}` + `records`; `createInvite`/`acceptInvite` Functions; link + QR; **the two-account test** (A creates, B joins, B adds, A sees it; edit; delete). |
| 6 | **Push + Recent activity** | FCM on both; APNs key (founder); `onGroupRecord` sends "Rahul added Dinner · ₹1,200 in Goa trip"; tap opens it; Recent activity on the group page. |
| 7 | **Find people** | @username (chosen in Settings), exact email, contacts matching (`findPeople`), `inviteUser` with push, the findable switch. |
| 8 | **Your phones + deletion** | Device list, "Was this you?" push (`onDeviceCreated`), Sign it out / Sign out everywhere, `deleteAccount`. |
| 9 | **Hosting + Oracle hand-off** | `/join` + app-link files on Firebase Hosting; DNS values for the founder; Oracle backup + shutdown commands. |
| 10 | **Release 0.4.0** | Versions per §10.3, paste-ready notes, Developer Tools exemption reminder. |

Units 2–10 get their own detailed task lists in this file when they start (each depends on what the previous one
found).

**Re-cut, 2026-10-02 (D-194):** every expense the app saves belongs to a group (even a group of one), so "personal
sync" alone would sync almost nothing visible. Unit 4 (D-194) therefore syncs ALL of an account's records between its
own phones: personal-scope records to `users/{uid}/records`, and each local group to `groups/{g}` (this account its
only member) with its records under `groups/{g}/records`; listeners on both. No core change (the vault's RecordWire
records are the unit of sync). Unit 5 keeps invites (`createInvite`/`acceptInvite`, link + QR) and the two-ACCOUNT test.
Unit 2's "sync-only core crypto deleted" is still open (D-192 residue (e)).

---

## Unit 1 — Firebase sign-in

**Files**
- Create `ios/Skipti/App/FirebaseConfig.swift` — `FirebaseOptions` in code; `FirebaseConfig.configure()`.
- Create `ios/Skipti/App/Account.swift` — `Account` (observable Firebase user + method), sign-in/out, the
  one-account-per-phone check on the Firebase uid.
- Modify `ios/Skipti/Relay/AppleSignIn.swift`, `ios/Skipti/Relay/GoogleSignIn.swift` — produce Firebase
  credentials instead of relay sessions (Apple: hashed nonce; Google: the existing PKCE code flow, code exchanged
  at Google's token endpoint by the app).
- Modify `ios/Skipti/App/SignInScreen.swift` — Firebase flows; no email code; no Recovery Kit step.
- Modify every iOS "has an account" site (grep `restoreSession() != nil` / `restoreSession() == nil`) to
  `Account.shared.isSignedIn`; `AccountHubScreen` sign-out via `Account.shared.signOut()`.
- Modify `ios/Skipti.xcodeproj/project.pbxproj` — the firebase-ios-sdk package (exact 12.19.2), product
  `FirebaseAuth`, 2 new Swift files; commit `Package.resolved`.
- Create `android/app/src/main/java/com/vardrlabs/skipti/FirebaseConfig.kt`, `Account.kt` — the twins.
- Modify `android/app/build.gradle.kts` (BoM 34.19.0 + `firebase-auth`), `GoogleSignIn.kt` (Firebase's web
  client id `403030719865-p4a2s68o0rn18rn37h2v4pq110hih40o…`), `play/GoogleCredential.kt` (random nonce, no relay),
  `SignInScreen.kt`, `AccountHubScreen.kt`, the Android "has an account" sites, `AppRoot.kt` (configure at start).
- Create `firebase/firebase.json` (emulators only in this unit), `firebase/.firebaserc` (`skipti-26`).
- Tests: `ios/SkiptiTests/AccountTests.swift`, `android/app/src/test/java/com/vardrlabs/skipti/AccountTest.kt`.

**Interfaces produced (later units rely on these names)**
- Swift: `Account.shared.isSignedIn: Bool`, `Account.shared.uid: String?`, `Account.shared.method: SignInMethod?`,
  `func signIn(_ credential: AuthCredential, method: SignInMethod) async -> Account.Outcome`
  (`.signedIn | .otherAccount | .failed(String)`), `func signOut()`, `Account.ownerKey = "account/firebase-uid"`.
- Kotlin: `Account.isSignedIn(): Boolean`, `Account.uid(): String?`, `Account.method(store): SignInMethod?`,
  `suspend fun Account.signIn(context, credential: AuthCredential, method: SignInMethod): Account.Outcome`,
  `fun Account.signOut()`, `Account.OWNER_KEY = "account/firebase-uid"`.
- Proof arg `--auth-emulator-proof` on both shells: points Auth at the emulator (iOS `localhost:9099`, Android
  `10.0.2.2:9099`), signs in with an emulator-only fake Google ID token, prints uid + method, signs out.

### Task 1.1: Firebase SDKs in both builds, configured in code

- [ ] Write `.drafts/d191/apply_pbxproj.py`: asserted-anchor inserts for `XCRemoteSwiftPackageReference`
      (`https://github.com/firebase/firebase-ios-sdk`, `kind = exactVersion; version = 12.19.2;`),
      `XCSwiftPackageProductDependency` (`FirebaseAuth`), a `PBXBuildFile` with `productRef`, the Frameworks
      phase entry, the app target's `packageProductDependencies`, the project's `packageReferences`; the two Swift
      files' four entries each (CLAUDE.md §7.1, `sourceTree = SOURCE_ROOT` copied from a sibling). Assert every
      new ID unused.
- [ ] `FirebaseConfig.swift`:
```swift
import FirebaseCore
import Foundation

/// Firebase, configured in code (2026-10-01, Firebase unit 1): the values are
/// public identifiers from `firebase apps:sdkconfig IOS`, so a clean clone
/// builds without the gitignored GoogleService-Info.plist.
enum FirebaseConfig {
    static let projectID = "skipti-26"
    static let googleAppID = "1:403030719865:ios:26a313b36c35afcdcd43a8"
    static let gcmSenderID = "403030719865"
    static let apiKey = "AIzaSyDXwEpZ2z09zjhrtJVoKKuoDQzTnUtIGqc"
    static let storageBucket = "skipti-26.firebasestorage.app"
    /// The iOS OAuth client (Google sign-in's PKCE code flow).
    static let iosClientID = "403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj.apps.googleusercontent.com"

    static var options: FirebaseOptions {
        let o = FirebaseOptions(googleAppID: googleAppID, gcmSenderID: gcmSenderID)
        o.apiKey = apiKey
        o.projectID = projectID
        o.storageBucket = storageBucket
        o.clientID = iosClientID
        o.bundleID = "com.vardrlabs.skipti"
        return o
    }

    /// Once, before any Firebase call. Idempotent (tests host the app).
    static func configure() {
        guard FirebaseApp.app() == nil else { return }
        FirebaseApp.configure(options: options)
    }
}
```
- [ ] Call `FirebaseConfig.configure()` first in `SkiptiApp.init()` (add an `init()` if none).
- [ ] Android: in `android/app/build.gradle.kts` dependencies,
      `implementation(platform("com.google.firebase:firebase-bom:34.19.0"))` and
      `implementation("com.google.firebase:firebase-auth")` (no google-services Gradle plugin). `FirebaseConfig.kt`:
```kotlin
package com.vardrlabs.skipti

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** Firebase, configured in code (Firebase unit 1); iOS's twin is FirebaseConfig.swift.
 *  Public identifiers from `firebase apps:sdkconfig ANDROID`; google-services.json stays out of git. */
object FirebaseConfig {
    const val PROJECT_ID = "skipti-26"
    const val APP_ID = "1:403030719865:android:b8e41683ad5cacfacd43a8"
    const val API_KEY = "<api_key.current_key from android/app/google-services.json>"
    const val SENDER_ID = "403030719865"
    const val STORAGE_BUCKET = "skipti-26.firebasestorage.app"
    /** Firebase's auto-created web client: Credential Manager's serverClientId. */
    const val WEB_CLIENT_ID = "403030719865-p4a2s68o0rn18rn37h2v4pq110hih40o.apps.googleusercontent.com"

    val options: FirebaseOptions
        get() = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID).setApplicationId(APP_ID).setApiKey(API_KEY)
            .setGcmSenderId(SENDER_ID).setStorageBucket(STORAGE_BUCKET).build()

    fun configure(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, options)
    }
}
```
      (the API key is copied at execution from the json, never typed; assert it starts `AIza`). Call
      `FirebaseConfig.configure(this)` first in `MainActivity.onCreate`.
- [ ] Tests (twins): options carry project `skipti-26`, the app ids above, sender = the project number;
      negative: `GoogleSignInConfig.isAvailable` stays false for a client id without the
      `.apps.googleusercontent.com` suffix (existing pure test kept).
- [ ] Build both (`make ios-app`, `make android-app`); read the merged Play manifest
      (`scripts/check-play-manifest.sh` output); add only permissions Firebase Auth really adds; regenerate
      `native/licences/licences.json` (`python3 scripts/licences.py`), extending `scripts/licences.py` to read
      SwiftPM's `Package.resolved` + each package's LICENSE if it has no iOS source yet.

### Task 1.2: Account on both shells (pure parts tested first)

- [ ] Failing tests first (`AccountTests.swift` / `AccountTest.kt`):
  - `Account.owns(stored: nil, incoming: "u1") == true`; `owns("u1","u1") == true`; `owns("u1","u2") == false`.
  - A relay-era owner under the OLD key (`AccountOwner`'s key) does not block a Firebase sign-in (only
    `account/firebase-uid` counts): seed the old key with `"relay-subject"`, `Account.storedOwner(store) == nil`.
  - Apple nonce: `AppleSignIn.sha256Hex("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"`
    (FIPS 180-2 vector, derived independently of the implementation).
  - Google token request body: `GoogleSignIn.tokenRequestBody(code:"c", verifier:"v", clientID: id)` contains
    exactly `grant_type=authorization_code`, `code=c`, `code_verifier=v`, `client_id=…`, `redirect_uri=…:/oauth2redirect`,
    and NO `client_secret`.
- [ ] Implement `Account` (iOS, `@Observable @MainActor final class`): `isSignedIn` from
      `Auth.auth().currentUser != nil`, refreshed by `addStateDidChangeListener`; `signIn` = check owner →
      `Auth.auth().signIn(with:)` → if the vault's owner is nil remember the uid, remember the method →
      `.signedIn`; a different stored uid → sign straight back out, `.otherAccount`; `signOut` = `try? Auth.auth().signOut()`.
      Android twin with `FirebaseAuth.getInstance()` and a `suspendCancellableCoroutine` over the `Task`.
- [ ] Apple (iOS): random 32-byte raw nonce, `request.nonce = sha256Hex(raw)`, credential
      `OAuthProvider.appleCredential(withIDToken:rawNonce:fullName: nil)`.
- [ ] Google (iOS): keep `authorizationURL`/`parseCallback`/`present`; replace the relay exchange with a POST to
      `https://oauth2.googleapis.com/token` (`tokenRequestBody`), read `id_token` + `access_token`,
      `GoogleAuthProvider.credential(withIDToken:accessToken:)`. The relay nonce becomes a local random value.
- [ ] Google (Android): `GoogleCredential.idToken(FirebaseConfig.WEB_CLIENT_ID, nonce = random)` →
      `GoogleAuthProvider.getCredential(idToken, null)`.
- [ ] Run the twin tests green; sabotage: (a) `owns` returns true for a different uid → the negative test fails
      alone; (b) the Apple request gets the RAW nonce → the hash vector test fails alone; (c) a `client_secret`
      appended to the body → the body test fails alone.

### Task 1.3: Screens and every "has an account" check

- [ ] `SignInScreen` (both): buttons call the Firebase flows; `finish` maps `Account.Outcome` to the existing
      confirmation / `OtherAccountNotice` / error; the email-code UI and the Recovery Kit step after sign-in are
      removed (the kit protected the relay's sync key; the kit SCREENS go in unit 2).
- [ ] Replace each "has an account" read (`LedgerSync.shared.restoreSession() != nil` and `== nil`, iOS; the
      Android equivalents) with `Account.shared.isSignedIn` / `Account.isSignedIn()`. Sites that USE a relay
      session (`if let session = …restoreSession()`) are left untouched: no relay session can be created any more,
      so they never run, and unit 2 deletes them. Record the list of both kinds in the D-row.
- [ ] `AccountHubScreen` (both): the model's `hasSession` comes from the account; Sign out calls `Account.signOut()`;
      the method line reads `Account.method`.
- [ ] `firebase/firebase.json` (emulators block: auth 9099, firestore 8180, ui disabled) and `firebase/.firebaserc`.
- [ ] Proof arg `--auth-emulator-proof` (both shells): `Auth.auth().useEmulator(withHost:port:)`, sign in with
      `GoogleAuthProvider.credential(withIDToken: #"{"sub":"proof-a","email":"a@example.test","email_verified":true}"#, accessToken: "")`,
      show uid + "Signed in with Google", sign out, show "signed out". Run against
      `firebase/node_modules/.bin/firebase emulators:start --only auth --project demo-skipti` (a `demo-` project
      never touches production).
- [ ] Look: sign-in page and Settings on the iOS simulator (light + dark) and the API 34 + API 37 Android
      emulators; the proof screens on both. Real Apple/Google sign-in is the founder's check on his phones (Claude
      never types credentials).
- [ ] Full gate (emulators and relay off), BUILD-LOG D-191 (justification for the SDKs, sabotage table, the
      dormant relay-session sites as §4 residue for unit 2), commit `[skip ci]`, push, WORKLOG.
