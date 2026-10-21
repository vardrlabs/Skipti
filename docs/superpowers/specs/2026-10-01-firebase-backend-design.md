# Firebase replaces the relay — design

Date: 2026-10-01. Status: approved by the founder ("Go with A and let's build it"; spec reviewed 2026-10-01: "Looks good"). Decisions below are his unless marked **(Claude's call, delegated)**. This document is the design;
the step-by-step plan is a separate file, and each unit is still recorded as a D-row in BUILD-LOG.

## 1. Why

Testing 0.3.1 on real phones (2026-10-01): groups do not sync between people, an expense a friend adds never
shows up, and the device list stays empty. The production relay runs the newest server code (`2160d90`), so
this is not an undeployed relay; the root cause was never established. The founder decided to stop repairing
the end-to-end-encrypted relay sync and replace the cloud half with Firebase. The local ledger, the Rust core's
money/splitting logic and the screens stay.

## 2. Decisions

| # | Decision |
|---|---|
| 1 | Cloud data is stored as ordinary Firestore data, readable only by the people it belongs to under Firebase security rules. **No end-to-end encryption.** Google and Vardr Labs could technically read it. GUARANTEES #7 is rewritten honestly. |
| 2 | The personal ledger also syncs, between one account's own phones (readable by that account only). |
| 3 | Without an account the personal ledger works fully, offline. **Groups need an account** to create or join. Groups already on a phone are never deleted: they stay usable and upload at the first sign-in. |
| 4 | Pushes are sent by **Cloud Functions** (Blaze plan, card as a fallback; designed to stay inside the free tier). |
| 5 | Firestore region **nam5** (US multi-region). |
| 6 | Push text says who, what and how much: "Rahul added Dinner · ₹1,200 in Goa trip". Tapping opens the expense. |
| 7 | Removed: the relay (`server/`), passkeys, email code, Recovery Kit, six-word safety phrase and UNVERIFIED, Maximum security and phone approvals, the D-190 background check, the D-189 bell. |
| 8 | Kept from D-189: a **Recent activity** list on each group's page. |
| 9 | **(Claude's call, delegated)** The Android `direct` flavour is retired. Its purpose (ADR-0010) was SMS capture and F-Droid; SMS was never built, F-Droid refuses proprietary Firebase, and FCM and Google sign-in need Play services. Recoverable from git. |
| 10 | Firebase CLI: yes, pinned in the repo (devDependency + lockfile, run with `npx`); the founder runs `firebase login`. This is the founder's §13 yes for firebase-tools and the Functions' own npm dependencies only. |
| 11 | Oracle is retired. `skipti.vardrlabs.com` (the `/join` page and the app-link files) moves to Firebase Hosting. |
| 12 | A new phone signing in pushes "Pixel 8 signed in to your account. Was this you?" to the account's other phones, with **Sign it out**. |
| 13 | Find people three ways: **@username** search, **exact email** match, and **matching your contacts**. Inviting someone found this way sends them a push and an in-app invite. Invite links and QR stay. |
| 14 | Findable by default, with a switch: Settings → Privacy, "Let people find me by my username or email". |
| 15 | Approach A (§3): the app's own record format on Firestore, not Firestore-native mutable documents. |

## 3. Architecture (approach A)

Each phone keeps its encrypted local vault (SQLCipher) as the database the screens read; nothing about the
screens' data path changes. Every change is already an immutable record in core (expense, expense revision,
settlement, group operation, with an HLC). Sync becomes:

- **Up:** a record is saved to the vault first (offline works), then an outbox writes it as one Firestore
  document. Records are append-only, so writes never conflict.
- **Down:** one Firestore snapshot listener for the personal collection and one per group deliver new records
  within about a second. Each is applied through core's existing merge, so edits and deletes resolve exactly as
  they do today (HLC, revisions).
- Firestore's own offline cache is kept small: the vault is the source of truth.

Rejected: (B) Firestore-native mutable documents read directly by screens: it rewrites every screen's data
layer on both shells and replaces tested merges with last-write-wins. (C) Ciphertext on Firestore: ruled out by
decision 1.

## 4. Data model

```
users/{uid}                      name, username, participantId ("you"), findable: bool,
                                 emailLower (only while findable), createdAt
users/{uid}/devices/{deviceId}   name ("Pixel 8"), platform, fcmToken, lastSeen, signedOut: bool
users/{uid}/records/{recordId}   personal records (see "record document")
users/{uid}/invites/{inviteId}   incoming in-app invites: groupId, groupName, fromName, memberId?, createdAt
usernames/{handle}               uid (uniqueness by document id; lowercase a–z, 0–9, _; 3–20 chars)
groups/{groupId}                 name, memberUids: [uid], createdBy, pictureRef?, emoji?
groups/{groupId}/records/{id}    group records
invites/{sha256(token)}          groupId, memberId (the named person), expiresAt, createdBy  (link + QR)
```

**Record document:** `type`, `hlc` (millis, counter, device), `authorUid`, `deviceId`, `createdAt`
(server timestamp, used as the listener cursor), `payload` (core's record fields as a map), and for expense
records `notice` (the push line, formatted by core on the author's phone, e.g. "Rahul added Dinner · ₹1,200",
so every money string still comes from core).

**Group photos:** Cloud Storage `groups/{groupId}/picture.jpg` (the existing ≤ 64,000-byte JPEG), readable by
members only. Emoji pictures stay a field.

## 5. Security rules (sketch)

- `users/{uid}/**`: read and write only when `request.auth.uid == uid`, except: `devices` and `invites` are
  written by Functions too; `users/{uid}` fields `name` and `username` are readable by any signed-in user only
  through the find-people Function, never by direct reads.
- `usernames/{handle}`: created in the same transaction as the user's `username` field; never readable in bulk.
- `groups/{g}`: read if `request.auth.uid in resource.data.memberUids`. Create allowed only with
  `memberUids == [request.auth.uid]`. Clients can never change `memberUids` (only Functions).
- `groups/{g}/records/{id}`: read if a member; **create** if a member and `authorUid == request.auth.uid`;
  no update, no delete.
- `invites/*`: no client access; Functions only.
- Every rule has a negative test in the emulator suite (a non-member read, a forged `authorUid`, an update to a
  record, a client write to `memberUids`).

## 6. Cloud Functions (TypeScript, Node 22 runtime)

| Function | Trigger | Job |
|---|---|---|
| `onGroupRecord` | Firestore create `groups/{g}/records/{id}` | Push `notice` + group name to every other member's devices; drop dead tokens. |
| `onDeviceCreated` | Firestore create `users/{uid}/devices/{d}` | Push "{name} signed in to your account. Was this you?" to the account's other devices. |
| `createInvite` / `acceptInvite` | Callable | Link/QR invites (token hashed at rest, expiry); accept adds the caller to `memberUids` and links the named member to their account. |
| `findPeople` | Callable | Exact username, exact email, or a list of contact emails → matches among findable users only (name, username). Stores nothing it is sent. Rate-limited per caller. |
| `inviteUser` | Callable | Writes `users/{target}/invites/{id}` and pushes "Prithvi invited you to Goa trip". |
| `removeMember` | Callable | D-186's rule (only someone with no history); removes from `memberUids`. |
| `signOutDevice` / `signOutEverywhere` | Callable | Flag a device `signedOut` (that phone signs itself out on its next contact) / revoke every refresh token of the account. |
| `deleteAccount` | Callable | Store requirement: deletes `users/{uid}/**`, the username, the auth user; removes them from groups; a group's records go when its last member leaves (SPEC §4.4). |

Known limit, stated in the UI: Firebase cannot force-sign-out one phone; "Sign it out" works when that phone
next goes online, and "Sign out everywhere" is the remedy for a lost or stolen phone.

## 7. Shells

Both shells, twins as always:
- **Auth:** Firebase Auth with Apple (iOS) and Google (both) through the system sign-in surfaces. Android keeps
  Google only, as today.
- **Sync engine** (one file per shell, replacing LedgerSync / GroupSync / SyncService / SyncStatus / RelayTransport
  / Relay/*): outbox, listeners, cursor per collection in the vault's kv.
- **Push:** FCM on both (APNs key uploaded to Firebase for iOS); permission asked at a meaningful moment
  (first group), not at launch; tap routes to the expense or invite.
- **Your phones:** list from `users/{uid}/devices`, Sign it out, Sign out everywhere, the "Was this you?" banner.
- **Find people:** a group's People screen gains Search (username or email) and "Find friends in contacts"
  (asks for contacts permission only then). Settings gains Username and the findable switch.
- **Recent activity** on a group's page, fed by the same records (D-189's list, without the bell).

## 8. Core

- An unsealed record form: export a record's header fields and payload as a map, and import one through the
  same merge. Money stays integers; formatting stays in core.
- Sync-only cryptography (envelopes, group keys, invite crypto, the account sync key, mnemonics) becomes unused
  and is deleted in the removals unit. The vault's SQLCipher key and keystore stay.

## 9. Guarantees and docs (drafts; final text in the removals unit)

- **#5:** "Your personal ledger works fully offline and with no account. Groups need an account, because they
  live in the cloud for everyone in them."
- **#6:** "The app talks to SKIPTI's backend, which runs on Google Firebase, and to the sign-in provider you
  choose. Notifications travel through Apple's or Google's push service. No ads, analytics, crash reporter or
  attribution SDK."
- **#7:** "Only you can read your personal ledger, and only a group's members can read the group, enforced by
  the backend's access rules. It is encrypted in transit and at rest by Google. It is not end-to-end encrypted:
  Google, and Vardr Labs as the operator, could technically access it."
- **#1** holds: Firebase Analytics is never added; the Firebase Installations ID that push requires is stated in
  the privacy policy and store privacy labels.
- New ADR-0040 (Firebase replaces the relay), superseding parts of ADR-0005, -0010, -0012 (the no-push rule),
  -0021, -0034, -0035, -0037, -0039. THREAT_MODEL, SPEC sections, privacy policy and README updated.
- New dependencies get CONTRIBUTING rule 4's written justification in the unit that adds them (Firebase iOS SDK
  via SwiftPM, Firebase Android BoM, firebase-tools, firebase-functions, firebase-admin); ADR-0012's 30-direct-
  dependency cap is checked there.

## 10. Testing

- **New gate leg:** the Firebase emulator suite runs the rules tests (every negative above) and the Functions
  tests; `npm audit` joins `cargo audit`.
- **Shell tests** run the sync engine against the emulator.
- **The test that was missing:** two phones, two different accounts: A creates a group, invites B, B joins, B
  adds an expense, and A must show it within a few seconds; then an edit and a delete. Run on the iOS
  simulator and an Android emulator against the emulator suite, and once against the real project before
  release.
- Sabotage every rule and guard as usual.

## 11. Units (D-numbers assigned as they start)

**Order revised in the plan (2026-10-01):** the removals move to second place, right after sign-in, so no later unit
builds beside the dormant relay layer; see `docs/superpowers/plans/2026-10-01-firebase-backend.md`.

1. Foundation: config files, SDKs (justified), Firebase Auth sign-in, `users` doc, username, a phone's device doc.
2. Data model, security rules, Functions skeleton, emulator gate leg.
3. Personal sync (outbox + listener + core's unsealed records).
4. Groups: create, upload at sign-in, invite link/QR via Functions, the two-phone test.
5. Push: FCM/APNs, `onGroupRecord`, tap routing, Recent activity; remove the bell and background check.
6. Find people (username, email, contacts) and direct invites with push; the findable switch.
7. Your phones: device list, "Was this you?", Sign it out / everywhere, account deletion.
8. Removals and docs: relay, passkeys, Recovery Kit, safety phrase, Maximum security, direct flavour, dead core
   crypto; GUARANTEES, ADR-0040, THREAT_MODEL, SPEC, privacy policy.
9. Hosting: `/join` and app-link files on Firebase Hosting; DNS hand-off; Oracle backup and shutdown hand-off.
10. Release **0.4.0** on both (the founder: "a new version jump for both"): iOS Version 0.4.0 Build 6; Android
    versionName 0.4.0, versionCode 4000 (CLAUDE.md §10.3), with paste-ready notes; Developer Tools exemption reminder.

## 12. Founder actions (console; Claude never acts in consoles)

| When | Action |
|---|---|
| Unit 1 | Add Firebase to the existing Google Cloud project (Analytics **off**); add the iOS app (`com.vardrlabs.skipti`) and the Android app (`com.vardrlabs.skipti`, SHA-1 and SHA-256 of the debug key and Play's app-signing key); enable Google and Apple sign-in; put `GoogleService-Info.plist` and `google-services.json` in the repo; install nothing yourself: Claude adds the pinned CLI, then you run `npx firebase login`. |
| Unit 2 | Create Firestore in production mode, region **nam5**. |
| Unit 5 | Blaze plan with a budget alert; create an APNs key (.p8) and upload it under Cloud Messaging with its Key ID and team `27GK2T7UCT`. |
| Unit 9 | One DNS change in Cloudflare (values given then); back up and shut down Oracle (commands given then). |
| Release | Store privacy labels updated; Developer Tools exemption off before signed builds. |

## 13. Risks

- **Build time and size:** the Firebase iOS SDK (Firestore pulls gRPC) adds minutes to a clean build and
  megabytes to the app.
- **Free tier:** a phone's first sync reads every record once; listeners then read only new ones. Testing stays
  far inside 50,000 reads and 20,000 writes a day.
- **Apple "Hide my email"** users cannot be found by email; username and invite links cover them.
- **Testers on 0.3.x** sign in again after updating; their phones keep all data and upload it. Old relay
  copies are abandoned (the relay held ciphertext only).
