# D2: your name and picture, on your phones and in your groups (design)

Approved by the founder 2026-10-14 (popups). Feedback-round item 8 ("avatar sync"), widened by the founder: a joined
person's own name replaces the name the inviter typed. Builds as D-211 after D-210. Nothing deploys until 0.5.0.

## Founder's decisions (2026-10-14)

1. **Who sees the picture:** your own phones see it in full and you can enlarge it; your groups' members see a small
   preview and cannot enlarge it.
2. **Old pictures:** records are written once. A replaced or removed picture stays in your private cloud history
   (only you read it) until you delete your account. Accepted, and said plainly in the app.
3. **Server-bound:** your preview and name reach a group only through a Function that decides which place in the
   group is yours. Nobody can put a picture or a name on someone else's row.
4. **Names:** once someone joins, their own name (the one search showed beside their @username) replaces the name
   the inviter typed, and their later changes follow in every group. Others can no longer rename a joined person.
5. **Old previews in groups (2026-10-14, after the whole-branch review):** when you share a new preview, the
   Function deletes your earlier preview records in that group in the same transaction; deleting your account deletes
   your preview records from the groups you leave. Later joiners never see an old preview; phones that already
   downloaded one keep their copy. At first sign-in only your latest picture goes up to your private history, not
   pictures you removed before you had an account.

## What exists today (survey, 2026-10-14)

- The avatar is vault kv `ledger/self-avatar` only (`Profile.swift:102`, `Profile.kt:184`): JSON
  `{kind: none|emoji|photo, emoji?, jpeg?}`, a 256 px centre-cropped square at JPEG 0.8, no byte cap. It never leaves
  the phone and is not exported. The screen says "not sent anywhere" (`Profile.swift:231,440`; `Profile.kt:282,431`),
  already half wrong: the name syncs.
- The name syncs to your own phones as the personal `GroupOp::You { id, name? }` under record type 0x03
  (`core/src/straumr/groups.rs:116`). `DeviceName` and `Security` are the precedent for a new personal op. Never add a
  field to `You`: older builds would refuse every new `You` record and lose the name.
- Group pictures (D-187) are a group-scope `GroupOp::GroupPicture` with the photo as hex inside the record
  (`check_photo`: JPEG, at most `PICTURE_MAX_BYTES` = 64,000). Cloud Storage is not used and stays unused.
- An unknown GroupOp kind is listed in the projection's `rejected` and never applied: older builds carry on.
- `RenameMember` is applied for any author today (`groups.rs:631`): any member can rename anyone.
- Group record documents pass `validRecord`'s `hasOnly([...])` (`firestore.rules:61`), so a phone can never write a
  field the list omits; that is how D-210's `removal` mark is Functions-only.
- `users/{uid}.name` (1 to 60 characters, written by the account) is the name find-people shows.

## 1. Your own phones: a personal `Avatar` record

- New personal op `GroupOp::Avatar { emoji: Option<String>, photo: Option<String> }` under 0x03, scope personal (as
  `DeviceName`). Both absent means no picture. `check_emoji` and `check_photo` apply; an emoji and a photo together
  are refused (as `GroupPicture`). Latest write wins; the projection exposes it as `avatar` on `GroupsProjectionOut`.
- The shells keep the 256 px square at JPEG 0.8 and step the quality down until it is at most 64,000 bytes (as
  `GroupPictures` does), then hex-encode it.
- **Migration:** on the first launch of the new build, if kv `ledger/self-avatar` holds a picture and no `Avatar`
  record exists, one `Avatar` record is written from it, once (a kv flag). The kv value then becomes a cache written by
  the rebuild, as `ledger/self` is for the name.
- **Without an account** the record is written the same way and never leaves the phone (GUARANTEES #5).
- **Enlarge:** tapping your own picture on You opens it full screen (your phones only). Nothing else enlarges a
  picture.
- **Export (GUARANTEES #4):** the JSON export carries the current `Avatar` (emoji or base64 JPEG); the import brings it
  back. CSV does not.

## 2. Your groups: a server-bound `MemberProfile`

- New group op `GroupOp::MemberProfile { group, member, name, emoji: Option<String>, thumb: Option<String> }` under
  0x03, scope the group. `thumb` is a JPEG, 120 px square, at most **12,000 bytes** (a new `check_thumb`: JPEG magic,
  hex, cap). An emoji and a thumb together are refused. `name` passes `ffi_member_name`'s rules.
- **The phone** builds one record per group in which it holds a place (its claimed place) and calls a new callable,
  **`shareProfile`** `{ records }` (1 to 50 records, the D-210 record shape).
- **The Function**, per record, in a transaction per group: the caller is a member; `member` is the place the server
  says the caller holds (`placeIn`, D-210); the blob is a well-formed `MemberProfile` of that group. It writes each record with
  `profile: true` and the caller as author. A group that refuses (not a member, no place, contested) is skipped and
  reported in the result `{ shared: [groupId], skipped: [groupId] }`; it never fails the others.
- **The name is account-level:** every record's `name` must equal `users/{uid}.name` exactly, or the whole call is
  refused (`failed-precondition`; the phone re-reads its profile and builds again). An account with no
  `users/{uid}.name` is refused the same way and the phone does not call until it has one (the username step asks
  for a name first, D-209).
- **The rules** need no change (`validRecord`'s `hasOnly` already refuses `profile` from a phone); a refusal test
  proves it.
- **Phones import** a `MemberProfile` only when its document carries `profile: true` (the D-210 refusal path:
  `refusal` returns the new case for an unmarked one, as for an unmarked removal).
- **When the phone calls it:** after creating a group, after joining (once the claim is written), after you change
  your name or picture, and once after updating to the build that has D2 (a kv flag: the backfill for groups joined
  before). Offline or failed: retried like the other group jobs (`GroupJobs`), never blocking the screen.
- **Without an account** nothing is called (groups need an account).

## 3. Names in a group

- **Projection:** a place with at least one marked `MemberProfile` takes its name and preview from the latest one;
  every `RenameMember` of that place is ignored, whatever its time. A place with none behaves as today.
- **Rename** is absent (not disabled, D-126) for a place with a marked profile, on both shells. People who never
  joined stay renamable.
- **Duplicates:** two joined people may carry the same name; their own names are theirs. (The "Name (2)" rule stays
  for names typed by the inviter.)
- **Removed people** keep the name and preview their last `MemberProfile` gave them.
- **Members see** the preview on People cards, member rows and the person's sheet; tapping it does nothing.

## 4. Copy (twins)

| Where | Text |
|---|---|
| You, picture footnote, with an account | `Your picture syncs to your phones. Your groups see a small preview.` |
| You, second line, with an account | `Earlier pictures stay in your private history until you delete your account.` |
| You, picture footnote, no account | `Your name and picture stay on this phone.` |
| Profile section title (was "Name and picture, on this device") | `Name and picture` |

## 5. Testing and proof

- **Core:** `Avatar` codec and checks; `check_thumb` (JPEG magic, cap, hex); projection goldens worked by hand: a
  marked profile beats an earlier and a later rename; the latest marked profile wins; a place without one keeps its
  rename; an emoji and a photo together refused. Bindings regenerated; skipti-core 0.2.0 -> 0.3.0.
- **Cloud (emulator):** `shareProfile` refuses another's place, a name that is not `users/{uid}.name`, a stranger, a
  malformed or oversize thumb, more than 50 records; skips a contested group without failing the rest; writes the mark
  and the author. Rules: a phone's record with `profile` is refused. A sabotage row for every guard.
- **Shells (twins):** an unmarked `MemberProfile` is refused at import; Rename is absent for a joined place; the
  migration writes one `Avatar` once; the backfill runs once.
- **Look:** two accounts on the local emulators (throwaway devices): set a picture on one, see the preview on the
  other's People card and sheet, no enlarge; the joined person's own name replaces the typed one, then follows a
  rename on their side; your second phone shows your picture and enlarges it.

## Not in D2

- Cloud Storage for pictures (records carry them, as D-187).
- Deleting earlier pictures from the cloud (decided: accepted).
- Showing @usernames on member rows.
- The public `users/{uid}.name` and the personal `You` name drifting apart (60 vs 64 characters): `shareProfile` uses
  `users/{uid}.name`; aligning them is a later clean-up if a look shows a difference.
