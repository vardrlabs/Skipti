# ADR-0037: Shared groups: group keys, group logs, invites

Date: 2026-09-24
Status: Accepted (the founder's decisions, 2026-09-24; the technical shape below
follows from them, SPEC §2–§3 and ADR-0005, and is recorded for review)

## Context

Groups sync between one person's own devices (D-152–D-154), sealed with the
account's sync key in the account's own log. Groups do not yet sync between
PEOPLE: nobody else can read a group, and a member such as "Rahul" is only a
name typed by whoever added him (D-134: "add by name, link later").

SPEC already fixed the cryptography: each group has its own random 32-byte key,
wrapped once per member device with X25519 → HKDF("skipti/v1/groupwrap" ‖ G ‖
epoch) → AES-GCM (ADR-0005); peers are verified with a six-word safety phrase,
by QR scan when together or read aloud when apart, and unverified members show
UNVERIFIED on the group screen (SPEC §2). The relay already keeps per-group
envelope sets with membership derived from them (D-41): the first push creates
a group, members add members, `/replace` rotates, reads are per recipient.

SPEC left open how a person is invited, and how they become the member already
in the ledger. The founder decided (2026-09-24):

1. **QR together, link apart, and an in-app invite.** Adding someone makes them
   part of the group AND its ledger at once, whether they accept now, later or
   never, so nobody avoids a share by ignoring an invite. Accepting only links
   their account and devices.
2. **Invite a named person.** An invite is bound to one member ("Rahul").
3. **In-app invites reach people you already share a group with**, whose
   account is known through that group: no directory, no lookup by email or
   phone, and the relay learns nothing new.
4. **The in-app invite is a banner inside SKIPTI** when they next open it. No
   APNs or FCM (GUARANTEES #6, ADR-0012).

## Decision

### Group keys and group logs
- Every group gets a group key (random 32 bytes, epoch 0) the first time the
  account syncs it. A group never shared simply has only your devices as
  recipients: one code path for every group.
- Records scoped to a group (expenses, settlements, revisions, group
  operations) are sealed with the group key and pushed to that group's log,
  `/v1/groups/{G}/log`. Personal records ("you", personal expenses) stay in the
  account's own log, sealed with the account key. Records already in the
  account log are pushed to their group log once; readers merge by record
  identity, so a record seen in both places counts once.
- The relay authorises a group log exactly like envelope pushes: the caller's
  account must own a device in that group's envelope set. A group's log is not
  removed when one member deletes their account; the last member takes it with
  them (SPEC §4.4).

### Invites (link and QR are the same link)
- An invite is a 32-byte secret `s`, carried in the URL FRAGMENT
  (`https://skipti.vardrlabs.com/join#…`), which browsers and the relay never
  see. The QR code on the inviter's screen is that link; the joiner's own
  camera opens it, so SKIPTI needs no camera permission.
- From `s`: `token = HKDF(s, "skipti/v1/invite-token")` and
  `key = HKDF(s, "skipti/v1/invite-key")`. The inviter registers the invite
  with the relay: SHA-256(token), an expiry, and a payload sealed with `key`
  (the group key and epoch, the group id, the invited member's id and name,
  and the inviter device's agreement public key).
- The joiner (signed in; remote membership needs an account, SPEC §4.1)
  presents `token` to read the payload, opens it with `key`, wraps the group
  key to its own devices, and asks the relay to ADMIT those devices to the
  group's envelope set with the same token. The invite is single-use: admission
  burns it. The inviter does not need to be online, which is what "accept
  later" requires.
- The joiner then writes a `claim` operation into the group log ("member M is
  me", with its devices' agreement public keys). The joiner's app reads M as
  "you" in that group (D-154's merge); everyone else sees M linked to a real
  account, with the devices whose six words they can verify.
- Verification (SPEC §2): the joiner learns the inviter's device key from the
  link itself, so the relay cannot substitute it. The inviter confirms the
  joiner's six words; until then the joiner shows UNVERIFIED. In person both
  screens show their six words side by side and one tap confirms.

### In-app invites
- An inbox entry is the invite secret sealed to each of the invitee's devices
  (X25519, as envelopes are). The relay accepts it only if the inviter's
  account and each recipient device's account already share a group (it
  checks its own envelope sets). The invitee's app shows a banner when it
  syncs: "Tim added you to Goa Trip. Accept?" Accepting redeems the invite as
  above.

### What the relay learns
Group ids, member counts and envelope fan-out it already saw (SPEC §2.1); that
an invite exists for a group, when it was redeemed, and which account's devices
were admitted; for in-app invites, that two accounts already sharing a group
exchanged one. Never a name, an amount, the group key or the invite secret.

## Consequences
- The relay needs group logs, invites, admission and an inbox (schema change;
  the founder deploys it).
- The shells need group keys, group-log sync (and the one-time move of group
  records), the invite and join screens, the banner, and verification states.
- A link is a bearer capability until it is redeemed or expires: whoever holds
  it can join as the named person. That is why the joiner starts UNVERIFIED
  when the link travelled through a messenger, and why invites expire.
- Removing a member and rotating the key (SPEC §3.6) is later work; epoch is
  already in every record header.

## Refinements while building (D-157, D-158, D-160)

Recorded here so the ADR stays the single description of the design:

- **Your own devices get a group's key from your account's own log**, as a
  personal record sealed with the account key, not from an envelope. The
  relay is the adversary (ADR-0021), and an envelope's sender key comes from
  the relay's device list; a key in the account log comes from a device you
  already approved. The relay still needs one envelope row per ACCOUNT per
  group, because it authorises a group by its envelope set (ADR-0022); that
  row is a proper wrap of the key to the device that wrote it. Readers try
  every key the account holds for a group, so two devices minting a key at
  once lose nothing.
- **The account's own log keeps every record you write**, group records
  included: it is your backup. A group's log carries that group's records
  for the other members. A record seen in both counts once.
- **What a group's log may bring in** is checked on arrival. The record's
  scope must be that group, and so must the group inside its payload. A
  personal record ("you", a key) is refused. A record that claims to be
  written by this device is refused, because otherwise it could stall this
  device's pushes. A timestamp more than a day ahead is applied but never
  moves this device's clock. Core applies an edit or a delete only when its
  scope matches the expense it changes, so no member can reach an expense in
  another group or a personal one.
- **A joiner writes their own "you" id** into the shared group. Everyone
  else's group knows them as the member they claimed, so the claim (which
  carries the joiner's "you" id) makes that id read as the member, in that
  group only: payer, shares, settlements, "added by", renames.
- **In-app invites, as built (D-160).** The people an invite can reach are
  other accounts' claims in your other groups, with the devices those claims
  list; a device missing from its account's claim adds itself on its next
  sync. The link is sealed to each device and posted one device per request,
  so a device someone no longer has is refused alone. A device reads only its
  own inbox entries (a device-less session is shown every device's, and
  another device's invite is not this one's to discard), and uses an entry id
  in a path only if it has the relay's own shape. The banner's "Not now" puts
  the invite aside until the next launch; it lapses with the invite after 7
  days, and there is no separate decline.

## Tripwires
- The invite secret, the group key or a name reaching the relay in the clear.
- A path that lets someone who shares no group with you drop an invite into
  your inbox (that would be a directory by another name).
- A camera, contacts or notification permission added for this.
- A member who has not accepted being left out of a group's balances.
