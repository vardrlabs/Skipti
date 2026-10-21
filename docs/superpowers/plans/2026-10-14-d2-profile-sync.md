# D2 (D-211): your name and picture on your phones and in your groups, Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Your profile picture syncs to your own phones as a personal record; a server-bound Function writes your own name and a small preview into every group where you hold a place, and a joined person's own name beats every rename.

**Architecture:** Two new group operations under record type 0x03: personal `Avatar` (latest wins, like `DeviceName`) and group-scope `MemberProfile` (written only by the `shareProfile` Function with `profile: true`, the D-210 removal-mark pattern). Core projects both; the shells write the avatar, build and send the profile records (never into their own vault), import a `MemberProfile` only with the mark, and draw previews.

**Tech Stack:** Rust (skipti-core, UniFFI), Firebase Functions (plain JS, node:test against the emulators), SwiftUI, Jetpack Compose.

**Spec:** `docs/superpowers/specs/2026-10-14-d2-profile-sync-design.md` (approved 2026-10-14). Read it first.

## Global Constraints

- No new dependency. No em dashes in copy, comments or commit messages. Twins: same copy, order and behaviour.
- Money is untouched by this unit.
- `Avatar` photo: JPEG as lowercase hex, at most `PICTURE_MAX_BYTES` = 64,000 bytes (`check_photo`). `MemberProfile` thumb: JPEG as lowercase hex, 120 px square, at most `THUMB_MAX_BYTES` = 12,000 bytes (`check_thumb`). An emoji and a photo (or thumb) together are refused.
- Never add a field to `GroupOp::You` (older builds would refuse every new `You` record).
- `profile: true` on a cloud record is written only by Functions (`validRecord`'s `hasOnly` already refuses it from phones); phones import a `MemberProfile` only when its document carries it, and never put their own `MemberProfile` into their vault (it comes back marked through the group listener).
- `shareProfile` checks `member` with D-210's `placeIn` and every record's `name` against `users/{uid}.name` exactly.
- Rename is ABSENT (not disabled, D-126) for a place with a marked profile.
- Without an account: the `Avatar` record is written and never leaves the phone; `shareProfile` is never called.
- skipti-core 0.2.0 -> **0.3.0**; JSON export schema 3 -> **4** (adds `profile`).
- Nothing is deployed (the founder, 2026-10-14: 0.5.0 ships everything at once). The unit ends at merge + records.
- Commits: `[skip ci]`, explicit paths only, no attribution of any kind (CLAUDE.md §12). `git diff --cached --stat` before every commit.
- Never edit tracked files while a full gate runs; drafts in `.drafts/d211/`.
- Every guard gets a sabotage row (CLAUDE.md §8 step 7).

## Copy (twins; exact strings)

| Where | Text |
|---|---|
| You, picture footnote, with an account | `Your picture syncs to your phones. Your groups see a small preview.` |
| You, second footnote line, with an account | `Earlier pictures stay in your private history until you delete your account.` |
| You, picture footnote, no account | `Your name and picture stay on this phone.` |
| Profile section title (was `Name and picture, on this device`) | `Name and picture` |
| Full-screen picture viewer, close button | `Done` |

## File map

| File | Responsibility |
|---|---|
| `core/src/straumr/groups.rs` (modify) | `GroupOp::Avatar`, `GroupOp::MemberProfile`, `check_thumb`, `check_picture`, projection: `avatar`, `profiles`, names beat renames, `distinct_names` exemption |
| `core/src/ffi.rs` (modify) | `GroupOpIn::{Avatar, MemberProfile}`, `MemberProfileOut`, `ProjectedGroupOut.profiles`, `GroupsProjectionOut.avatar` |
| `core/src/flutningr/{mod,jsonio}.rs` (modify) | `PortProfile`, `LedgerSnapshot.profile`, schema 4 + the 3 -> 4 upgrade step |
| `core/Cargo.toml`, `Cargo.lock`, `core/skipti-core.cdx.json` | 0.3.0 |
| `ios/Skipti/Bindings/*`, `android/app/src/main/kotlin/uniffi/skipti_core/*` | regenerated (`make bindings`) |
| `firebase/functions/groups.js` (modify) | `checkProfileRecords` (pure) |
| `firebase/functions/index.js` (modify) | `shareProfile` callable, `profileDoc` |
| `firebase/tests/groups.test.mjs`, `groups-functions.test.mjs`, `rules.test.mjs` (modify) | tests |
| `ios/Skipti/App/ProfileSync.swift` (create) | `ProfileThumb` (120 px), `ProfileShare` (records, callable data, the pending job) |
| `ios/Skipti/App/{Profile,GroupStore,LedgerStore,CloudSync,People,AccountHubScreen,GroupPicker}.swift` (modify) | avatar record, migration, import refusal, previews, rename absent, copy, viewer |
| `ios/SkiptiTests/ProfileSyncTests.swift` (create) | twins |
| `ios/Skipti.xcodeproj/project.pbxproj` | 1 app file + 1 test file (CLAUDE.md §7.1) |
| `android/.../ProfileSync.kt` (create) | twin of ProfileSync.swift |
| `android/.../{Profile,LedgerStore,CloudSync,People,GroupScreens,AccountHubScreen,GroupJobs}.kt` (modify) | twins |
| `android/app/src/test/.../ProfileSyncTest.kt` (create) | twins |

---

### Task 1: Core: `Avatar`, `MemberProfile` and their projection (Rust only)

**Files:**
- Modify: `core/src/straumr/groups.rs`
- Modify: `core/src/ffi.rs`
- Test: `core/src/straumr/groups.rs` (`mod tests`), `core/src/ffi.rs` (`mod tests`)

**Interfaces:**
- Produces (Rust): `GroupOp::Avatar { emoji: Option<String>, photo: Option<String> }`; `GroupOp::MemberProfile { group, member, name: String, emoji: Option<String>, thumb: Option<String> }`; `THUMB_MAX_BYTES: usize = 12_000`; `GroupsProjection.avatar: Option<GroupPicture>`; `ProjectedGroup.profiles: Vec<ProjectedProfile>`; `ProjectedProfile { member: String, emoji: Option<String>, thumb: Option<String> }`.
- Produces (FFI, used by Tasks 4-7): `GroupOpIn::Avatar { emoji: Option<String>, photo_hex: Option<String> }`; `GroupOpIn::MemberProfile { group_id: String, member_id: String, name: String, emoji: Option<String>, thumb_hex: Option<String> }`; `MemberProfileOut { member_id: String, emoji: Option<String>, thumb_hex: Option<String> }`; `ProjectedGroupOut.profiles: Vec<MemberProfileOut>`; `GroupsProjectionOut.avatar: Option<GroupPictureOut>`. Swift: `.avatar(emoji:photoHex:)`, `.memberProfile(groupId:memberId:name:emoji:thumbHex:)`. Kotlin: `GroupOpIn.Avatar(emoji, photoHex)`, `GroupOpIn.MemberProfile(groupId, memberId, name, emoji, thumbHex)`.

- [ ] **Step 1: Write the failing codec and validation tests** (append inside `mod tests` in `core/src/straumr/groups.rs`)

```rust
    // ---- D-211: Avatar and MemberProfile ------------------------------------

    /// A JPEG of `n` bytes as hex: the magic, then zeros.
    fn jpeg(n: usize) -> String {
        let mut s = String::from("ffd8ff");
        s.push_str(&"00".repeat(n - 3));
        s
    }

    fn profile(group: u8, member: u8, name: &str, thumb: Option<String>) -> GroupOp {
        GroupOp::MemberProfile {
            group: hex(group),
            member: hex(member),
            name: name.into(),
            emoji: None,
            thumb,
        }
    }

    /// Written out by hand: `op` first, fields in declaration order, absent
    /// options omitted.
    #[test]
    fn avatar_and_member_profile_encode_to_exactly_these_bytes() {
        let cases: Vec<(GroupOp, &str)> = vec![
            (
                GroupOp::Avatar { emoji: Some("🦊".into()), photo: None },
                r#"{"op":"avatar","emoji":"🦊"}"#,
            ),
            (GroupOp::Avatar { emoji: None, photo: None }, r#"{"op":"avatar"}"#),
            (
                profile(9, 2, "Meow Catto", Some("ffd8ff00".into())),
                concat!(
                    r#"{"op":"memberProfile","group":"00000000000000000000000000000009","#,
                    r#""member":"00000000000000000000000000000002","name":"Meow Catto","thumb":"ffd8ff00"}"#
                ),
            ),
        ];
        for (op, expected) in cases {
            let bytes = encode(&op).unwrap();
            assert_eq!(String::from_utf8(bytes.clone()).unwrap(), expected);
            assert_eq!(decode(&bytes).unwrap(), op, "{expected}");
        }
    }

    #[test]
    fn avatar_and_member_profile_hold_their_rules() {
        let bad: Vec<GroupOp> = vec![
            // an emoji and a photo together
            GroupOp::Avatar { emoji: Some("🦊".into()), photo: Some(jpeg(10)) },
            // a photo one byte over the cap, and one that is not a JPEG
            GroupOp::Avatar { emoji: None, photo: Some(jpeg(PICTURE_MAX_BYTES + 1)) },
            GroupOp::Avatar { emoji: None, photo: Some("89504e47".into()) },
            // a thumb one byte over its cap
            profile(9, 2, "Meow", Some(jpeg(THUMB_MAX_BYTES + 1))),
            // an empty name, a name with outer space, a bad member id
            profile(9, 2, "", None),
            profile(9, 2, " Meow", None),
            GroupOp::MemberProfile {
                group: hex(9),
                member: "x".into(),
                name: "Meow".into(),
                emoji: None,
                thumb: None,
            },
            // an emoji and a thumb together
            GroupOp::MemberProfile {
                group: hex(9),
                member: hex(2),
                name: "Meow".into(),
                emoji: Some("🦊".into()),
                thumb: Some(jpeg(10)),
            },
        ];
        for op in bad {
            assert!(matches!(encode(&op), Err(GroupOpError::Invalid(_))), "{op:?}");
        }
        assert!(encode(&GroupOp::Avatar { emoji: None, photo: Some(jpeg(PICTURE_MAX_BYTES)) }).is_ok());
        assert!(encode(&profile(9, 2, "Meow", Some(jpeg(THUMB_MAX_BYTES)))).is_ok());
    }
```

- [ ] **Step 2: Write the failing projection tests** (same module)

```rust
    #[test]
    fn the_latest_avatar_is_the_accounts_and_an_empty_one_clears_it() {
        let fox = GroupOp::Avatar { emoji: Some("🦊".into()), photo: None };
        let photo = GroupOp::Avatar { emoji: None, photo: Some(jpeg(10)) };
        let cleared = GroupOp::Avatar { emoji: None, photo: None };
        let p = project(&[rec(vid(2, 0, 1), &photo), rec(vid(1, 0, 1), &fox)]);
        assert_eq!(p.avatar, Some(GroupPicture { emoji: None, photo: Some(jpeg(10)) }));
        let p = project(&[rec(vid(1, 0, 1), &fox), rec(vid(3, 0, 2), &cleared)]);
        assert_eq!(p.avatar, None, "the latest write cleared it");
        assert_eq!(project(&[]).avatar, None);
    }

    /// Worked by hand: Tim (1) creates group 9 with Rahul (2) typed by Tim as
    /// "Ra". Rahul's own profile (t=5) names him "Rahul K"; a rename by Tim
    /// BEFORE it (t=3) and one AFTER it (t=7) are both ignored. Priya (3) has
    /// no profile: Tim's rename (t=6) lands.
    #[test]
    fn a_joined_persons_own_name_beats_every_rename() {
        let records = vec![
            rec(vid(1, 0, 1), &create(9, "Goa", vec![member(1, Some("Tim")), member(2, Some("Ra")), member(3, Some("P"))])),
            rec(vid(3, 0, 1), &GroupOp::RenameMember { group: hex(9), member: hex(2), name: "R".into() }),
            rec(vid(5, 0, 2), &profile(9, 2, "Rahul K", Some(jpeg(20)))),
            rec(vid(6, 0, 1), &GroupOp::RenameMember { group: hex(9), member: hex(3), name: "Priya".into() }),
            rec(vid(7, 0, 1), &GroupOp::RenameMember { group: hex(9), member: hex(2), name: "Rah".into() }),
        ];
        let p = project(&records);
        let g = &p.groups[0];
        assert_eq!(
            names(g),
            vec![
                (hex(1), Some("Tim".into())),
                (hex(2), Some("Rahul K".into())),
                (hex(3), Some("Priya".into())),
            ]
        );
        assert_eq!(
            g.profiles,
            vec![ProjectedProfile { member: hex(2), emoji: None, thumb: Some(jpeg(20)) }]
        );
        assert!(p.pending.is_empty() && p.rejected.is_empty());
    }

    #[test]
    fn the_latest_profile_of_a_place_wins_and_a_profile_for_nobody_waits() {
        let records = vec![
            rec(vid(1, 0, 1), &create(9, "Goa", vec![member(1, Some("Tim")), member(2, Some("Ra"))])),
            rec(vid(4, 0, 2), &profile(9, 2, "Rahul", Some(jpeg(20)))),
            rec(vid(8, 0, 2), &profile(9, 2, "Rahul K", None)),
            rec(vid(9, 0, 2), &profile(9, 7, "Ghost", None)),
        ];
        let p = project(&records);
        let g = &p.groups[0];
        assert_eq!(names(g)[1], (hex(2), Some("Rahul K".into())));
        assert_eq!(g.profiles, vec![ProjectedProfile { member: hex(2), emoji: None, thumb: None }]);
        assert_eq!(p.pending, vec![vid(9, 0, 2).render()], "a profile for a place nobody added waits");
    }

    /// Two joined people may share a name; a typed name that clashes with a
    /// joined one gets " (2)", whatever the member order.
    #[test]
    fn joined_names_are_never_suffixed_and_typed_ones_yield() {
        let records = vec![
            rec(vid(1, 0, 1), &create(9, "Goa", vec![
                member(1, Some("Tim")), member(2, Some("Meow")), member(3, Some("x")), member(4, Some("y")),
            ])),
            rec(vid(2, 0, 3), &profile(9, 3, "Meow", None)),
            rec(vid(3, 0, 4), &profile(9, 4, "Meow", None)),
        ];
        let p = project(&records);
        assert_eq!(
            names(&p.groups[0]),
            vec![
                (hex(1), Some("Tim".into())),
                (hex(2), Some("Meow (2)".into())),
                (hex(3), Some("Meow".into())),
                (hex(4), Some("Meow".into())),
            ]
        );
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cargo test -p skipti-core --lib straumr::groups::tests`
Expected: compile errors (`GroupOp::Avatar`, `MemberProfile`, `THUMB_MAX_BYTES`, `avatar`, `profiles`, `ProjectedProfile` not found). That is the expected red for a new type.

- [ ] **Step 4: Implement in `core/src/straumr/groups.rs`**

Add the two variants at the END of `enum GroupOp` (after `DeviceName`):

```rust
    /// D-211: your picture (PERSONAL scope, like `DeviceName`): your phones
    /// show it in full. An emoji or a JPEG (lowercase hex, at most
    /// `PICTURE_MAX_BYTES`), at most one; neither means no picture. The
    /// latest write wins.
    #[serde(rename_all = "camelCase")]
    Avatar {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        emoji: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        photo: Option<String>,
    },
    /// D-211: a joined person's own name and preview in a group (group
    /// scope). Only the `shareProfile` Function writes one (the cloud marks
    /// it `profile: true`, and phones import it only with the mark), so every
    /// one in the vault is the server's word: the place is the author's, the
    /// name their account's. It beats every rename of the place.
    #[serde(rename_all = "camelCase")]
    MemberProfile {
        group: String,
        member: String,
        name: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        emoji: Option<String>,
        /// A JPEG as lowercase hex, at most `THUMB_MAX_BYTES`.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        thumb: Option<String>,
    },
```

Replace `check_photo` with a shared checker, and add `check_thumb` and `check_picture`:

```rust
/// D-211: the largest member preview, in bytes: 120 px square, never worth
/// enlarging.
pub const THUMB_MAX_BYTES: usize = 12_000;

/// A JPEG (it starts FF D8 FF) as lowercase hex of at most `max` bytes. Core
/// reads no pixels.
fn check_jpeg_hex(what: &str, p: &str, max: usize) -> Result<(), GroupOpError> {
    let ok = p.len().is_multiple_of(2)
        && p.len() / 2 <= max
        && p.starts_with("ffd8ff")
        && p.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    if ok {
        Ok(())
    } else {
        Err(GroupOpError::Invalid(format!(
            "{what} of {} hex characters",
            p.len()
        )))
    }
}

/// D-187: a group photo (and D-211: your own picture).
fn check_photo(p: &str) -> Result<(), GroupOpError> {
    check_jpeg_hex("photo", p, PICTURE_MAX_BYTES)
}

/// D-211: a member's preview.
fn check_thumb(p: &str) -> Result<(), GroupOpError> {
    check_jpeg_hex("thumb", p, THUMB_MAX_BYTES)
}

/// An emoji or an image, never both; either may be absent.
fn check_picture(
    emoji: &Option<String>,
    image: &Option<String>,
    image_ok: fn(&str) -> Result<(), GroupOpError>,
) -> Result<(), GroupOpError> {
    if emoji.is_some() && image.is_some() {
        return Err(GroupOpError::Invalid(
            "a picture is an emoji or a photo".into(),
        ));
    }
    if let Some(e) = emoji {
        check_emoji(e)?;
    }
    if let Some(p) = image {
        image_ok(p)?;
    }
    Ok(())
}
```

In `validate`, make `GroupPicture` use it and add the two arms:

```rust
        GroupOp::GroupPicture {
            group,
            emoji,
            photo,
        } => {
            check_id("group", group)?;
            check_picture(emoji, photo, check_photo)
        }
        // ... existing arms unchanged ...
        GroupOp::Avatar { emoji, photo } => check_picture(emoji, photo, check_photo),
        GroupOp::MemberProfile {
            group,
            member,
            name,
            emoji,
            thumb,
        } => {
            check_id("group", group)?;
            check_id("member", member)?;
            check_member_name(name)?;
            check_picture(emoji, thumb, check_thumb)
        }
```

Add the projection types next to `GroupPicture`, and the fields:

```rust
/// D-211: a joined person's preview in a group, under the id they are shown
/// by. Its presence means their name is their own (Rename is absent).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedProfile {
    pub member: String,
    pub emoji: Option<String>,
    pub thumb: Option<String>,
}
```

- `ProjectedGroup`: add `pub profiles: Vec<ProjectedProfile>,` (doc: "D-211: members with a marked profile, in member order.").
- `GroupsProjection`: add `pub avatar: Option<GroupPicture>,` (doc: "D-211: the account's picture, the latest written; None when never written or cleared.").

In `project`:
1. `Building` gains `profiles: BTreeMap<String, Option<(VersionId, (String, PictureParts))>>`, initialised `BTreeMap::new()` in pass 1.
2. Pass 2 gains:

```rust
            GroupOp::MemberProfile {
                group,
                member,
                name,
                emoji,
                thumb,
            } => match groups.get_mut(group.as_str()) {
                Some(b) => write(
                    b.profiles.entry(member.clone()).or_insert(None),
                    vid,
                    &(name.clone(), (emoji.clone(), thumb.clone())),
                ),
                None => out.pending.push(vid.render()),
            },
```

and `| GroupOp::Avatar { .. }` joins the arm that does nothing.
3. Directly AFTER the renames loop (`for (vid, group, member, name) in renames { ... }`), insert:

```rust
    // D-211: a joined person's own name (the server's word) beats every
    // rename of their place, earlier or later. A profile for a place nobody
    // added waits, as a rename does.
    for b in groups.values_mut() {
        let profiled: Vec<(String, VersionId, String)> = b
            .profiles
            .iter()
            .filter_map(|(m, slot)| slot.as_ref().map(|(v, (n, _))| (m.clone(), v.clone(), n.clone())))
            .collect();
        for (m, v, n) in profiled {
            match b.names.get_mut(&m) {
                Some(slot) => *slot = Some((v, n)),
                None => out.pending.push(v.render()),
            }
        }
    }
```

4. In the build loop, compute the profiles under the shown id and pass the profiled ids to `distinct_names`:

```rust
        let mut profiles: Vec<ProjectedProfile> = Vec::new();
        for m in &b.order {
            if let Some(Some((_, (_, (emoji, thumb))))) = b.profiles.get(m) {
                let mid = yous.as_ref().map_or_else(|| m.clone(), |y| y.id(m));
                if !profiles.iter().any(|p| p.member == mid) {
                    profiles.push(ProjectedProfile { member: mid, emoji: emoji.clone(), thumb: thumb.clone() });
                }
            }
        }
        let own: BTreeSet<&str> = profiles.iter().map(|p| p.member.as_str()).collect();
        let members = distinct_names(merged, you.as_deref(), &own);
```

and `profiles,` in the `ProjectedGroup { .. }` literal.
5. `distinct_names` gains `own: &BTreeSet<&str>`: before its loop, insert the lowercase name of every member in `own` into `taken`; in the loop, `continue` for a member in `own` (as for "you"). Update its doc: "D-211: joined people keep their own names (several may share one); typed names yield to them."
6. After the D-185 block, before `out`:

```rust
    // ---- D-211: your picture, the latest write ----
    let mut avatar: Option<(VersionId, (Option<String>, Option<String>))> = None;
    for (vid, op) in &ops {
        if let GroupOp::Avatar { emoji, photo } = op {
            write(&mut avatar, vid, &(emoji.clone(), photo.clone()));
        }
    }
    out.avatar = avatar.and_then(|(_, (emoji, photo))| {
        (emoji.is_some() || photo.is_some()).then_some(GroupPicture { emoji, photo })
    });
```

Every other exhaustive `match` on `GroupOp` in the crate must name the new variants (the compiler lists them): `Avatar` with the personal ops, `MemberProfile` with the group ops.

- [ ] **Step 5: Run the groups tests**

Run: `cargo test -p skipti-core --lib straumr::groups::tests`
Expected: all PASS (the existing D-187 picture tests too: `check_photo`'s message keeps the word "photo").

- [ ] **Step 6: FFI** (`core/src/ffi.rs`): add to `enum GroupOpIn` (at the end):

```rust
    /// D-211: your picture (PERSONAL scope): an emoji, a JPEG as lowercase
    /// hex, or neither for none.
    Avatar {
        emoji: Option<String>,
        photo_hex: Option<String>,
    },
    /// D-211: a joined person's name and preview in a group (group scope).
    /// Phones BUILD it for `shareProfile`; only the Function writes it.
    MemberProfile {
        group_id: String,
        member_id: String,
        name: String,
        emoji: Option<String>,
        thumb_hex: Option<String>,
    },
```

the two conversions (`group_op` and `group_op_in`) map field for field (`photo_hex` <-> `photo`, `group_id` <-> `group`, `member_id` <-> `member`, `thumb_hex` <-> `thumb`), and:

```rust
/// D-211: a joined person's preview, under the id they are shown by.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MemberProfileOut {
    pub member_id: String,
    pub emoji: Option<String>,
    pub thumb_hex: Option<String>,
}
```

`ProjectedGroupOut` gains `pub profiles: Vec<MemberProfileOut>,` (filled from `g.profiles`), and `GroupsProjectionOut` gains `pub avatar: Option<GroupPictureOut>,` (filled `p.avatar.map(|a| GroupPictureOut { emoji: a.emoji, photo_hex: a.photo })`).

- [ ] **Step 7: FFI round-trip test** (in `ffi.rs`'s tests, beside the D-185 device-name test at ~:4017): encode `GroupOpIn::Avatar { emoji: None, photo_hex: Some("ffd8ff00".into()) }` and a `GroupOpIn::MemberProfile`, decode both back equal; project a create + a member profile through `ffi_project_groups` and assert `groups[0].profiles == vec![MemberProfileOut { member_id, emoji: None, thumb_hex: Some(..) }]` and that `avatar` is `Some(GroupPictureOut { .. })` after an avatar record.

Run: `cargo test -p skipti-core --lib` -> all PASS. `cargo clippy -p skipti-core --all-targets -- -D warnings` -> clean.

- [ ] **Step 8: Commit**

```bash
git add core/src/straumr/groups.rs core/src/ffi.rs
git diff --cached --stat
git commit -m "D-211: core: your picture as a personal record, a joined person's own name and preview in a group beat every rename [skip ci]"
```

---

### Task 2: Core: the picture in the export (schema 4), version 0.3.0, bindings

**Files:** `core/src/flutningr/{mod,jsonio}.rs`, `core/Cargo.toml`, `Cargo.lock`, `core/skipti-core.cdx.json`, bindings (both shells), any shell call site the binding change breaks.

**Pattern to follow:** D-210's commit `73bc3d7` (schema 2 -> 3 for redistributions, 0.1.0 -> 0.2.0, bindings). Read `git show 73bc3d7 --stat` and its `jsonio.rs` diff first, and do the same steps.

**Interfaces:**
- Produces: `PortProfile { name: Option<String>, emoji: Option<String>, photo_hex: Option<String> }` (serde snake_case, `deny_unknown_fields`, uniffi Record); `LedgerSnapshot.profile: Option<PortProfile>` (`#[serde(default)]`); `SCHEMA_VERSION = 4`.

- [ ] **Step 1: Failing tests** in `jsonio.rs`'s tests: (a) a v4 export with `profile: { name: "Tim", emoji: null, photo_hex: "ffd8ff00" }` round-trips byte-stable; (b) a pinned v3 document (copy the existing v3 golden) upgrades to v4 with `profile: null` and nothing else changed; (c) a profile whose `photo_hex` is not a JPEG, or over 64,000 bytes, or both emoji and photo, is refused as `InvalidLedger` (validation reuses `GroupOp::Avatar`'s rules through `straumr::groups::encode(&GroupOp::Avatar { .. })`); (d) a profile name breaking `check_member_name` is refused.
- [ ] **Step 2:** Run `cargo test -p skipti-core --lib flutningr` -> fail (no `profile`).
- [ ] **Step 3:** Implement: `PortProfile`; the field; `EnvelopeV3` frozen as the v3 shape (as `EnvelopeV2` was for v2); `upgrade_step(3, doc)` rewrites to v4 with `profile: None`; validation as above. CSV export unchanged.
- [ ] **Step 4:** `core/Cargo.toml` `version = "0.3.0"`; `cargo build` updates `Cargo.lock`; regenerate the SBOM exactly as the gate's SBOM leg does (read `scripts/gate.sh` for the command); `make bindings`; fix every shell call site that builds a `LedgerSnapshot` to pass `profile: nil` / `profile = null` for now (Task 4/6 fill it).
- [ ] **Step 5:** `cargo test -p skipti-core` PASS; `make ios-app` and `make android-test` compile and pass (the suites are unchanged in behaviour).
- [ ] **Step 6: Commit** the core files, `Cargo.lock`, the cdx file, both bindings and the call sites (explicit paths), `"D-211: core 0.3.0: your picture in the export (schema 4); bindings [skip ci]"`.

---

### Task 3: Cloud: `shareProfile` (pure check, callable, emulator and rules tests)

**Files:**
- Modify: `firebase/functions/groups.js`, `firebase/functions/index.js`
- Test: `firebase/tests/groups.test.mjs` (pure), `firebase/tests/groups-functions.test.mjs` (emulator), `firebase/tests/rules.test.mjs`

**Interfaces:**
- Produces: callable `shareProfile` `{ records: [record] }` -> `{ shared: [groupId], skipped: [groupId] }`. A record is the D-210 shape (`{id, type, hlc: {millis, counter, device}, deviceId, payload: {version, epoch, flags, nonce, blob}}`, base64 `nonce`/`blob`), `type: "3"`, blob JSON `{"op":"memberProfile","group":G,"member":M,"name":N[, "emoji":E][, "thumb":T]}`. Errors: `invalid-argument` (records malformed), `failed-precondition` (a name is not `users/{uid}.name`, or the account has no name). Written docs carry `profile: true`.

- [ ] **Step 1: Pure check** in `groups.js`:

```js
const MAX_PROFILES = 50; // shareProfile: one record per group
const JPEG_HEX = /^ffd8ff([0-9a-f]{2})*$/;
const THUMB_MAX_BYTES = 12000;

/** null when `records` (1 to 50) are each a memberProfile of a distinct
 *  group, well formed, named `name`, with no id twice; otherwise 'records'
 *  or 'name'. The place (`member`) is decided per group by the caller. */
export function checkProfileRecords(records, name) {
  if (!Array.isArray(records) || records.length < 1 || records.length > MAX_PROFILES) return 'records';
  const ids = new Set();
  const seenGroups = new Set();
  for (const r of records) {
    if (!recordShape(r) || ids.has(r.id) || r.type !== '3') return 'records';
    ids.add(r.id);
    const b = json(r.payload.blob);
    if (b?.op !== 'memberProfile' || !HEX32.test(b.group ?? '') || !HEX32.test(b.member ?? '')) return 'records';
    if (seenGroups.has(b.group)) return 'records';
    seenGroups.add(b.group);
    if (typeof b.name !== 'string' || !b.name || b.name !== b.name.trim()) return 'records';
    if (b.emoji != null && b.thumb != null) return 'records';
    if (b.thumb != null && (typeof b.thumb !== 'string' || !JPEG_HEX.test(b.thumb) || b.thumb.length / 2 > THUMB_MAX_BYTES)) return 'records';
    if (b.emoji != null && (typeof b.emoji !== 'string' || !b.emoji || [...b.emoji].length > 8)) return 'records';
    const extra = Object.keys(b).filter((k) => !['op', 'group', 'member', 'name', 'emoji', 'thumb'].includes(k));
    if (extra.length) return 'records';
  }
  for (const r of records) if (json(r.payload.blob).name !== name) return 'name';
  return null;
}
```

Pure tests in `groups.test.mjs` (build blobs with a local `profileRec(group, member, name, extra)` helper modelled on the file's existing record helpers): accepts one and fifty; refuses 0, 51, a second record for one group, a twice-used id, type `'6'`, an op other than `memberProfile`, a bad member id, an emoji with a thumb, a thumb of 12,001 bytes, a non-JPEG thumb, an unknown field, a name with outer spaces; answers `'name'` for a well-formed record whose name differs from the account's.

- [ ] **Step 2: The callable** in `index.js` (after `adoptRemovals`), with `profileDoc` beside `removalDoc`:

```js
/** A checked phone-built profile record as shareProfile writes it: the
 *  caller its author, the server's time, and `profile: true`, which phones
 *  can never write. */
function profileDoc(r, uid, now) {
  const { removal: _, ...doc } = removalDoc(r, uid, now);
  return { ...doc, profile: true };
}

// D-211: writes the caller's own name and preview into each group where the
// server says they hold the place the record names. The name must be the
// account's own (users/{uid}.name). A group that refuses (not a member, not
// their place, contested) is skipped; the others are written.
export const shareProfile = onCall(CALLABLE, async (request) => {
  const uid = signedIn(request);
  const data = request.data ?? {};
  const self = await db.collection('users').doc(uid).get();
  const name = self.exists && typeof self.data().name === 'string' ? self.data().name : null;
  const you = self.exists && typeof self.data().participantId === 'string' ? self.data().participantId : null;
  const bad = groups.checkProfileRecords(data.records, name);
  if (bad === 'records') throw new HttpsError('invalid-argument', 'records are not valid');
  if (!name || !you || bad === 'name') throw new HttpsError('failed-precondition', 'Your name changed. Try again.');
  const shared = [];
  const skipped = [];
  for (const r of data.records) {
    const b = JSON.parse(Buffer.from(r.payload.blob, 'base64').toString('utf8'));
    try {
      const { ref } = await groupOf(b.group, uid);
      await db.runTransaction(async (tx) => {
        const g = await again(tx, ref);
        const place = await placeIn(tx, ref, g, b.member, you);
        if (place.target !== uid) throw new HttpsError('permission-denied', 'Not your place.');
        tx.create(ref.collection('records').doc(r.id), profileDoc(r, uid, FieldValue.serverTimestamp()));
      });
      shared.push(b.group);
    } catch (e) {
      // Already written (a retry after a lost answer) counts as shared.
      if (!(e instanceof HttpsError) && e?.code === 6) { shared.push(b.group); continue; }
      logger.warn('shareProfile: a group was skipped', { code: e instanceof HttpsError ? e.code : 'other' });
      skipped.push(b.group);
    }
  }
  return { shared, skipped };
});
```

(`placeIn` throws for a contested place and refuses a `you` that is not the holder; both land in `skipped`. Read `placeIn` (index.js ~:569) before relying on it: if it returns `target: null` for a place without an account, the `!== uid` check skips it.)

- [ ] **Step 3: Emulator tests** in `groups-functions.test.mjs` (new section `// ---- shareProfile ----`, using the file's `G`, `M`, `P`, `call`, `read`, `list`, `seed` and a new `profileOf(member, name, extra, n)` built like `removalOf`):
  1. `shareProfile: a member's own name and preview reach the group, marked`: `me` sends one record for `M.me` named `Mo` with a thumb -> `{ shared: [G], skipped: [] }`; the record doc has `profile: true`, `authorUid: 'me'`, and the blob's bytes.
  2. `shareProfile refuses a name that is not the account's, and an account with no name`: name `Mona` -> `FAILED_PRECONDITION`; seed `users/eve` without `name` and call as eve -> `FAILED_PRECONDITION`; nothing written.
  3. `shareProfile never writes on someone else's place`: `me` sends a record for `M.sam` -> `{ shared: [], skipped: [G] }`; nothing written.
  4. `shareProfile skips a group the caller is not in and writes the others`: seed a second group `G2 = 'a'.repeat(32)` with `memberUids: ['ow', 'sam']` (NOT `me`) and its own create op; `me` sends, in ONE call, a record for `G` (place `M.me`) and one for `G2` (any place) -> `{ shared: [G], skipped: [G2] }`; `G`'s record is written, `G2` has no new record.
  5. `shareProfile refuses malformed records and is idempotent`: 51 records -> `INVALID_ARGUMENT`; unauthenticated -> `UNAUTHENTICATED`; the same record twice in two calls -> both `{ shared: [G] }`, one doc.
- [ ] **Step 4: Rules test** in `rules.test.mjs`: a member's phone writing a group record with `profile: true` is refused (PERMISSION_DENIED), the same record without it is allowed (copy the D-210 `removal` refusal test and change the field).
- [ ] **Step 5:** `zsh -lic './scripts/firebase-rules-test.sh'` -> `RULES TESTS PASSED (N tests)`, N = 150 + the new ones. Note: the script needs Java, so run it through `zsh -lic`.
- [ ] **Step 6: Commit** the four firebase files, `"D-211: cloud: shareProfile writes a member's own name and preview into the groups where the server says the place is theirs [skip ci]"`.

---

### Task 4: iOS: the ledger and sync layer

**Files:** create `ios/Skipti/App/ProfileSync.swift`, `ios/SkiptiTests/ProfileSyncTests.swift`; modify `Profile.swift`, `GroupStore.swift`, `LedgerStore.swift`, `CloudSync.swift`, `CloudProfile.swift` (only if needed, below), the export builder (`ExportFlow.swift`), `project.pbxproj` (CLAUDE.md §7.1: copy `People.swift`'s reference line for the app file and a SkiptiTests sibling's for the test file; assert every new id is unused).

**Interfaces:**
- Consumes: Task 1's `GroupOpIn.avatar(emoji:photoHex:)`, `.memberProfile(groupId:memberId:name:emoji:thumbHex:)`, `GroupsProjectionOut.avatar`, `ProjectedGroupOut.profiles`; Task 2's `PortProfile`; Task 3's `shareProfile`.
- Produces (for Task 5): `LedgerStore` publishes `avatar: Avatar` from the projection (the existing `Avatar` enum in `Profile.swift`) and `memberPreviews: [String: [String: Avatar]]` (groupId -> shown member id -> preview); `ProfileShare.request()` (marks the share pending and runs it).

Behaviour:
1. **Write:** `Profile`'s save of a picture calls `Sync.appendGroupOps([.avatar(emoji:photoHex:)])` (personal scope: add `.avatar` to the personal case list in `groupOpRecord`, `LedgerStore.swift:752-763`). Photos keep the 256 px square and step JPEG quality 0.8 -> 0.4 until at most 64,000 bytes (copy `GroupPictures.swift:30-90`'s loop, square instead of 3:2). Clearing writes `.avatar(emoji: nil, photoHex: nil)`.
2. **Read:** `rebuildGroups` writes kv `ledger/self-avatar` from `projection.avatar` (as `ledger/self` is written from `you`), so `Profile` keeps reading kv.
3. **Migration, once** (kv flag `profile.avatar-record`): at launch, if kv `ledger/self-avatar` holds an emoji or photo and `projection.avatar == nil`, write one `.avatar` record from it; set the flag either way.
4. **Import refusal** (`CloudSync.refusal`, :675): add `profile: Bool = false` beside `removal`, read from the document's `profile` field exactly as `removal` is read. `.memberProfile(let g, ...)`: `guard g == group else { return .wrongGroup }; return profile ? nil : .unverifiedProfile` (new `Refusal` case). The this-device rule (:677) exempts a marked profile as it exempts a marked removal. `.avatar` refuses as `.personal` in a group's log, like `.deviceName`. In the PERSONAL log, a `.memberProfile` is refused (wrong scope) the way group ops are today.
5. **ProfileThumb** (`ProfileSync.swift`): `static func make(from jpeg: Data) -> Data?`: decode, centre-crop square, draw at 120 px, JPEG 0.8 stepping down by 0.1 to 0.4 until at most 12,000 bytes; nil if never under (then the share sends `emoji: nil, thumbHex: nil`, name only).
6. **ProfileShare** (`ProfileSync.swift`):
   - `static func records(places: [(groupId: String, memberId: String)], name: String, emoji: String?, thumbHex: String?) -> [RecordWire]`: one `.memberProfile` record per place, group scope, built through the same path as `groupOpRecord` but NOT put into the vault.
   - `static func callData(_ records: [RecordWire]) -> [String: Any]`: the D-210 record shape (`CloudRecords.adoptData` is the model).
   - **Places:** for each group in the projection where this account holds a place: the claimed member (`claims` whose `youId` is one of this account's yous) or, for a group the account created, its own `you` id. Skip removed/read-only groups.
   - **The job:** kv `profile.share.pending` (bool). `request()` sets it and calls `run()`; `run()` (also called when sync starts, signed in only) reads the name from `users/{uid}.name` (CloudProfile), builds, calls `shareProfile`, and clears the flag on any answer. On `failed-precondition` it re-reads the name once and retries; on any other error it keeps the flag. Never blocks a screen; never throws to UI.
   - **Triggers:** after `createGroup` succeeds in the cloud, after a join's claim is written, after the name or picture is saved, and once per install (kv `profile.share.backfilled`).
   - **Name source check:** confirm that saving the profile name already writes `users/{uid}.name` (CloudProfile.swift:33-50,82-86). If it does not, make the name save do so (same 60-character limit as `validName`); a name longer than 60 is not shared (record in the report).
7. **Export:** `ExportFlow` fills `LedgerSnapshot.profile` from `you.name` and `projection.avatar`; import writes an `.avatar` record only when the file has a picture and this phone has none.

Tests (`ProfileSyncTests.swift`, all hosted XCTest; names are the Android twins' in camelCase):
- `testAnUnmarkedMemberProfileIsRefusedAndAMarkedOneComesIn` (both from another device; a marked one from THIS device also comes in).
- `testAnAvatarInAGroupLogIsRefusedAsPersonal`.
- `testThePlacesAreTheClaimedOnesAndTheCreatorsOwn` (a projection with a created group, a joined group via claim, a removed group -> two places, correct member ids).
- `testTheRecordsAreOnePerPlaceAndNeverInTheVault` (vault record count unchanged after `records`).
- `testTheMigrationWritesOneAvatarRecordOnce` (run twice -> one record).
- `testTheThumbIsAtMost12000BytesAnd120Pixels` (a 2000 px JPEG in).
- `testTheBackfillRequestsOnce`.

Run `make ios-app` (the hosted suite) -> all pass; tally in the report. Commit the explicit paths with `"D-211: iOS: your picture syncs as a record, your name and preview go to your groups through shareProfile, only marked profiles come in [skip ci]"`.

---

### Task 5: iOS: the screens

**Files:** `People.swift` (:916, :944 draw `AvatarView(avatar: .none, ...)` for others), `GroupPicker.swift` / the group page member rows, the person's sheet (`Roles.swift`), `AccountHubScreen.swift` / `Profile.swift` (You), `RemovalFlow.swift` only if it draws member avatars.

Behaviour:
1. Every place a group member is drawn (People cards, member rows, the person's sheet) uses `LedgerStore.memberPreviews[groupId][memberId]` (emoji or thumb) instead of `.none`; a person in several groups uses the preview from the group the card is about, else the first group (in projection order) that has one. Tapping a preview does nothing.
2. **Rename** is absent for a member with a profile (`profiles` contains them), on every surface that offers it.
3. **You:** tapping your own picture opens a full-screen viewer (`.fullScreenCover`, black background, the picture fit, `Done` top trailing, `PrimaryButtonStyle` not used: a plain toolbar `Button("Done")` is acceptable here only inside a toolbar). No viewer for an emoji or no picture.
4. Copy from the Copy table: the footnotes switch on whether an account exists (`Account` state, as other account-only copy does; GUARANTEES #5: no account, no cloud words).

Tests: `testRenameIsAbsentForAJoinedPerson` and `testAPreviewIsTheGroupsOwnElseTheFirst` (pure helpers, in `ProfileSyncTests.swift`). Look at it (Task 8). Commit `"D-211: iOS: members' previews on cards and rows, Rename absent for a joined person, your picture full screen, the new copy [skip ci]"`.

---

### Task 6: Android: the ledger and sync layer (twin of Task 4)

**Files:** create `android/app/src/main/java/com/vardrlabs/skipti/ProfileSync.kt`, `android/app/src/test/java/com/vardrlabs/skipti/ProfileSyncTest.kt`; modify `Profile.kt`, `LedgerStore.kt` (scope switch :440-460, `rebuildGroups` :884-921), `CloudSync.kt` (refusal ~:752), `GroupJobs.kt` (the pending job lives with the other group jobs), the exporter.

Same behaviour, names and kv keys as Task 4 (`profile.avatar-record`, `profile.share.pending`, `profile.share.backfilled`). Thumbnail: `Bitmap` with EXIF upright (copy `Profile.kt:136-176`), 120 px, `compress(JPEG, 80)` stepping to 40. Tests are Task 4's list in snake_case (`an_unmarked_member_profile_is_refused_and_a_marked_one_comes_in`, ...). A JVM test that reads a file declares it as an input (CLAUDE.md §7, D-162). Run `cd android && zsh -lic './gradlew :app:testPlayDebugUnitTest'`; check the XML mtimes are fresh before quoting the tally. Commit `"D-211: Android: your picture syncs as a record, your name and preview go to your groups through shareProfile, only marked profiles come in [skip ci]"`.

---

### Task 7: Android: the screens (twin of Task 5)

**Files:** `People.kt`, `GroupScreens.kt`, `Roles.kt` (the sheet), `AccountHubScreen.kt` / `Profile.kt`.

Same behaviour and copy as Task 5. The viewer is a full-screen `Dialog` with `usePlatformDefaultWidth = false`, black background, `Done`. Twin tests `rename_is_absent_for_a_joined_person`, `a_preview_is_the_groups_own_else_the_first`. Commit `"D-211: Android: members' previews on cards and rows, Rename absent for a joined person, your picture full screen, the new copy [skip ci]"`.

---

### Task 8: Prove, record, merge (NO deploy)

- [ ] Whole-branch review (Opus) of `main..d2-profile`; fix waves as needed; re-review.
- [ ] **Look** (CLAUDE.md §8 step 8; throwaway devices only, local emulators, `--emulators --emulator-as <sub>`; tooling `.drafts/c3/a.sh`, `.drafts/c3/t.py`, WORKLOG's look notes): account A sets a photo; A's second phone shows it and enlarges it; account B (in a group with A) sees A's preview on the People card, the member row and the sheet, cannot enlarge it, and sees A's own name replace the one B typed; B has no Rename for A; A renames themselves and B follows; an unjoined person stays renamable. Dark mode once. Screenshots to `.drafts/d211/look/`. Shut every device down after.
- [ ] **Sabotage** every guard (backup, asserted anchor, zero compile errors, exactly the intended red, restore, `cmp`): core `check_thumb` cap, `check_picture` both-refusal, the profile-beats-rename block, the `distinct_names` exemption, the avatar latest-write; cloud `checkProfileRecords` (cap, one-per-group, name), `shareProfile`'s `place.target !== uid`, `profileDoc`'s mark, the rules `hasOnly`; each shell's unmarked-profile refusal, the this-device exemption, Rename absence, the migration once. Runner and results in `.drafts/d211/`.
- [ ] Full gate `./scripts/gate.sh --full` in the background, machine free (no emulators, no simulators).
- [ ] Record D-211 with `.drafts/common/record_unit.py` (row, §3 with the sabotage table, §4 residue, changelog); CLOUD-SECURITY's "Pending deploy at 0.5.0" note gains `shareProfile` (and the callable count); WORKLOG.
- [ ] Commit records, `git checkout main && git merge --no-ff d2-profile`, push, `gh run list --branch main --limit 3` (no run).
- [ ] Do NOT deploy. Next in the founder's order: E1.
