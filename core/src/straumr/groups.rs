//! Groups and "you" as records (D-152; SPEC §5.3, §6.1; ADR-0034).
//!
//! Until now groups and "you" (this person's participant id and name) lived
//! only in each device's kv, so sync carried expenses that named groups and
//! people no other device knew, and a lost device got its expenses back but
//! not its groups (D-121 (3), D-151). This module makes them records of type
//! `GroupOperation` (0x03, reserved since the header format and unused until
//! now), and gives both shells ONE projection from those records to the groups
//! people see.
//!
//! Why 0x03 carries "you" as well: every shipped build already packs, stores
//! and ignores 0x03 records, while an unknown type is refused and would stop an
//! older build's sync outright. "You" is written with PERSONAL scope and group
//! operations with the group's scope, so when groups are shared between people
//! the scope keeps "you" out of any group's log.
//!
//! The rules, from SPEC §6.1 and ADR-0034 §2 (silent, deterministic, nothing
//! destroyed):
//! - every field is a last-writer-wins register ordered by record identity
//!   (`VersionId`): the group's name, and each member's name;
//! - the settlement currency is fixed at creation (§5.2): the EARLIEST create
//!   of a group sets it and nothing changes it;
//! - members are a union, in the order they first appeared (nobody can be
//!   removed yet, BUILD-LOG §4);
//! - "you" is the id of the EARLIEST `you` record in the account (the founder,
//!   2026-09-24: one "you" per account, a new device adopts it); its name is
//!   the latest `you` record for that id. Any other ids are reported, for the
//!   merge that makes them one person;
//! - two OTHER members who end up with one name (two devices each added a
//!   "Rahul" offline) are told apart for display, "Rahul" and "Rahul (2)", so
//!   the group still validates; the records keep what each device wrote;
//! - unreadable payloads are reported, never dropped silently, and operations
//!   on a group whose creation has not arrived wait in `pending`;
//! - D-229, the place: in each group, this account's place is the raw member
//!   id the group knows it by. With `mine` = "you", the other ids and the
//!   members it claimed, "present" = the latest add or removal, and "claimed
//!   by another" = a winning claim whose `you` is not in `mine`: (1) "you"
//!   itself when it is a member, present, not claimed by another; else (2) the
//!   member of the first claim of this account, in member order, that is
//!   present; else (3) the first member in `mine`, in member order, present
//!   and not claimed by another; else none (none everywhere with no "you");
//! - D-229, `you_as`: "you" is readable in a group when rule 1 holds or the
//!   alias every phone reads it by is a claim's whose member is present;
//!   then records are written with "you" as before, otherwise with the place.
//!   A `placeAlias` does not count (0.4.0 builds cannot read it), so a phone
//!   keeps writing the place, which every build reads;
//! - D-229, aliases: one per (group, from id): a `placeAlias` (marked by the
//!   server) first, the earliest by record identity, else the winning claim
//!   with that `you`. A claim whose `you` is another member of the same group
//!   gives no alias (the claim still stands); a `placeAlias` whose `you` is a
//!   member, or a second one for the same id to another place, is contested
//!   (the same one again changes nothing);
//! - D-229 decision 44 (narrowed by decision 49): an id that the winning
//!   claims of two or more PRESENT members of one group name is contested
//!   there: no claim alias, no place through those claims, and their members
//!   join no account's other ids (a denial, never a takeover: claims are
//!   phone-written, and one account holds no two present members of one
//!   group because the server's `acceptInvite` refuses an account that
//!   already holds a place there, decision 52; it cannot see a creator place
//!   whose create has not reached the cloud). Claims on removed members do
//!   not count, so an honest rejoin stays one person and removing a forger's
//!   member lifts the denial while the victim's member is present;
//! - D-229 decision 50: a winning claim on a REMOVED member counts for an
//!   id's alias and for the merge into "you" only when its record was written
//!   before every present member's winning claim naming the same id (an
//!   honest rejoin's old claim always is), and contests a marked alias only
//!   when written before that alias;
//! - D-229 decision 51: an id that is not contested reads as the member of
//!   its EARLIEST-WRITTEN winning claim that still counts (record identity
//!   order, removed members included), never by member order. So only a claim
//!   written BEFORE the person's own claim can take their history (G-08):
//!   one with a backdated record identity, or simply one written before they
//!   joined by a member who already knew their id (claims and records of any
//!   group they share name it), with an honest timestamp. While both members
//!   are present it is a denial (decision 44: the person's group is read-only
//!   from the moment they join); once the forger's member is removed, the
//!   person's records under that id read as the forger's member on every
//!   phone. Only the server's checks (D-227's invites, decision 47's refusal
//!   of another account's participant id) and decision 44's contest limit it;
//!   core cannot tell such a claim from an honest one without its author. A
//!   claim written while the person is out of the group still merges into
//!   their "you" on their own phone after a rejoin (residue, G-08);
//! - D-229 decision 47: a claim naming a marked alias's id on another member
//!   contests that id the same way (no alias, no place through it);
//! - D-229 decision 45: `place_lost` says this account had a place here and
//!   has none now: another account's claim holds one of its ids, or one of its
//!   ids is contested (decisions 44 and 47), so nothing it writes here would
//!   read as itself;
//! - D-230, held places: a marked `heldPlace` is final for its member (its
//!   winning claim) and its id (no unmarked claim naming it elsewhere
//!   counts); the latest per member stands; see decisions 53 to 56. A held
//!   place's claim counts from its member's first ADD (decision 59), so when
//!   one id holds two places (an honest rejoin, both marked) its history
//!   stays on the earlier-added one whatever order the marks were written
//!   in. A held `you` that is a raw member of the group still stands
//!   (decision 60): that raw id reads as the held member on every phone and
//!   is never the holder's place (rules 1 and 3), so adding a member with a
//!   held id takes nothing. A held id aliases only to the member that holds
//!   it (A6): a removed creator's rejoin keeps its history on its old raw
//!   place.
//!
//! Order independence is the property that matters: every device applies the
//! same records in whatever order they arrived and must see the same groups.

use super::revision::VersionId;
use crate::money::Currency;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

/// Longest member name in characters, the same limit as `ffi::member_name`
/// (a test pins that the two agree).
pub const MEMBER_NAME_MAX_CHARS: usize = 64;

/// A member as an operation names them. The name is optional: groups written
/// before names existed (D-134) carry bare ids, and the shells show those as
/// placeholders.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OpMember {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

/// A device of the account that claims a member (D-157): its id and its X25519
/// agreement public key, which the other members verify with six words
/// (SPEC §2).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClaimDevice {
    pub id: String,
    /// 64 lowercase hex.
    pub key: String,
}

/// Devices one claim may list.
pub const CLAIM_DEVICES_MAX: usize = 16;

/// One operation. The JSON carries its kind in `op`; unknown kinds and unknown
/// fields are REFUSED, so an operation from a newer build is reported as
/// unreadable rather than half-applied.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "camelCase", deny_unknown_fields)]
pub enum GroupOp {
    #[serde(rename_all = "camelCase")]
    Create {
        group: String,
        name: String,
        settlement_currency: String,
        members: Vec<OpMember>,
    },
    #[serde(rename_all = "camelCase")]
    AddMember { group: String, member: OpMember },
    #[serde(rename_all = "camelCase")]
    RenameMember {
        group: String,
        member: String,
        name: String,
    },
    #[serde(rename_all = "camelCase")]
    RenameGroup { group: String, name: String },
    /// D-186: takes a person out of a group's people (someone added by
    /// mistake; the shells allow it only for a person in no expense or
    /// payment who never joined on a phone). Group scope. The member stays
    /// in the records, so an expense another phone wrote with them offline
    /// still balances; the projection lists them as removed, and a later
    /// add of the same id brings them back (the latest of the two wins).
    #[serde(rename_all = "camelCase")]
    RemoveMember { group: String, member: String },
    /// D-187: a group's picture (the founder, 2026-09-30: a photo or an
    /// emoji). Group scope, so every member sees it, sealed like every group
    /// record. At most one of the two; neither puts the group's initial back.
    /// The latest write wins.
    #[serde(rename_all = "camelCase")]
    GroupPicture {
        group: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        emoji: Option<String>,
        /// A JPEG as lowercase hex, at most `PICTURE_MAX_BYTES` bytes.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        photo: Option<String>,
    },
    /// Who "you" are in this account (personal scope, never a group's).
    #[serde(rename_all = "camelCase")]
    You {
        id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        name: Option<String>,
    },
    /// D-157 (ADR-0037): "member `member` of this group is me". Written into
    /// the group's log by someone who joined through an invite: `you` is
    /// their account's "you" id, `devices` their devices. Group scope.
    #[serde(rename_all = "camelCase")]
    Claim {
        group: String,
        member: String,
        you: String,
        devices: Vec<ClaimDevice>,
    },
    /// D-158: a group's key, as this account holds it. PERSONAL scope: it
    /// travels only in the account's own log, sealed with the account key,
    /// which is how the account's other devices get it without trusting a
    /// key the relay hands them.
    #[serde(rename_all = "camelCase")]
    GroupKey {
        group: String,
        epoch: u32,
        /// 64 lowercase hex.
        key: String,
    },
    /// D-178 (ADR-0039): the account's "Maximum security" choice. PERSONAL
    /// scope, like "you" and the group keys: it travels only in the account's
    /// own log, sealed with the account key, so every phone of the account
    /// follows it and the relay can neither read nor forge it. The latest
    /// write wins.
    #[serde(rename_all = "camelCase")]
    Security { maximum: bool },
    /// D-185: what one of the account's phones is called ("iPhone 17"), so
    /// the device list can say which phone is which. PERSONAL scope like
    /// Maximum security: sealed with the account key, so the relay never
    /// learns what phones an account uses. The latest write per device wins.
    #[serde(rename_all = "camelCase")]
    DeviceName { device: String, name: String },
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
    ///
    /// `member` is the PLACE id (the member as the group knows them), checked
    /// by the Function to be the author's own place, never another account's
    /// "you" id, so no alias mapping applies to it (aliases re-point renames
    /// only). On a phone where several places are one person (this account's
    /// "you", `other_you_ids`), the latest profile of any of them gives the
    /// shown name and preview.
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
    /// D-229: "in this group, `you` reads as the place `member`" (group
    /// scope). Only the `shareAliases` Function writes one (the cloud marks it
    /// `alias: true`, and phones import it only with the mark, as
    /// `MemberProfile`), after checking that `member` is the author's own place
    /// and `you` the author's own id and nobody else's. It repairs records one
    /// account wrote into the group under an id the other members cannot read
    /// (D-153's adopted "you"), without rewriting them.
    #[serde(rename_all = "camelCase")]
    PlaceAlias {
        group: String,
        member: String,
        you: String,
    },
    /// D-230: "in this group, place `member` is held by the account whose id
    /// is `you`" (group scope). Only the `sharePlaces` and `acceptInvite`
    /// Functions write one (the cloud marks it `held: true`; phones import it
    /// only with the mark, never upload one), after checking that the caller
    /// holds `member` (D-227's `holderIn`) and that `you` is its own id and no
    /// other account's. Final for its member and its id: unmarked claims
    /// against it are void (CLOUD-SECURITY G-08). `you == member` is the
    /// creator's raw place.
    #[serde(rename_all = "camelCase")]
    HeldPlace {
        group: String,
        member: String,
        you: String,
    },
    /// D-244 (ADR-0043): one of the account's plain preferences (the theme,
    /// light or dark, text size, Less motion, haptics, the app icon choice,
    /// the default currency), so the account's other phones match. PERSONAL
    /// scope, like `Avatar`: it travels only in the account's own log. Key and
    /// value are strings, so a new key or value never breaks an older build's
    /// decode; the shells decide which keys they apply. The latest write per
    /// key wins.
    #[serde(rename_all = "camelCase")]
    Setting { key: String, value: String },
}

/// D-244: the longest setting key, in characters (ASCII by the key rule).
pub const SETTING_KEY_MAX_CHARS: usize = 48;
/// D-244: the longest setting value, in characters (the member name limit).
pub const SETTING_VALUE_MAX_CHARS: usize = 64;
/// D-244: the one key whose value core checks beyond the text rule: a
/// default currency must be a code core knows.
pub const SETTING_KEY_DEFAULT_CURRENCY: &str = "money.default-currency";
/// D-259: the key prefixes kept on each phone and never synced: the app
/// lock's timeout and screen privacy are security controls of the phone they
/// were set on (iOS `SettingsSync.perPhone`, Android's twin). Both shells
/// already drop such a key; core refuses one too, so no account record can
/// carry one to a build that forgets. Compared ignoring ASCII case.
pub const SETTING_PER_PHONE_PREFIXES: [&str; 2] = ["applock.", "privacy."];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GroupOpError {
    /// The payload is not an operation this build can read.
    Unreadable(String),
    /// Readable, but breaks a rule (bad id, empty name, unknown currency).
    Invalid(String),
}

impl fmt::Display for GroupOpError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            GroupOpError::Unreadable(m) => write!(f, "unreadable group operation: {m}"),
            GroupOpError::Invalid(m) => write!(f, "invalid group operation: {m}"),
        }
    }
}

fn is_id(s: &str) -> bool {
    s.len() == 32
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn check_id(what: &str, s: &str) -> Result<(), GroupOpError> {
    if is_id(s) {
        Ok(())
    } else {
        Err(GroupOpError::Invalid(format!(
            "{what} {s:?} is not 32 lowercase hex"
        )))
    }
}

/// A member name exactly as `ffi::member_name` would store it: already
/// trimmed, non-empty, at most 64 characters, no control characters.
pub(crate) fn check_member_name(name: &str) -> Result<(), GroupOpError> {
    if name.is_empty()
        || name.trim() != name
        || name.chars().count() > MEMBER_NAME_MAX_CHARS
        || name.chars().any(char::is_control)
    {
        return Err(GroupOpError::Invalid(format!("member name {name:?}")));
    }
    Ok(())
}

/// A group name as `ffi::create_group` stores it: trimmed and non-empty, and
/// (D-235, decision 110 a) at most `check::TEXT_MAX_CHARS` characters.
fn check_group_name(name: &str) -> Result<(), GroupOpError> {
    if name.is_empty() || name.trim() != name || super::check::check_text(name).is_err() {
        return Err(GroupOpError::Invalid(format!("group name {name:?}")));
    }
    Ok(())
}

/// D-244: a setting as a shell may write it. The key is 1 to 48 characters of
/// `A-Z a-z 0-9 . -` and (D-259) not a per-phone key; the value is trimmed, 1 to 64 characters, no control
/// characters (the member name rule); a default currency is a code core knows.
fn check_setting(key: &str, value: &str) -> Result<(), GroupOpError> {
    let key_ok = !key.is_empty()
        && key.len() <= SETTING_KEY_MAX_CHARS
        && key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-');
    if !key_ok {
        return Err(GroupOpError::Invalid(format!("setting key {key:?}")));
    }
    let per_phone = SETTING_PER_PHONE_PREFIXES.iter().any(|prefix| {
        key.get(..prefix.len())
            .is_some_and(|head| head.eq_ignore_ascii_case(prefix))
    });
    if per_phone {
        return Err(GroupOpError::Invalid(format!(
            "setting key {key:?} is kept on each phone and never synced"
        )));
    }
    if value.is_empty()
        || value.trim() != value
        || value.chars().count() > SETTING_VALUE_MAX_CHARS
        || value.chars().any(char::is_control)
    {
        return Err(GroupOpError::Invalid(format!("setting value {value:?}")));
    }
    if key == SETTING_KEY_DEFAULT_CURRENCY {
        Currency::parse(value).map_err(|_| GroupOpError::Invalid(format!("currency {value:?}")))?;
    }
    Ok(())
}

fn check_member(m: &OpMember) -> Result<(), GroupOpError> {
    check_id("member", &m.id)?;
    if let Some(name) = &m.name {
        check_member_name(name)?;
    }
    Ok(())
}

fn validate(op: &GroupOp) -> Result<(), GroupOpError> {
    match op {
        GroupOp::Create {
            group,
            name,
            settlement_currency,
            members,
        } => {
            check_id("group", group)?;
            check_group_name(name)?;
            Currency::parse(settlement_currency)
                .map_err(|_| GroupOpError::Invalid(format!("currency {settlement_currency:?}")))?;
            if members.is_empty() {
                return Err(GroupOpError::Invalid("a group needs a member".into()));
            }
            let mut seen = BTreeSet::new();
            for m in members {
                check_member(m)?;
                if !seen.insert(m.id.as_str()) {
                    return Err(GroupOpError::Invalid(format!("member {} twice", m.id)));
                }
            }
            Ok(())
        }
        GroupOp::AddMember { group, member } => {
            check_id("group", group)?;
            check_member(member)
        }
        GroupOp::RenameMember {
            group,
            member,
            name,
        } => {
            check_id("group", group)?;
            check_id("member", member)?;
            check_member_name(name)
        }
        GroupOp::RenameGroup { group, name } => {
            check_id("group", group)?;
            check_group_name(name)
        }
        GroupOp::RemoveMember { group, member } => {
            check_id("group", group)?;
            check_id("member", member)
        }
        GroupOp::GroupPicture {
            group,
            emoji,
            photo,
        } => {
            check_id("group", group)?;
            check_picture(emoji, photo, check_photo)
        }
        GroupOp::You { id, name } => {
            check_id("you", id)?;
            if let Some(name) = name {
                check_member_name(name)?;
            }
            Ok(())
        }
        GroupOp::Claim {
            group,
            member,
            you,
            devices,
        } => {
            check_id("group", group)?;
            check_id("member", member)?;
            check_id("you", you)?;
            if devices.is_empty() || devices.len() > CLAIM_DEVICES_MAX {
                return Err(GroupOpError::Invalid(format!(
                    "a claim lists 1 to {CLAIM_DEVICES_MAX} devices"
                )));
            }
            let mut seen = BTreeSet::new();
            for d in devices {
                check_id("device", &d.id)?;
                check_key(&d.key)?;
                if !seen.insert(d.id.as_str()) {
                    return Err(GroupOpError::Invalid(format!("device {} twice", d.id)));
                }
            }
            Ok(())
        }
        GroupOp::GroupKey { group, key, .. } => {
            check_id("group", group)?;
            check_key(key)
        }
        GroupOp::Security { .. } => Ok(()),
        GroupOp::DeviceName { device, name } => {
            check_id("device", device)?;
            check_member_name(name)
        }
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
        GroupOp::PlaceAlias { group, member, you } => {
            check_id("group", group)?;
            check_id("member", member)?;
            check_id("you", you)?;
            if you == member {
                return Err(GroupOpError::Invalid(
                    "an alias names two different ids".into(),
                ));
            }
            Ok(())
        }
        GroupOp::HeldPlace { group, member, you } => {
            check_id("group", group)?;
            check_id("member", member)?;
            check_id("you", you)
        }
        GroupOp::Setting { key, value } => check_setting(key, value),
    }
}

/// D-187: the largest group photo, in bytes: a small JPEG (the shells make
/// 768 by 512, the photo filling the group's card), so a picture never
/// outweighs a group. On the wire a record is hex inside hex, about four
/// times this, far under the relay's 2 MB push.
pub const PICTURE_MAX_BYTES: usize = 64_000;

/// D-187: an emoji as a group's picture: one to eight characters (a family or
/// a flag is several code points), at most 32 bytes, trimmed, no control
/// characters. Core does not judge which characters are emoji.
fn check_emoji(e: &str) -> Result<(), GroupOpError> {
    let n = e.chars().count();
    if n == 0 || n > 8 || e.len() > 32 || e.trim() != e || e.chars().any(char::is_control) {
        return Err(GroupOpError::Invalid(format!("emoji {e:?}")));
    }
    Ok(())
}

/// D-211: the largest member preview, in bytes: 120 px square, never worth
/// enlarging.
pub const THUMB_MAX_BYTES: usize = 12_000;

/// D-235 (Android audit H2): the largest picture a group photo or your own
/// picture may DECLARE, in pixels a side and in all. A JPEG under 64,000
/// bytes can declare 7000 by 7000 in its frame header, and a phone that
/// decodes it whole runs out of memory on every launch. The shells make 768
/// by 512 (smaller when a busy photo will not fit), so 2048 a side and two
/// megapixels (8 MB decoded) are generous.
pub const PICTURE_MAX_SIDE: u32 = 2048;
pub const PICTURE_MAX_PIXELS: u32 = 2048 * 1024;

/// D-235: the same for a member's preview (the shells make 120 by 120).
pub const THUMB_MAX_SIDE: u32 = 512;
pub const THUMB_MAX_PIXELS: u32 = 512 * 512;

/// Every frame size a JPEG's headers declare before its first scan, read as
/// libjpeg reads markers: bytes between markers are skipped up to the next
/// FF, FF fill bytes are skipped, a marker without a length (RSTn, TEM, a
/// stuffed 00) has none to skip, and every SOFn (C0 to CF but DHT C4, JPG
/// C8 and DAC CC) and DHP (DE) gives its (width, height). It stops at the
/// first SOS (DA) or EOI (D9): a decoder knows its size by then. Nothing past
/// a header's size fields is read, and nothing is decoded.
fn jpeg_frame_sizes(bytes: &[u8]) -> Vec<(u32, u32)> {
    let mut sizes = Vec::new();
    let mut i = 2;
    loop {
        while i < bytes.len() && bytes[i] != 0xFF {
            i += 1;
        }
        while i < bytes.len() && bytes[i] == 0xFF {
            i += 1;
        }
        let Some(&marker) = bytes.get(i) else {
            return sizes;
        };
        i += 1;
        match marker {
            0x00 | 0x01 | 0xD0..=0xD8 => continue,
            0xD9 | 0xDA => return sizes,
            _ => {}
        }
        let Some(len) = bytes
            .get(i..i + 2)
            .map(|b| u16::from_be_bytes([b[0], b[1]]) as usize)
        else {
            return sizes;
        };
        let frame = matches!(marker, 0xC0..=0xCF if !matches!(marker, 0xC4 | 0xC8 | 0xCC))
            || marker == 0xDE;
        if frame {
            // length(2) precision(1) height(2) width(2)
            if let Some(f) = bytes.get(i + 3..i + 7) {
                let height = u16::from_be_bytes([f[0], f[1]]) as u32;
                let width = u16::from_be_bytes([f[2], f[3]]) as u32;
                sizes.push((width, height));
            }
        }
        if len < 2 {
            return sizes;
        }
        i += len;
    }
}

/// A JPEG (it starts FF D8 FF) as lowercase hex of at most `max` bytes, and
/// (D-235) every frame size its headers declare is 1 to `max_side` pixels a
/// side and at most `max_pixels` in all. Core decodes no pixels. A file whose
/// headers declare no frame before its first scan is not refused here: no
/// decoder can size it, so none can draw it (the shells' decoders return
/// nothing for it).
fn check_jpeg_hex(
    what: &str,
    p: &str,
    max: usize,
    max_side: u32,
    max_pixels: u32,
) -> Result<(), GroupOpError> {
    let ok = p.len().is_multiple_of(2)
        && p.len() / 2 <= max
        && p.starts_with("ffd8ff")
        && p.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    if !ok {
        return Err(GroupOpError::Invalid(format!(
            "{what} of {} hex characters",
            p.len()
        )));
    }
    let nibble = |c: u8| match c {
        b'0'..=b'9' => c - b'0',
        _ => c - b'a' + 10,
    };
    let bytes: Vec<u8> = p
        .as_bytes()
        .chunks(2)
        .map(|pair| (nibble(pair[0]) << 4) | nibble(pair[1]))
        .collect();
    for (width, height) in jpeg_frame_sizes(&bytes) {
        if width == 0
            || height == 0
            || width > max_side
            || height > max_side
            || width * height > max_pixels
        {
            return Err(GroupOpError::Invalid(format!(
                "{what} of {width} by {height} pixels"
            )));
        }
    }
    Ok(())
}

/// D-187: a group photo (and D-211: your own picture).
fn check_photo(p: &str) -> Result<(), GroupOpError> {
    check_jpeg_hex(
        "photo",
        p,
        PICTURE_MAX_BYTES,
        PICTURE_MAX_SIDE,
        PICTURE_MAX_PIXELS,
    )
}

/// D-211: a member's preview.
fn check_thumb(p: &str) -> Result<(), GroupOpError> {
    check_jpeg_hex(
        "thumb",
        p,
        THUMB_MAX_BYTES,
        THUMB_MAX_SIDE,
        THUMB_MAX_PIXELS,
    )
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

/// 32 bytes as 64 lowercase hex.
fn check_key(key: &str) -> Result<(), GroupOpError> {
    let ok = key.len() == 64
        && key
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    if ok {
        Ok(())
    } else {
        Err(GroupOpError::Invalid(format!("key {key:?}")))
    }
}

/// The exact bytes a group operation record carries. Deterministic: `op`
/// first, then the fields in declaration order, absent names omitted, so two
/// devices encoding the same operation write the same bytes.
pub fn encode(op: &GroupOp) -> Result<Vec<u8>, GroupOpError> {
    validate(op)?;
    serde_json::to_vec(op).map_err(|e| GroupOpError::Unreadable(e.to_string()))
}

pub fn decode(bytes: &[u8]) -> Result<GroupOp, GroupOpError> {
    let op: GroupOp =
        serde_json::from_slice(bytes).map_err(|e| GroupOpError::Unreadable(e.to_string()))?;
    validate(&op)?;
    Ok(op)
}

/// A group operation record as it sits in the vault: identity and payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpRecord {
    pub id: VersionId,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedMember {
    pub id: String,
    /// The display name: the latest name written for this member, told
    /// apart from another member's identical name with " (2)", " (3)".
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedGroup {
    pub id: String,
    pub name: String,
    pub settlement_currency: String,
    pub members: Vec<ProjectedMember>,
    /// The earliest create's identity: when the group entered the account.
    pub created: String,
    /// D-157: members linked to an account, in member order.
    pub claims: Vec<ProjectedClaim>,
    /// D-186: members taken out of the group (still in `members`, so what
    /// they are in still balances), in member order.
    pub removed: Vec<String>,
    /// D-187: the group's picture, the latest written; None for the initial.
    pub picture: Option<GroupPicture>,
    /// D-211: members with a marked profile, in member order.
    pub profiles: Vec<ProjectedProfile>,
    /// D-230: the applied marked held places, in member order, the latest
    /// per member. Raw ids (no merge), so a phone can compare with `place`.
    pub held: Vec<ProjectedHeld>,
    /// D-229: this account's place here, the raw member id the group knows it
    /// by (module doc, "the place"); None with no "you" or no place.
    pub place: Option<String>,
    /// D-229: the id this account writes as in this group's records: the
    /// place, whenever the account's "you" is not readable to the group's
    /// other members. None means "write as you" ONLY when `place_lost` is
    /// false; with `place_lost` true nothing this account writes here reads
    /// as itself.
    pub you_as: Option<String>,
    /// D-229: the account's own "you" ids (its `you` records) that the
    /// group's other members cannot read: not a member here and aliased by no
    /// claim or `placeAlias`. Sorted; empty when the account has no place.
    pub unaliased_yous: Vec<String>,
    /// D-229 decision 45: this account has no place here although it had
    /// one: one of its ids is a member that another account's winning claim
    /// holds (D-227 decision 36 seen from the creator's phone), or its own
    /// claim names a contested id (decision 44: claimed on two present
    /// members; decision 47: contesting a marked alias). Records it would
    /// write here read as someone else or nobody; the shells make the group
    /// read-only. False with no "you".
    pub place_lost: bool,
}

/// D-187: a group's picture: an emoji or a JPEG (lowercase hex), not both.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupPicture {
    pub emoji: Option<String>,
    pub photo: Option<String>,
}

/// D-211: a joined person's preview in a group. `member` is the id the
/// member is SHOWN by, after this account's merge of its own places (the
/// source `MemberProfile.member` is the server-checked place id, with no
/// alias mapping). Its presence means their name is their own (Rename is
/// absent); for a merged member it is the latest profile of any of its places.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedProfile {
    pub member: String,
    pub emoji: Option<String>,
    pub thumb: Option<String>,
}

/// D-230: a place the server marked as held: `member` is the raw place id,
/// `you` the holder's id (equal to `member` for a creator's raw place).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedHeld {
    pub member: String,
    pub you: String,
}

/// D-157: a member some account claimed as its own. `member` is the id the
/// group knows them by (before D-154's merge); `you` is the claiming account's
/// "you" id: equal to this account's "you", it is you.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedClaim {
    pub member: String,
    pub you: String,
    /// In first-claimed order; a later claim by the same account adds its
    /// new devices.
    pub devices: Vec<ClaimDevice>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct You {
    pub id: String,
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct GroupsProjection {
    pub you: Option<You>,
    /// Other ids some device wrote as "you", sorted: the same person, to be
    /// merged (the founder's decision; its own unit).
    pub other_you_ids: Vec<String>,
    /// In creation order.
    pub groups: Vec<ProjectedGroup>,
    /// Record ids whose payload could not be read. Reported, never dropped.
    pub rejected: Vec<String>,
    /// Record ids that wait for something not yet arrived: an operation on a
    /// group whose creation is missing, or a rename of a member never added.
    pub pending: Vec<String>,
    /// D-157: record ids of claims on a member another account claimed
    /// first. The first claim stands; these are reported, never applied.
    pub contested: Vec<String>,
    /// D-158: every group key this account holds, sorted by group, then
    /// epoch, then first written; one key appears once. Seal with the FIRST
    /// key of a group's highest epoch; open by trying each key of the group,
    /// so a key two devices minted at once loses nothing.
    pub keys: Vec<GroupKeyHeld>,
    /// D-158: other people who joined a group: in `group`, `from` (their
    /// "you") reads as `to` (the member they claimed). Sorted.
    pub aliases: Vec<Alias>,
    /// D-178: the account's Maximum security choice, the latest written;
    /// off when never written.
    pub maximum_security: bool,
    /// D-185: each phone's name, the latest written for it; sorted by device.
    pub device_names: Vec<(String, String)>,
    /// D-211: the account's picture, the latest written; None when never
    /// written or cleared.
    pub avatar: Option<GroupPicture>,
    /// D-244 (ADR-0043): the account's settings, the latest write per key,
    /// sorted by key. Every valid key is listed, known to the shells or not.
    pub settings: Vec<ProjectedSetting>,
}

/// D-244: one setting as the account holds it: the winning value and the
/// identity of the record that wrote it (a shell remembers which it applied,
/// and knows its own by the device).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedSetting {
    pub key: String,
    pub value: String,
    pub id: VersionId,
}

/// D-158: one alias (see `GroupsProjection::aliases`).
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Alias {
    pub group: String,
    pub from: String,
    pub to: String,
}

/// D-158: one group key held by the account.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupKeyHeld {
    pub group: String,
    pub epoch: u32,
    pub key: String,
}

/// A last-writer-wins register: keeps the value written by the greatest id.
fn write<T: Clone>(slot: &mut Option<(VersionId, T)>, id: &VersionId, value: &T) {
    if slot.as_ref().is_none_or(|(held, _)| id > held) {
        *slot = Some((id.clone(), value.clone()));
    }
}

/// Records → groups and "you". Pure and order-independent.
pub fn project(records: &[OpRecord]) -> GroupsProjection {
    // Idempotent by identity, like the vault's record index. Two different
    // payloads can never share an HLC; if a damaged set did, the smaller
    // payload wins so the result is still deterministic.
    let mut by_id: BTreeMap<VersionId, &[u8]> = BTreeMap::new();
    for r in records {
        by_id
            .entry(r.id.clone())
            .and_modify(|kept| {
                if r.bytes.as_slice() < *kept {
                    *kept = &r.bytes;
                }
            })
            .or_insert(&r.bytes);
    }

    let mut out = GroupsProjection::default();
    let mut ops: Vec<(VersionId, GroupOp)> = Vec::new();
    for (id, bytes) in by_id {
        match decode(bytes) {
            Ok(op) => ops.push((id, op)),
            Err(_) => out.rejected.push(id.render()),
        }
    }
    // `ops` is ascending by identity from here on.

    // ---- you ----
    let mut you_id: Option<&str> = None;
    let mut you_ids: BTreeSet<&str> = BTreeSet::new();
    for (_, op) in &ops {
        if let GroupOp::You { id, .. } = op {
            you_id.get_or_insert(id.as_str());
            you_ids.insert(id.as_str());
        }
    }
    if let Some(you) = you_id {
        let mut name: Option<(VersionId, String)> = None;
        for (vid, op) in &ops {
            if let GroupOp::You { id, name: Some(n) } = op {
                if id == you {
                    write(&mut name, vid, n);
                }
            }
        }
        out.you = Some(You {
            id: you.to_string(),
            name: name.map(|(_, n)| n),
        });
        out.other_you_ids = you_ids
            .into_iter()
            .filter(|id| *id != you)
            .map(str::to_string)
            .collect();
    }

    // ---- groups ----
    // D-187: a picture as written: (emoji, photo).
    type PictureParts = (Option<String>, Option<String>);
    // D-211: a joined person's profile as written: (name, (emoji, thumb)).
    type ProfileParts = (String, PictureParts);
    struct Building {
        created: VersionId,
        currency: String,
        name: Option<(VersionId, String)>,
        order: Vec<String>,
        names: BTreeMap<String, Option<(VersionId, String)>>,
        claims: BTreeMap<String, ProjectedClaim>,
        // D-186: whether each member is in the group, the latest of their
        // adds and removals.
        present: BTreeMap<String, Option<(VersionId, bool)>>,
        // D-187: the latest picture written: (emoji, photo).
        picture: Option<(VersionId, PictureParts)>,
        // D-211: each joined person's own name and preview, the latest write
        // per member: (name, (emoji, thumb)).
        profiles: BTreeMap<String, Option<(VersionId, ProfileParts)>>,
        // D-229: the applied marked aliases, `you` -> the place `member`.
        marked: BTreeMap<String, String>,
        // D-229 decision 50: the record identity of each applied marked
        // alias (`you` -> its record) and of each member's winning claim
        // (member -> its first record).
        marked_at: BTreeMap<String, VersionId>,
        claimed_at: BTreeMap<String, VersionId>,
        // D-229 decision 50: REMOVED members whose winning claim was written
        // after a present member's winning claim naming the same id: ignored
        // for aliasing and for the merge into "you".
        stale: BTreeSet<String>,
        // D-229: every alias of the group, as any phone reads it before its
        // own ids are taken out: `from` -> `to`, one per `from`.
        aliases: BTreeMap<String, String>,
        // D-229: the aliases the group's claims give (0.4.0 builds read only
        // these): `from` -> `to`.
        claim_aliases: BTreeMap<String, String>,
        // D-229 decision 44 (narrowed by 49): `you` ids that the winning
        // claims of two or more present members name, and (decision 47) ids
        // a claim on another member contests a marked alias of. Such an id
        // gets no alias, makes no place and joins no account's other ids (a
        // denial, never a takeover).
        split: BTreeSet<String>,
        // D-230: the marked held places, member -> (record identity, you),
        // the latest per member; and the id each gives, you -> member.
        held: BTreeMap<String, (VersionId, String)>,
        held_ids: BTreeMap<String, String>,
        // D-230 decision 59: the record identity of each member's first add
        // (create or addMember); a held place's claim counts from it.
        added: BTreeMap<String, VersionId>,
    }
    let mut groups: BTreeMap<&str, Building> = BTreeMap::new();
    // Pass 1: the earliest create of each group fixes it into existence.
    for (vid, op) in &ops {
        if let GroupOp::Create {
            group,
            settlement_currency,
            ..
        } = op
        {
            groups.entry(group.as_str()).or_insert_with(|| Building {
                created: vid.clone(),
                currency: settlement_currency.clone(),
                name: None,
                order: Vec::new(),
                names: BTreeMap::new(),
                claims: BTreeMap::new(),
                marked_at: BTreeMap::new(),
                claimed_at: BTreeMap::new(),
                stale: BTreeSet::new(),
                present: BTreeMap::new(),
                picture: None,
                profiles: BTreeMap::new(),
                marked: BTreeMap::new(),
                aliases: BTreeMap::new(),
                claim_aliases: BTreeMap::new(),
                split: BTreeSet::new(),
                held: BTreeMap::new(),
                held_ids: BTreeMap::new(),
                added: BTreeMap::new(),
            });
        }
    }
    fn add(b: &mut Building, vid: &VersionId, m: &OpMember) {
        if !b.names.contains_key(&m.id) {
            b.order.push(m.id.clone());
            b.names.insert(m.id.clone(), None);
            b.added.insert(m.id.clone(), vid.clone());
        }
        if let (Some(n), Some(slot)) = (&m.name, b.names.get_mut(&m.id)) {
            write(slot, vid, n);
        }
        write(b.present.entry(m.id.clone()).or_insert(None), vid, &true);
    }
    // D-229: this account's place in one group, the id it writes as there,
    // and its own ids nobody else can read there (module doc, "the place").
    // `mine` is final: "you", its other ids, and the members it claimed.
    // Returns (place, you_as, unaliased_yous, place_lost).
    fn placing(
        b: &Building,
        you: Option<&str>,
        mine: &BTreeSet<String>,
        you_records: &BTreeSet<String>,
    ) -> (Option<String>, Option<String>, Vec<String>, bool) {
        let Some(you) = you else {
            return (None, None, Vec::new(), false);
        };
        let present = |m: &str| matches!(b.present.get(m), Some(Some((_, true))));
        let another = |m: &str| b.claims.get(m).is_some_and(|c| !mine.contains(&c.you));
        let raw = |m: &str| b.names.contains_key(m);
        // Rule 1: "you" itself, unless a held place gives that id to
        // another member (decision 60: a raw member added with a held id is
        // an empty placeholder, never the holder's place; rule 1.5 places
        // the account at the held member).
        let held_elsewhere = b.held_ids.get(you).is_some_and(|m| m != you);
        let own = raw(you) && present(you) && !another(you) && !held_elsewhere;
        let place = if own {
            Some(you.to_string())
        } else {
            // D-230 D6: the present member whose held place names one of
            // this account's ids, before any phone-written claim (a
            // contested id makes no place here either, decision 44).
            b.order
                .iter()
                .find(|m| {
                    b.held
                        .get(*m)
                        .is_some_and(|(_, y)| mine.contains(y) && !b.split.contains(y))
                        && present(m)
                })
                // Rule 2: the first claim of this account, in member order (a
                // claim naming a contested id makes no place, decision 44).
                .or_else(|| {
                    b.order.iter().find(|m| {
                        b.claims.get(*m).is_some_and(|c| {
                            mine.contains(&c.you) && !b.split.contains(&c.you) && present(m)
                        })
                    })
                })
                // Rule 3: the first member that is one of this account's ids
                // (never a raw id a held place gives to another member: the
                // re-review's M-1).
                .or_else(|| {
                    b.order.iter().find(|m| {
                        mine.contains(*m)
                            && present(m)
                            && !another(m)
                            && !b.held_ids.get(*m).is_some_and(|h| h != *m)
                    })
                })
                .cloned()
        };
        // "You" is readable when it is the place itself, or when a claim's
        // alias reads it (uncontested, decisions 44 and 47, and not voided by
        // the hardening; it is then also the alias every phone reads) on a
        // present member: 0.4.0 builds read only claim aliases. A marked
        // alias does not count: 0.4.0 builds cannot read one, and they read
        // the place.
        let claimed = b.claim_aliases.get(you).is_some_and(|to| present(to));
        let you_as = if own || claimed { None } else { place.clone() };
        // Decision 45: the place this account had here is held by another
        // account (one of its ids is a member another account's winning
        // claim holds: D-227 decision 36 seen from the creator's phone), or
        // its own claim is contested, and no place is left. Writing "you"
        // would then read as someone else, or as nobody.
        let lost = place.is_none()
            && (mine.iter().any(|id| raw(id) && another(id))
                || b.claims
                    .values()
                    .any(|c| mine.contains(&c.you) && b.split.contains(&c.you)));
        let unaliased = match place {
            None => Vec::new(),
            Some(_) => you_records
                .iter()
                .filter(|id| !raw(id) && !b.aliases.contains_key(*id))
                .cloned()
                .collect(),
        };
        (place, you_as, unaliased, lost)
    }
    // Pass 2: every operation, ascending, so members appear in the order
    // they were first written, whatever order the records arrived in.
    let mut renames: Vec<(&VersionId, &str, &str, &str)> = Vec::new();
    for (vid, op) in &ops {
        match op {
            GroupOp::Create {
                group,
                name,
                members,
                ..
            } => {
                let b = groups.get_mut(group.as_str()).expect("pass 1 saw it");
                write(&mut b.name, vid, name);
                for m in members {
                    add(b, vid, m);
                }
            }
            GroupOp::AddMember { group, member } => match groups.get_mut(group.as_str()) {
                Some(b) => add(b, vid, member),
                None => out.pending.push(vid.render()),
            },
            GroupOp::RenameGroup { group, name } => match groups.get_mut(group.as_str()) {
                Some(b) => write(&mut b.name, vid, name),
                None => out.pending.push(vid.render()),
            },
            GroupOp::RenameMember {
                group,
                member,
                name,
            } => renames.push((vid, group.as_str(), member.as_str(), name.as_str())),
            GroupOp::RemoveMember { group, member } => match groups.get_mut(group.as_str()) {
                Some(b) => write(b.present.entry(member.clone()).or_insert(None), vid, &false),
                None => out.pending.push(vid.render()),
            },
            GroupOp::GroupPicture {
                group,
                emoji,
                photo,
            } => match groups.get_mut(group.as_str()) {
                Some(b) => write(&mut b.picture, vid, &(emoji.clone(), photo.clone())),
                None => out.pending.push(vid.render()),
            },
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
            GroupOp::You { .. }
            | GroupOp::Claim { .. }
            | GroupOp::GroupKey { .. }
            | GroupOp::Security { .. }
            | GroupOp::DeviceName { .. }
            | GroupOp::Avatar { .. }
            | GroupOp::PlaceAlias { .. }
            | GroupOp::HeldPlace { .. }
            | GroupOp::Setting { .. } => {}
        }
    }
    // D-230: held places, once the member set is complete. Per member the
    // LATEST by record identity stands (the server bounds a marked record's
    // clock to ten minutes of its own, decision 54); an older one is reported
    // contested, never applied silently. A `you` that is another raw member
    // stands (decision 60: the server never marks a raw place's id, so such
    // a member was added with the held id afterwards, or by someone else;
    // the mark wins and that raw id reads as the held member). One for a
    // place nobody added waits.
    for (vid, op) in &ops {
        let GroupOp::HeldPlace { group, member, you } = op else {
            continue;
        };
        let Some(b) = groups
            .get_mut(group.as_str())
            .filter(|b| b.names.contains_key(member))
        else {
            out.pending.push(vid.render());
            continue;
        };
        match b.held.get(member) {
            // Defensive and unreachable: `ops` is ascending and identities
            // are unique, so an applied mark is never later than this one.
            Some((at, _)) if at > vid => out.contested.push(vid.render()),
            _ => {
                if let Some((old, _)) = b.held.insert(member.clone(), (vid.clone(), you.clone())) {
                    out.contested.push(old.render());
                }
            }
        }
    }
    for b in groups.values_mut() {
        // Decision 59: an id held by two places (an honest rejoin, both
        // marked) gives itself to the EARLIEST-ADDED of them, as its alias
        // does (decision 51, by `claimed_at` below, ties by member id), never
        // to the latest mark: the order the marks were written in changes
        // nothing.
        let mut by_added: Vec<(&String, &String)> =
            b.held.iter().map(|(m, (_, y))| (m, y)).collect();
        by_added.sort_by(|x, y| (b.added.get(x.0), x.0).cmp(&(b.added.get(y.0), y.0)));
        let mut ids = BTreeMap::new();
        for (member, you) in by_added {
            ids.entry(you.clone()).or_insert_with(|| member.clone());
        }
        b.held_ids = ids;
        // D4: the held place is the member's winning claim, seeded before
        // any phone-written claim is read. Decision 59: it counts from the
        // member's first add, not from the mark (the mark's identity stays
        // in `held` for D2's latest-per-member rule).
        let seeded: Vec<(String, VersionId, String)> = b
            .held
            .iter()
            .map(|(m, (v, y))| (m.clone(), b.added.get(m).unwrap_or(v).clone(), y.clone()))
            .collect();
        for (member, vid, you) in seeded {
            b.claimed_at.insert(member.clone(), vid);
            b.claims.insert(
                member.clone(),
                ProjectedClaim {
                    member,
                    you,
                    devices: Vec::new(),
                },
            );
        }
    }
    // D-157: claims, once the member set is complete. Ascending, so the
    // first claim of a member decides whose it is; the same account claiming
    // again adds devices; another account's claim is contested.
    for (vid, op) in &ops {
        let GroupOp::Claim {
            group,
            member,
            you,
            devices,
        } = op
        else {
            continue;
        };
        let Some(b) = groups
            .get_mut(group.as_str())
            .filter(|b| b.names.contains_key(member))
        else {
            out.pending.push(vid.render());
            continue;
        };
        // D-230 D4: a claim naming an id a held place gives to another member
        // is void; so is a claim with another `you` on a held member (the
        // match below reports it contested, since the seeded claim stands).
        // A claim on a held member with the mark's own `you` is the holder's
        // own (an honest rejoin's old claim): it adds devices, never void.
        let own_held = b.held.get(member).is_some_and(|(_, y)| y == you);
        if !own_held && b.held_ids.get(you).is_some_and(|m| m != member) {
            out.contested.push(vid.render());
            continue;
        }
        match b.claims.get_mut(member) {
            None => {
                b.claimed_at.insert(member.clone(), vid.clone());
                b.claims.insert(
                    member.clone(),
                    ProjectedClaim {
                        member: member.clone(),
                        you: you.clone(),
                        devices: devices.clone(),
                    },
                );
            }
            Some(held) if held.you == *you => {
                for d in devices {
                    if !held.devices.iter().any(|h| h.id == d.id) {
                        held.devices.push(d.clone());
                    }
                }
            }
            Some(_) => out.contested.push(vid.render()),
        }
    }
    // D-229 decision 44 (as decision 49 narrows it): ids that the winning
    // claims of two or more PRESENT members of one group name. A claim on a
    // removed member does not count: an honest rejoin (D-210 re-invites a
    // removed person to a new member, the same "you" claims it) stays one
    // person, and removing a forger's member lifts the denial.
    for b in groups.values_mut() {
        let mut seen: BTreeSet<&str> = BTreeSet::new();
        let mut split: BTreeSet<String> = BTreeSet::new();
        for c in b.claims.values() {
            let present = matches!(b.present.get(&c.member), Some(Some((_, true))));
            if present && !seen.insert(c.you.as_str()) {
                split.insert(c.you.clone());
            }
        }
        b.split = split;
        // Decision 50: a claim on a removed member counts for an id's alias
        // (and merge) only when it was written before every present member's
        // claim naming that id. An honest rejoin's old claim always is; a
        // claim forged later (then its member removed, or written on an
        // already removed member) is not, so removal never hands a person's
        // history to the removed member. A backdated record identity can
        // still get past this (CLOUD-SECURITY G-08).
        let present = |m: &str| matches!(b.present.get(m), Some(Some((_, true))));
        let stale: BTreeSet<String> = b
            .claims
            .values()
            .filter(|c| !present(&c.member))
            .filter(|c| {
                b.claims.values().any(|d| {
                    d.you == c.you
                        && present(&d.member)
                        && b.claimed_at.get(&d.member) < b.claimed_at.get(&c.member)
                })
            })
            .map(|c| c.member.clone())
            .collect();
        b.stale = stale;
    }
    // D-229: marked aliases, once the member set is complete. Ascending, so
    // the earliest alias of an id stands; a later one for the same id, or one
    // whose `you` is a member of the group (it would take that member's
    // records over), is contested. One for a place nobody added waits.
    for (vid, op) in &ops {
        let GroupOp::PlaceAlias { group, member, you } = op else {
            continue;
        };
        let Some(b) = groups
            .get_mut(group.as_str())
            .filter(|b| b.names.contains_key(member))
        else {
            out.pending.push(vid.render());
            continue;
        };
        // D-230 D5: a held id reads only as its held member.
        if b.held_ids.get(you).is_some_and(|m| m != member) {
            out.contested.push(vid.render());
            continue;
        }
        if b.marked.get(you) == Some(member) && !b.names.contains_key(you) {
            // The same alias again: already applied.
            continue;
        }
        if b.names.contains_key(you) || b.marked.contains_key(you) {
            out.contested.push(vid.render());
            continue;
        }
        b.marked.insert(you.clone(), member.clone());
        b.marked_at.insert(you.clone(), vid.clone());
    }
    // D-229 decision 47: a winning claim naming a marked alias's id on
    // ANOTHER member (the id's owner says it is someone else here) contests
    // the alias: the id joins the contested ids of decision 44 and reads as
    // nobody, never as either member.
    for b in groups.values_mut() {
        let denied: Vec<String> = b
            .marked
            .iter()
            .filter(|(you, member)| {
                // Decision 50: a claim on a removed member contests only when
                // it was written before the alias.
                b.claims.values().any(|c| {
                    &c.you == *you
                        && &c.member != *member
                        && (matches!(b.present.get(&c.member), Some(Some((_, true))))
                            || b.claimed_at.get(&c.member) < b.marked_at.get(*you))
                })
            })
            .map(|(you, _)| you.clone())
            .collect();
        b.split.extend(denied);
    }
    // D-229: the account's own "you" ids as its `you` records wrote them,
    // before claims add the members it claimed (`unaliased_yous`).
    let you_records: BTreeSet<String> = out
        .you
        .iter()
        .map(|y| y.id.clone())
        .chain(out.other_you_ids.iter().cloned())
        .collect();
    // A member THIS account claimed is one more "you" (D-154's merge makes
    // them one person: the joiner sees themselves where the group put them).
    // D-229: so is the place of a marked alias of one of its ids.
    // D-237 (the Fable audit, A1): in ITS OWN GROUP only. `other_you_ids`
    // still lists every claimed place (the shells name them and test "is
    // this me" with it), but whatever reads a place as "you" (the member
    // merge, `placing`, and the FFI merges through the aliases emitted
    // below) is per group, from `claimed_in`. A co-member can mint any id
    // into a group it makes, so a place id named in another group's log
    // must never read as this account elsewhere: with a global merge, one
    // accepted invite made every record of that id's real holder, in every
    // group they share, this account's on this phone.
    let mut claimed_in: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    if let Some(y) = &out.you {
        let mine: BTreeSet<&str> = out
            .other_you_ids
            .iter()
            .map(String::as_str)
            .chain([y.id.as_str()])
            .collect();
        let mut claimed: BTreeSet<String> = out.other_you_ids.iter().cloned().collect();
        for (group, b) in groups.iter() {
            for c in b.claims.values() {
                if mine.contains(c.you.as_str())
                    && c.member != y.id
                    && !b.split.contains(&c.you)
                    && !b.stale.contains(&c.member)
                {
                    claimed.insert(c.member.clone());
                    claimed_in
                        .entry(group.to_string())
                        .or_default()
                        .insert(c.member.clone());
                }
            }
            for (you, member) in &b.marked {
                if mine.contains(you.as_str()) && *member != y.id && !b.split.contains(you) {
                    claimed.insert(member.clone());
                    claimed_in
                        .entry(group.to_string())
                        .or_default()
                        .insert(member.clone());
                }
            }
        }
        out.other_you_ids = claimed.into_iter().collect();
    }
    // D-158: another account's claim makes their "you" id read, in that
    // group, as the member they claimed: their app writes its own "you".
    // D-229 (Design 6): one alias per (group, from id): a marked alias first,
    // else the earliest-written winning claim naming that id that still
    // counts (decisions 44, 49 to 51). A claim whose `you` is a member of the
    // same group gives no alias (the claim itself stands): otherwise any
    // member could make another member's records read as an unclaimed
    // member's.
    // D-237 round 2 (review I1): an alias is left out of the account's own
    // list PER GROUP, as the merge now is: for the account's own "you"
    // records and the places it claimed HERE. An id claimed as a place in
    // ANOTHER group (a co-member copied someone's "you" id into a group it
    // made) keeps its alias here, so that person's records still read as
    // their place on this phone instead of as an id nobody has.
    let own_here = |group: &str, id: &String| -> bool {
        you_records.contains(id) || claimed_in.get(group).is_some_and(|c| c.contains(id))
    };
    for (group, b) in groups.iter_mut() {
        let mut claim_aliases: BTreeMap<String, String> = BTreeMap::new();
        // Decision 51: the EARLIEST-WRITTEN winning claim that still counts
        // (record identity order), never member order: with no present claim
        // to compare against (the person left, or is between removal and
        // rejoin), member order would let a later claim on an earlier member
        // take the history.
        let mut by_written: Vec<&String> = b.claims.keys().collect();
        by_written.sort_by(|x, y| b.claimed_at.get(*x).cmp(&b.claimed_at.get(*y)));
        for m in by_written {
            let Some(c) = b.claims.get(m) else { continue };
            // Decision 60: a held place's claim aliases its id even when a
            // raw member has that id (the mark wins over the placeholder).
            let seeded = b.held.get(&c.member).is_some_and(|(_, y)| *y == c.you);
            // A6: an id a held place gives to ANOTHER member aliases only
            // there (or, held by itself, nowhere): a removed creator's rejoin
            // mark {7, you C} never moves C's history to 7.
            let held_elsewhere = b.held_ids.get(&c.you).is_some_and(|h| *h != c.member);
            if c.you == c.member
                || (b.names.contains_key(&c.you) && !seeded)
                || held_elsewhere
                || b.split.contains(&c.you)
                || b.stale.contains(&c.member)
            {
                continue;
            }
            // Removed members included (decisions 49 and 51): a rejoiner's
            // history stays on the old member, written first, where the
            // removal's redistribution expects it.
            claim_aliases
                .entry(c.you.clone())
                .or_insert_with(|| c.member.clone());
        }
        let mut aliases: BTreeMap<String, String> = b
            .marked
            .iter()
            .filter(|(you, _)| !b.split.contains(*you))
            .map(|(you, member)| (you.clone(), member.clone()))
            .collect();
        for (from, to) in &claim_aliases {
            aliases.entry(from.clone()).or_insert_with(|| to.clone());
        }
        b.claim_aliases = claim_aliases;
        for (from, to) in &aliases {
            if !own_here(group, from) {
                out.aliases.push(Alias {
                    group: group.to_string(),
                    from: from.clone(),
                    to: to.clone(),
                });
            }
        }
        b.aliases = aliases;
    }
    out.aliases.sort();

    // Renames apply once the member set is complete: a rename that arrived
    // before the member's add still lands, and one for a member nobody added
    // waits. D-158: a rename of another person's "you" lands on the member
    // they claimed.
    for (vid, group, member, name) in renames {
        let member = out
            .aliases
            .iter()
            .find(|a| a.group == group && a.from == member)
            .map_or(member, |a| a.to.as_str());
        match groups.get_mut(group).and_then(|b| b.names.get_mut(member)) {
            Some(slot) => write(slot, vid, &name.to_string()),
            None => out.pending.push(vid.render()),
        }
    }
    // D-211: a profile for a place nobody added waits, as a rename does. (A
    // profile beats every rename without any write here: the build below
    // takes a shown member's name and preview from the latest profile among
    // the places merged into them before it reads `names`.)
    for b in groups.values() {
        for (m, slot) in &b.profiles {
            if let Some((v, _)) = slot {
                if !b.names.contains_key(m) {
                    out.pending.push(v.render());
                }
            }
        }
    }

    let mut built: Vec<(&str, Building)> = groups.into_iter().collect();
    built.sort_by(|a, b| a.1.created.cmp(&b.1.created));
    let you = out.you.as_ref().map(|y| y.id.clone());
    // D-237 (A1): the places this account claimed, as aliases to "you" in
    // their own group, so the FFI merges (`Yous::with_aliases`) read each as
    // you there and nowhere else. The account's own "you" records (its other
    // phones' ids) stay global and are not listed. After the renames above:
    // a rename of such a place lands on the place.
    if let Some(y) = &you {
        for (group, members) in &claimed_in {
            for m in members {
                if !you_records.contains(m) {
                    out.aliases.push(Alias {
                        group: group.clone(),
                        from: m.clone(),
                        to: y.clone(),
                    });
                }
            }
        }
        out.aliases.sort();
    }
    // D-154: one "you" per account. A member id that another device wrote as
    // "you" reads as the account's "you": in its first position, named by
    // "you"'s own name in this group if it has one. D-237 (A1): per group,
    // the account's own "you" records and the places it claimed HERE.
    let mine_in = |group: &str| -> BTreeSet<String> {
        you_records
            .iter()
            .cloned()
            .chain(claimed_in.get(group).into_iter().flatten().cloned())
            .collect()
    };
    for (id, b) in built {
        let mine = mine_in(id);
        let yous = you
            .as_deref()
            .map(|y| super::identity::Yous::new(y, &mine.iter().cloned().collect::<Vec<_>>()));
        let mut merged: Vec<ProjectedMember> = Vec::with_capacity(b.order.len());
        let mut profiles: Vec<ProjectedProfile> = Vec::new();
        for m in &b.order {
            let own = b.names[m].as_ref().map(|(_, n)| n.clone());
            let mid = yous.as_ref().map_or_else(|| m.clone(), |y| y.id(m));
            if merged.iter().any(|x| x.id == mid) {
                continue;
            }
            // D-211: of the places merged into this shown member, the one
            // with the LATEST profile gives both its name and its preview.
            let profile = b
                .order
                .iter()
                .filter(|p| yous.as_ref().map_or_else(|| *p == &mid, |y| y.id(p) == mid))
                .filter_map(|p| b.profiles.get(p).and_then(Option::as_ref))
                .max_by(|x, y| x.0.cmp(&y.0));
            let name = if let Some((_, (name, _))) = profile {
                Some(name.clone())
            } else if &mid == m {
                own
            } else {
                b.names
                    .get(&mid)
                    .and_then(|slot| slot.as_ref().map(|(_, n)| n.clone()))
                    .or(own)
            };
            if let Some((_, (_, (emoji, thumb)))) = profile {
                profiles.push(ProjectedProfile {
                    member: mid.clone(),
                    emoji: emoji.clone(),
                    thumb: thumb.clone(),
                });
            }
            merged.push(ProjectedMember { id: mid, name });
        }
        let own: BTreeSet<&str> = profiles.iter().map(|p| p.member.as_str()).collect();
        let members = distinct_names(merged, you.as_deref(), &own);
        // D-186: removed as the latest write says, under the id the member
        // is shown by (one "you" per account), once each.
        let mut removed: Vec<String> = Vec::new();
        for m in &b.order {
            if matches!(b.present.get(m), Some(Some((_, false)))) {
                let mid = yous.as_ref().map_or_else(|| m.clone(), |y| y.id(m));
                if !removed.contains(&mid) {
                    removed.push(mid);
                }
            }
        }
        let (place, you_as, unaliased_yous, place_lost) =
            placing(&b, you.as_deref(), &mine, &you_records);
        let claims = b
            .order
            .iter()
            .filter_map(|m| b.claims.get(m).cloned())
            .collect();
        let held = b
            .order
            .iter()
            .filter_map(|m| {
                b.held.get(m).map(|(_, you)| ProjectedHeld {
                    member: m.clone(),
                    you: you.clone(),
                })
            })
            .collect();
        out.groups.push(ProjectedGroup {
            id: id.to_string(),
            name: b.name.map(|(_, n)| n).expect("a create wrote the name"),
            settlement_currency: b.currency,
            members,
            created: b.created.render(),
            claims,
            removed,
            picture: b.picture.and_then(|(_, (emoji, photo))| {
                (emoji.is_some() || photo.is_some()).then_some(GroupPicture { emoji, photo })
            }),
            profiles,
            held,
            place,
            you_as,
            unaliased_yous,
            place_lost,
        });
    }
    out.pending.sort();
    out.contested.sort();
    // D-158: keys. `ops` is ascending, so a stable sort by (group, epoch)
    // keeps each epoch's keys in the order they were first written.
    let mut keys: Vec<GroupKeyHeld> = Vec::new();
    for (_, op) in &ops {
        if let GroupOp::GroupKey { group, epoch, key } = op {
            let held = GroupKeyHeld {
                group: group.clone(),
                epoch: *epoch,
                key: key.clone(),
            };
            if !keys.contains(&held) {
                keys.push(held);
            }
        }
    }
    keys.sort_by(|a, b| (&a.group, a.epoch).cmp(&(&b.group, b.epoch)));
    out.keys = keys;

    // ---- D-178: Maximum security, the latest write ----
    let mut security: Option<(VersionId, bool)> = None;
    for (vid, op) in &ops {
        if let GroupOp::Security { maximum } = op {
            write(&mut security, vid, maximum);
        }
    }
    out.maximum_security = security.is_some_and(|(_, on)| on);

    // ---- D-185: each phone's name, the latest write per device ----
    let mut names: BTreeMap<&str, Option<(VersionId, String)>> = BTreeMap::new();
    for (vid, op) in &ops {
        if let GroupOp::DeviceName { device, name } = op {
            write(names.entry(device.as_str()).or_insert(None), vid, name);
        }
    }
    out.device_names = names
        .into_iter()
        .filter_map(|(device, v)| v.map(|(_, name)| (device.to_string(), name)))
        .collect();

    // ---- D-211: your picture, the latest write ----
    let mut avatar: Option<(VersionId, PictureParts)> = None;
    for (vid, op) in &ops {
        if let GroupOp::Avatar { emoji, photo } = op {
            write(&mut avatar, vid, &(emoji.clone(), photo.clone()));
        }
    }
    out.avatar = avatar.and_then(|(_, (emoji, photo))| {
        (emoji.is_some() || photo.is_some()).then_some(GroupPicture { emoji, photo })
    });

    // ---- D-244: the account's settings, the latest write per key ----
    let mut settings: BTreeMap<&str, Option<(VersionId, String)>> = BTreeMap::new();
    for (vid, op) in &ops {
        if let GroupOp::Setting { key, value } = op {
            write(settings.entry(key.as_str()).or_insert(None), vid, value);
        }
    }
    out.settings = settings
        .into_iter()
        .filter_map(|(key, v)| {
            v.map(|(id, value)| ProjectedSetting {
                key: key.to_string(),
                value,
                id,
            })
        })
        .collect();
    out
}

/// Tells apart OTHER members who share a name, case-insensitively, as
/// `ffi::named_group` compares them: the first keeps the name, later ones get
/// " (2)", " (3)" (shortened to fit 64 characters). "You" may share a name with
/// someone and is left alone, as `named_group` allows. D-211: joined people
/// (`own`, the members with a profile, including this account's own profiled
/// name) keep their own names and several may share one; typed names yield to
/// them, whatever the member order.
fn distinct_names(
    mut members: Vec<ProjectedMember>,
    you: Option<&str>,
    own: &BTreeSet<&str>,
) -> Vec<ProjectedMember> {
    let mut taken: BTreeSet<String> = BTreeSet::new();
    for m in members.iter() {
        if own.contains(m.id.as_str()) {
            if let Some(name) = &m.name {
                taken.insert(name.to_lowercase());
            }
        }
    }
    for m in members.iter_mut() {
        if Some(m.id.as_str()) == you || own.contains(m.id.as_str()) {
            continue;
        }
        let Some(name) = m.name.clone() else { continue };
        if taken.insert(name.to_lowercase()) {
            continue;
        }
        let mut n = 2u32;
        loop {
            let suffix = format!(" ({n})");
            let room = MEMBER_NAME_MAX_CHARS - suffix.chars().count();
            let base: String = name.chars().take(room).collect();
            let candidate = format!("{}{suffix}", base.trim_end());
            if taken.insert(candidate.to_lowercase()) {
                m.name = Some(candidate);
                break;
            }
            n += 1;
        }
    }
    members
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(n: u8) -> String {
        format!("{n:032x}")
    }

    fn vid(millis: u64, counter: u32, device: u8) -> VersionId {
        VersionId {
            millis,
            counter,
            device: hex(device),
        }
    }

    fn member(id: u8, name: Option<&str>) -> OpMember {
        OpMember {
            id: hex(id),
            name: name.map(str::to_string),
        }
    }

    fn create(group: u8, name: &str, members: Vec<OpMember>) -> GroupOp {
        GroupOp::Create {
            group: hex(group),
            name: name.into(),
            settlement_currency: "INR".into(),
            members,
        }
    }

    fn rec(id: VersionId, op: &GroupOp) -> OpRecord {
        OpRecord {
            id,
            bytes: encode(op).expect("valid"),
        }
    }

    fn names(g: &ProjectedGroup) -> Vec<(String, Option<String>)> {
        g.members
            .iter()
            .map(|m| (m.id.clone(), m.name.clone()))
            .collect()
    }

    // ---- the codec ----------------------------------------------------------

    /// Written out by hand: `op` first, fields in declaration order, absent
    /// names omitted. (Re-derived in Python in the D-152 drafts.)
    #[test]
    fn each_operation_encodes_to_exactly_these_bytes() {
        let cases: Vec<(GroupOp, &str)> = vec![
            (
                create(9, "Goa Trip", vec![member(1, Some("Tim")), member(2, None)]),
                concat!(
                    r#"{"op":"create","group":"00000000000000000000000000000009","#,
                    r#""name":"Goa Trip","settlementCurrency":"INR","members":["#,
                    r#"{"id":"00000000000000000000000000000001","name":"Tim"},"#,
                    r#"{"id":"00000000000000000000000000000002"}]}"#
                ),
            ),
            (
                GroupOp::AddMember {
                    group: hex(9),
                    member: member(3, Some("Priya")),
                },
                concat!(
                    r#"{"op":"addMember","group":"00000000000000000000000000000009","#,
                    r#""member":{"id":"00000000000000000000000000000003","name":"Priya"}}"#
                ),
            ),
            (
                GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(2),
                    name: "Rahul".into(),
                },
                concat!(
                    r#"{"op":"renameMember","group":"00000000000000000000000000000009","#,
                    r#""member":"00000000000000000000000000000002","name":"Rahul"}"#
                ),
            ),
            (
                GroupOp::RenameGroup {
                    group: hex(9),
                    name: "Goa 2026".into(),
                },
                r#"{"op":"renameGroup","group":"00000000000000000000000000000009","name":"Goa 2026"}"#,
            ),
            (
                GroupOp::You {
                    id: hex(1),
                    name: Some("Tim".into()),
                },
                r#"{"op":"you","id":"00000000000000000000000000000001","name":"Tim"}"#,
            ),
            (
                GroupOp::You {
                    id: hex(1),
                    name: None,
                },
                r#"{"op":"you","id":"00000000000000000000000000000001"}"#,
            ),
        ];
        for (op, expected) in cases {
            let bytes = encode(&op).unwrap();
            assert_eq!(String::from_utf8(bytes.clone()).unwrap(), expected);
            assert_eq!(decode(&bytes).unwrap(), op, "{expected}");
        }
    }

    /// Negative: every rule refuses on the way in AND on the way out, so a
    /// shell cannot write a bad operation and a damaged one cannot load.
    #[test]
    fn bad_operations_are_refused_both_ways() {
        let bad: Vec<GroupOp> = vec![
            create(9, "", vec![member(1, None)]),
            create(9, " Goa", vec![member(1, None)]),
            create(9, "Goa", vec![]),
            create(9, "Goa", vec![member(1, None), member(1, Some("Tim"))]),
            create(9, "Goa", vec![member(1, Some(" Tim"))]),
            create(9, "Goa", vec![member(1, Some("Ti\u{7}m"))]),
            create(9, "Goa", vec![member(1, Some(&"x".repeat(65)))]),
            GroupOp::Create {
                group: hex(9),
                name: "Goa".into(),
                settlement_currency: "inr".into(),
                members: vec![member(1, None)],
            },
            GroupOp::AddMember {
                group: "9".into(),
                member: member(1, None),
            },
            GroupOp::RenameMember {
                group: hex(9),
                member: hex(0xab).to_uppercase(),
                name: "Tim".into(),
            },
            GroupOp::RenameGroup {
                group: hex(9),
                name: "".into(),
            },
            GroupOp::You {
                id: hex(1),
                name: Some("".into()),
            },
        ];
        for op in bad {
            assert!(
                matches!(encode(&op), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
            let raw = serde_json::to_vec(&op).unwrap();
            assert!(
                matches!(decode(&raw), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
        }
        // Exactly 64 characters is allowed (the limit is inclusive).
        assert!(encode(&create(9, "Goa", vec![member(1, Some(&"é".repeat(64)))])).is_ok());
    }

    /// Negative: an operation from a newer build is unreadable, not half-read.
    #[test]
    fn unknown_kinds_and_fields_are_unreadable() {
        for raw in [
            // D-186 made removeMember a real kind; this one is still unknown.
            r#"{"op":"archiveGroup","group":"00000000000000000000000000000009"}"#,
            r#"{"op":"you","id":"00000000000000000000000000000001","colour":"blue"}"#,
            r#"{"group":"00000000000000000000000000000009"}"#,
            "not json",
        ] {
            assert!(
                matches!(decode(raw.as_bytes()), Err(GroupOpError::Unreadable(_))),
                "{raw}"
            );
        }
    }

    #[test]
    fn the_name_limit_matches_core_member_names() {
        assert_eq!(MEMBER_NAME_MAX_CHARS, crate::ffi::MEMBER_NAME_MAX_CHARS);
    }

    // ---- the projection -----------------------------------------------------

    #[test]
    fn a_create_alone_is_the_group() {
        let p = project(&[rec(
            vid(1000, 0, 1),
            &create(9, "Goa Trip", vec![member(1, Some("Tim")), member(2, None)]),
        )]);
        assert_eq!(p.groups.len(), 1);
        let g = &p.groups[0];
        assert_eq!(
            (g.id.as_str(), g.name.as_str()),
            (hex(9).as_str(), "Goa Trip")
        );
        assert_eq!(g.settlement_currency, "INR");
        assert_eq!(names(g), vec![(hex(1), Some("Tim".into())), (hex(2), None)]);
        assert_eq!(g.created, vid(1000, 0, 1).render());
        assert!(p.you.is_none() && p.rejected.is_empty() && p.pending.is_empty());
    }

    /// Per-field last writer wins: the later rename of a member and the later
    /// group name hold whichever device wrote them and in whatever order they
    /// arrived, and an earlier rename never overwrites a later one.
    #[test]
    fn renames_are_last_writer_wins_by_identity() {
        let recs = vec![
            rec(
                vid(1000, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![member(1, Some("Tim")), member(2, Some("Rahul"))],
                ),
            ),
            rec(
                vid(3000, 0, 2),
                &GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(2),
                    name: "Rahul K".into(),
                },
            ),
            rec(
                vid(2000, 0, 1),
                &GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(2),
                    name: "R".into(),
                },
            ),
            rec(
                vid(2500, 0, 2),
                &GroupOp::RenameGroup {
                    group: hex(9),
                    name: "Goa 2026".into(),
                },
            ),
        ];
        let mut rev = recs.clone();
        rev.reverse();
        for set in [recs, rev] {
            let p = project(&set);
            let g = &p.groups[0];
            assert_eq!(g.name, "Goa 2026");
            assert_eq!(g.members[1].name.as_deref(), Some("Rahul K"));
        }
    }

    /// Same millisecond: the counter, then the device, decide. No clock
    /// precision is assumed (ADR-0034).
    #[test]
    fn ties_are_broken_by_counter_then_device() {
        let base = rec(vid(1000, 0, 1), &create(9, "Goa", vec![member(1, None)]));
        let by_device = project(&[
            base.clone(),
            rec(
                vid(2000, 0, 3),
                &GroupOp::RenameGroup {
                    group: hex(9),
                    name: "Three".into(),
                },
            ),
            rec(
                vid(2000, 0, 2),
                &GroupOp::RenameGroup {
                    group: hex(9),
                    name: "Two".into(),
                },
            ),
        ]);
        assert_eq!(by_device.groups[0].name, "Three");
        let by_counter = project(&[
            base,
            rec(
                vid(2000, 1, 2),
                &GroupOp::RenameGroup {
                    group: hex(9),
                    name: "Later".into(),
                },
            ),
            rec(
                vid(2000, 0, 3),
                &GroupOp::RenameGroup {
                    group: hex(9),
                    name: "Earlier".into(),
                },
            ),
        ]);
        assert_eq!(by_counter.groups[0].name, "Later");
    }

    /// Members are a union, in the order first written: the create's list,
    /// then adds by identity. A second create of the same group (two devices
    /// both backfilled it) adds nobody twice and cannot change the currency.
    #[test]
    fn members_are_a_union_in_first_written_order_and_the_currency_is_fixed() {
        let mut usd = create(
            9,
            "Goa",
            vec![member(2, Some("Rahul")), member(4, Some("Amit"))],
        );
        if let GroupOp::Create {
            settlement_currency,
            ..
        } = &mut usd
        {
            *settlement_currency = "USD".into();
        }
        let p = project(&[
            rec(vid(5000, 0, 2), &usd),
            rec(
                vid(3000, 0, 1),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(3, Some("Priya")),
                },
            ),
            rec(
                vid(1000, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![member(1, Some("Tim")), member(2, Some("Rahul"))],
                ),
            ),
        ]);
        let g = &p.groups[0];
        assert_eq!(g.settlement_currency, "INR", "the earliest create fixes it");
        let ids: Vec<String> = g.members.iter().map(|m| m.id.clone()).collect();
        assert_eq!(ids, vec![hex(1), hex(2), hex(3), hex(4)]);
        assert_eq!(g.created, vid(1000, 0, 1).render());
    }

    /// A name written once and a later add of the same member without a name
    /// keeps the name: an absent name is not a write.
    #[test]
    fn an_absent_name_never_erases_one() {
        let p = project(&[
            rec(
                vid(1000, 0, 1),
                &create(9, "Goa", vec![member(1, Some("Tim"))]),
            ),
            rec(
                vid(2000, 0, 2),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(1, None),
                },
            ),
        ]);
        assert_eq!(names(&p.groups[0]), vec![(hex(1), Some("Tim".into()))]);
    }

    /// Two devices each added a "Rahul" offline: both are kept, told apart
    /// for display, so the group still validates in `named_group`.
    #[test]
    fn two_others_with_one_name_are_told_apart() {
        let p = project(&[
            rec(
                vid(900, 0, 1),
                &GroupOp::You {
                    id: hex(1),
                    name: Some("Tim".into()),
                },
            ),
            rec(
                vid(1000, 0, 1),
                &create(9, "Goa", vec![member(1, Some("Tim"))]),
            ),
            rec(
                vid(2000, 0, 1),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(2, Some("Rahul")),
                },
            ),
            rec(
                vid(2000, 0, 2),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(3, Some("rahul")),
                },
            ),
            rec(
                vid(2100, 0, 2),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(4, Some("Rahul (2)")),
                },
            ),
            rec(
                vid(2200, 0, 2),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(5, Some("Tim")),
                },
            ),
        ]);
        assert_eq!(
            names(&p.groups[0]),
            vec![
                (hex(1), Some("Tim".into())),
                (hex(2), Some("Rahul".into())),
                (hex(3), Some("rahul (2)".into())),
                (hex(4), Some("Rahul (2) (2)".into())),
                (hex(5), Some("Tim".into())),
            ],
            "you may share a name; other members may not"
        );
    }

    #[test]
    fn a_told_apart_name_still_fits_sixty_four_characters() {
        let long = "x".repeat(64);
        let p = project(&[
            rec(
                vid(1000, 0, 1),
                &create(9, "Goa", vec![member(1, Some(&long))]),
            ),
            rec(
                vid(2000, 0, 1),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(2, Some(&long)),
                },
            ),
        ]);
        let second = p.groups[0].members[1].name.clone().unwrap();
        assert_eq!(second.chars().count(), 64);
        assert!(second.ends_with("x (2)"));
    }

    /// Operations whose group has not arrived, and renames of members nobody
    /// added, wait; unreadable payloads are reported. None is lost.
    #[test]
    fn early_and_unreadable_records_are_reported_not_dropped() {
        let p = project(&[
            rec(
                vid(3000, 0, 2),
                &GroupOp::AddMember {
                    group: hex(8),
                    member: member(3, None),
                },
            ),
            rec(vid(1000, 0, 1), &create(9, "Goa", vec![member(1, None)])),
            rec(
                vid(2000, 0, 1),
                &GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(7),
                    name: "Ghost".into(),
                },
            ),
            rec(
                vid(2500, 0, 1),
                &GroupOp::RenameGroup {
                    group: hex(8),
                    name: "Later".into(),
                },
            ),
            OpRecord {
                id: vid(4000, 0, 3),
                bytes: br#"{"op":"removeMember"}"#.to_vec(),
            },
        ]);
        assert_eq!(p.groups.len(), 1);
        assert_eq!(
            p.pending,
            vec![
                vid(2000, 0, 1).render(),
                vid(2500, 0, 1).render(),
                vid(3000, 0, 2).render()
            ]
        );
        assert_eq!(p.rejected, vec![vid(4000, 0, 3).render()]);
    }

    /// "You" is the earliest `you` record's id (one per account, the founder's
    /// decision); its name is the latest written for that id; any other ids
    /// are reported for the merge.
    #[test]
    fn you_is_the_earliest_and_other_yous_are_reported() {
        let p = project(&[
            rec(
                vid(5000, 0, 2),
                &GroupOp::You {
                    id: hex(2),
                    name: Some("Timothy".into()),
                },
            ),
            rec(
                vid(3000, 0, 1),
                &GroupOp::You {
                    id: hex(1),
                    name: Some("Tim R".into()),
                },
            ),
            rec(
                vid(1000, 0, 1),
                &GroupOp::You {
                    id: hex(1),
                    name: None,
                },
            ),
            rec(
                vid(2000, 0, 1),
                &GroupOp::You {
                    id: hex(1),
                    name: Some("Tim".into()),
                },
            ),
            rec(
                vid(6000, 0, 3),
                &GroupOp::You {
                    id: hex(3),
                    name: None,
                },
            ),
        ]);
        assert_eq!(
            p.you,
            Some(You {
                id: hex(1),
                name: Some("Tim R".into())
            })
        );
        assert_eq!(p.other_you_ids, vec![hex(2), hex(3)]);
    }

    /// D-154: a member who is another "you" of the account reads as "you",
    /// and a group that holds two of them holds one, named by "you"'s own
    /// name in that group.
    #[test]
    fn another_you_in_a_group_reads_as_you() {
        let yous = [
            rec(
                vid(900, 0, 1),
                &GroupOp::You {
                    id: hex(1),
                    name: Some("Tim".into()),
                },
            ),
            rec(
                vid(950, 0, 2),
                &GroupOp::You {
                    id: hex(2),
                    name: Some("Timothy".into()),
                },
            ),
        ];
        let only_other = project(&[
            yous[0].clone(),
            yous[1].clone(),
            rec(
                vid(1000, 0, 2),
                &create(
                    9,
                    "Paris",
                    vec![member(2, Some("Timothy")), member(5, Some("Anna"))],
                ),
            ),
        ]);
        assert_eq!(
            names(&only_other.groups[0]),
            vec![
                (hex(1), Some("Timothy".into())),
                (hex(5), Some("Anna".into()))
            ],
            "the other you's place and, with no name of yours here, its name"
        );
        let both = project(&[
            yous[0].clone(),
            yous[1].clone(),
            rec(
                vid(1000, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![
                        member(5, Some("Anna")),
                        member(2, Some("Timothy")),
                        member(1, Some("Tim")),
                    ],
                ),
            ),
        ]);
        assert_eq!(
            names(&both.groups[0]),
            vec![(hex(5), Some("Anna".into())), (hex(1), Some("Tim".into()))],
            "one you, first place, your own name"
        );
        assert_eq!(both.groups[0].members.len(), 2);
    }

    #[test]
    fn groups_come_out_in_creation_order() {
        let p = project(&[
            rec(vid(2000, 0, 1), &create(3, "B", vec![member(1, None)])),
            rec(vid(1000, 0, 2), &create(7, "A", vec![member(1, None)])),
            rec(vid(3000, 0, 1), &create(5, "C", vec![member(1, None)])),
        ]);
        let order: Vec<&str> = p.groups.iter().map(|g| g.name.as_str()).collect();
        assert_eq!(order, vec!["A", "B", "C"]);
    }

    /// THE property: every order of the same records projects the same.
    /// All 720 orders of six records that exercise every rule at once.
    #[test]
    fn every_arrival_order_projects_the_same() {
        let recs = vec![
            rec(
                vid(900, 0, 1),
                &GroupOp::You {
                    id: hex(1),
                    name: Some("Tim".into()),
                },
            ),
            rec(
                vid(1000, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![member(1, Some("Tim")), member(2, Some("Rahul"))],
                ),
            ),
            rec(
                vid(2000, 0, 2),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(3, Some("Rahul")),
                },
            ),
            rec(
                vid(2000, 1, 1),
                &GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(2),
                    name: "Rahul K".into(),
                },
            ),
            rec(
                vid(1500, 0, 2),
                &GroupOp::RenameGroup {
                    group: hex(9),
                    name: "Goa 2026".into(),
                },
            ),
            rec(
                vid(1200, 0, 3),
                &GroupOp::You {
                    id: hex(4),
                    name: None,
                },
            ),
        ];
        let expected = project(&recs);
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        let mut count = 0;
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
            count += 1;
        });
        assert_eq!(count, 720);
        assert_eq!(expected.groups[0].name, "Goa 2026");
        assert_eq!(
            names(&expected.groups[0]),
            vec![
                (hex(1), Some("Tim".into())),
                (hex(2), Some("Rahul K".into())),
                (hex(3), Some("Rahul".into())),
            ]
        );
        assert_eq!(expected.other_you_ids, vec![hex(4)]);
    }

    /// Duplicated records (a pull that re-delivers) change nothing.
    #[test]
    fn a_record_delivered_twice_counts_once() {
        let one = rec(vid(1000, 0, 1), &create(9, "Goa", vec![member(1, None)]));
        let add = rec(
            vid(2000, 0, 1),
            &GroupOp::AddMember {
                group: hex(9),
                member: member(2, None),
            },
        );
        assert_eq!(
            project(&[one.clone(), add.clone()]),
            project(&[one.clone(), add.clone(), one, add])
        );
    }

    // ---- D-157: claims ----

    fn device(n: u8) -> ClaimDevice {
        ClaimDevice {
            id: hex(n),
            key: format!("{n:064x}"),
        }
    }

    fn claim(group: u8, member: u8, you: u8, devices: Vec<ClaimDevice>) -> GroupOp {
        GroupOp::Claim {
            group: hex(group),
            member: hex(member),
            you: hex(you),
            devices,
        }
    }

    /// Tim's group invited "Rahul" (member 2); Bob joined as Rahul. Bob's own
    /// devices see Bob in Rahul's place, named as the group names him; Tim's
    /// devices see Rahul, linked to Bob's account and devices.
    #[test]
    fn a_claimed_member_is_you_for_the_claimer_and_linked_for_everyone_else() {
        let group = rec(
            vid(1000, 0, 1),
            &create(
                9,
                "Goa",
                vec![member(1, Some("Tim")), member(2, Some("Rahul"))],
            ),
        );
        let bobs_claim = rec(vid(3000, 0, 30), &claim(9, 2, 20, vec![device(30)]));
        let bob = project(&[
            rec(
                vid(2000, 0, 30),
                &GroupOp::You {
                    id: hex(20),
                    name: Some("Bob".into()),
                },
            ),
            group.clone(),
            bobs_claim.clone(),
        ]);
        assert_eq!(bob.you.as_ref().unwrap().id, hex(20));
        assert_eq!(bob.other_you_ids, vec![hex(2)], "Rahul is one more you");
        assert_eq!(
            names(&bob.groups[0]),
            vec![
                (hex(1), Some("Tim".into())),
                (hex(20), Some("Rahul".into()))
            ]
        );
        let linked = ProjectedClaim {
            member: hex(2),
            you: hex(20),
            devices: vec![device(30)],
        };
        assert_eq!(bob.groups[0].claims, vec![linked.clone()]);

        let tim = project(&[
            rec(
                vid(900, 0, 1),
                &GroupOp::You {
                    id: hex(1),
                    name: Some("Tim".into()),
                },
            ),
            group,
            bobs_claim,
        ]);
        assert!(tim.other_you_ids.is_empty(), "Bob's claim is not Tim's");
        assert_eq!(
            names(&tim.groups[0]),
            vec![(hex(1), Some("Tim".into())), (hex(2), Some("Rahul".into()))]
        );
        assert_eq!(tim.groups[0].claims, vec![linked]);
    }

    /// The first claim of a member decides whose it is. The same account
    /// claiming again adds devices; another account is contested, reported
    /// and never applied. In every arrival order.
    #[test]
    fn the_first_claim_stands_in_every_order() {
        let recs = vec![
            rec(
                vid(1000, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![member(1, Some("Tim")), member(2, Some("Rahul"))],
                ),
            ),
            rec(vid(3000, 0, 30), &claim(9, 2, 20, vec![device(30)])),
            rec(vid(3001, 0, 40), &claim(9, 2, 21, vec![device(40)])),
            rec(
                vid(3002, 0, 31),
                &claim(9, 2, 20, vec![device(31), device(30)]),
            ),
            rec(vid(3003, 0, 30), &claim(9, 2, 20, vec![device(30)])),
        ];
        let expected = project(&recs);
        assert_eq!(
            expected.groups[0].claims,
            vec![ProjectedClaim {
                member: hex(2),
                you: hex(20),
                devices: vec![device(30), device(31)],
            }]
        );
        assert_eq!(expected.contested, vec![vid(3001, 0, 40).render()]);
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        let mut count = 0;
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
            count += 1;
        });
        assert_eq!(count, 120);
    }

    #[test]
    fn a_claim_waits_for_its_group_and_its_member() {
        let p = project(&[
            rec(
                vid(1000, 0, 1),
                &create(9, "Goa", vec![member(1, Some("Tim"))]),
            ),
            rec(vid(2000, 0, 2), &claim(8, 1, 20, vec![device(30)])),
            rec(vid(2001, 0, 2), &claim(9, 5, 20, vec![device(30)])),
        ]);
        assert_eq!(
            p.pending,
            vec![vid(2000, 0, 2).render(), vid(2001, 0, 2).render()]
        );
        assert!(p.groups[0].claims.is_empty());
        assert!(p.contested.is_empty());
    }

    /// The exact bytes, written out by hand from the field order rule (op
    /// first, then fields in declaration order).
    #[test]
    fn a_claim_encodes_to_pinned_bytes() {
        let bytes = encode(&claim(9, 2, 20, vec![device(30)])).unwrap();
        let expected = format!(
            "{{\"op\":\"claim\",\"group\":\"{}\",\"member\":\"{}\",\"you\":\"{}\",\"devices\":[{{\"id\":\"{}\",\"key\":\"{:064x}\"}}]}}",
            hex(9),
            hex(2),
            hex(20),
            hex(30),
            30
        );
        assert_eq!(String::from_utf8(bytes.clone()).unwrap(), expected);
        assert_eq!(decode(&bytes).unwrap(), claim(9, 2, 20, vec![device(30)]));
    }

    #[test]
    fn bad_claims_are_refused_both_ways() {
        let upper = ClaimDevice {
            id: hex(30),
            key: format!("{:064X}", 0xabu8),
        };
        let short = ClaimDevice {
            id: hex(30),
            key: "ab".repeat(31),
        };
        let bad_you = GroupOp::Claim {
            group: hex(9),
            member: hex(2),
            you: "Z".repeat(32),
            devices: vec![device(30)],
        };
        let cases = vec![
            claim(9, 2, 20, vec![]),
            claim(9, 2, 20, (0..17).map(device).collect()),
            claim(9, 2, 20, vec![device(30), device(30)]),
            claim(9, 2, 20, vec![upper]),
            claim(9, 2, 20, vec![short]),
            bad_you,
        ];
        for op in cases {
            assert!(
                matches!(encode(&op), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
            let raw = serde_json::to_vec(&op).unwrap();
            assert!(
                matches!(decode(&raw), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
        }
        assert!(
            encode(&claim(9, 2, 20, (0..16).map(device).collect())).is_ok(),
            "sixteen devices is the limit, not past it"
        );
    }

    // ---- D-178: Maximum security ----

    /// The wire shape, re-derived outside the code (Python's json.dumps of
    /// {"op":"security","maximum":true}); a field this build does not know is
    /// refused, like every other op.
    #[test]
    fn security_is_a_small_personal_op_with_a_pinned_shape() {
        let on = GroupOp::Security { maximum: true };
        assert_eq!(
            encode(&on).unwrap(),
            br#"{"op":"security","maximum":true}"#.to_vec()
        );
        assert_eq!(
            decode(br#"{"op":"security","maximum":false}"#).unwrap(),
            GroupOp::Security { maximum: false }
        );
        assert!(decode(br#"{"op":"security","maximum":true,"scope":"all"}"#).is_err());
        assert!(decode(br#"{"op":"security"}"#).is_err());
    }

    /// Off until written; then the latest write wins, in every arrival order,
    /// and it touches nothing else in the projection.
    #[test]
    fn maximum_security_is_the_latest_write() {
        assert!(!project(&[]).maximum_security);
        let recs = vec![
            rec(vid(1000, 0, 1), &GroupOp::Security { maximum: true }),
            rec(vid(3000, 0, 2), &GroupOp::Security { maximum: false }),
            rec(vid(2000, 0, 1), &GroupOp::Security { maximum: true }),
        ];
        let expected = project(&recs);
        assert!(
            !expected.maximum_security,
            "the write at 3000 turned it off"
        );
        assert!(expected.groups.is_empty() && expected.you.is_none() && expected.keys.is_empty());
        assert!(expected.rejected.is_empty() && expected.pending.is_empty());
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
        });
        let on_again = [
            recs.clone(),
            vec![rec(vid(4000, 0, 3), &GroupOp::Security { maximum: true })],
        ]
        .concat();
        assert!(project(&on_again).maximum_security);
    }

    // ---- D-187: a group's picture ----

    /// The wire shape (Python's compact json.dumps of {"op":"groupPicture",
    /// "group":<id>,"emoji":"🏖️"}); an emoji or a JPEG, never both; a photo
    /// is lowercase hex of FF D8 FF… at most 64,000 bytes.
    #[test]
    fn a_group_picture_is_a_small_group_op_with_a_pinned_shape() {
        let pic = |emoji: Option<&str>, photo: Option<String>| GroupOp::GroupPicture {
            group: hex(9),
            emoji: emoji.map(str::to_string),
            photo,
        };
        assert_eq!(
            encode(&pic(Some("🏖️"), None)).unwrap(),
            format!(
                r#"{{"op":"groupPicture","group":"{}","emoji":"🏖️"}}"#,
                hex(9)
            )
            .into_bytes()
        );
        assert_eq!(
            encode(&pic(None, None)).unwrap(),
            format!(r#"{{"op":"groupPicture","group":"{}"}}"#, hex(9)).into_bytes()
        );
        let jpeg = |bytes: usize| format!("ffd8ff{}", "00".repeat(bytes - 3));
        assert!(encode(&pic(None, Some(jpeg(PICTURE_MAX_BYTES)))).is_ok());
        assert!(
            encode(&pic(Some("👨‍👩‍👧"), None)).is_ok(),
            "a family is five code points"
        );
        for bad in [
            pic(Some("🏖️"), Some(jpeg(10))),
            pic(Some(""), None),
            pic(Some("abcdefghi"), None),
            pic(Some(" 🏖️"), None),
            pic(Some("a\nb"), None),
            pic(None, Some(jpeg(PICTURE_MAX_BYTES + 1))),
            pic(None, Some("89504e47".into())),
            pic(None, Some("FFD8FF00".into())),
            pic(None, Some("ffd8ff0".into())),
        ] {
            assert!(
                matches!(encode(&bad), Err(GroupOpError::Invalid(_))),
                "{bad:?}"
            );
        }
    }

    /// The latest picture wins in every arrival order; writing neither puts
    /// the initial back; one for a group not here yet waits.
    #[test]
    fn a_groups_picture_is_the_latest_written() {
        let pic = |emoji: Option<&str>| GroupOp::GroupPicture {
            group: hex(9),
            emoji: emoji.map(str::to_string),
            photo: None,
        };
        let recs = vec![
            rec(
                vid(1000, 0, 1),
                &create(9, "Goa", vec![member(1, Some("Tim"))]),
            ),
            rec(vid(3000, 0, 2), &pic(Some("🏖️"))),
            rec(vid(2000, 0, 1), &pic(Some("⛰️"))),
        ];
        let expected = project(&recs);
        assert_eq!(
            expected.groups[0].picture,
            Some(GroupPicture {
                emoji: Some("🏖️".into()),
                photo: None
            })
        );
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
        });
        let cleared = project(&[recs.clone(), vec![rec(vid(4000, 0, 1), &pic(None))]].concat());
        assert_eq!(cleared.groups[0].picture, None);
        assert_eq!(project(&recs[..1]).groups[0].picture, None);
        let orphan = project(&[rec(vid(3000, 0, 2), &pic(Some("🏖️")))]);
        assert!(orphan.groups.is_empty() && orphan.pending.len() == 1);
    }

    // ---- D-186: taking a person out of a group ----

    /// The wire shape (Python's compact json.dumps of
    /// {"op":"removeMember","group":<32 hex>,"member":<32 hex>}); both must be
    /// ids.
    #[test]
    fn remove_member_is_a_group_op_with_a_pinned_shape() {
        let op = GroupOp::RemoveMember {
            group: hex(9),
            member: hex(2),
        };
        assert_eq!(
            encode(&op).unwrap(),
            format!(
                r#"{{"op":"removeMember","group":"{}","member":"{}"}}"#,
                hex(9),
                hex(2)
            )
            .into_bytes()
        );
        for bad in [
            GroupOp::RemoveMember {
                group: "goa".into(),
                member: hex(2),
            },
            GroupOp::RemoveMember {
                group: hex(9),
                member: "rahul".into(),
            },
        ] {
            assert!(
                matches!(encode(&bad), Err(GroupOpError::Invalid(_))),
                "{bad:?}"
            );
        }
    }

    /// A removed person stays in the group's members (what they are in still
    /// balances) and is listed as removed; the latest of add and remove wins,
    /// in every arrival order; a removal for a group not here yet waits.
    #[test]
    fn a_removed_person_is_listed_removed_and_can_come_back() {
        let remove = |m: u8| GroupOp::RemoveMember {
            group: hex(9),
            member: hex(m),
        };
        let recs = vec![
            rec(
                vid(1000, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![
                        member(1, Some("Tim")),
                        member(2, Some("Rahul")),
                        member(3, Some("Sam")),
                    ],
                ),
            ),
            rec(vid(2000, 0, 1), &remove(2)),
            rec(vid(2500, 0, 2), &remove(3)),
            rec(
                vid(3000, 0, 2),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(3, None),
                },
            ),
        ];
        let expected = project(&recs);
        let g = &expected.groups[0];
        assert_eq!(
            names(g),
            vec![
                (hex(1), Some("Tim".into())),
                (hex(2), Some("Rahul".into())),
                (hex(3), Some("Sam".into()))
            ]
        );
        assert_eq!(g.removed, vec![hex(2)], "Sam was added back after");
        assert!(expected.pending.is_empty() && expected.rejected.is_empty());
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
        });
        let early = project(&[recs[0].clone(), rec(vid(500, 0, 3), &remove(1))]);
        assert!(
            early.groups[0].removed.is_empty(),
            "a removal older than the add loses"
        );
        let orphan = project(&[rec(vid(2000, 0, 1), &remove(2))]);
        assert!(orphan.groups.is_empty() && orphan.pending.len() == 1);
    }

    // ---- D-185: phone names ----

    /// The wire shape, re-derived outside the code (Python's compact
    /// json.dumps of {"op":"deviceName","device":<32 hex>,"name":"iPhone 17"});
    /// the device must be an id, the name a member-rule name (trimmed, 1 to 64
    /// characters, no control characters), and a field this build does not
    /// know is refused.
    #[test]
    fn device_name_is_a_small_personal_op_with_a_pinned_shape() {
        let op = GroupOp::DeviceName {
            device: hex(7),
            name: "iPhone 17".into(),
        };
        assert_eq!(
            encode(&op).unwrap(),
            format!(
                r#"{{"op":"deviceName","device":"{}","name":"iPhone 17"}}"#,
                hex(7)
            )
            .into_bytes()
        );
        let named = |device: String, name: String| GroupOp::DeviceName { device, name };
        for bad in [
            named("not-hex".into(), "iPhone 17".into()),
            named(hex(0xab).to_uppercase(), "iPhone 17".into()),
            named(hex(7), String::new()),
            named(hex(7), " iPhone".into()),
            named(hex(7), "a\nb".into()),
            named(hex(7), "x".repeat(65)),
        ] {
            assert!(
                matches!(encode(&bad), Err(GroupOpError::Invalid(_))),
                "{bad:?}"
            );
        }
        assert!(encode(&named(hex(7), "x".repeat(64))).is_ok());
        let extra = format!(
            r#"{{"op":"deviceName","device":"{}","name":"x","model":"y"}}"#,
            hex(7)
        );
        assert!(decode(extra.as_bytes()).is_err());
    }

    /// Each phone's latest write wins, in every arrival order; the list is
    /// sorted by device and touches nothing else in the projection.
    #[test]
    fn a_phones_name_is_its_latest_write() {
        let name = |d: u8, n: &str| GroupOp::DeviceName {
            device: hex(d),
            name: n.into(),
        };
        let recs = vec![
            rec(vid(1000, 0, 1), &name(2, "iPhone 16")),
            rec(vid(3000, 0, 1), &name(2, "iPhone 17")),
            rec(vid(2000, 0, 2), &name(1, "Pixel 8")),
            rec(vid(1500, 0, 2), &name(1, "Pixel 7")),
        ];
        let expected = project(&recs);
        assert_eq!(
            expected.device_names,
            vec![
                (hex(1), "Pixel 8".to_string()),
                (hex(2), "iPhone 17".to_string())
            ]
        );
        assert!(expected.groups.is_empty() && expected.you.is_none() && !expected.maximum_security);
        assert!(expected.rejected.is_empty() && expected.pending.is_empty());
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
        });
        assert!(project(&[]).device_names.is_empty());
    }

    // ---- D-158: group keys ----

    fn key_op(group: u8, epoch: u32, fill: u8) -> GroupOp {
        GroupOp::GroupKey {
            group: hex(group),
            epoch,
            key: format!("{fill:02x}").repeat(32),
        }
    }

    fn held(group: u8, epoch: u32, fill: u8) -> GroupKeyHeld {
        GroupKeyHeld {
            group: hex(group),
            epoch,
            key: format!("{fill:02x}").repeat(32),
        }
    }

    /// Keys come out by group, then epoch, then first written, each once, in
    /// every arrival order; they touch nothing else in the projection.
    #[test]
    fn keys_are_listed_by_group_epoch_and_first_written() {
        let recs = vec![
            rec(vid(2000, 0, 2), &key_op(9, 0, 0xbb)),
            rec(vid(1000, 0, 1), &key_op(9, 0, 0xaa)),
            rec(vid(3000, 0, 1), &key_op(9, 1, 0xcc)),
            rec(vid(1500, 0, 1), &key_op(8, 0, 0xdd)),
            rec(vid(4000, 0, 3), &key_op(9, 0, 0xaa)),
        ];
        let expected = project(&recs);
        assert_eq!(
            expected.keys,
            vec![
                held(8, 0, 0xdd),
                held(9, 0, 0xaa),
                held(9, 0, 0xbb),
                held(9, 1, 0xcc)
            ]
        );
        assert!(expected.groups.is_empty() && expected.you.is_none());
        assert!(expected.pending.is_empty() && expected.rejected.is_empty());
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        let mut count = 0;
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
            count += 1;
        });
        assert_eq!(count, 120);
    }

    #[test]
    fn bad_keys_are_refused_both_ways() {
        let cases = vec![
            GroupOp::GroupKey {
                group: hex(9),
                epoch: 0,
                key: "AB".repeat(32),
            },
            GroupOp::GroupKey {
                group: hex(9),
                epoch: 0,
                key: "ab".repeat(31),
            },
            GroupOp::GroupKey {
                group: "nope".into(),
                epoch: 0,
                key: "ab".repeat(32),
            },
        ];
        for op in cases {
            assert!(
                matches!(encode(&op), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
            let raw = serde_json::to_vec(&op).unwrap();
            assert!(
                matches!(decode(&raw), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
        }
    }

    // ---- D-235 (decision 110 a): a group's name is bounded ---------------

    #[test]
    fn d235_a_group_name_is_at_most_1000_characters() {
        use crate::straumr::check::TEXT_MAX_CHARS;
        let at = "\u{1F600}".repeat(TEXT_MAX_CHARS);
        let bytes = encode(&GroupOp::RenameGroup {
            group: hex(9),
            name: at.clone(),
        })
        .expect("at the bound");
        assert!(bytes.len() < 32 * 1024);
        assert!(encode(&create(9, &at, vec![member(1, None)])).is_ok());
        let past = "x".repeat(TEXT_MAX_CHARS + 1);
        for op in [
            create(9, &past, vec![member(1, None)]),
            GroupOp::RenameGroup {
                group: hex(9),
                name: past.clone(),
            },
        ] {
            assert!(matches!(encode(&op), Err(GroupOpError::Invalid(_))));
            let raw = serde_json::to_vec(&op).unwrap();
            assert!(matches!(decode(&raw), Err(GroupOpError::Invalid(_))));
        }
        assert!(crate::ffi::create_group(&past, "INR", &[hex(1)]).is_err());
        assert!(crate::ffi::create_group(&at, "INR", &[hex(1)]).is_ok());
    }

    // ---- D-235: a picture's declared size --------------------------------

    /// A minimal JPEG's headers as hex: SOI, a JFIF APP0, a quantisation
    /// table, then `frames` (marker, width, height), then SOS and EOI. Only
    /// the headers matter: core reads sizes, never pixels.
    fn jpeg_sized(frames: &[(u8, u16, u16)]) -> String {
        let mut b: Vec<u8> = vec![0xFF, 0xD8];
        b.extend_from_slice(&[0xFF, 0xE0, 0x00, 0x10]);
        b.extend_from_slice(b"JFIF\0");
        b.extend_from_slice(&[1, 1, 0, 0, 1, 0, 1, 0, 0]);
        b.extend_from_slice(&[0xFF, 0xDB, 0x00, 0x43, 0x00]);
        b.extend_from_slice(&[1u8; 64]);
        for (marker, w, h) in frames {
            b.extend_from_slice(&[0xFF, *marker, 0x00, 0x0B, 0x08]);
            b.extend_from_slice(&h.to_be_bytes());
            b.extend_from_slice(&w.to_be_bytes());
            b.extend_from_slice(&[0x01, 0x01, 0x11, 0x00]);
        }
        b.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00]);
        b.extend_from_slice(&[0x12, 0x34, 0xFF, 0x00, 0x56]);
        b.extend_from_slice(&[0xFF, 0xD9]);
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    fn group_photo(p: String) -> GroupOp {
        GroupOp::GroupPicture {
            group: hex(9),
            emoji: None,
            photo: Some(p),
        }
    }

    #[test]
    fn d235_a_picture_declaring_too_many_pixels_is_refused() {
        let ok = |frames: &[(u8, u16, u16)]| encode(&group_photo(jpeg_sized(frames))).is_ok();
        // What the shells make, and the bounds themselves.
        assert!(ok(&[(0xC0, 768, 512)]));
        assert!(ok(&[(0xC0, 2048, 1024)]), "exactly the pixel cap");
        assert!(ok(&[(0xC2, 1024, 2048)]), "progressive, on the cap");
        // Past a side, past the pixel cap, empty, and the audit's 7000 by 7000.
        assert!(!ok(&[(0xC0, 2049, 1)]));
        assert!(!ok(&[(0xC0, 1, 2049)]));
        assert!(!ok(&[(0xC0, 2048, 1025)]));
        assert!(!ok(&[(0xC0, 0, 512)]));
        assert!(!ok(&[(0xC0, 512, 0)]));
        assert!(!ok(&[(0xC0, 7000, 7000)]));
        assert!(!ok(&[(0xC2, 7000, 7000)]), "progressive");
        assert!(!ok(&[(0xCF, 7000, 7000)]), "the last SOF marker");
        assert!(!ok(&[(0xDE, 7000, 7000)]), "hierarchical DHP");
        assert!(
            !ok(&[(0xC0, 768, 512), (0xC1, 7000, 7000)]),
            "a second frame"
        );
        // Markers that are not frames carry no size: a DHT shaped like a
        // huge frame is not one.
        assert!(ok(&[(0xC4, 7000, 7000), (0xC0, 768, 512)]));
        // Garbage and fill bytes before the frame are skipped as libjpeg
        // skips them, so a size cannot hide behind them.
        let hidden = jpeg_sized(&[(0xC0, 7000, 7000)]).replacen("ffc0", "1234ffffffc0", 1);
        assert!(encode(&group_photo(hidden)).is_err());
        // A frame after the first scan is never read (a decoder has its size).
        let after = jpeg_sized(&[(0xC0, 768, 512)]).replacen(
            "ffd9",
            &format!("{}ffd9", &jpeg_sized(&[(0xC0, 7000, 7000)])[4..]),
            1,
        );
        assert!(encode(&group_photo(after)).is_ok());
        // A file that declares no frame (the magic and zeros) is not refused.
        assert!(encode(&group_photo(jpeg(500))).is_ok());
        // Decode refuses it too (the shells' ingest path).
        let raw = serde_json::to_vec(&group_photo(jpeg_sized(&[(0xC0, 7000, 7000)]))).unwrap();
        assert!(matches!(decode(&raw), Err(GroupOpError::Invalid(_))));
    }

    #[test]
    fn d235_a_preview_declaring_too_many_pixels_is_refused() {
        let ok = |w: u16, h: u16| {
            encode(&GroupOp::MemberProfile {
                group: hex(9),
                member: hex(2),
                name: "Meow".into(),
                emoji: None,
                thumb: Some(jpeg_sized(&[(0xC0, w, h)])),
            })
            .is_ok()
        };
        assert!(ok(120, 120), "what the shells make");
        assert!(ok(512, 512));
        assert!(!ok(513, 1));
        assert!(!ok(1, 513));
        assert!(!ok(768, 512), "a photo's size is too big for a preview");
        // Your own picture is a photo.
        let avatar = |w: u16, h: u16| {
            encode(&GroupOp::Avatar {
                emoji: None,
                photo: Some(jpeg_sized(&[(0xC0, w, h)])),
            })
            .is_ok()
        };
        assert!(avatar(768, 512));
        assert!(!avatar(4096, 4096));
    }

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
                GroupOp::Avatar {
                    emoji: Some("🦊".into()),
                    photo: None,
                },
                r#"{"op":"avatar","emoji":"🦊"}"#,
            ),
            (
                GroupOp::Avatar {
                    emoji: None,
                    photo: None,
                },
                r#"{"op":"avatar"}"#,
            ),
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
            GroupOp::Avatar {
                emoji: Some("🦊".into()),
                photo: Some(jpeg(10)),
            },
            // a photo one byte over the cap, and one that is not a JPEG
            GroupOp::Avatar {
                emoji: None,
                photo: Some(jpeg(PICTURE_MAX_BYTES + 1)),
            },
            GroupOp::Avatar {
                emoji: None,
                photo: Some("89504e47".into()),
            },
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
            assert!(
                matches!(encode(&op), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
        }
        assert!(encode(&GroupOp::Avatar {
            emoji: None,
            photo: Some(jpeg(PICTURE_MAX_BYTES))
        })
        .is_ok());
        assert!(encode(&profile(9, 2, "Meow", Some(jpeg(THUMB_MAX_BYTES)))).is_ok());
    }

    #[test]
    fn the_latest_avatar_is_the_accounts_and_an_empty_one_clears_it() {
        let fox = GroupOp::Avatar {
            emoji: Some("🦊".into()),
            photo: None,
        };
        let photo = GroupOp::Avatar {
            emoji: None,
            photo: Some(jpeg(10)),
        };
        let cleared = GroupOp::Avatar {
            emoji: None,
            photo: None,
        };
        let p = project(&[rec(vid(2, 0, 1), &photo), rec(vid(1, 0, 1), &fox)]);
        assert_eq!(
            p.avatar,
            Some(GroupPicture {
                emoji: None,
                photo: Some(jpeg(10))
            })
        );
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
            rec(
                vid(1, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![
                        member(1, Some("Tim")),
                        member(2, Some("Ra")),
                        member(3, Some("P")),
                    ],
                ),
            ),
            rec(
                vid(3, 0, 1),
                &GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(2),
                    name: "R".into(),
                },
            ),
            rec(vid(5, 0, 2), &profile(9, 2, "Rahul K", Some(jpeg(20)))),
            rec(
                vid(6, 0, 1),
                &GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(3),
                    name: "Priya".into(),
                },
            ),
            rec(
                vid(7, 0, 1),
                &GroupOp::RenameMember {
                    group: hex(9),
                    member: hex(2),
                    name: "Rah".into(),
                },
            ),
        ];
        let expected = project(&records);
        let mut indices: Vec<usize> = (0..records.len()).collect();
        let mut count = 0;
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| records[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
            count += 1;
        });
        assert_eq!(count, 120);
        let p = expected;
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
            vec![ProjectedProfile {
                member: hex(2),
                emoji: None,
                thumb: Some(jpeg(20))
            }]
        );
        assert!(p.pending.is_empty() && p.rejected.is_empty());
    }

    #[test]
    fn the_latest_profile_of_a_place_wins_and_a_profile_for_nobody_waits() {
        let records = vec![
            rec(
                vid(1, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![member(1, Some("Tim")), member(2, Some("Ra"))],
                ),
            ),
            rec(vid(4, 0, 2), &profile(9, 2, "Rahul", Some(jpeg(20)))),
            rec(vid(8, 0, 2), &profile(9, 2, "Rahul K", None)),
            rec(vid(9, 0, 2), &profile(9, 7, "Ghost", None)),
        ];
        let p = project(&records);
        let g = &p.groups[0];
        assert_eq!(names(g)[1], (hex(2), Some("Rahul K".into())));
        assert_eq!(
            g.profiles,
            vec![ProjectedProfile {
                member: hex(2),
                emoji: None,
                thumb: None
            }]
        );
        assert_eq!(
            p.pending,
            vec![vid(9, 0, 2).render()],
            "a profile for a place nobody added waits"
        );
    }

    /// Two joined people may share a name; a typed name that clashes with a
    /// joined one gets " (2)", whatever the member order.
    #[test]
    fn joined_names_are_never_suffixed_and_typed_ones_yield() {
        let records = vec![
            rec(
                vid(1, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![
                        member(1, Some("Tim")),
                        member(2, Some("Meow")),
                        member(3, Some("x")),
                        member(4, Some("y")),
                        member(5, Some("MEOW")),
                    ],
                ),
            ),
            rec(vid(2, 0, 3), &profile(9, 3, "Meow", None)),
            rec(vid(3, 0, 4), &profile(9, 4, "Meow", None)),
        ];
        let p = project(&records);
        // By hand: joined 3 and 4 keep "Meow" and reserve "meow". Typed 2
        // "Meow" clashes, " (2)" is free: "Meow (2)". Typed 5 "MEOW" (after
        // the joined ones) clashes with "meow" and with "meow (2)" now held,
        // so it takes " (3)": "MEOW (3)".
        assert_eq!(
            names(&p.groups[0]),
            vec![
                (hex(1), Some("Tim".into())),
                (hex(2), Some("Meow (2)".into())),
                (hex(3), Some("Meow".into())),
                (hex(4), Some("Meow".into())),
                (hex(5), Some("MEOW (3)".into())),
            ]
        );
    }

    /// A removed member keeps what they are in: their own name and their
    /// profile entry stay (what they are in still balances).
    #[test]
    fn a_removed_member_keeps_their_profile() {
        let records = vec![
            rec(
                vid(1, 0, 1),
                &create(
                    9,
                    "Goa",
                    vec![member(1, Some("Tim")), member(2, Some("Ra"))],
                ),
            ),
            rec(vid(2, 0, 2), &profile(9, 2, "Rahul K", Some(jpeg(20)))),
            rec(
                vid(3, 0, 1),
                &GroupOp::RemoveMember {
                    group: hex(9),
                    member: hex(2),
                },
            ),
        ];
        let p = project(&records);
        let g = &p.groups[0];
        assert_eq!(g.removed, vec![hex(2)]);
        assert_eq!(names(g)[1], (hex(2), Some("Rahul K".into())));
        assert_eq!(
            g.profiles,
            vec![ProjectedProfile {
                member: hex(2),
                emoji: None,
                thumb: Some(jpeg(20))
            }]
        );
    }

    /// A merged member (this account's "you" and a place it claimed or wrote
    /// as "you" on another phone) is shown by ONE id; the LATEST profile of
    /// any place merged into it gives both its name and its preview.
    ///
    /// By hand: You{1} (t=1) makes hex(1) the account's "you"; You{5} (t=2,
    /// another phone) puts hex(5) in other_you_ids, so hex(5) shows as
    /// hex(1). The create (t=3) has hex(1) "Tim" and hex(5) (unnamed), in
    /// either order. Profile on place 5 at t=5 is "Tim K" with thumb
    /// jpeg(20); profile on place 1 at t=6 is "Timothy" with thumb jpeg(30).
    /// With both, the latest (t=6) wins: name "Timothy", thumb jpeg(30).
    /// With only the place-5 profile: name "Tim K", thumb jpeg(20), though
    /// the shown id hex(1) has its own typed name "Tim". Either way ONE
    /// member, hex(1), is shown.
    #[test]
    fn a_merged_members_latest_profile_gives_name_and_preview() {
        let you = |id: u8, t: u64, dev: u8| {
            rec(
                vid(t, 0, dev),
                &GroupOp::You {
                    id: hex(id),
                    name: None,
                },
            )
        };
        for five_first in [true, false] {
            let members = if five_first {
                vec![member(5, None), member(1, Some("Tim"))]
            } else {
                vec![member(1, Some("Tim")), member(5, None)]
            };
            let base = vec![
                you(1, 1, 1),
                you(5, 2, 2),
                rec(vid(3, 0, 1), &create(9, "Goa", members)),
            ];
            let on_five = rec(vid(5, 0, 2), &profile(9, 5, "Tim K", Some(jpeg(20))));
            let on_one = rec(vid(6, 0, 1), &profile(9, 1, "Timothy", Some(jpeg(30))));

            let mut both = base.clone();
            both.extend([on_five.clone(), on_one]);
            let p = project(&both);
            assert_eq!(p.other_you_ids, vec![hex(5)]);
            let g = &p.groups[0];
            assert_eq!(names(g), vec![(hex(1), Some("Timothy".into()))]);
            assert_eq!(
                g.profiles,
                vec![ProjectedProfile {
                    member: hex(1),
                    emoji: None,
                    thumb: Some(jpeg(30))
                }]
            );

            let mut only_five = base;
            only_five.push(on_five);
            let p = project(&only_five);
            let g = &p.groups[0];
            assert_eq!(names(g), vec![(hex(1), Some("Tim K".into()))]);
            assert_eq!(
                g.profiles,
                vec![ProjectedProfile {
                    member: hex(1),
                    emoji: None,
                    thumb: Some(jpeg(20))
                }]
            );
        }
    }

    // ---- D-229: the place, you_as, placeAlias and the alias hardening ----
    // Every expected value below is written out by hand and re-derived by an
    // independent model of the plan's rules, `.drafts/d229/goldens.py`.

    fn you_rec(id: u8, millis: u64, dev: u8) -> OpRecord {
        rec(
            vid(millis, 0, dev),
            &GroupOp::You {
                id: hex(id),
                name: None,
            },
        )
    }

    fn create_rec(group: u8, members: &[u8], millis: u64, dev: u8) -> OpRecord {
        rec(
            vid(millis, 0, dev),
            &create(
                group,
                "Goa",
                members.iter().map(|m| member(*m, None)).collect(),
            ),
        )
    }

    fn claim_rec(group: u8, member: u8, you: u8, millis: u64, dev: u8) -> OpRecord {
        rec(
            vid(millis, 0, dev),
            &claim(group, member, you, vec![device(dev)]),
        )
    }

    fn remove_rec(group: u8, member: u8, millis: u64, dev: u8) -> OpRecord {
        rec(
            vid(millis, 0, dev),
            &GroupOp::RemoveMember {
                group: hex(group),
                member: hex(member),
            },
        )
    }

    fn place_alias(group: u8, member: u8, you: u8) -> GroupOp {
        GroupOp::PlaceAlias {
            group: hex(group),
            member: hex(member),
            you: hex(you),
        }
    }

    fn alias_rec(group: u8, member: u8, you: u8, millis: u64, dev: u8) -> OpRecord {
        rec(vid(millis, 0, dev), &place_alias(group, member, you))
    }

    fn alias(group: u8, from: u8, to: u8) -> Alias {
        Alias {
            group: hex(group),
            from: hex(from),
            to: hex(to),
        }
    }

    /// (place, you_as, unaliased_yous) of the group `group`.
    fn placed(p: &GroupsProjection, group: u8) -> (Option<String>, Option<String>, Vec<String>) {
        let g = p
            .groups
            .iter()
            .find(|g| g.id == hex(group))
            .expect("the group");
        (g.place.clone(), g.you_as.clone(), g.unaliased_yous.clone())
    }

    /// Test 1: one row per branch of the place rules (Design 1) and of
    /// "readable" (Design 2). y = 1 is the account's "you" (its earliest), x = 2
    /// an older "you" of the same account, x2 = 7 a third, 8 another account.
    #[test]
    fn d229_the_place_and_you_as_follow_every_branch() {
        type Row = (
            &'static str,
            Vec<OpRecord>,
            Option<u8>,
            Option<u8>,
            Vec<u8>,
            Vec<Alias>,
        );
        let rows: Vec<Row> = vec![
            (
                "R1 you is a raw member: place you, write you",
                vec![you_rec(1, 900, 1), create_rec(9, &[1, 2], 1000, 1)],
                Some(1),
                None,
                vec![],
                vec![],
            ),
            (
                "R2 case (ii): {x, you x} stands, y unreadable: write x",
                vec![
                    you_rec(1, 900, 1),
                    you_rec(2, 950, 2),
                    create_rec(9, &[2, 3], 1000, 2),
                    claim_rec(9, 2, 2, 1100, 2),
                ],
                Some(2),
                Some(2),
                vec![1],
                vec![],
            ),
            (
                "R3 case (i): {x, you y}: y reads as x, write y",
                vec![
                    you_rec(1, 900, 1),
                    you_rec(2, 950, 2),
                    create_rec(9, &[2, 3], 1000, 2),
                    claim_rec(9, 2, 1, 1100, 2),
                ],
                Some(2),
                None,
                vec![],
                vec![],
            ),
            (
                "R4 a joiner {M, you z}, you y: write M",
                vec![
                    you_rec(1, 900, 1),
                    you_rec(4, 950, 4),
                    create_rec(9, &[5, 6], 1000, 5),
                    claim_rec(9, 6, 4, 1100, 4),
                ],
                Some(6),
                Some(6),
                vec![1],
                // D-237 (A1): the claimed place, an alias to you in this group.
                vec![alias(9, 6, 1)],
            ),
            (
                "R5 x removed, x2 present: x2",
                vec![
                    you_rec(1, 900, 1),
                    you_rec(2, 950, 2),
                    you_rec(7, 960, 7),
                    create_rec(9, &[2, 7, 3], 1000, 2),
                    remove_rec(9, 2, 1200, 2),
                ],
                Some(7),
                Some(7),
                vec![1],
                vec![],
            ),
            (
                "R6 x claimed by another account: skipped, x2 next",
                vec![
                    you_rec(1, 900, 1),
                    you_rec(2, 950, 2),
                    you_rec(7, 960, 7),
                    create_rec(9, &[2, 7, 3], 1000, 2),
                    claim_rec(9, 2, 8, 1100, 8),
                ],
                Some(7),
                Some(7),
                vec![1],
                vec![alias(9, 8, 2)],
            ),
            (
                "R7 x claimed by another account, nothing next: none",
                vec![
                    you_rec(1, 900, 1),
                    you_rec(2, 950, 2),
                    create_rec(9, &[2, 3], 1000, 2),
                    claim_rec(9, 2, 8, 1100, 8),
                ],
                None,
                None,
                vec![],
                vec![alias(9, 8, 2)],
            ),
            (
                "R8 no you: none",
                vec![create_rec(9, &[2, 3], 1000, 2), claim_rec(9, 2, 8, 1100, 8)],
                None,
                None,
                vec![],
                vec![alias(9, 8, 2)],
            ),
            (
                "R11 one you, its own claim: place you, write you",
                vec![
                    you_rec(1, 900, 1),
                    create_rec(9, &[1, 2], 1000, 1),
                    claim_rec(9, 1, 1, 1100, 1),
                ],
                Some(1),
                None,
                vec![],
                vec![],
            ),
        ];
        for (name, recs, place, you_as, unaliased, aliases) in rows {
            let p = project(&recs);
            assert_eq!(
                placed(&p, 9),
                (
                    place.map(hex),
                    you_as.map(hex),
                    unaliased.into_iter().map(hex).collect()
                ),
                "{name}"
            );
            assert_eq!(p.aliases, aliases, "{name}");
        }
    }

    /// A2 (D-227 decision 36): an invitee who joined the creator's place
    /// before the creator's create reached the cloud holds it on the server.
    /// Core agrees: the invitee's claim is "claimed by another" on the
    /// creator's phone (rule 1 and rule 3 skip it), and the invitee's own
    /// phone gets the place through rule 2. (Where the two can still differ,
    /// the creator's phone writing its own claim on the place with an EARLIER
    /// record identity than the invitee's, is residue in the report.)
    #[test]
    fn d229_an_invited_place_is_the_invitees_on_both_sides() {
        let creator = project(&[
            you_rec(2, 900, 2),
            create_rec(9, &[2, 3], 1000, 2),
            claim_rec(9, 2, 8, 1100, 8),
        ]);
        assert_eq!(placed(&creator, 9), (None, None, vec![]));
        assert!(lost(&creator, 9), "decision 45: and the shells know it");
        let invitee = project(&[
            you_rec(8, 800, 8),
            create_rec(9, &[2, 3], 1000, 2),
            claim_rec(9, 2, 8, 1100, 8),
        ]);
        assert_eq!(placed(&invitee, 9), (Some(hex(2)), None, vec![]));
        assert!(!lost(&invitee, 9));
    }

    /// The founder scenario as phone P of account A holds it (Task 1 test 7's
    /// records): y = 1 (phone Q), x = 2 (phone P), c = 3 (account C), Old = 9
    /// made on P under x, New = 8 made on Q under y.
    fn founder_on_p() -> Vec<OpRecord> {
        vec![
            you_rec(1, 1000, 0x51),
            you_rec(2, 2000, 0x50),
            create_rec(9, &[2, 3], 2100, 0x50),
            claim_rec(9, 2, 2, 2200, 0x50),
            create_rec(8, &[1, 3], 3000, 0x51),
            claim_rec(9, 3, 3, 2300, 0x53),
            claim_rec(8, 3, 3, 3100, 0x53),
        ]
    }

    /// Test 2: all 5040 orders of the founder scenario give the same place,
    /// you_as, unaliased_yous and aliases (the whole projection, in fact).
    #[test]
    fn d229_every_order_of_the_founder_scenario_places_the_same() {
        let recs = founder_on_p();
        let expected = project(&recs);
        assert_eq!(expected.you.as_ref().unwrap().id, hex(1));
        assert_eq!(expected.other_you_ids, vec![hex(2)]);
        assert_eq!(
            placed(&expected, 9),
            (Some(hex(2)), Some(hex(2)), vec![hex(1)])
        );
        assert_eq!(placed(&expected, 8), (Some(hex(1)), None, vec![hex(2)]));
        assert!(expected.aliases.is_empty());
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        let mut count = 0;
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
            count += 1;
        });
        assert_eq!(count, 5040);
    }

    /// Test 3 (Design 8): no account, one "you". A phone that never met
    /// another "you" writes "you" everywhere, exactly as before D-229.
    #[test]
    fn d229_one_you_writes_you_in_every_group() {
        let one = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[1, 2], 1000, 1),
            create_rec(8, &[1, 3], 1100, 1),
            rec(
                vid(1200, 0, 1),
                &GroupOp::AddMember {
                    group: hex(8),
                    member: member(4, Some("Asha")),
                },
            ),
        ]);
        for g in &one.groups {
            assert_eq!(g.place, Some(hex(1)), "{}", g.id);
            assert_eq!(g.you_as, None, "{}", g.id);
            assert!(g.unaliased_yous.is_empty(), "{}", g.id);
        }
        let none = project(&[create_rec(9, &[1, 2], 1000, 1)]);
        assert_eq!(placed(&none, 9), (None, None, vec![]));
    }

    /// Test 4: the pinned bytes, written out by hand (Python's json.dumps with
    /// compact separators gives the same, `.drafts/d229/goldens.py`).
    #[test]
    fn d229_a_place_alias_encodes_to_pinned_bytes() {
        let op = place_alias(9, 2, 1);
        let bytes = encode(&op).unwrap();
        assert_eq!(
            String::from_utf8(bytes.clone()).unwrap(),
            concat!(
                r#"{"op":"placeAlias","group":"00000000000000000000000000000009","#,
                r#""member":"00000000000000000000000000000002","#,
                r#""you":"00000000000000000000000000000001"}"#
            )
        );
        assert_eq!(decode(&bytes).unwrap(), op);
    }

    /// Test 4, negatives: refused on the way in AND on the way out.
    #[test]
    fn d229_bad_place_aliases_are_refused_both_ways() {
        let short = "a".repeat(31);
        let upper = hex(0xab).to_uppercase();
        let with =
            |group: String, member: String, you: String| GroupOp::PlaceAlias { group, member, you };
        let bad = vec![
            with(short.clone(), hex(2), hex(1)),
            with(hex(9), short.clone(), hex(1)),
            with(hex(9), hex(2), short),
            with(upper.clone(), hex(2), hex(1)),
            with(hex(9), upper.clone(), hex(1)),
            with(hex(9), hex(2), upper),
            place_alias(9, 2, 2),
        ];
        for op in bad {
            assert!(
                matches!(encode(&op), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
            let raw = serde_json::to_vec(&op).unwrap();
            assert!(
                matches!(decode(&raw), Err(GroupOpError::Invalid(_))),
                "{op:?}"
            );
        }
        let unknown = concat!(
            r#"{"op":"placeAlias","group":"00000000000000000000000000000009","#,
            r#""member":"00000000000000000000000000000002","#,
            r#""you":"00000000000000000000000000000001","alias":true}"#
        );
        assert!(matches!(
            decode(unknown.as_bytes()),
            Err(GroupOpError::Unreadable(_))
        ));
    }

    /// Test 5: where a marked alias applies, waits, or is contested.
    #[test]
    fn d229_place_aliases_wait_contest_and_apply() {
        // Pending: no group, and a member nobody added.
        let p = project(&[you_rec(1, 900, 1), alias_rec(7, 2, 1, 2000, 9)]);
        assert_eq!(p.pending, vec![vid(2000, 0, 9).render()]);
        let p = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[2, 3], 1000, 2),
            alias_rec(9, 5, 1, 2000, 9),
        ]);
        assert_eq!(p.pending, vec![vid(2000, 0, 9).render()]);
        assert!(p.aliases.is_empty() && p.other_you_ids.is_empty());
        // Contested: `you` is a raw member of the group (it would take over a
        // member's records), seen by a third member (you = 3).
        let p = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 4], 1000, 2),
            alias_rec(9, 2, 4, 2000, 9),
        ]);
        assert_eq!(p.contested, vec![vid(2000, 0, 9).render()]);
        assert!(p.aliases.is_empty());
        // Contested: a second alias for the same (group, you); the first stands.
        let p = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 4], 1000, 2),
            alias_rec(9, 4, 1, 2100, 9),
            alias_rec(9, 2, 1, 2000, 9),
        ]);
        assert_eq!(p.contested, vec![vid(2100, 0, 9).render()]);
        assert_eq!(p.aliases, vec![alias(9, 1, 2)]);
        // A claim naming the same id on another member contests the marked
        // alias (decision 47, which replaced "a marked alias beats a claim
        // alias"): no alias for the id at all.
        let p = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[5, 2, 3], 1000, 2),
            claim_rec(9, 5, 1, 1100, 5),
            alias_rec(9, 2, 1, 2000, 9),
        ]);
        assert!(p.aliases.is_empty(), "{:?}", p.aliases);
        // On the account's own phone the place joins `other_you_ids` (as a
        // claim's member does), the alias is not listed, and the aliased id
        // leaves `unaliased_yous`. `you_as` stays the place: a marked alias
        // does not make "you" readable (0.4.0 builds cannot read it).
        let own = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[2, 3], 1000, 2),
            alias_rec(9, 2, 1, 2000, 9),
        ]);
        assert_eq!(own.other_you_ids, vec![hex(2)]);
        // D-237 (A1): listed as an alias to you, for this group only.
        assert_eq!(own.aliases, vec![alias(9, 2, 1)]);
        assert_eq!(placed(&own, 9), (Some(hex(2)), Some(hex(2)), vec![]));
        assert_eq!(
            own.groups[0].members[0].id,
            hex(1),
            "the place reads as you"
        );
        // The founder's phone P: y leaves Old's unaliased_yous once aliased.
        let mut recs = founder_on_p();
        recs.push(alias_rec(9, 2, 1, 4000, 0x50));
        let p = project(&recs);
        assert_eq!(placed(&p, 9), (Some(hex(2)), Some(hex(2)), vec![]));
        assert_eq!(p.other_you_ids, vec![hex(2)]);
        assert!(p.aliases.is_empty());
    }

    /// Test 6 (Design 6): one alias per (group, from id), and a claim whose
    /// `you` is a raw member of the group gives no alias, so that member's
    /// records keep reading as that member. A joiner (D-158) still aliases.
    #[test]
    fn d229_a_claim_cannot_alias_a_member_and_one_id_has_one_alias() {
        let hole = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 4], 1000, 2),
            claim_rec(9, 4, 2, 1100, 7),
        ]);
        assert!(hole.aliases.is_empty(), "D (2) is not read as U (4)");
        assert_eq!(
            hole.groups[0].claims,
            vec![ProjectedClaim {
                member: hex(4),
                you: hex(2),
                devices: vec![device(7)],
            }],
            "the claim itself still stands"
        );
        let yous = super::super::identity::Yous::new(&hex(3), &[]).with_aliases(
            &hole
                .aliases
                .iter()
                .map(|a| (a.group.clone(), a.from.clone(), a.to.clone()))
                .collect::<Vec<_>>(),
        );
        assert_eq!(yous.id_in(Some(&hex(9)), &hex(2)), hex(2));
        // Two winning claims naming one id: contested, no alias at all
        // (decision 44; it was "the first in member order" before the fix).
        let two = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 4, 5], 1000, 2),
            claim_rec(9, 5, 1, 1100, 5),
            claim_rec(9, 4, 1, 1200, 4),
        ]);
        assert!(two.aliases.is_empty(), "{:?}", two.aliases);
        // The D-158 joiner.
        let joiner = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[1, 2], 1000, 1),
            claim_rec(9, 2, 20, 3000, 30),
        ]);
        assert_eq!(joiner.aliases, vec![alias(9, 20, 2)]);
    }

    fn lost(p: &GroupsProjection, group: u8) -> bool {
        p.groups
            .iter()
            .find(|g| g.id == hex(group))
            .expect("the group")
            .place_lost
    }

    /// Decision 44: a member who claims an unclaimed EARLIER member with a
    /// joiner's own id takes nothing. The id is contested: it reads as no
    /// member on other phones (a denial, never a takeover), the attacker's
    /// member never merges into the joiner, and the joiner's place does not
    /// move there (it is lost, decision 45, so the shells stop writing).
    #[test]
    fn d229_a_second_claim_on_a_joiners_id_takes_nothing() {
        // Group 9: creator 1, an unclaimed placeholder U = 5, the joiner's
        // member M = 6. The joiner J's "you" is 20; the attacker writes 7's
        // device.
        let base = vec![
            create_rec(9, &[1, 5, 6], 1000, 1),
            claim_rec(9, 6, 20, 1100, 30),
        ];
        let attack = claim_rec(9, 5, 20, 1200, 7);
        // Before: everyone reads 20 as M; J's place is M.
        let mut third = vec![you_rec(1, 900, 1)];
        third.extend(base.clone());
        assert_eq!(project(&third).aliases, vec![alias(9, 20, 6)]);
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(base.clone());
        let before = project(&joiner);
        assert_eq!(placed(&before, 9), (Some(hex(6)), None, vec![]));
        assert!(!lost(&before, 9));
        // After: the third member reads 20 as nobody, never as U.
        third.push(attack.clone());
        let p = project(&third);
        assert!(p.aliases.is_empty(), "{:?}", p.aliases);
        let yous = super::super::identity::Yous::new(&hex(1), &[]).with_aliases(
            &p.aliases
                .iter()
                .map(|a| (a.group.clone(), a.from.clone(), a.to.clone()))
                .collect::<Vec<_>>(),
        );
        assert_eq!(yous.id_in(Some(&hex(9)), &hex(20)), hex(20));
        // On J's phone U never joins J, and the place does not move to U.
        joiner.push(attack);
        let p = project(&joiner);
        assert!(!p.other_you_ids.contains(&hex(5)), "{:?}", p.other_you_ids);
        assert_eq!(placed(&p, 9).0, None, "not U, and no claim J cannot prove");
        assert!(lost(&p, 9), "the group goes read-only on J's phone");
        assert_eq!(
            p.groups[0]
                .members
                .iter()
                .filter(|m| m.id == hex(20))
                .count(),
            0
        );
    }

    /// Decision 45: the lost place, from every side. The A2 case (decision
    /// 36 seen from the creator's phone), an older id held by another account
    /// (R7) and a contested own claim are lost; ordinary groups are not.
    #[test]
    fn d229_a_lost_place_is_reported_and_an_ordinary_one_is_not() {
        let creator = project(&[
            you_rec(2, 900, 2),
            create_rec(9, &[2, 3], 1000, 2),
            claim_rec(9, 2, 8, 1100, 8),
        ]);
        assert!(lost(&creator, 9), "decision 36 from the creator's phone");
        let older = project(&[
            you_rec(1, 900, 1),
            you_rec(2, 950, 2),
            create_rec(9, &[2, 3], 1000, 2),
            claim_rec(9, 2, 8, 1100, 8),
        ]);
        assert!(
            lost(&older, 9),
            "R7: the older id is held by another account"
        );
        // Negatives: one you; case (ii); case (i); the invitee; no you; a
        // place merely removed (not taken); a group made by someone else
        // the account is no member of.
        let ordinary: Vec<Vec<OpRecord>> = vec![
            vec![you_rec(1, 900, 1), create_rec(9, &[1, 2], 1000, 1)],
            vec![
                you_rec(1, 900, 1),
                you_rec(2, 950, 2),
                create_rec(9, &[2, 3], 1000, 2),
                claim_rec(9, 2, 2, 1100, 2),
            ],
            vec![
                you_rec(1, 900, 1),
                you_rec(2, 950, 2),
                create_rec(9, &[2, 3], 1000, 2),
                claim_rec(9, 2, 1, 1100, 2),
            ],
            vec![
                you_rec(8, 800, 8),
                create_rec(9, &[2, 3], 1000, 2),
                claim_rec(9, 2, 8, 1100, 8),
            ],
            vec![create_rec(9, &[2, 3], 1000, 2), claim_rec(9, 2, 8, 1100, 8)],
            vec![
                you_rec(1, 900, 1),
                create_rec(9, &[1, 3], 1000, 1),
                remove_rec(9, 1, 1200, 3),
            ],
            vec![you_rec(1, 900, 1), create_rec(9, &[4, 3], 1000, 4)],
        ];
        for (n, recs) in ordinary.iter().enumerate() {
            assert!(!lost(&project(recs), 9), "ordinary row {n}");
        }
        let founder = project(&founder_on_p());
        assert!(!lost(&founder, 9) && !lost(&founder, 8));
    }

    /// Minor 3: "you" is readable only through the alias every phone reads
    /// it by. A marked alias naming another place for y contests y (decision
    /// 47): 0.4.0 builds still read the claim, new builds nothing, so "you"
    /// is not readable, the claim makes no place, and the place is the
    /// account's raw member: write that.
    #[test]
    fn d229_you_is_readable_only_through_the_alias_every_phone_reads() {
        let p = project(&[
            you_rec(1, 900, 1),
            you_rec(2, 950, 2),
            create_rec(9, &[2, 6, 3], 1000, 2),
            claim_rec(9, 6, 1, 1100, 1),
            alias_rec(9, 2, 1, 2000, 9),
        ]);
        assert_eq!(placed(&p, 9), (Some(hex(2)), Some(hex(2)), vec![hex(1)]));
        let plain = project(&[
            you_rec(1, 900, 1),
            you_rec(2, 950, 2),
            create_rec(9, &[2, 6, 3], 1000, 2),
            claim_rec(9, 6, 1, 1100, 1),
        ]);
        assert_eq!(placed(&plain, 9), (Some(hex(6)), None, vec![]));
    }

    /// Decision 47: the id's owner claiming another member with the same id
    /// contests a marked alias: the id reads as nobody, not as the place. A
    /// claim on the alias's own place agrees with it, and no claim leaves it.
    #[test]
    fn d229_an_owners_claim_contests_a_marked_alias() {
        let base = vec![
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 4], 1000, 2),
            alias_rec(9, 2, 1, 2000, 9),
        ];
        let mut contested = base.clone();
        contested.push(claim_rec(9, 4, 1, 2100, 7));
        let p = project(&contested);
        assert!(p.aliases.is_empty(), "{:?}", p.aliases);
        let yous = super::super::identity::Yous::new(&hex(3), &[]).with_aliases(
            &p.aliases
                .iter()
                .map(|a| (a.group.clone(), a.from.clone(), a.to.clone()))
                .collect::<Vec<_>>(),
        );
        assert_eq!(yous.id_in(Some(&hex(9)), &hex(1)), hex(1), "not x");
        // The owner's own phone: the place does not merge into "you".
        let mut own = vec![you_rec(1, 800, 1)];
        own.extend(contested[1..].iter().cloned());
        assert!(project(&own).other_you_ids.is_empty());
        // Negatives: no claim, and a claim on the alias's own place.
        assert_eq!(project(&base).aliases, vec![alias(9, 1, 2)]);
        let mut agreeing = base;
        agreeing.push(claim_rec(9, 2, 1, 2100, 7));
        assert_eq!(project(&agreeing).aliases, vec![alias(9, 1, 2)]);
    }

    /// Minor 4: the same alias again (same place, same id) changes nothing and
    /// is not contested; one for another place still is.
    #[test]
    fn d229_the_same_place_alias_again_changes_nothing() {
        let p = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 4], 1000, 2),
            alias_rec(9, 2, 1, 2000, 9),
            alias_rec(9, 2, 1, 2100, 8),
        ]);
        assert!(p.contested.is_empty(), "{:?}", p.contested);
        assert_eq!(p.aliases, vec![alias(9, 1, 2)]);
    }

    // ---- D-230: held places (the server's word on who holds a place) ----
    // Goldens re-derived by `.drafts/d230/goldens.py` (the same rules in
    // Python, every expected value below printed by it).

    fn held_place(group: u8, member: u8, you: u8) -> GroupOp {
        GroupOp::HeldPlace {
            group: hex(group),
            member: hex(member),
            you: hex(you),
        }
    }

    fn held_rec(group: u8, member: u8, you: u8, millis: u64, dev: u8) -> OpRecord {
        rec(vid(millis, 0, dev), &held_place(group, member, you))
    }

    fn held_of(p: &GroupsProjection, group: u8) -> Vec<(String, String)> {
        p.groups
            .iter()
            .find(|g| g.id == hex(group))
            .map(|g| {
                g.held
                    .iter()
                    .map(|h| (h.member.clone(), h.you.clone()))
                    .collect()
            })
            .unwrap_or_default()
    }

    #[test]
    fn d230_a_held_place_encodes_to_pinned_bytes() {
        let op = held_place(9, 2, 1);
        let bytes = encode(&op).unwrap();
        assert_eq!(
            String::from_utf8(bytes.clone()).unwrap(),
            format!(
                r#"{{"op":"heldPlace","group":"{}","member":"{}","you":"{}"}}"#,
                hex(9),
                hex(2),
                hex(1)
            )
        );
        assert_eq!(decode(&bytes).unwrap(), op);
        // The creator's raw place: `you` equal to `member` is a held place (never an alias).
        let own = held_place(9, 2, 2);
        assert_eq!(decode(&encode(&own).unwrap()).unwrap(), own);
    }

    #[test]
    fn d230_bad_held_places_are_refused_both_ways() {
        let make =
            |group: String, member: String, you: String| GroupOp::HeldPlace { group, member, you };
        for bad in [
            make("9".into(), hex(2), hex(1)),
            make(hex(9), "zz".repeat(16), hex(1)),
            make(hex(9), hex(2), String::new()),
        ] {
            assert!(
                matches!(encode(&bad), Err(GroupOpError::Invalid(_))),
                "{bad:?}"
            );
            let raw = serde_json::to_vec(&bad).unwrap();
            assert!(
                matches!(decode(&raw), Err(GroupOpError::Invalid(_))),
                "{bad:?}"
            );
        }
        let extra = format!(
            r#"{{"op":"heldPlace","group":"{}","member":"{}","you":"{}","x":1}}"#,
            hex(9),
            hex(2),
            hex(1)
        );
        assert!(decode(extra.as_bytes()).is_err(), "unknown field");
    }

    /// D1, D2 and decision 60: waits for its member (no group, or a group
    /// without that member), the latest per member stands, an older one is
    /// contested, and a `you` that is another raw member (a shape the server
    /// refuses to write) stands: that raw id reads as the held member.
    #[test]
    fn d230_held_places_wait_supersede_and_name_a_raw_member() {
        let p = project(&[you_rec(1, 900, 1), held_rec(9, 2, 1, 2000, 9)]);
        assert_eq!(p.pending, vec![vid(2000, 0, 9).render()]);
        let p = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3], 1000, 2),
            held_rec(9, 5, 1, 2000, 9),
        ]);
        assert_eq!(p.pending, vec![vid(2000, 0, 9).render()], "no member 5");
        assert!(held_of(&p, 9).is_empty());
        let p = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3], 1000, 2),
            held_rec(9, 2, 1, 2000, 9),
            held_rec(9, 2, 4, 2100, 9),
            held_rec(9, 2, 5, 1900, 9),
        ]);
        assert_eq!(held_of(&p, 9), vec![(hex(2), hex(4))], "the latest");
        assert_eq!(
            p.contested,
            vec![vid(1900, 0, 9).render(), vid(2000, 0, 9).render()]
        );
        let raw = vec![
            create_rec(9, &[2, 3, 4], 1000, 2),
            held_rec(9, 2, 4, 2000, 9),
        ];
        let mut third = vec![you_rec(3, 900, 3)];
        third.extend(raw.clone());
        let p = project(&third);
        assert!(p.contested.is_empty(), "{:?}", p.contested);
        assert_eq!(held_of(&p, 9), vec![(hex(2), hex(4))]);
        assert_eq!(p.aliases, vec![alias(9, 4, 2)], "4 reads as 2");
        let mut holder = vec![you_rec(4, 900, 4)];
        holder.extend(raw);
        let h = project(&holder);
        assert_eq!(placed(&h, 9), (Some(hex(2)), None, vec![]));
        assert!(!lost(&h, 9));
    }

    /// D4, the G-08 pinned case flipped: with 6 held by id 20, the backdated
    /// claim on 4 takes nothing, before and after 4 is removed, and J's group
    /// is never read-only.
    #[test]
    fn d230_a_backdated_claim_takes_nothing_from_a_held_place() {
        let group = vec![
            create_rec(9, &[1, 4, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            held_rec(9, 6, 20, 1101, 9),
            claim_rec(9, 6, 20, 1100, 30),
            claim_rec(9, 4, 20, 1060, 4),
        ];
        let mut creator = vec![you_rec(1, 900, 1)];
        creator.extend(group.clone());
        let c = project(&creator);
        assert_eq!(
            c.aliases,
            vec![alias(9, 20, 6)],
            "J reads as 6 while 4 is present"
        );
        assert_eq!(
            c.contested,
            vec![vid(1060, 0, 4).render()],
            "the forged claim is void"
        );
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group.clone());
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (Some(hex(6)), None, vec![]));
        assert!(!lost(&j, 9), "never read-only");
        assert_eq!(j.other_you_ids, vec![hex(6)]);
        creator.push(remove_rec(9, 4, 2000, 1));
        assert_eq!(
            project(&creator).aliases,
            vec![alias(9, 20, 6)],
            "and after 4 is removed"
        );
    }

    /// D4: a pre-join claim with an honest time (whole-branch review I2)
    /// takes nothing once the joiner's place is held; a decision 44 denial
    /// lifts with the mark alone, nobody removed; the creator's raw place
    /// with a backdated foreign claim stays the creator's.
    #[test]
    fn d230_a_pre_join_claim_and_a_creator_forgery_take_nothing() {
        let base = vec![
            create_rec(9, &[1, 4, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 4, 20, 1060, 4),
            claim_rec(9, 6, 20, 1100, 30),
        ];
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(base.clone());
        assert!(
            lost(&project(&joiner), 9),
            "decision 44's denial before the mark"
        );
        joiner.push(held_rec(9, 6, 20, 1101, 9));
        let j = project(&joiner);
        assert!(!lost(&j, 9), "the mark lifts it");
        assert_eq!(placed(&j, 9), (Some(hex(6)), None, vec![]));
        let mut third = vec![you_rec(4, 900, 4)];
        third.extend(base);
        third.push(held_rec(9, 6, 20, 1101, 9));
        let t = project(&third);
        assert_eq!(t.aliases, vec![alias(9, 20, 6)]);
        assert!(
            t.other_you_ids.is_empty(),
            "4's forged claim merges nothing"
        );
        // The creator: a backdated claim {1, you 7} loses to the held raw place {1, you 1}.
        let creator = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[1, 4], 1000, 1),
            claim_rec(9, 1, 7, 1040, 4),
            claim_rec(9, 1, 1, 1050, 1),
            held_rec(9, 1, 1, 1051, 9),
        ]);
        assert_eq!(placed(&creator, 9), (Some(hex(1)), None, vec![]));
        assert!(!lost(&creator, 9));
        assert_eq!(creator.contested, vec![vid(1040, 0, 4).render()]);
        assert_eq!(held_of(&creator, 9), vec![(hex(1), hex(1))]);
    }

    /// D2 and D5: decision 36's race (the invitee's later mark supersedes the
    /// creator's on the raw place); a held id beats a marked alias naming it
    /// for another member; a held place on a removed member still fixes its
    /// history; the same record again changes nothing.
    #[test]
    fn d230_the_latest_mark_wins_and_beats_an_alias() {
        let race = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[1, 4], 1000, 1),
            held_rec(9, 1, 1, 1050, 9),
            held_rec(9, 1, 8, 1200, 9),
            claim_rec(9, 1, 8, 1201, 8),
        ]);
        assert_eq!(held_of(&race, 9), vec![(hex(1), hex(8))]);
        assert!(
            lost(&race, 9),
            "the creator's phone: the place is the invitee's (decision 45)"
        );
        assert_eq!(race.contested, vec![vid(1050, 0, 9).render()]);
        let p = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 5], 1000, 2),
            held_rec(9, 5, 1, 1500, 9),
            alias_rec(9, 2, 1, 2000, 9),
        ]);
        assert_eq!(p.aliases, vec![alias(9, 1, 5)]);
        assert_eq!(p.contested, vec![vid(2000, 0, 9).render()]);
        let gone = project(&[
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 5], 1000, 2),
            held_rec(9, 5, 1, 1500, 9),
            remove_rec(9, 5, 1600, 2),
        ]);
        assert_eq!(
            gone.aliases,
            vec![alias(9, 1, 5)],
            "history stays on the removed member"
        );
        let once = vec![
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 5], 1000, 2),
            held_rec(9, 5, 1, 1500, 9),
        ];
        let mut twice = once.clone();
        twice.push(held_rec(9, 5, 1, 1500, 9));
        assert_eq!(project(&once), project(&twice));
    }

    /// D6: the held place comes before rule 2. An account with two ids (1,
    /// and 2 from its other phone) claimed 4 under 2 earlier in member order;
    /// the server marked 6 as held by 1. The place is 6, the server's word,
    /// not 4; with no mark, rule 2 takes 4 (and writes as 4).
    #[test]
    fn d230_a_held_place_comes_before_an_earlier_claim() {
        let unmarked = vec![
            you_rec(1, 900, 1),
            you_rec(2, 950, 2),
            create_rec(9, &[4, 6, 3], 1000, 3),
            claim_rec(9, 4, 2, 1100, 2),
        ];
        assert_eq!(
            placed(&project(&unmarked), 9),
            (Some(hex(4)), Some(hex(4)), vec![hex(1)])
        );
        let mut marked = unmarked;
        marked.push(held_rec(9, 6, 1, 1500, 9));
        let p = project(&marked);
        assert_eq!(placed(&p, 9), (Some(hex(6)), None, vec![]));
        assert!(!lost(&p, 9));
        assert_eq!(p.other_you_ids, vec![hex(2), hex(4), hex(6)]);
    }

    fn add_rec(group: u8, id: u8, millis: u64, dev: u8) -> OpRecord {
        rec(
            vid(millis, 0, dev),
            &GroupOp::AddMember {
                group: hex(group),
                member: member(id, None),
            },
        )
    }

    /// Decision 59 (A2, the review's I1 and M1): an honest rejoin with BOTH
    /// places marked keeps the history on the old place whatever order the
    /// two marks were written in: a held place counts from its member's
    /// first add, not from its mark. The rejoiner's phone merges both places
    /// and writes as the new one (6 is not present, so the claim alias does
    /// not read there). A marked alias of the held id onto the newer place is
    /// contested in both orders (the id gives itself to the earliest-added
    /// holder, never to the latest mark).
    #[test]
    fn d230_a_marked_rejoin_keeps_the_history_on_the_old_place() {
        // d229_an_honest_rejoin_stays_one_person's inputs.
        let group = vec![
            create_rec(9, &[1, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            remove_rec(9, 6, 2000, 1),
            add_rec(9, 7, 3000, 1),
            claim_rec(9, 7, 20, 3100, 30),
            held_rec(9, 7, 20, 3050, 9),
        ];
        for (order, six) in [("6 marked first", 1101), ("6 marked after 7", 4000)] {
            let mut marked = group.clone();
            marked.push(held_rec(9, 6, 20, six, 9));
            let mut third = vec![you_rec(1, 900, 1)];
            third.extend(marked.clone());
            let c = project(&third);
            assert_eq!(c.aliases, vec![alias(9, 20, 6)], "{order}: history on 6");
            assert!(c.contested.is_empty(), "{order}: {:?}", c.contested);
            assert_eq!(
                held_of(&c, 9),
                vec![(hex(6), hex(20)), (hex(7), hex(20))],
                "{order}"
            );
            let mut joiner = vec![you_rec(20, 800, 30)];
            joiner.extend(marked.clone());
            let j = project(&joiner);
            assert_eq!(j.other_you_ids, vec![hex(6), hex(7)], "{order}");
            assert_eq!(
                placed(&j, 9),
                (Some(hex(7)), Some(hex(7)), vec![]),
                "{order}"
            );
            assert!(!lost(&j, 9), "{order}");
            third.push(alias_rec(9, 7, 20, 5000, 9));
            let a = project(&third);
            assert_eq!(a.aliases, vec![alias(9, 20, 6)], "{order}: alias onto 7");
            assert_eq!(a.contested, vec![vid(5000, 0, 9).render()], "{order}");
        }
    }

    /// Decision 58's transient, pinned (A2, the review's I1): with only the
    /// new place marked, core voids the honest old claim on 6 and the history
    /// reads as 7 (core cannot tell it from G-08's backdated forgery). The
    /// server's mark for 6 repairs it, whatever its identity: written after
    /// 7's, the history is back on 6.
    #[test]
    fn d230_an_unmarked_old_place_is_repaired_when_its_mark_arrives() {
        let group = vec![
            create_rec(9, &[1, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            remove_rec(9, 6, 2000, 1),
            add_rec(9, 7, 3000, 1),
            held_rec(9, 7, 20, 3050, 9),
            claim_rec(9, 7, 20, 3100, 30),
        ];
        let mut third = vec![you_rec(1, 900, 1)];
        third.extend(group.clone());
        let c = project(&third);
        assert_eq!(c.aliases, vec![alias(9, 20, 7)], "the transient");
        assert_eq!(c.contested, vec![vid(1100, 0, 30).render()]);
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group.clone());
        let j = project(&joiner);
        assert_eq!(j.other_you_ids, vec![hex(7)]);
        assert_eq!(placed(&j, 9), (Some(hex(7)), None, vec![]));
        let repair = held_rec(9, 6, 20, 4000, 9);
        third.push(repair.clone());
        let c = project(&third);
        assert_eq!(c.aliases, vec![alias(9, 20, 6)], "repaired");
        assert!(c.contested.is_empty(), "{:?}", c.contested);
        joiner.push(repair);
        let j = project(&joiner);
        assert_eq!(j.other_you_ids, vec![hex(6), hex(7)]);
        assert_eq!(placed(&j, 9), (Some(hex(7)), Some(hex(7)), vec![]));
        assert!(!lost(&j, 9));
    }

    /// Decision 60 (A3, the review's I2): any member can write
    /// `addMember { id: 20 }` with J's held id. Written before or after the
    /// mark, it takes nothing: 20 reads as the held 6 on every phone, the
    /// mark is not contested, J's phone places at 6 (never at the raw 20)
    /// and writes as 20, and 20 stays listed as an empty placeholder.
    #[test]
    fn d230_the_mark_wins_over_a_raw_member_added_with_its_id() {
        let group = vec![
            create_rec(9, &[1, 4, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            held_rec(9, 6, 20, 1101, 9),
        ];
        let placeholder = |millis: u64| {
            rec(
                vid(millis, 0, 4),
                &GroupOp::AddMember {
                    group: hex(9),
                    member: member(20, Some("X")),
                },
            )
        };
        for (when, millis) in [("before the mark", 1090), ("after the mark", 1200)] {
            let mut recs = group.clone();
            recs.push(placeholder(millis));
            for phone in [1, 4] {
                let mut other = vec![you_rec(phone, 900, phone)];
                other.extend(recs.clone());
                let p = project(&other);
                assert_eq!(p.aliases, vec![alias(9, 20, 6)], "{when}, phone {phone}");
                assert!(p.contested.is_empty(), "{when}: {:?}", p.contested);
                assert_eq!(held_of(&p, 9), vec![(hex(6), hex(20))], "{when}");
                let g = p.groups.iter().find(|g| g.id == hex(9)).unwrap();
                assert!(
                    g.members
                        .iter()
                        .any(|m| m.id == hex(20) && m.name.as_deref() == Some("X")),
                    "{when}: the placeholder is listed: {:?}",
                    g.members
                );
            }
            let mut joiner = vec![you_rec(20, 800, 30)];
            joiner.extend(recs);
            let j = project(&joiner);
            assert_eq!(placed(&j, 9), (Some(hex(6)), None, vec![]), "{when}");
            assert!(!lost(&j, 9), "{when}");
            // M-1: once 6 is removed, rule 3 does not place J at the raw 20
            // either (a held place gives that id to 6): no place, not lost,
            // and 20 still reads as 6 on every other phone.
            joiner.push(remove_rec(9, 6, 2000, 1));
            let j = project(&joiner);
            assert_eq!(placed(&j, 9), (None, None, vec![]), "{when}, 6 removed");
            assert!(!lost(&j, 9), "{when}, 6 removed");
            let mut third = vec![you_rec(1, 900, 1)];
            third.extend(joiner[1..].iter().cloned());
            assert_eq!(project(&third).aliases, vec![alias(9, 20, 6)], "{when}");
        }
    }

    /// A6, I-A: a creator (1, its "you" its own raw place, marked {1, you 1})
    /// is removed after a handover and re-invited as 7. With the server's
    /// mark {7, you 1}: placed at 7 and writes as 7 (no claim alias reads 1),
    /// not lost, and 1 aliases to nothing on any phone, so the creator's old
    /// history stays on 1 (held by itself). Without that mark (the transient
    /// the server repairs): the phone's claim {7, 1} is void, no place, not
    /// lost.
    #[test]
    fn d230_a_removed_creator_who_rejoins_keeps_its_history() {
        let group = vec![
            create_rec(9, &[1, 4], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            held_rec(9, 1, 1, 1051, 9),
            remove_rec(9, 1, 2000, 4),
            add_rec(9, 7, 3000, 4),
            claim_rec(9, 7, 1, 3100, 1),
        ];
        let mut marked = group.clone();
        marked.push(held_rec(9, 7, 1, 3050, 9));
        // D-237 (A1): on the creator's own phone its rejoined place is an
        // alias to you (this group only); on phone 4 nothing is.
        for (phone, dev, own) in [(1, 1, vec![alias(9, 7, 1)]), (4, 4, vec![])] {
            let mut recs = vec![you_rec(phone, 900, dev)];
            recs.extend(marked.clone());
            let p = project(&recs);
            assert_eq!(p.aliases, own, "phone {phone}");
            assert!(p.contested.is_empty(), "phone {phone}: {:?}", p.contested);
        }
        let mut creator = vec![you_rec(1, 900, 1)];
        creator.extend(marked);
        let c = project(&creator);
        assert_eq!(placed(&c, 9), (Some(hex(7)), Some(hex(7)), vec![]));
        assert!(!lost(&c, 9));
        assert_eq!(c.other_you_ids, vec![hex(7)]);
        let mut unmarked = vec![you_rec(1, 900, 1)];
        unmarked.extend(group.clone());
        let u = project(&unmarked);
        assert_eq!(placed(&u, 9), (None, None, vec![]), "the transient");
        assert!(!lost(&u, 9));
        assert_eq!(u.contested, vec![vid(3100, 0, 1).render()]);
        let mut third = vec![you_rec(4, 900, 4)];
        third.extend(group);
        assert!(project(&third).aliases.is_empty());
    }

    /// A6, I-B: the old place 6 marked, the new place 7 claimed but its mark
    /// failed at the join (decision 56): the claim {7, 20} is void, so no
    /// place, not lost, and history reads as 6. The mark {7, 20} the next
    /// push asks for returns the place; the history stays on 6.
    #[test]
    fn d230_a_rejoin_whose_new_mark_failed_gets_its_place_back() {
        let group = vec![
            create_rec(9, &[1, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            held_rec(9, 6, 20, 1101, 9),
            remove_rec(9, 6, 2000, 1),
            add_rec(9, 7, 3000, 1),
            claim_rec(9, 7, 20, 3100, 30),
        ];
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group.clone());
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (None, None, vec![]), "the transient");
        assert!(!lost(&j, 9));
        assert_eq!(j.contested, vec![vid(3100, 0, 30).render()]);
        let mut third = vec![you_rec(1, 900, 1)];
        third.extend(group);
        assert_eq!(project(&third).aliases, vec![alias(9, 20, 6)]);
        let mark = held_rec(9, 7, 20, 3200, 9);
        joiner.push(mark.clone());
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (Some(hex(7)), Some(hex(7)), vec![]));
        assert!(!lost(&j, 9));
        assert!(j.contested.is_empty(), "{:?}", j.contested);
        third.push(mark);
        assert_eq!(project(&third).aliases, vec![alias(9, 20, 6)]);
    }

    /// M2: the holder's own phone-written claim on a held place (the mark's
    /// `you`) is not reported contested, even where the id gives itself to
    /// another held place (a marked rejoin); a claim with another `you` on a
    /// held place is.
    #[test]
    fn d230_a_holders_own_claim_on_a_held_place_is_not_contested() {
        let mut recs = vec![
            you_rec(1, 900, 1),
            create_rec(9, &[1, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            held_rec(9, 6, 20, 1101, 9),
            remove_rec(9, 6, 2000, 1),
            add_rec(9, 7, 3000, 1),
            held_rec(9, 7, 20, 3050, 9),
            claim_rec(9, 7, 20, 3100, 30),
        ];
        assert!(project(&recs).contested.is_empty());
        recs.push(claim_rec(9, 7, 21, 3200, 7));
        let p = project(&recs);
        assert_eq!(p.contested, vec![vid(3200, 0, 7).render()]);
        assert_eq!(p.aliases, vec![alias(9, 20, 6)]);
    }

    /// M3: one id held by two PRESENT places (decision 52 keeps the server
    /// from writing it) is contested (decision 44): it makes no place
    /// through rule 1.5 either, and the account's group is read-only.
    #[test]
    fn d230_a_held_id_on_two_present_places_makes_no_place() {
        let group = vec![
            create_rec(9, &[1, 6, 7], 1000, 1),
            held_rec(9, 6, 20, 1101, 9),
            held_rec(9, 7, 20, 1102, 9),
        ];
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group.clone());
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (None, None, vec![]));
        assert!(lost(&j, 9));
        let mut third = vec![you_rec(1, 900, 1)];
        third.extend(group);
        assert!(project(&third).aliases.is_empty());
    }

    /// Decision 49: an honest rejoin (D-210 re-invites a removed person to a
    /// NEW member and the phone claims it with its unchanged "you") stays one
    /// person. History reads as the old (removed) member, where the removal's
    /// redistribution expects it; new records go to the new place.
    #[test]
    fn d229_an_honest_rejoin_stays_one_person() {
        // Creator 1 (self-claimed), J = 20 joined as 6, was removed, was
        // re-invited as 7 and claimed it.
        let group = vec![
            create_rec(9, &[1, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            remove_rec(9, 6, 2000, 1),
            add_rec(9, 7, 3000, 1),
            claim_rec(9, 7, 20, 3100, 30),
        ];
        let mut creator = vec![you_rec(1, 900, 1)];
        creator.extend(group.clone());
        let c = project(&creator);
        assert_eq!(c.aliases, vec![alias(9, 20, 6)], "history stays on 6");
        assert!(c.contested.is_empty());
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group);
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (Some(hex(7)), Some(hex(7)), vec![]));
        assert!(!lost(&j, 9));
        assert_eq!(j.other_you_ids, vec![hex(6), hex(7)]);
    }

    /// Decision 49: removing the forger's member lifts a decision 44 denial
    /// (its claim stops being on a present member), here with the forged
    /// member AFTER the joiner's in member order.
    #[test]
    fn d229_removing_the_forgers_member_lifts_the_denial() {
        let mut recs = vec![
            you_rec(1, 900, 1),
            create_rec(9, &[1, 6, 5], 1000, 1),
            claim_rec(9, 6, 20, 1100, 30),
            claim_rec(9, 5, 20, 1200, 7),
        ];
        assert!(project(&recs).aliases.is_empty(), "two present claims");
        recs.push(remove_rec(9, 5, 2000, 1));
        assert_eq!(project(&recs).aliases, vec![alias(9, 20, 6)]);
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(recs[1..].iter().cloned());
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (Some(hex(6)), None, vec![]));
        assert!(!lost(&j, 9));
    }

    /// Decision 50 (it flipped decision 49's residue): a forged claim on a
    /// removed member that comes BEFORE the victim's member in member order,
    /// written AFTER the victim's claim, takes nothing: the victim's history
    /// stays the victim's on every phone, and the removed member does not
    /// merge into the victim's "you".
    #[test]
    fn d229_a_forged_claim_on_a_removed_earlier_member_takes_nothing() {
        let group = vec![
            create_rec(9, &[1, 5, 6], 1000, 1),
            claim_rec(9, 6, 20, 1100, 30),
            claim_rec(9, 5, 20, 1200, 7),
            remove_rec(9, 5, 1300, 7),
        ];
        let mut third = vec![you_rec(1, 900, 1)];
        third.extend(group.clone());
        assert_eq!(project(&third).aliases, vec![alias(9, 20, 6)]);
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group);
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (Some(hex(6)), None, vec![]));
        assert!(!lost(&j, 9));
        assert_eq!(j.other_you_ids, vec![hex(6)]);
    }

    /// Decision 50, the re-review's P11: the forger F never self-claims its
    /// own member 4 (before the victim's 6 in member order), writes
    /// `{4, you 20}` with J's id, and the admin removes F to lift the denial.
    /// J's history stays J's on every phone (the creator's, F's own, J's),
    /// nothing is credited to F, and F's member never merges into J. A claim
    /// BACKDATED before J's still gets past the rule: G-08 residue, pinned.
    #[test]
    fn d229_removing_a_forger_who_comes_first_credits_it_nothing() {
        let group = vec![
            create_rec(9, &[1, 4, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            claim_rec(9, 4, 20, 1200, 4),
        ];
        let mut creator = vec![you_rec(1, 900, 1)];
        creator.extend(group.clone());
        assert!(
            project(&creator).aliases.is_empty(),
            "denied while 4 is present"
        );
        let removal = remove_rec(9, 4, 2000, 1);
        creator.push(removal.clone());
        assert_eq!(project(&creator).aliases, vec![alias(9, 20, 6)]);
        let mut forger = vec![you_rec(4, 900, 4)];
        forger.extend(group.clone());
        forger.push(removal.clone());
        let f = project(&forger);
        assert_eq!(
            f.aliases,
            vec![alias(9, 20, 6)],
            "not credited to the forger"
        );
        assert!(f.other_you_ids.is_empty());
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group);
        joiner.push(removal.clone());
        let j = project(&joiner);
        assert_eq!(j.other_you_ids, vec![hex(6)], "4 never merges into J");
        assert_eq!(placed(&j, 9), (Some(hex(6)), None, vec![]));
        assert!(!lost(&j, 9));
        // G-08 residue: the same forged claim with a record identity BEFORE
        // J's claim reads J's history as 4 once 4 is removed.
        let backdated = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[1, 4, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            claim_rec(9, 4, 20, 1060, 4),
            removal,
        ]);
        assert_eq!(backdated.aliases, vec![alias(9, 20, 4)]);
    }

    /// Decision 50: two honest rejoins (6, removed; 7, removed; 8): both old
    /// claims were written before the present one, so history reads as the
    /// first member and the account writes as 8.
    #[test]
    fn d229_two_rejoins_stay_one_person() {
        let group = vec![
            create_rec(9, &[1, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            remove_rec(9, 6, 2000, 1),
            add_rec(9, 7, 2050, 1),
            claim_rec(9, 7, 20, 2100, 30),
            remove_rec(9, 7, 3000, 1),
            add_rec(9, 8, 3050, 1),
            claim_rec(9, 8, 20, 3100, 30),
        ];
        let mut creator = vec![you_rec(1, 900, 1)];
        creator.extend(group.clone());
        assert_eq!(project(&creator).aliases, vec![alias(9, 20, 6)]);
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(group);
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (Some(hex(8)), Some(hex(8)), vec![]));
        assert_eq!(j.other_you_ids, vec![hex(6), hex(7), hex(8)]);
        assert!(!lost(&j, 9));
    }

    /// Decision 50, the re-review's M1: a forged claim on a removed member
    /// AFTER the victim's, written after the victim's claim, no longer merges
    /// that member into the victim's "you".
    #[test]
    fn d229_a_forged_claim_on_a_later_removed_member_merges_nothing() {
        let j = project(&[
            you_rec(20, 800, 30),
            create_rec(9, &[1, 6, 8], 1000, 1),
            remove_rec(9, 8, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            claim_rec(9, 8, 20, 1200, 7),
        ]);
        assert_eq!(j.other_you_ids, vec![hex(6)]);
        assert_eq!(placed(&j, 9), (Some(hex(6)), None, vec![]));
    }

    /// Decision 50 applied to decision 47 (the re-review's M2): a claim on an
    /// already removed member contests a marked alias only when it was written
    /// before the alias; one written after it (which no removal could ever
    /// lift) changes nothing.
    #[test]
    fn d229_a_later_claim_on_a_removed_member_does_not_contest_an_alias() {
        let base = vec![
            you_rec(3, 900, 3),
            create_rec(9, &[2, 3, 4], 1000, 2),
            remove_rec(9, 4, 1100, 2),
            alias_rec(9, 2, 1, 2000, 9),
        ];
        let mut after = base.clone();
        after.push(claim_rec(9, 4, 1, 2100, 7));
        assert_eq!(project(&after).aliases, vec![alias(9, 1, 2)]);
        let mut before = base;
        before.push(claim_rec(9, 4, 1, 1500, 7));
        assert!(
            project(&before).aliases.is_empty(),
            "written before: contests"
        );
    }

    /// Decision 51 (the re-review's Q5 and Q2): once the victim has left, a
    /// later claim on an earlier member does not take the victim's history.
    /// The alias goes to the earliest-written claim, never by member order.
    #[test]
    fn d229_a_later_claimer_never_takes_a_departed_persons_history() {
        // Q5: J (20) joined as 6 and was removed; then F, never self-claimed
        // on its own present member 4, claims 4 with J's id.
        let q5 = vec![
            you_rec(1, 900, 1),
            create_rec(9, &[1, 4, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            remove_rec(9, 6, 2000, 1),
            claim_rec(9, 4, 20, 2500, 4),
        ];
        assert_eq!(project(&q5).aliases, vec![alias(9, 20, 6)]);
        let mut on_f = vec![you_rec(4, 900, 4)];
        on_f.extend(q5[1..].iter().cloned());
        let f = project(&on_f);
        assert_eq!(f.aliases, vec![alias(9, 20, 6)], "not credited to F");
        assert!(f.other_you_ids.is_empty());
        // Q2: P11 lifted by removing F's member, then J leaves too.
        let q2 = project(&[
            you_rec(1, 900, 1),
            create_rec(9, &[1, 4, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            claim_rec(9, 4, 20, 1200, 4),
            remove_rec(9, 4, 2000, 1),
            remove_rec(9, 6, 2500, 1),
        ]);
        assert_eq!(q2.aliases, vec![alias(9, 20, 6)]);
    }

    /// Decision 51 (the re-review's Q3): a claim forged in the gap between a
    /// removal and the rejoin, on a removed earlier placeholder, takes
    /// nothing, before or after the rejoin. Residue, pinned: on the rejoiner's
    /// own phone the gap claim's member still merges into "you" (core cannot
    /// tell it from an honest claim without its author; G-08).
    #[test]
    fn d229_a_claim_in_the_gap_before_a_rejoin_takes_no_history() {
        let gap = vec![
            create_rec(9, &[1, 5, 6], 1000, 1),
            claim_rec(9, 1, 1, 1050, 1),
            claim_rec(9, 6, 20, 1100, 30),
            remove_rec(9, 5, 1500, 1),
            remove_rec(9, 6, 2000, 1),
            claim_rec(9, 5, 20, 2500, 7),
        ];
        let mut third = vec![you_rec(1, 900, 1)];
        third.extend(gap.clone());
        assert_eq!(project(&third).aliases, vec![alias(9, 20, 6)], "in the gap");
        let rejoin = vec![add_rec(9, 7, 3000, 1), claim_rec(9, 7, 20, 3100, 30)];
        third.extend(rejoin.clone());
        assert_eq!(project(&third).aliases, vec![alias(9, 20, 6)], "after");
        let mut joiner = vec![you_rec(20, 800, 30)];
        joiner.extend(gap);
        joiner.extend(rejoin);
        let j = project(&joiner);
        assert_eq!(placed(&j, 9), (Some(hex(7)), Some(hex(7)), vec![]));
        assert_eq!(
            j.other_you_ids,
            vec![hex(5), hex(6), hex(7)],
            "residue: the gap claim's member merges on J's phone"
        );
    }

    fn permute(v: &mut Vec<usize>, k: usize, f: &mut dyn FnMut(&[usize])) {
        if k == v.len() {
            f(v);
            return;
        }
        for i in k..v.len() {
            v.swap(k, i);
            permute(v, k + 1, f);
            v.swap(k, i);
        }
    }

    /// D-237 (the Fable audit, A1): a place this account claimed reads as
    /// "you" in that group only. Mal shares group A with Carol (place 5) and
    /// Vic; Mal makes group B naming member 5 (Carol's A id, copied by a
    /// modified client) and invites Vic to it. Vic's claim of 5 in B used to
    /// put 5 into Vic's global "you" set, so every record of Carol's in A
    /// read as Vic's on Vic's phone (balances, the settle plan, who paid).
    #[test]
    fn d237_a_place_claimed_in_one_group_is_someone_else_in_another() {
        let p = project(&[
            you_rec(1, 900, 1),
            create_rec(0xa, &[5, 6], 1000, 7),
            claim_rec(0xa, 6, 1, 1100, 1),
            create_rec(0xb, &[8, 5], 2000, 8),
            claim_rec(0xb, 5, 1, 2100, 1),
        ]);
        assert_eq!(
            p.other_you_ids,
            vec![hex(5), hex(6)],
            "the shells still list both claimed places"
        );
        assert_eq!(p.aliases, vec![alias(0xa, 6, 1), alias(0xb, 5, 1)]);
        assert_eq!(placed(&p, 0xa).0, Some(hex(6)));
        assert_eq!(placed(&p, 0xb).0, Some(hex(5)));
        let ids = |g: u8| -> Vec<String> {
            p.groups
                .iter()
                .find(|x| x.id == hex(g))
                .expect("the group")
                .members
                .iter()
                .map(|m| m.id.clone())
                .collect()
        };
        assert_eq!(
            ids(0xa),
            vec![hex(5), hex(1)],
            "in A, 5 is Carol and 6 is you"
        );
        assert_eq!(ids(0xb), vec![hex(8), hex(1)], "in B, 5 is you");
        // What the shells' merges (ffi_merge_you_*) build from the projection.
        let aliases: Vec<(String, String, String)> = p
            .aliases
            .iter()
            .map(|a| (a.group.clone(), a.from.clone(), a.to.clone()))
            .collect();
        let yous =
            crate::straumr::identity::Yous::new(&hex(1), &p.other_you_ids).with_aliases(&aliases);
        assert_eq!(
            yous.id_in(Some(&hex(0xa)), &hex(5)),
            hex(5),
            "Carol's dinner in A stays Carol's"
        );
        assert_eq!(
            yous.id_in(Some(&hex(0xb)), &hex(5)),
            hex(1),
            "in B that id is Vic's place"
        );
        assert_eq!(yous.id_in(Some(&hex(0xa)), &hex(6)), hex(1));
        assert_eq!(
            yous.id_in(Some(&hex(0xb)), &hex(6)),
            hex(6),
            "Vic's A place is nobody's in B"
        );
        // Round 2 (review I1): Mal copies Carol's "you" id (0x1c, her claim's
        // `you` in A) instead of her place. Carol's alias in A (0x1c reads as
        // her place 5) must survive on Vic's phone, or her records there
        // would read as an id nobody has.
        let p = project(&[
            you_rec(1, 900, 1),
            create_rec(0xa, &[5, 6], 1000, 7),
            claim_rec(0xa, 5, 0x1c, 1050, 7),
            claim_rec(0xa, 6, 1, 1100, 1),
            create_rec(0xb, &[8, 0x1c], 2000, 8),
            claim_rec(0xb, 0x1c, 1, 2100, 1),
        ]);
        assert_eq!(
            p.aliases,
            vec![alias(0xa, 6, 1), alias(0xa, 0x1c, 5), alias(0xb, 0x1c, 1)]
        );
        let aliases: Vec<(String, String, String)> = p
            .aliases
            .iter()
            .map(|a| (a.group.clone(), a.from.clone(), a.to.clone()))
            .collect();
        let yous =
            crate::straumr::identity::Yous::new(&hex(1), &p.other_you_ids).with_aliases(&aliases);
        assert_eq!(
            yous.id_in(Some(&hex(0xa)), &hex(0x1c)),
            hex(5),
            "Carol's own id in A is her place"
        );
        assert_eq!(
            yous.id_in(Some(&hex(0xb)), &hex(0x1c)),
            hex(1),
            "in B it is Vic's place"
        );
        // Without B, nothing changes in A (the positive case D-154 relies on).
        let alone = project(&[
            you_rec(1, 900, 1),
            create_rec(0xa, &[5, 6], 1000, 7),
            claim_rec(0xa, 6, 1, 1100, 1),
        ]);
        assert_eq!(alone.other_you_ids, vec![hex(6)]);
        assert_eq!(alone.aliases, vec![alias(0xa, 6, 1)]);
    }

    // ---- D-244 (ADR-0043): settings follow the account ----

    fn setting(key: &str, value: &str) -> GroupOp {
        GroupOp::Setting {
            key: key.into(),
            value: value.into(),
        }
    }

    fn held_setting(key: &str, value: &str, id: VersionId) -> ProjectedSetting {
        ProjectedSetting {
            key: key.into(),
            value: value.into(),
            id,
        }
    }

    /// The wire shape, re-derived outside the code (Python's
    /// `json.dumps({"op":"setting","key":k,"value":v}, separators=(",",":"),
    /// ensure_ascii=False)`); it decodes back to the same operation, and a
    /// field this build does not know, or a missing one, is refused.
    #[test]
    fn a_setting_is_a_small_personal_op_with_a_pinned_shape() {
        for (key, value, wire) in [
            (
                "appearance.theme",
                "norse",
                r#"{"op":"setting","key":"appearance.theme","value":"norse"}"#,
            ),
            (
                "money.default-currency",
                "INR",
                r#"{"op":"setting","key":"money.default-currency","value":"INR"}"#,
            ),
            (
                "appearance.textSize",
                "-2",
                r#"{"op":"setting","key":"appearance.textSize","value":"-2"}"#,
            ),
            (
                "x.y",
                "Café \"x\"",
                r#"{"op":"setting","key":"x.y","value":"Café \"x\""}"#,
            ),
        ] {
            let op = setting(key, value);
            assert_eq!(encode(&op).unwrap(), wire.as_bytes(), "{key}");
            assert_eq!(decode(wire.as_bytes()).unwrap(), op, "{key}");
        }
        for bad in [
            r#"{"op":"setting","key":"appearance.theme","value":"norse","phone":"x"}"#,
            r#"{"op":"setting","key":"appearance.theme"}"#,
            r#"{"op":"setting","value":"norse"}"#,
            r#"{"op":"setting","key":"appearance.theme","value":3}"#,
        ] {
            assert!(
                matches!(decode(bad.as_bytes()), Err(GroupOpError::Unreadable(_))),
                "{bad}"
            );
        }
    }

    /// The key is 1 to 48 of `A-Z a-z 0-9 . -`; the value is trimmed, 1 to 64
    /// characters (not bytes), no control characters. Both ends of each bound.
    #[test]
    fn a_settings_key_and_value_are_bounded() {
        for key in [
            "a",
            "appearance.lessMotion",
            "money.some-other-key",
            "A-Z.a-z.0-9",
            &"k".repeat(48),
        ] {
            assert!(encode(&setting(key, "on")).is_ok(), "{key}");
        }
        for key in [
            "",
            &"k".repeat(49),
            "appearance theme",
            "appearance_theme",
            "appearance/theme",
            "appearance:theme",
            "thème",
            "a\n",
        ] {
            assert!(
                matches!(encode(&setting(key, "on")), Err(GroupOpError::Invalid(_))),
                "{key:?}"
            );
        }
        for value in ["x", "on off", &"v".repeat(64), &"é".repeat(64), "🦊"] {
            assert!(encode(&setting("k", value)).is_ok(), "{value:?}");
        }
        for value in [
            "",
            " ",
            " norse",
            "norse ",
            "nor\nse",
            "nor\tse",
            "nor\u{7f}se",
            "nor\u{85}se",
            &"v".repeat(65),
            &"é".repeat(65),
        ] {
            assert!(
                matches!(encode(&setting("k", value)), Err(GroupOpError::Invalid(_))),
                "{value:?}"
            );
        }
    }

    /// D-259: a per-phone key (the app lock's timeout, screen privacy) is
    /// refused at encode and at decode, in any ASCII case; a synced key, and
    /// a key that only contains the words elsewhere, is still accepted.
    #[test]
    fn a_per_phone_setting_key_is_refused() {
        for key in [
            "applock.timeout",
            "privacy.hide-when-captured",
            "applock.anything",
            "privacy.x",
            "AppLock.timeout",
            "PRIVACY.hide-when-captured",
        ] {
            assert!(
                matches!(
                    encode(&setting(key, "on")),
                    Err(GroupOpError::Invalid(m)) if m.contains("kept on each phone")
                ),
                "{key}"
            );
            let raw = format!(r#"{{"op":"setting","key":"{key}","value":"on"}}"#);
            assert!(
                matches!(decode(raw.as_bytes()), Err(GroupOpError::Invalid(_))),
                "{key}"
            );
        }
        for key in [
            "appearance.theme",
            "money.default-currency",
            "appearance.lessMotion",
            "applock",
            "privacy",
            "applocks.timeout",
            "x.applock.timeout",
            "my.privacy.x",
        ] {
            // A currency code is a valid value for every key.
            assert!(encode(&setting(key, "USD")).is_ok(), "{key}");
            let raw = format!(r#"{{"op":"setting","key":"{key}","value":"USD"}}"#);
            assert!(decode(raw.as_bytes()).is_ok(), "{key}");
        }
        assert_eq!(SETTING_PER_PHONE_PREFIXES, ["applock.", "privacy."]);
        assert_eq!(
            encode(&setting("applock.timeout", "off"))
                .unwrap_err()
                .to_string(),
            r#"invalid group operation: setting key "applock.timeout" is kept on each phone and never synced"#
        );
    }

    /// D-259: the projection lists a per-phone setting record as rejected,
    /// even as the latest write, and keeps the valid writes around it.
    #[test]
    fn a_per_phone_setting_record_lands_in_rejected() {
        let raw = |id: VersionId, json: &str| OpRecord {
            id,
            bytes: json.as_bytes().to_vec(),
        };
        let recs = vec![
            rec(vid(1000, 0, 1), &setting("appearance.theme", "norse")),
            raw(
                vid(2000, 0, 1),
                r#"{"op":"setting","key":"applock.timeout","value":"off"}"#,
            ),
            raw(
                vid(3000, 0, 2),
                r#"{"op":"setting","key":"privacy.hide-when-captured","value":"false"}"#,
            ),
            rec(vid(1500, 0, 2), &setting("money.default-currency", "GBP")),
        ];
        let p = project(&recs);
        assert_eq!(
            p.settings,
            vec![
                held_setting("appearance.theme", "norse", vid(1000, 0, 1)),
                held_setting("money.default-currency", "GBP", vid(1500, 0, 2)),
            ]
        );
        assert_eq!(
            p.rejected,
            vec![vid(2000, 0, 1).render(), vid(3000, 0, 2).render()]
        );
        assert!(p.pending.is_empty() && p.contested.is_empty());
    }

    /// The default currency must be a code core knows; the same value under
    /// any other key is only text.
    #[test]
    fn a_default_currency_setting_is_a_code_core_knows() {
        for code in ["INR", "USD", "EUR", "JPY", "BHD"] {
            assert!(
                encode(&setting(SETTING_KEY_DEFAULT_CURRENCY, code)).is_ok(),
                "{code}"
            );
        }
        for code in ["inr", "XYZ", "INRR", "IN", "US D", "1NR"] {
            assert!(
                matches!(
                    encode(&setting(SETTING_KEY_DEFAULT_CURRENCY, code)),
                    Err(GroupOpError::Invalid(_))
                ),
                "{code}"
            );
        }
        assert_eq!(SETTING_KEY_DEFAULT_CURRENCY, "money.default-currency");
        assert!(encode(&setting("money.other-currency", "XYZ")).is_ok());
        let raw = br#"{"op":"setting","key":"money.default-currency","value":"XYZ"}"#;
        assert!(matches!(decode(raw), Err(GroupOpError::Invalid(_))));
    }

    /// Each key's latest write wins, by record identity (millis, then counter,
    /// then device), in every arrival order; keys are independent of each
    /// other; a key no shell knows is listed like any other; the list is
    /// sorted by key and touches nothing else in the projection.
    #[test]
    fn the_latest_setting_per_key_wins_in_every_order() {
        let recs = vec![
            rec(vid(1000, 0, 1), &setting("appearance.theme", "norse")),
            rec(vid(3000, 0, 2), &setting("appearance.theme", "vardr")),
            rec(vid(3000, 1, 1), &setting("appearance.theme", "aurora")),
            rec(vid(2000, 0, 2), &setting("money.default-currency", "EUR")),
            rec(vid(2000, 0, 1), &setting("money.default-currency", "INR")),
            rec(vid(5000, 0, 1), &setting("future.key", "anything")),
            rec(vid(4000, 0, 2), &setting("appearance.mode", "light")),
        ];
        let expected = project(&recs);
        assert_eq!(
            expected.settings,
            vec![
                held_setting("appearance.mode", "light", vid(4000, 0, 2)),
                held_setting("appearance.theme", "aurora", vid(3000, 1, 1)),
                held_setting("future.key", "anything", vid(5000, 0, 1)),
                held_setting("money.default-currency", "EUR", vid(2000, 0, 2)),
            ]
        );
        assert!(expected.groups.is_empty() && expected.you.is_none());
        assert!(!expected.maximum_security && expected.avatar.is_none());
        assert!(expected.device_names.is_empty() && expected.keys.is_empty());
        assert!(expected.rejected.is_empty() && expected.pending.is_empty());
        let mut indices: Vec<usize> = (0..recs.len()).collect();
        let mut count = 0;
        permute(&mut indices, 0, &mut |order| {
            let shuffled: Vec<OpRecord> = order.iter().map(|&i| recs[i].clone()).collect();
            assert_eq!(project(&shuffled), expected, "order {order:?}");
            count += 1;
        });
        assert_eq!(count, 5040);
        assert!(project(&[]).settings.is_empty());

        // Keys are independent: a later write to one key moves only that key.
        let mut more = recs.clone();
        more.push(rec(vid(9000, 0, 2), &setting("appearance.mode", "dark")));
        let after = project(&more);
        assert_eq!(
            after.settings[0],
            held_setting("appearance.mode", "dark", vid(9000, 0, 2))
        );
        assert_eq!(after.settings[1..], expected.settings[1..]);
    }

    /// A setting that breaks a rule (written by a modified client, or a
    /// currency a later build knows) is reported as rejected, never applied,
    /// and never hides the valid writes around it, even when it is the latest.
    #[test]
    fn an_invalid_setting_record_lands_in_rejected() {
        let raw = |id: VersionId, json: &str| OpRecord {
            id,
            bytes: json.as_bytes().to_vec(),
        };
        let recs = vec![
            rec(vid(1000, 0, 1), &setting("appearance.theme", "norse")),
            raw(
                vid(2000, 0, 1),
                r#"{"op":"setting","key":"appearance.theme","value":" vardr"}"#,
            ),
            raw(
                vid(2100, 0, 1),
                r#"{"op":"setting","key":"appearance theme","value":"vardr"}"#,
            ),
            raw(
                vid(2200, 0, 1),
                r#"{"op":"setting","key":"money.default-currency","value":"XYZ"}"#,
            ),
            raw(
                vid(2300, 0, 1),
                r#"{"op":"setting","key":"appearance.theme","value":"vardr","x":1}"#,
            ),
            rec(vid(1500, 0, 2), &setting("money.default-currency", "GBP")),
        ];
        let p = project(&recs);
        assert_eq!(
            p.settings,
            vec![
                held_setting("appearance.theme", "norse", vid(1000, 0, 1)),
                held_setting("money.default-currency", "GBP", vid(1500, 0, 2)),
            ]
        );
        assert_eq!(
            p.rejected,
            vec![
                vid(2000, 0, 1).render(),
                vid(2100, 0, 1).render(),
                vid(2200, 0, 1).render(),
                vid(2300, 0, 1).render(),
            ]
        );
        assert!(p.pending.is_empty() && p.contested.is_empty());
    }
}
