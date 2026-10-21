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

## What SKIPTI defends

- **A1:** TLS to Firebase, Apple and Google.
- **A4:** Firestore and Cloud Storage security rules: only you can read or
  write your personal ledger; only a group's members can read a group, and
  they can add records only in their own name; nobody can change who is in a
  group except through SKIPTI's Cloud Functions; nobody can list other users.
  Each rule has a test that it refuses (the Firebase plan's unit 3).
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

## Honest limit: a group member sees the group

Anyone in a group can read every expense in it, screenshot it, and export it.
Removing them stops future access and nothing else.

- **A hostile member can add or change expenses in the group.** Every record
  carries its author's account, set by the access rules rather than by the
  app, so changes are attributable; edits and deletes are new records, so
  history is never silently rewritten.
- **Personal transactions are never in a group.** They live in a different
  place in the cloud, readable only by you.
- **An invite link admits whoever holds it.** Forwarding the link forwards
  the invitation; it is single-use and expires (the Firebase plan's unit 5).

## Honest limit: signing in with Google or Apple

- **What the provider learns.** That your Google or Apple account signed in to
  SKIPTI, and when. On Android, Google Play services carries out the sign-in.
- **What Firebase keeps.** Your account's Firebase user id and the provider's
  id for you. SKIPTI asks for no name and no email from Apple or Google.
- **If your Google or Apple account is compromised,** the attacker can sign in
  to SKIPTI as you and read your cloud data. Your other phones are told when a
  new phone signs in, and "Sign out everywhere" ends every session (the
  Firebase plan's unit 8).

## Metadata the cloud learns

Everything in your cloud data (see above), plus: your internet address when
you connect, when you sync, how many phones you use (each registers for
notifications), and the push tokens Apple's and Google's push services issue.
Notification text ("Rahul added Dinner · ₹1,200 in Goa trip") passes through
Apple's or Google's push service.

## Explicitly out of scope

- A compromised or rooted device (A10). Root detection is a warning, not a
  control, and the docs say so plainly.
- Coercion (A11). No decoy vaults. A decoy vault is a feature that gets
  people hurt when it is discovered, and it invites a false sense of safety.
- Screenshots by a group member (A5).
- Shoulder surfing.
- Correlation of settle-up transfers by your bank or UPI provider. SKIPTI
  never sees them, but your bank does.
