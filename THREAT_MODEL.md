# THREAT_MODEL.md

Published verbatim as part of the product's claims discipline (SPEC §14.6).
If this document and marketing ever disagree, this document wins.

**Rewritten 2026-10-01 for ADR-0040.** SKIPTI's cloud is Google Firebase, and
cloud data is protected by access rules, not by end-to-end encryption. The
earlier version of this document described an end-to-end-encrypted relay that
was never released publicly; it is in git history.

## Assets, ranked

1. **Transaction ledger.** Amounts, merchants, dates, notes. This reveals
   medical conditions, relationships, addictions, politics, and location
   history. A spending log is more intimate than a chat log and is treated as
   such here.
2. **The phone's vault key** (the key that opens the ledger on the phone).
3. **Group membership and social graph.** Who you split with.
4. **Identity bindings.** Your Apple or Google account, and later your
   username and (if you allow it) your email for being found.
5. **Metadata.** Transaction counts, sync timing, device count.

## Adversaries

| ID | Adversary | Assumed capability | In scope |
|---|---|---|---|
| A1 | Passive network observer | Reads all traffic, cannot break TLS | Yes |
| A2 | The cloud's host (Google) | Full access to Firebase's storage and logs | **No for confidentiality.** See below |
| A3 | The cloud's operator (Vardr Labs) | Administrator of the Firebase project | **No for confidentiality.** See below |
| A4 | Another SKIPTI user who is not in your group | A signed-in account of their own | Yes: the access rules |
| A5 | A hostile group member | Legitimately reads and writes the group | Partially, see below |
| A6 | Someone who picks up your unlocked phone | Physical access, device unlocked | Yes |
| A7 | Someone who steals your locked phone | Physical access, device locked, no biometrics | Yes |
| A8 | Forensic extraction from a seized device | Chip-off, exploit chains, cloud backup subpoena | Best effort, honestly bounded |
| A9 | Malicious third-party SDK in the app | Supply chain | Yes, bounded: the only third-party SDKs are Firebase's, named below |
| A10 | Compromised OS, root, or targeted malware | Full device control | **No.** Nothing at this layer helps. |
| A11 | Coercion of the user | Rubber hose | **No.** No deniability features, no fake vault. |
| A12 | Anyone who signs in, before the operator approves the account | A signed-in account of their own | Yes: the approval gate |

## What SKIPTI defends

- **A1:** TLS to Firebase, Apple and Google.
- **A4:** Firestore and Cloud Storage security rules: only you can read or
  write your personal ledger; only a group's members can read a group, and
  they can add records only in their own name; nobody can change who is in a
  group except through SKIPTI's Cloud Functions; nobody can list other users.
  Each rule has a test that it refuses (the Firebase plan's unit 3). Your
  "Just me" expenses and your app settings (theme, text size and the like,
  since 0.5.1) are records in your personal ledger, readable only by you. A
  deleted group's id can never become a group again (0.5.1: the deletion
  leaves a marker that the rules check).
- **A12:** While SKIPTI is in early testing, a new account can write nothing
  to the cloud until the operator approves it: the rules refuse every create
  and update of an unapproved account (it may still read and delete what is
  its own, and clear its phone's notification address), and every Cloud
  Function except signing out, deleting the account and asking for approval
  refuses it. Approval is a document only Cloud Functions write; the
  function that sets it answers only the operator's own account, signed in
  with Google. Each refusal has a test (D-254).
- **A5, bounded:** every group has daily limits on invitations, joins and
  removals, and every account on the notifications its records can send
  (0.5.1); a notification names its author as the cloud knows the account,
  never as the author's phone chose to write it.
- **A6 and A7:** The phone's ledger is encrypted (SQLCipher) with a key held
  in the platform keystore (Keychain, Android Keystore), with app-level
  auto-lock and a privacy cover when SKIPTI leaves the screen.
- **A8:** No cloud backup of the vault on either platform, enforced with
  backup exclusion flags rather than left to defaults. The cloud copy (with
  an account) is a separate matter: see A2/A3.
- **A9:** The third-party SDKs are Firebase's (Auth, Firestore, Messaging,
  Storage, Functions) and, on Android, Google's sign-in libraries. Never
  Analytics, Crashlytics, Performance, Remote Config or In-App Messaging.
  The dependency budget (SPEC §10.5) and CONTRIBUTING rule 4 still apply.

## Honest limit: the host and the operator can read cloud data

With an account, your personal ledger and your groups are stored in Cloud
Firestore (region nam5, United States) as ordinary documents. Google encrypts
them in transit and at rest, but Google holds those keys. **This is not
end-to-end encryption.** Google, as the host, and Vardr Labs, as the operator
of the Firebase project, could technically read them, and either could be
compelled by law to hand them over. The access rules stop other users, not
the people who run the cloud.

What limits this in practice: SKIPTI's code never reads users' data for any
purpose but delivering it (the Cloud Functions read a record only to address
a notification or to check a membership); there are no analytics; and
without an account nothing leaves your phone. If the operator or the host is
in your threat model, use SKIPTI without an account.

Since 0.5.1, every read and write of a cloud document, the operator's own
included, is recorded in Google Cloud's audit log: who, when and which
document, never its contents. That makes a reading by the operator visible,
not impossible: whoever holds the project's owner rights could switch the log
off, and Google's own access as the host is not in it.

## Honest limit: a group member sees the group

Anyone in a group can read every expense in it, screenshot it, and export it.
Removing them stops future access and nothing else.

- **A hostile member can add or change expenses in the group.** Every record
  carries its author's account, set by the access rules rather than by the
  app, so changes are attributable in the cloud; edits and deletes are new
  records, so history is never silently rewritten. Two limits of that: the
  "added by" and "edited by" names the app shows come from the record as the
  author's phone wrote them, not from the cloud's author (the cloud's author
  is not yet carried into the phone's ledger); and a member can name someone
  who is not in the group on an expense or a settlement, which reads as an
  unknown member and is corrected like any other entry (a revision, or a
  counter-settlement). A member who floods a group with the largest possible
  amounts can push its totals past what the ledger can add up: that group
  then reads "Amounts too large to add up" and the others are unaffected
  (0.5.1).
- **Personal transactions are never in a group.** They live in a different
  place in the cloud, readable only by you.
- **An invite link admits whoever holds it.** Forwarding the link forwards
  the invitation; it is single-use and expires (the Firebase plan's unit 5).

## Honest limit: signing in with Google or Apple

- **What the provider learns.** That your Google or Apple account signed in to
  SKIPTI, and when. On Android, Google Play services carries out the sign-in.
- **What Firebase keeps.** Your account's Firebase user id and the provider's
  id for you. On iPhone, SKIPTI asks Apple and Google for no name and no email,
  and Firebase keeps none. On Android, Google's sign-in includes the email
  address of the Google account you choose: SKIPTI does not ask for it, but
  Firebase keeps it with the account (and, if you can be found, a fingerprint
  of it lets people who know that address find you; an iPhone account can be
  found by username only).
- **If your Google or Apple account is compromised,** the attacker can sign in
  to SKIPTI as you and read your cloud data. Your other phones are told when a
  new phone signs in, and "Sign out everywhere" ends every session (the
  Firebase plan's unit 8). Signing one phone out tells that phone, and from
  then on the cloud refuses new records from it (0.5.1); an app modified to
  hide its identity could still act for up to an hour, until its token
  expires. A compromised account cannot weaken your phone's lock: the app
  lock, its timeout and the screen privacy setting never leave the phone.

## Metadata the cloud learns

Everything in your cloud data (see above), plus: your internet address when
you connect, when you sync, how many phones you use (each registers for
notifications), and the push tokens Apple's and Google's push services issue.
Notification text ("Emma added Dinner · $120.00 in Miami trip") passes
through Apple's or Google's push service. When you sign in, an approval
request holds the name you show others, your sign-in email if Apple or
Google gave one, and your phone type; it is deleted with your account, and
a denied request keeps neither the name nor the email. When a group is
deleted, a marker keeps the group's id, who deleted it and the accounts
that were in it, so that every phone can tell the group ended; it holds no
expense. The operator keeps audit logs of its own reads and writes of the
database (switched on with 0.5.1), so that "the operator could technically
read" is at least visible after the fact.

## Explicitly out of scope

- A compromised or rooted device (A10). Root detection is a warning, not a
  control, and the docs say so plainly.
- Coercion (A11). No decoy vaults. A decoy vault is a feature that gets
  people hurt when it is discovered, and it invites a false sense of safety.
- Screenshots by a group member (A5).
- Shoulder surfing.
- Correlation of settle-up transfers by your bank or UPI provider. SKIPTI
  never sees them, but your bank does.
