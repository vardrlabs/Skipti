# ADR-0039: A new phone on your account gets your data automatically; Maximum security asks first

Date: 2026-09-29
Status: Accepted (the founder's decision, 2026-09-29)

## Context

Every phone of an account seals its sync log with one account key. D-123
gave that key to a new phone only when an existing phone wrapped it to the
new phone's published agreement key, and D-124 made that an explicit
approval: both phones show six words, the person compares them, and only
then is the key wrapped. That is SPEC A-01's defence: the relay (the
adversary, ADR-0021) cannot slip in a key of its own, because the words
would not match. SPEC §3.5: "Adding a device is an explicit approval on an
existing device … Never a silent join."

On 2026-09-29 the founder signed in with Google on his iPhone and his Android
phone and nothing reached the Android phone. Two defects were behind it
(D-178): Apple and Google sign-ins carried no device, so no phone could
publish its key; and both apps sent every PUT without its JSON body, so the
relay refused every key publish (415) since D-123. With both fixed, the
designed approval still meant finding Settings → Devices on the old phone and
comparing six words. The founder called that an inconvenience and decided,
knowing the cost, that a new phone should get the data automatically unless
the person asks for more.

## Decision

1. **Automatic by default.** A phone that holds the account key shares it
   with every phone of the account that has published an agreement key and
   has not yet been given the key by this phone, on its next sync (every 10 s
   while SKIPTI is in front, and on pull to sync). No tap.
2. **A notice, always.** After an automatic share the old phone shows "Your
   data was shared with a new phone" with the new phone's six words and "Not
   you? Turn on Maximum security in Settings." until dismissed. A join is
   never silent.
3. **Maximum security (Settings → Privacy & security, off unless set).** With
   it on, a phone that holds the key asks first: a popup with the new phone's
   six words, "They match, share" or "Not me". "Not me" refuses that phone's
   key for good. The setting is the **account's**: a personal record (core's
   `Security` op) in the account's own log, sealed with the account key, so
   every phone of the account follows it and the relay can neither read nor
   forge it. (Built first as a per-phone switch; three phones showed that a
   phone with it off shared while the phone that had it on asked.)
4. The new phone shows "Getting your data from your other phone" with its own
   six words while it waits.
5. Sessions from Apple, Google and the email code declare their phone after
   the app's own one-account check (D-168), through `PUT /v1/session/device`:
   once per session, only a device of this account.

## Consequences

- **GUARANTEES #7 changes for phones with Maximum security off.** Whoever runs
  the relay can publish an agreement key for a "device" of your account (it
  mints sessions and knows your subject), and your phone would wrap the
  account key to it on its next sync. That party could then open everything
  in your account log: your personal ledger and every group key in it. The
  notice shows it happened; it does not stop it. With Maximum security on,
  A-01's defence is exactly as before. GUARANTEES.md's amendment says this in
  plain words, and THREAT_MODEL.md's hosted-instance section names it.
- SPEC §3.5's "explicit approval … Never a silent join" becomes the Maximum
  security behaviour; the default is automatic with a notice.
- A phone that joined after syncing under its own key pushes its records
  again under the account key; entries nobody can open are skipped by every
  pull instead of stopping it.
- Not built: revoking a device (SPEC §3.5's Revoke). "Not you?" can stop
  future shares (Maximum security) but cannot take back a key already given.
- Turning Maximum security on reaches the other phones with their next sync
  (every 10 s while open); a new phone that appears inside that window can
  still be shared with automatically by a phone that has not yet heard.
- The key envelope now also names the phones the sharing phone already
  shares with, so a phone that joins does not announce them as new. That list
  is not sealed; a relay could edit it, which would only hide a notice on the
  joining phone.
