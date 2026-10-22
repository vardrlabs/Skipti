# D-254: the approval gate (decisions 228, 229)

Status: design, 2026-10-21 (session 12). Mock-up v1: https://claude.ai/artifact/4ULGr42bEn7YUhSu7pQaTx (private).
Nothing here deploys in this unit. Deploying is the founder's yes on 0.5.1's deploy day, after Fable's security pass.

## 1. What and why

Anyone can sign in. Until the founder approves the account, the cloud does nothing for it: no sync, no shared groups,
no invites, no profile, no phones list, no notifications. The ledger on the phone works exactly as it does with no
account (guarantee 5: the app itself is never gated). The phone says so honestly, in the same words and places on
both phones. The founder decides on a small private admin page.

The server enforces it: the Firestore and Storage rules refuse every client write of an unapproved account, and every
Cloud Function but the ones that let a person leave refuses an unapproved caller. The phones' own gate is a courtesy
(no failing writes, honest words), never the control.

## 2. Approaches considered

- **A. A document, `approvals/{uid}`, written only by Functions (chosen).** The rules read it with `get()`. It takes
  effect on the next write in both directions (approve, or take approval away), the phone can listen to its own
  document and start sync the moment it is approved, and the admin list is a Firestore query. Cost: one extra document
  read per gated write.
- B. A custom claim (`approved: true`). Free to read in rules, but it lives in the ID token: a change waits for a token
  refresh (up to an hour unless the phone forces one), taking approval away needs a token revocation as well, and
  listing pending accounts needs `listUsers` over Auth. Rejected for the delay and the second moving part.
- C. An Auth blocking function (`beforeUserSignedIn`). Needs the Identity Platform upgrade (a paid console change)
  and would refuse sign-in itself, which the founder did not ask for. Rejected.

## 3. Data

`approvals/{uid}` (no client writes; the account may `get` its own):

| field | written by | meaning |
|---|---|---|
| `state` | Functions | `pending`, `approved` or `denied` |
| `requestedAt` | requestApproval | server time of the first request |
| `name` | requestApproval | the name the phone shows others (trimmed, 1 to 60 characters), or absent |
| `platform` | requestApproval | `ios` or `android` |
| `provider` | requestApproval | the token's `firebase.sign_in_provider` (`google.com`, `apple.com`) |
| `email` | requestApproval | the token's `email` when the provider gave one and `email_verified` is true, else absent |
| `decidedAt`, `decidedBy` | adminApprovals | server time; `admin` or `migration` |
| `deleteAt`, `deletionRequestedAt` | requestDeletion / cancelDeletion | only for an account with no profile (section 6) |

`meta/approvalsMigration` marks the one-time migration done (count, time). No client access.

## 4. Rules (`firebase/firestore.rules`, `firebase/storage.rules`)

- `approved()`: signed in, and `approvals/{request.auth.uid}` exists with `state == 'approved'`.
- Every client `create` and `update` adds `approved()`: `users/{uid}`, `users/{uid}/devices/{d}`,
  `users/{uid}/records/{r}`, `groups/{g}`, `groups/{g}/records/{r}`. Storage: the group picture `write`.
- Reads and deletes are unchanged: reading what is yours and removing it never waits for anyone.
- `approvals/{uid}`: `get` if `isUser(uid)`; no `list`, no writes. `meta/**`: nothing.

## 5. Functions (`firebase/functions/`)

- `approvedCaller(request)`: `signedIn`, then reads `approvals/{uid}`; refuses with `permission-denied`, message
  "This account is waiting for approval." (or "This account was not approved."), `details: { approval: state }`. It is
  the FIRST statement of every callable except the exempt ones, before any argument check, so an unapproved caller
  learns nothing else.
- Exempt: `signOutDevice`, `signOutEverywhere`, `requestDeletion`, `cancelDeletion` (leaving never waits),
  `requestApproval`, `adminApprovals` (its own check). A test lists every `onCall` export in `index.js` and asserts the
  gate on every one not in the exempt list, so a new callable cannot be added ungated.
- Triggers need nothing new: `onGroupRecord` and `onDeviceCreated` fire only on writes the rules already gated.
- `requestApproval({ name?, platform })`: signed in. No document: creates `pending` with the fields above. `pending`:
  refreshes `name`, `platform`, `email` (never `requestedAt`). Decided: changes nothing. Returns `{ state }`. Bad input
  is `invalid-argument`.
- `adminApprovals({ action, ... })`: only when `request.auth.uid` equals the `SKIPTI_ADMIN_UID` param AND the token's
  `sign_in_provider` is `google.com`; an empty param refuses everyone. A refusal is `permission-denied` "This is not the
  admin account." with `details: { uid }` (the caller's own id, so the founder can copy his into the param on deploy
  day). `cors`: the admin site's two hosts and `http://localhost:*` / `http://127.0.0.1:*`.
  - `list`: `{ pending, approved, denied }`, each newest first, at most 500, fields as stored plus `uid`.
  - `decide({ uid, state: 'approved' | 'denied' })`: the document must exist; sets `state`, `decidedAt`, `decidedBy:
    'admin'`. Taking approval away (approved to denied) is the same call.
  - `migrate()`: once (`meta/approvalsMigration` absent, else `failed-precondition`): every `users/{uid}` profile
    without an approval document becomes `approved`, `decidedBy: 'migration'`; a `pending` one too (its profile
    predates the gate: the rules refuse a profile to an unapproved account); `denied` is left alone. Returns the count.
    Writes in batches of at most 400.
- `requestDeletion` / `cancelDeletion` for an account with no profile (a pending or denied account): the deletion
  marker goes on `approvals/{uid}` instead of failing with "setting up"; `purgeDeletedAccounts` also purges approval
  documents past their `deleteAt` (the document and the Auth user). For every purged account, `approvals/{uid}` is
  deleted with the rest. The seven days and Keep my account work the same for everyone.
- Param: `SKIPTI_ADMIN_UID` (`defineString`). Emulators read it from `firebase/functions/.env.local` (committed, an
  emulator-only id, `emulator-admin`; `*.local` is never uploaded). Production has no committed value: the deploy
  asks for it, and an empty value refuses every admin call.

## 6. The admin page (`firebase/admin/index.html`, its own Hosting site)

- `firebase.json` hosting becomes two targets: `main` (today's config, unchanged) and `admin` (public `admin`).
  `.firebaserc` maps `main` to `skipti-26` and `admin` to `skipti-26-admin` (the site the founder creates on deploy
  day). `--only hosting` deploys both; the runbooks say `hosting:main` / `hosting:admin`.
- One HTML file, one inline script pinned by hash, no library, no font, nothing from elsewhere, nothing kept in the
  browser past the sign-in round trip (a nonce and state in `sessionStorage`, removed on return).
- Sign-in without an SDK: a redirect to Google's OAuth endpoint (`response_type=id_token`, the Web client id, `openid
  email`, a random nonce and state), back with the token in the fragment, checked (state, nonce), exchanged at
  `identitytoolkit.googleapis.com/v1/accounts:signInWithIdp` for a Firebase ID token held in memory; then the callable
  over `fetch` (`POST {data}` with the bearer token). Reload means sign in again.
- Views: signed out ("Sign in with Google"); not the admin ("This is not the admin account." and the caller's id);
  the list: a one-time migration banner while `meta/approvalsMigration` is absent ("N accounts already had a profile
  before the gate. Approve them so nobody already testing is locked out." / "Approve the N"); Waiting (name or "No
  name yet", provider, email or "no email shared", iPhone or Android, how long ago) with Approve and Deny; Approved and
  Denied folded, each row with the opposite button. Every action re-reads the list.
- Headers for the admin site: CSP `default-src 'none'; script-src 'sha256-…'; style-src 'unsafe-inline'; connect-src
  https://identitytoolkit.googleapis.com https://us-central1-skipti-26.cloudfunctions.net; img-src 'none'; base-uri
  'none'; form-action 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options:
  nosniff`, `X-Robots-Tag: noindex, nofollow`, `Cache-Control: no-store`.
- Emulator mode only when the page is served from `localhost` / `127.0.0.1`: a test-uid field instead of the Google
  redirect (the Auth emulator accepts an unsigned token), the emulators' URLs. Off on any other host.
- The API key and Web client id are the apps' own (public by design); a hosting test pins them to the shells' values.

## 7. The phones (iOS and Android twins)

- `Approval` (new, `Approval.swift` / `Approval.kt`): state `unknown | pending | approved | denied` for the signed-in
  uid, cached in the vault as a local-only setting (never a synced setting record) so a launch offline shows the
  last-known state without a flash; cleared on sign-out and erase.
  - After a successful sign-in (after the owner is kept, before `CloudSync.appIsActive(true)`) and on every front
    event while signed in and not approved: `requestApproval({ name, platform })` (the name the person set, if any).
  - While signed in: one listener on `approvals/{uid}`. `approved` arriving: cache, a one-time toast "Your account is
    approved. Syncing now.", then `CloudSync.appIsActive(true)`. Approved to `denied`: cache, stop the cloud as sync
    off does (local data untouched).
- The cloud gates gain approval as a third condition next to ownership: iOS `LaunchWork.signedInAccount` /
  `InstallSession.owned` callers that start cloud work, Android `CloudGate.owned`; plus the paths that bypass `ready()`:
  `registerPhone` / `registration`, Android's direct `CloudProfile.register` in AppRoot's username step, the push
  restart, and `CloudFunctions.call` (a non-exempt name refuses locally with the waiting words, no network call).
- A Functions refusal with `details.approval` maps to the waiting (or not approved) words, never to "not a member".
- Words (both phones, identical, measured in both settings-summary width tables where they are summaries):
  - You, Account row summary: "Waiting for approval" (pending) / "Not approved" (denied); otherwise as today.
  - Sync card while pending: value "Waiting", body "SKIPTI is in early testing, so new accounts are approved by hand.
    Your ledger on this phone works as usual. Sync and shared groups start once your account is approved." No switch.
  - Sync card while denied: value "Off", body "This account was not approved for sync. Your ledger on this phone works
    as usual and stays on this phone." No switch.
  - Opening an invite while pending: title "Waiting for approval", body "You can join {group} once your account is
    approved. The link works for 7 days, so open it again then." (no group name known: "You can join this group once
    ..."), one "Done". Denied: "This account was not approved, so it cannot join groups."
  - A group's invite rows, Find friends and Add by username while pending: one line, "Inviting people starts once your
    account is approved." (denied: "This account was not approved, so it cannot invite people.")
  - Approved: the toast once.
  - Sign out and Delete account always work.
- `AccountState` gains the two states; `accountHubModel` takes the approval. The copy lives in one table per shell
  (`ApprovalCopy`), Android's parity test reads the Swift file.

## 8. Tests and proof

- Rules (`firebase/tests/approvals.test.mjs` plus the existing suites seeding approval for their users): every gated
  write refused for no document, `pending` and `denied`, allowed for `approved`; reads and deletes unchanged; own
  approval readable, another's not, no client write to `approvals` or `meta` (the admin uid included); Storage picture
  write refused unapproved.
- Functions: every gated callable refuses an unapproved caller first (the structural test over `index.js`); exempt
  ones work; `requestApproval` creates, refreshes, never overturns a decision, stores email only when verified;
  `adminApprovals` refuses a non-admin, an Apple-signed admin uid, an empty param; `list`, `decide` both ways,
  `migrate` once (second run refused, denied untouched, pending with a profile approved); deletion of a profile-less
  account; purge removes the approval document.
- Hosting: the admin target's headers, the page's single hashed script, no external origin but the two named, the
  shells' API key and client id; the main target unchanged.
- Shells: the approval decision and copy as pure functions; gates (pending refuses push, listen, registration, calls;
  exempt calls pass); twin copy parity; width tables.
- Sabotage sized to risk (CLAUDE.md 4): each new rule guard, `approvedCaller` on a sample of callables plus the
  structural test, the admin check's two halves and empty param, `migrate`'s marker, the shells' gates and mapping.
- Emulator proof on throwaway devices: a new account on the iOS simulator and the Android AVD shows Waiting, a local
  expense works, the cloud holds nothing for it; approved from the admin page (served locally, emulator mode) the
  phone toasts and its records arrive; denied, sync stops; an existing approved account is unaffected.

## 9. Deploy day (the founder's, after Fable; not this unit)

1. Create the Hosting site `skipti-26-admin`; add `https://skipti-26-admin.web.app` as an authorized redirect URI on
   the Web OAuth client and the domain to Firebase Auth's authorized domains.
2. Deploy `adminApprovals` alone with `SKIPTI_ADMIN_UID` set (first sign-in on the page shows his uid if unknown).
3. On the admin page: Approve the N (the migration). Check the count against `users`.
4. Deploy the other Functions, then the rules and Storage rules, then `hosting:admin` and `hosting:main` (privacy
   page Version 3), then the apps.
5. CLOUD-SECURITY section 2 rows for each; the cloud audit script learns the second site.
Until step 3 runs, every existing tester would be refused, so the order matters. A 0.5.0 phone signed in AFTER the
gate gets refusals and no waiting words (it never asks for approval); 0.5.0 was internal testing only.

## 10. Not in this unit

A push to the founder when someone asks, or to the person when approved; an appeal; automatic expiry of abandoned
pending requests (a pending record is a name and an email at most, deleted with the account). Recorded as gaps.
