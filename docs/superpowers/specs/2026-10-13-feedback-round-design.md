# Founder feedback round, 2026-10-13: design

The founder's 15 items (verbatim in `.drafts/feedback-2026-10-13.md`, git-excluded) after 0.4.0's Phase E. This file
collects each sub-project's design as the founder approves it. A section marked **approved** is ready for a plan; one
marked **open** is not.

## Decomposition and order (approved 2026-10-13)

| Sub-project | Items | State |
|---|---|---|
| A. Phase E fixes (D-204: push) | E5 | **shipped** (D-204) |
| B. Re-tap a tab to go back to its first screen | 3 | **shipped** (D-205) |
| G. Friction and gamification audit, a report | 10 | **done**: https://claude.ai/artifact/D69teLiNEycxDtjs7PdWLR |
| D. You and Settings: profile hero, greeting, grouped settings, completion ring, avatar sync | 4, 5, 8, 9 | **approved** (look book, below); D1 **shipped** (D-206) |
| E. Look and feel: bolder icons, themes (Norse, Vardr Labs, Aurora), confirmation animations | 6, 11, 12 | **approved** (look book v2.1, below) |
| H. A "Just me" ledger (personal expenses without a group) | audit | **decided** (founder, 2026-10-13); design open |
| C. People and usernames: add by @username first, remove a person, username at sign-in, suggestions, one change, blocked words | 1, 2, 7, 14, 15 | C1 and C2 **shipped** (D-207, D-209); C3 **approved** 2026-10-13 (below), building as D-210 |
| F. Welcome guide | 7 | after D and E |

Item 13 (username safety) needed no work: uniqueness is the document id `usernames/{handle}`, claimed inside a
Firestore transaction by `setUsername`; clients cannot write `usernames/` (rules). No Redis.

Standing constraint for D: GUARANTEES #5. A profile-completion ring must be complete for someone with no account; account
steps appear only after the person has chosen to sign in. Changing that is a fork (an ADR), not a design detail.

## B. Re-tap a tab (approved 2026-10-13)

**Behaviour (both shells, identical):**
1. Tapping the tab you are already in, while deeper than its first screen, returns to that tab's first screen.
2. Tapping it again at the first screen scrolls that screen to the top.
3. Tapping a different tab is unchanged: every tab keeps its place.
4. The raised + is not a tab and is unchanged.

**iOS approach (chosen over path navigation):** each tab's `NavigationStack` carries `.id(rootToken[tab])`; a re-tap
while deeper bumps that tab's token, rebuilding the stack at its root (a short cross-fade; none under Reduce Motion).
"Deeper" is known from a per-tab depth the shell already can observe, or simply: a re-tap always rebuilds, and a re-tap
at the root also scrolls to top. Path-driven navigation (native slide-back) was rejected: it rewires all 31
closure-style `NavigationLink`s for a convenience.

**Android:** the twin on the Compose shell (each tab's own route state reset to its start; a re-tap at the start scrolls
to top).

**Tests:** the shell's re-tap rule as a pure function on both shells (selected tab, tapped tab, depth > 0 -> rebuild /
scroll / switch), with a negative (tapping another tab never rebuilds); a sabotage that makes a re-tap switch-only.
**Look:** on the simulator and emulator: Groups -> a group -> tap Groups -> the list; tap again -> top.

## D and E. You, Settings and the look (approved 2026-10-13, look book v2.1)

**The approved artifact:** https://claude.ai/artifact/EGKh2cQvJkT6d6wESv2QUk (version 7; source
`.drafts/lookbook/skipti-lookbook.html`). It is the visual reference for every unit below; where this text and the
look book disagree, ask. Round 1 was rejected as generic: purple is out (the founder's own web standards ban purple
primaries and purple gradients, `Vardr_Labs_Website/CLAUDEwebdesign.md` §1).

**Founder decisions (2026-10-13):** three themes ship (Aurora default, Norse, Vardr Labs); all four gamified touches
(completion ring, settled celebration, settled meter, group milestones); streaks and spending badges are out; a
"Just me" ledger is added (sub-project H).

### The three themes

- **Aurora** (default). A real aurora borealis: green-dominant curtains with vertical ray streaks and a faint pink
  upper fringe over a starry night sky; light mode is an arctic dawn (pale `#EEF5F3`, curtains faint). Accent aurora
  green (`#0C8A6C` light, `#45E6B0` dark). The Balance hero is always a night sky with its own curtains. No purple.
- **Norse**. Always dark (no light version: black and gold is the theme). Black `#08080A`, bone text `#EDE6D3`, gold
  `#CDA750`, silver `#8E939B`. Runes carry meaning, never ornament alone: tabs are ᚠ Fehu (wealth, Balance), ᚷ Gebo
  (gift and exchange, Expenses), ᛗ Mannaz (people, Groups), ᛏ Tiwaz (justice, Settle up); the completion ring is the
  24-rune Elder Futhark lit in gold as steps complete; ᛫ (the runic separator) replaces the middle dot in meta lines;
  ᛟ marks section labels; cards have notched corners, tiles are diamonds, the + is a gold diamond; runes drift slowly
  up behind every screen; a settled group bursts into runes. Display face: a small-caps serif (Cormorant SC in the look
  book); figures stay in Space Grotesk.
- **Vardr Labs**. vardrlabs.com's own language: `#0b0f13` / `#e8ecef` ground, steel `#9db0bf` / `#4c6172` accent,
  cyan `#5ad0f2` / `#0a6d93` and ember `#e89055` / `#9a4d16` for owed and owing, Space Grotesk with IBM Plex Mono
  kickers spaced .22em, corner frame marks on cards, square badges with a dot. The Balance hero has the world's currency signs
  ($ ₹ € £ ¥ 元 ₩ ₽ ฿ ₺ ₦ ₱ ₪ R$ A$ and more, IBM Plex Mono) tossed across the card, each drifting on its own slow,
  smooth path at its own size and depth, mostly steel with a few in cyan, quieter behind the balance; the major currencies (₹ largest and
  in cyan, then $, €, £, ¥) always float in clear view on the card's right side (founder: the rupee must be seen); a light hero in
  light mode. (Founder, 2026-10-13: a sphere of signs was cramped and a turning globe "messing stuff up"; this is the
  one he asked for.) The + is a machined hex bolt head (brushed steel, reticle cross) that turns 30
  degrees when pressed.

### Screens

- **Home** gains the greeting ("Good evening, Prithvi", from the phone's clock and the profile name; nothing leaves
  the phone), the theme's hero, "Your ledgers" with Just me first and a settled meter on each, and a "Lately" feed.
- **You** (the profile glyph opens it): the photo inside the completion ring, name, @username (or "On this phone
  only"), "N of M done", one "next step" card, then five groups: Profile and account, Appearance, Security, Your data,
  About. This replaces the one long Settings page (AccountHubScreen); every existing row keeps a home.
- **Appearance**: theme cards, System / Light / Dark (Norse disables it), SKIPTI's own text size (starts at the
  phone's size, scales SKIPTI only), Haptics on or off.
- **Security**: app lock, hide during recording, Your phones (account only); then, set apart, erase this phone's
  ledger and delete account (account only).
- **A group's page**: the settled meter, a milestone card when one is reached, and the celebration when the last debt
  is settled.

### The completion ring (GUARANTEES #5)

Without an account, 5 steps, complete without signing in: your name, a photo or emoji, a default currency, app lock,
a first group. Signed in adds 2: a username, notifications. Steps already done count from the start (the endowed
progress effect works only with real progress; `.drafts/feedback-research-2026-10-13.md`). The ring never mentions
signing in to someone without an account.

### Done moments, celebrations, milestones, haptics

- **Big** (once-only moments: first group, signed in, a group settled, a ledger erased): a check that draws itself,
  with the theme's ring (Norse: the Futhark turning).
- **Small** (everyday: expense added, person added, picture or username saved): a bar with a check and one line.
- **Settled celebration**: when a group reaches zero, a burst in the theme's material (Aurora: green sparks; Norse:
  runes; Vardr Labs: cyan points) and "Goa trip is all settled".
- **Milestones**: rare and once each (100 expenses together, a year since the first expense); never about money
  spent.
- **Haptics**: light on a tab change, success on every done moment, warning before anything destructive; one switch
  in Appearance. Reduce Motion turns every animation into its still end state; haptics stay.

### Constraints for every unit

- GUARANTEES #6: every font and glyph is bundled; nothing is fetched at run time (the look book used Google Fonts; the
  app may not). A bundled font is a licence entry (`NOTICE`, `scripts/licences.py`). iOS must be checked for a Runic
  block font before Norse is built; bundle one (Noto Sans Runic, OFL) if the system lacks it.
- Every colour pair is measured by the AppearanceTests on both shells, per theme and mode (WCAG AA).
- Money colours stay semantic per theme (owed and owing distinct from each other and readable on every surface).
- Twins: iOS and Android identical in behaviour, labels and order.
- A new ADR (0041, selectable themes) supersedes ADR-0038's single palette; ADR-0038's glass becomes Aurora's only.

### Units, in build order

1. **D1 You and Settings regroup** (both shells): the You screen with the profile hero, the five groups and their
   pages; no new data. Then the greeting on Home.
2. **D2 Profile picture sync** (item 8): the avatar becomes a personal record so it syncs like the name; core change.
3. **E1 Theme engine and Appearance**: token sets for three themes by two modes, the picker, text size, ADR-0041,
   AppearanceTests per pair; Aurora v2 as the new default palette.
4. **E2 Theme art**: Aurora curtains, Norse rune drift and rune details (tabs, ring, separators, shapes), Vardr Labs
   point cloud, frame marks and the hex-bolt +; Reduce Motion; performance on a real phone.
5. **E3 Done moments and haptics**: the shared component in two sizes, wired to every action the audit found silent.
6. **E4 Ring, meter, celebration, milestones**: the completion ring, settled meter, settled celebration, milestones.

## H. "Just me" ledger (decided 2026-10-13; design open)

Personal expenses without a group, always present, shown first among "Your ledgers". Open questions for its own
brainstorm: is it a built-in local group or a distinct record kind; does it sync to your own account (likely yes, as
your personal records); how it reads in exports and balances (it has no debts). Core work; after D1.

## C. People and usernames (decisions 2026-10-13; C1 design next)

**Split and order (founder):** C1 adding people, then C2 usernames, then C3 roles and removal.

- **C1 Adding people.** Adding a person asks for a name and an optional @username; a username is looked up and shown
  as a preview card before adding; the person can be added and invited (in-app invitation and push) in one step.
  **Recents** (founder): people from your other groups are offered when adding, so someone added before is one tap.
  Finding by username moves here from the bottom of the Invite screen (Phase E6's failure).
- **C2 Usernames.** Asked for at sign-in, with suggestions from your name that are checked as available; set once and
  changed at most once more; the approved blocklist (`.drafts/username-blocklist-draft.md`, approved 2026-10-13:
  two tiers, normalised, cloud decides, phone mirrors, usernames only).
- **C3 Roles and removal** (founder, 2026-10-13). The group's creator is its **owner**; the owner can make other
  members **admins**. Any member can ask to remove someone; an owner or admin approves (and removes directly). If
  the person still owes or is owed, the remover chooses to **settle first** or **redistribute** their balance among
  remaining members of the remover's choosing (a new core ledger operation; every minor unit accounted for). Defaults
  (founder's yes): when the owner leaves or deletes their account, ownership passes to the longest-standing admin,
  else the longest-standing member; a removed person is notified and keeps their own copy of the history. Membership
  and roles change only through Cloud Functions (ADR-0040), each with refusal tests.


### C2 Usernames: design (approved 2026-10-13, mock-up v2 https://claude.ai/artifact/MBhJTr3LbzmgWg2EaFBxJU)

**Founder's decisions (2026-10-13, night):**
- Asked right after Apple or Google sign-in succeeds, in place of "You're signed in". **Required, no close button.**
  An account that already has a username (a second phone) skips it.
- An account signed in **before** this ships, with no username, gets a **card** on Home and You ("Pick your
  username"), never a screen at app open: a required screen there would stand in front of logging an expense
  (GUARANTEES #2).
- No name on the phone yet: **the name is asked first, on the same screen**; suggestions are built from it, and it
  becomes the name people see when they find you.
- **Three suggestions** ("Suggested for you", the founder's wording), built from the name and checked by the cloud,
  so every chip shown is free; one tap fills the field.
- **Live availability while typing** (from 3 characters, after a 400 ms pause): available, taken, not allowed. Shape
  and blocked words are checked on the phone at once; "taken" needs the cloud. The founder asked whether Redis would
  be needed at scale: no. A username is typed about twice in an account's life (set, one change), so roughly 5-10
  checks per account ever, each one Function call and one document read: a few dollars per million sign-ups, inside
  the free allowances at our size; a managed Redis (Memorystore) costs tens of dollars a month just to exist.
- **Set once, changed once.** The first set is free; one change after that; a confirm step before it ("You can
  change your username once. After this, @new is yours for good, and @old is free for anyone to take."). You shows
  "1 change left" until it is used. **The old username is released at once.** A username can **never be removed**,
  only changed; deleting the account frees it. The three accounts that already have a username keep their one change.
- **The blocklist** as approved (`.drafts/username-blocklist-draft.md`): two tiers, normalised, the cloud decides,
  the phones mirror it for an instant answer, usernames only. A refusal never says which word matched; a reserved
  name and a blocked word read the same: "That username is not available."

**Cloud** (`firebase/functions/people.js` decides, `index.js` reads and writes; every rule node-tested):
- The list is one JSON file, `firebase/functions/blocklist/usernames.json` (as built: `reserved` whole handle only,
  `anywhere`, `whole`, `raw`; today's `RESERVED` folds in; tier A terms inside real names moved to whole-word, founder
  2026-10-13). Both apps bundle a byte-identical copy; a test on each shell compares them. The repo is public, so the list is
  public: it has to ship in the apps anyway.
- `people.blocked(handle)`: lower case; leetspeak undone (0 o, 1 i, 3 e, 4 a, 5 s, 7 t, 8 b); repeated letters
  collapsed (the terms are normalised the same way); a second variant without `_` and digits. Tier A blocks a term
  found anywhere in a variant; tier B only when the whole handle, or one of its parts split on `_` and digits, equals
  the term. Goldens re-derived in Python: `classic`, `peacock`, `grape`, `assam`, `method`, `prithvi_saha` allowed;
  `f_u_c_k`, `fuuuck`, `b1tch`, `dick69`, `support` refused.
- `setUsername` refuses: a blocked or reserved handle (`invalid-argument`, "unavailable"); clearing once set
  (`failed-precondition`); a second change (`failed-precondition`). A change sets `usernameChanged: true` on
  `users/{uid}` (only Functions write it; the rules already let a phone change only `name` and `updatedAt`).
- New callable `checkUsername({username})` -> `{available, reason?}` (reason `taken`, `shape` or `unavailable`, never
  the matched word); new callable `suggestUsernames({name})` -> up to 3 free
  handles from candidates built from the name (`prithvi`, `prithvisaha`, `prithvi_saha`, then `psaha`, `prithvi_s`,
  `prithvi` + 2 digits), never a blocked one. Both charge a new daily `checks` limit (100).
- Deploy (the founder's yes): the two new callables and `setUsername`; CLOUD-SECURITY rows; the three live usernames
  checked against the list at deploy.

**Phones** (twinned, same copy and order): `UsernameRules` (shape + the bundled list, the instant answer);
`UsernameStep` (the screen in the mock-up, shown by the sign-in flow); You's Profile and account row and the change
screen with its confirm; the Home and You card. Tests: rules goldens on both shells (same goldens as the cloud's),
the bundled-list identity test, and the step's decisions (when it shows, when the card shows) as pure functions.

**Not in C2:** usernames in display names (names stay free text, the founder's choice); a report button; deleting a
group and its people (the founder's request tonight, goes with C3).

### C3 Roles, removal and deleting a group: design (approved 2026-10-13, mock-up v2 https://claude.ai/artifact/GgYskSDAUMFMNvJoDGpM9x)

**Founder's decisions (2026-10-13, popups), on top of the C3 bullet above:**
- **Delete a group:** the owner only. The cloud copy is erased and nobody can add to it again; every member's phone
  keeps a **read-only copy** marked "Deleted by <owner>", which they can export or remove from their own phone.
  Allowed with open balances: the confirm names them, and they stay as they are on every copy.
- **No leaving.** A member who wants out asks for their own removal ("Ask to be removed" on their own row).
- **A removal request** is seen by the asker, the owner, the admins **and the person named** (each gets a push); the
  person named can settle up meanwhile but cannot approve or decline. The owner or an admin approves (straight into
  the removal) or declines. One open request per person. Owners and admins remove directly, without a request.
- **One owner.** Only the owner makes someone else the owner ("Make owner", with a confirm); the old owner becomes an
  admin. Ownership also passes at account deletion (the longest-standing admin, else the longest-standing member).
- **Admins can make other admins** (with a confirm); only the owner takes admin away, so admins cannot demote each
  other. Only people who joined on their phone can be admins. Admins remove members, never the owner or another admin;
  nobody removes the owner.
- **A person's sheet** (mock-up v2): Remove comes first; the role actions sit below under "Role", each behind a
  confirm, so a stray tap never changes a role (the founder's change to v1).
- **Redistribute:** tick who takes on the balance; equal by default, to the minor unit; **Edit amounts** to type each
  share, and Remove turns on only when the shares add up exactly. **Settle first** opens Settle up prefilled with the
  full balance, then the removal goes ahead.
- **Approach:** the removal reaches the history **through a Function, atomically** (the founder chose it over the phone
  writing records and a Function changing only membership, and over storing redistribution as payments).
- **skipti-core 0.1.0 -> 0.2.0** in D-210: the record format gains a type that older cores skip; About shows it.

**Cloud** (`firebase/functions`: the decisions in a pure module, `index.js` reads and writes; every rule node-tested):
- `groups/{id}` gains, written only by Functions: `owner` (one uid; when absent, `createdBy` stands in, so existing
  groups need no migration), `admins` (uids), `former` (uid -> `{at, by}`). "Longest-standing" is `memberUids` order
  (`arrayUnion` appends, so it is join order).
- Callables, each checking the caller's role inside a transaction:
  `setAdmin({group, uid, on})` (on: owner or admin; off: owner only; the target must be in `memberUids`);
  `makeOwner({group, uid})` (owner only; the old owner joins `admins`);
  `requestRemoval({group, member})` (any member, themselves included; writes `groups/{g}/requests/{member}`, the
  member's participant id as the document id, so one open request per person; pushes the owner, admins and the person
  named when they have an account; a request naming the owner is refused, and one naming an admin can be approved only by the owner);
  `declineRemoval({group, member})` (owner or admin; deletes the request; tells the asker and the person);
  `removeMember({group, member, records})` (owner or admin; never the owner; an admin never removes an admin; in one
  transaction: writes the phone-built records with type `removal`, the caller as author and the server's time, moves the
  uid, if the member has one, from `memberUids` to `former`, deletes an open request, writes a notice to the removed
  account and pushes it); `deleteGroup({group})` (owner only; a notice and push to every member, then the group, its
  records and requests deleted recursively, and `groups/{id}/picture.jpg` in Storage).
- Purge (`account.groupsPlan`): when the purged account owns a group that others remain in, `owner` passes to the first
  admin in `memberUids` order, else the first remaining member.
- **Rules:** a member reads the group document; a `former` uid reads the group document and the records whose
  `createdAt` is at or before their `former.at` (their phone catches up to the removal, including its records, then
  stops); `requests/` is readable by the asker, the person named, the owner and the admins, written only by Functions;
  a phone can never create a record of type `removal`; `users/{uid}/notices/{id}` is written by Functions and read and
  deleted by the account. Every rule gets a refusal test.

**Core** (`skipti-core` 0.2.0):
- Record type `0x06 Redistribution { group, from, currency, shares: [(member, amount)] }`: balances apply each share as
  that member taking on part of `from`'s balance; the shares sum exactly to `from`'s balance in that currency, so
  `from` ends at zero. A person with balances in several currencies gets one record per currency. Integer money only
  (ADR-0002). The history line: "Sam's ₹1,000.00 moved to Prithvi, Rahul and Asha".
- `ffi_redistribute_equal` uses hlutr's largest remainder (ties by ascending participant id, so every phone agrees);
  a check function validates typed shares (sum exact, members in the group, never `from`, no negatives).
- In a cloud group, the projection honours `RemoveMember` and `Redistribution` only from records of type `removal`
  (written by the Function); a local-only group keeps D-186's path (it is yours, so no roles).
- A per-group export: the existing JSON and CSV export over a snapshot filtered to one group.

**Phones** (twinned, same copy and order):
- People: Owner and Admin pills (members carry none); a person's sheet by your role (Remove first, then "Role" with
  confirms); "Ask to be removed" on your own row; request cards to whoever the rules let read them.
- Removal: no balance -> a confirm; a balance -> Settle first or Redistribute (ticks, equal preview, Edit amounts), one
  section per currency, Remove on only when every section adds up.
- Group settings: Delete group only for the owner (absent for everyone else, as D-126's account controls), the open
  balances named in the confirm.
- Read-only copy: a `users/{uid}/notices` listener tells the phone it was removed or the group was deleted, by whom and
  when. The group shows the banner; Add expense, Settle up and Add people are absent; Export this group and Remove from
  this phone remain (confirmed; deletes only that group's local records: an explicit, confirmed erase, D-127).
- Pushes: a request made, a request declined, removed, made admin or owner, the group deleted; a tap opens the group.

**Proof:** core goldens re-derived in Python, with negatives (shares not adding up, an unknown member, a cloud-group
removal not of type `removal`); emulator refusal tests for every callable and rule (an admin making an owner or taking
admin away, removing the owner or an admin, a phone writing `removal`, a former member reading past `former.at`, a
non-owner deleting, a stranger reading a request); twin pure-function tests on both shells (what a sheet offers per
role, who sees a request, when Remove turns on); a sabotage matrix; a three-account look (owner, asker, the person
named) on throwaway devices against the local emulators; the full gate. Deploy (the founder's yes): the new
Functions, purge's change and the rules; CLOUD-SECURITY rows the same day.

**Changed while planning (2026-10-13, the plan `docs/superpowers/plans/2026-10-13-c3-roles-removal.md`; Claude's
implementation calls, the behaviour above unchanged):** the Function's mark is a FIELD, `removal: true`, not a record
type `removal`: phones read `type` as a number and move their cursor past a record they cannot parse, and the rules'
`hasOnly` field list already refuses the field from any phone. `requests/` is Functions-only; the asker, the owner, the
admins and the person named see a request through a notice each (one listener per phone instead of two queries). The
phones never hold uids, so the role pills come from a members-only `groupRoles` callable (it also covers groups made
before D-210, through `createdBy`). `removeMember` also deletes the person's unused invites, and `acceptInvite` clears
`former` when someone is invited back. Settle first records the removed person's payments from core's settle-up plan
behind one confirm (no prefilled Settle up screen exists). A small Group settings screen (name, picture, settles in,
Delete group) is new: none existed, and the mock-up shows Delete group there.

**Not in C3:** leaving a group (the founder: no); a grace period before a deleted group's cloud copy goes (members'
copies hold what their phones had synced); old builds (0.4.0) skip type `0x06`, so testers update before anyone
redistributes; notices never expire yet.
