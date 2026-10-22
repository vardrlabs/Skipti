# GUARANTEES.md

These are load-bearing promises. They appear in the README, the store
listings, and here. Breaking one is a fork-worthy event. ADR-0013 records
what would have to be true for any of them to change, and the answer is:
a fork, not a quiet edit.

1. **No subscription. No ads. No telemetry. No analytics SDK. Ever.**

2. **No cap, meter, cooldown, or interstitial on logging an expense.** Not
   now, not as a growth experiment later.

3. **No feature is gated behind payment.** There is no paid tier to gate
   into.

4. **Your data is exportable in full, in an open format, at any time, in one
   tap.** No row caps, no watermark, no "we'll email it to you."

5. **Your own ledger is fully functional offline and with no account.** An
   account is opt-in and is never created, implied, or nudged for. Sharing a
   group with other people's phones needs an account, because shared groups
   live in the cloud for everyone in them. Sync requires an account, is on
   once your account is approved, and can be turned off at any time in one
   tap.

6. **No third-party network origin except SKIPTI's cloud and the services it
   needs.** The app talks to SKIPTI's cloud, which runs on Google Firebase;
   to Apple or Google when you tap Sign in with them, through your phone's own
   sign-in screen; and to Apple's or Google's push service, which delivers
   notifications. No CDN, no web fonts, no crash reporter, no analytics, no
   attribution SDK. Without an account, the app contacts none of them.

7. **Only you, and the people in your groups, can read your data.** With an
   account, your ledger and your groups are kept in SKIPTI's cloud, and its
   access rules let only you read your own ledger and only a group's members
   read that group. It is encrypted in transit and at rest. **It is not end-to-
   end encrypted:** Google, as the host, and Vardr Labs, as the operator, could
   technically access it. See THREAT_MODEL.md for precisely what that does and
   does not mean. Without an account, nothing leaves your phone.

## What would change one

Nothing that follows makes the above negotiable: ads or data sale are never
compatible with this project. Donations, a paid hosted sync tier that changes
nothing about the app itself, or a one-time purchase are the only funding
paths considered compatible (SPEC §17.6).

## Amendments

Every change to the list above is recorded here, dated, with the ADR that
decided it.

- **2026-09-14: #6 gains one exception: the sign-in provider you choose.**
  Before, #6 said only "The app talks to your chosen sync backend and nothing
  else", which no Google or Apple sign-in can satisfy. SKIPTI had not been
  released, so no build ever made the old promise. The exception is narrow:
  only at your tap, only through the system's sign-in screen, `openid` scope
  only. [ADR-0035](docs/adr/0035-sign-in-provider-exception.md) lists every
  constraint.
- **2026-09-29: #7 holds in full with Maximum security on.** A new phone
  signed in to your account now gets your data automatically from a phone
  that already has it, which shows a notice; before, the old phone had to
  approve it after you compared six words. That approval is now the
  **Maximum security** setting, off unless you turn it on. With it off,
  whoever runs the sync server could publish a "phone" of its own and be
  given the key. The founder chose this (convenience by default, the full
  guarantee one switch away) before any public release.
  [ADR-0039](docs/adr/0039-new-phones-join-automatically.md) records it.
- **2026-10-01: #5, #6 and #7 change: Firebase replaces the relay.** SKIPTI's
  own relay stored only ciphertext, but sharing keys between people's phones
  never worked reliably in testing, and the founder chose a cloud that does:
  Google Firebase. Cloud data is protected by access rules, not by end-to-end
  encryption, so #7 no longer says "we cannot read your data"; it says who
  can, and that the host and the operator technically could. #6 now names
  Firebase and the push services; #5 says groups need an account. No build
  that made the old #7 promise was ever released publicly (internal testing
  only). [ADR-0040](docs/adr/0040-firebase-replaces-the-relay.md) records it.
- **2026-10-20: #5 wording clarified, no behaviour change.** "Groups need an
  account" read as if a group on this phone alone needed one, which it never
  has. It now says what needs the account: sharing a group with other
  people's phones, because shared groups live in the cloud for everyone in
  them. The founder chose the words (decision 171); nothing about the app
  changes, so ADR-0013's fork is a clarification of the same promise.
- **2026-10-21: #5, sync waits for approval.** While SKIPTI is in early
  testing, a new account waits for the operator's approval before anything
  is kept in the cloud: no sync, no shared groups, no invites. The ledger on
  the phone works fully meanwhile, so the first sentence of #5 holds as it
  was. "Is on once you have one" became "is on once your account is
  approved". The founder chose the words (decisions 228 and 234), before the
  gate was released; the design is
  `docs/superpowers/specs/2026-10-21-approval-gate-design.md` (D-254).
