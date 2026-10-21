# ADR-0040: Firebase replaces the relay; the cloud is not end-to-end encrypted

Date: 2026-10-01
Status: Accepted (the founder's decisions, 2026-10-01; design in
`docs/superpowers/specs/2026-10-01-firebase-backend-design.md`, plan in
`docs/superpowers/plans/2026-10-01-firebase-backend.md`)

## Context

SKIPTI's cloud half was a relay (`server/`, ADR-0021) that stored only
ciphertext: every record was sealed on the phone with an account key or a
group key, keys travelled between phones as wrapped envelopes, people were
verified with six-word safety phrases, and a twelve-word Recovery Kit brought
the account key back on a new phone.

Testing 0.3.1 on real phones (2026-10-01), groups did not sync between people,
an expense a friend added never appeared, and the device list stayed empty.
The production relay ran the newest server code, so it was not an undeployed
fix. After several units spent on the key-sharing layer (D-123, D-124, D-156 to
D-161, D-178, D-185) the founder decided to stop repairing it and replace the
cloud half with Firebase, accepting that the operator and the host can
technically read cloud data.

## Decision

1. **Firebase is SKIPTI's cloud** (project `skipti-26`): Firebase Auth for
   accounts (Apple and Google, through each phone's own sign-in screen),
   Cloud Firestore for the personal ledger and groups (region nam5, United
   States), Cloud Functions for membership, invites, lookups and pushes,
   Firebase Cloud Messaging (with APNs on iPhone) for notifications, Cloud
   Storage for group photos, Hosting for the `/join` page and app-link files.
2. **No end-to-end encryption in the cloud.** Data is stored as ordinary
   Firestore documents. Firestore security rules let only the account read
   its personal ledger and only a group's members read the group. Google
   encrypts it in transit and at rest; Google as the host and Vardr Labs as
   the operator could technically access it.
3. **The phone's own ledger stays encrypted** (SQLCipher, key in the Keychain
   or Android Keystore) and stays the database every screen reads. Without an
   account nothing leaves the phone.
4. **Groups need an account.** The personal ledger works fully without one.
   Groups already on a phone are never deleted: they stay usable and upload at
   the first sign-in.
5. **Removed** (D-191, D-192): the relay (`server/`), passkeys and the email
   code, the Recovery Kit, the six-word safety phrase and UNVERIFIED,
   Maximum security and phone approvals, the D-189 bell and the D-190
   background check, the Android `direct` flavour.
6. **The Firebase SDKs are dependencies under CONTRIBUTING rule 4**, recorded
   in the units that add them; never Analytics, Crashlytics, Performance,
   Remote Config or In-App Messaging.

## Consequences

- GUARANTEES #5, #6 and #7 change (amended 2026-10-01). #1 to #4 hold.
- THREAT_MODEL is rewritten: the operator and the host are trusted for
  confidentiality; access control is the security rules; a group member
  still sees everything in the group.
- Superseded where they describe the cloud half: ADR-0005 (group key
  wrapping), ADR-0006 (the Recovery Kit, removed in D-192; Corrected D-200: this list
  first omitted it), ADR-0010 (two flavours), ADR-0012's "no push" position for
  notifications, ADR-0021 (the relay as the adversary), ADR-0034 (sealed
  record sync over the relay), ADR-0035's "the app's requests go to the relay
  alone", ADR-0037 (invites and group logs on the relay), ADR-0039 (phones
  sharing the account key). Their local-first parts stand.
- SPEC §2, §3 (the sync cryptography), §4 (identity) and §6 (sync) are
  superseded where they describe the relay; the note at the top of SPEC says
  so.
- Between D-192 and the Firebase plan's units 4 to 8, signing in works but
  nothing syncs. Nothing ships until 0.4.0.
