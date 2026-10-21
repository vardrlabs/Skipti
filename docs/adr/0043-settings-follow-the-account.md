# ADR-0043: Settings follow the account

Status: Accepted (founder-requested 2026-10-20, D-244; reverses ADR-0041's "chosen per phone, never synced" for the
theme, the mode, the text size and the app icon, and SPEC section 5.2's "never synced" for the default currency; no
guarantee changes)

## Context

ADR-0041 made the theme, light or dark, the text size and (D-216) the app icon choices of one phone: stored outside
the vault, never synced. SPEC section 5.2 (D-165) did the same for the default currency. Haptics and Less motion
(D-231) followed that pattern. Someone with two phones on one account therefore set each phone up by hand, and a
change on one never reached the other.

On 2026-10-20 the founder asked that the theme, light or dark, text size, Less motion, haptics, the app icon and the
default currency follow the account to its other phones, that the icon on another phone change only when SKIPTI comes
to the front, and that the public privacy page say so in his words: "Your app settings: theme, light or dark, text
size, Less motion, haptics, app icon and default currency, so your other phones match."

The account's own log already carries personal preferences that follow it: Maximum security (D-178) and your picture
(D-211, `GroupOp::Avatar`), each a personal record whose latest write wins. This decision uses the same shape.

## Decision

1. **With an account, a phone's plain preferences follow the account**: the theme (`appearance.theme`), light or
   dark (`appearance.mode`), the text size (`appearance.textSize`, steps relative to each phone's own system size),
   Less motion (`appearance.lessMotion`, explicit values only, never a phone's low-memory default), haptics
   (`appearance.haptics`), the app icon choice (`appearance.icon`) and the default currency (`money.default-currency`,
   explicit only). The keys are the iOS preference keys and are permanent: they are on the wire.
2. **Each is a personal `setting` record in the account's own log**: group operation record type 3, header version 1,
   no group, wire `{"op":"setting","key":"appearance.theme","value":"norse"}`. Key and value are strings, so a new key
   or a new value never breaks an older build's decode. Core checks every one: the key 1 to 48 characters of
   `A-Z a-z 0-9 . -`, the value 1 to 64 characters, trimmed, no control characters (the member name rule), and a
   default currency must be a code core knows. A `setting` record in a group's log is refused, as every personal
   operation is.
3. **Each key keeps its latest write** (the record identity, as for every latest-write-wins value in the log), and the
   projection lists every key with its winning value and record id. Keys are independent: a change to one never moves
   another. A key this build does not know is listed and left alone.
4. **Applied on arrival and at launch.** A phone applies a winning value it understands and remembers which record it
   applied; a value it does not understand (an unknown theme, an out-of-range step, a currency it does not know) is
   skipped and applied after an update, never read as a default. Applying a value never writes a record.
5. **At a phone's first sign-in the account's values win.** The account's values are applied; keys the account does
   not hold yet and this phone set by hand are written as records. The first phone to update to a build with this
   decision therefore fills the account.
6. **An arrived icon change applies when SKIPTI next comes to the front**, never at the moment it arrives and never
   in the background: iOS shows a system alert on every icon change, and Android's launcher swaps the entry.
7. **Without an account nothing is written; with sync off nothing leaves the phone.** A change made with sync off is
   written to the log and goes up when sync is turned on again. There is no per-phone opt-out switch.
8. **Per phone, unchanged**: the app lock and its timeout, screen privacy, the sync switch, notification permission,
   the device name and every internal mark.

## Consequences

- A change on one phone reaches the account's other phones the next time they sync, and shows at once (the icon at
  the next coming to the front).
- Guarantee 5 holds by construction: the records are written only with an account and travel only through sync, which
  is off in one tap. Guarantee 7 holds as for every personal record today: only the account can read them under the
  access rules, and the cloud is not end-to-end encrypted, so the operator technically could. The privacy page lists
  the app settings among what the account holds.
- Firestore's rules do not change: a `setting` record is a type 3 record with header version 1, which the rules
  already accept by shape and size. A phone on 0.5.0 stores the record and lists it as rejected (it cannot read the
  new operation); its own changes do not sync until it updates. No cursor reset is needed: no core rule is relaxed and
  the header version is unchanged.
- Each change adds one small record to the account's own log; phones send only the latest pending write per key.
- An explicit Less motion off on one phone turns Less motion off on a low-memory phone of the same account (decision
  1 syncs explicit values both ways).
- On Android 12 and later, an arrived change of theme or mode that turns the app from light to dark makes the activity
  again once (ADR-0042, decision 78). Some launchers drop the home-screen shortcut when the icon alias changes, as they
  do when the person changes it by hand (ADR-0041, App icon).

## What it changes

- ADR-0041, decision 1 ("chosen per phone ... never synced") and the App icon section ("never synced", and the icon
  changing only when the Appearance page closes, which gains the arrival exception of decision 6): amended by this
  decision.
- SPEC section 5.2's "the default currency is a per-phone preference in Settings, never synced": changed by this
  decision.
