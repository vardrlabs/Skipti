# C3 Roles, Removal and Deleting a Group Implementation Plan (D-210)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A shared group has one owner and admins; members ask to remove someone and an owner or admin removes them, settling or redistributing their balance to the minor unit; the owner can hand over or delete the group; a removed person and every member of a deleted group keep a read-only copy.

**Architecture:** The cloud decides who may do what: a pure module (`firebase/functions/groups.js`) holds every role decision, and new callables in `index.js` apply them inside Firestore transactions. A removal reaches the group's history only through `removeMember`, which writes the phone-built records with a field phones can never write (`removal: true`) and moves the uid to a `former` map; phones refuse removal and redistribution records without it. Core gains record type `0x06 Redistribution` (balances treat each share as a settlement), an equal split on hlutr's largest remainder, a per-group snapshot and a vault erase for one group. Both shells add a notices listener, role pills, the removal flow, group settings and the read-only copy, twinned.

**Tech Stack:** Rust (skipti-core, UniFFI), Firebase Functions (Node 22, plain JS, node:test against the emulators), Firestore and Storage rules, SwiftUI (iOS 17+), Jetpack Compose.

**Spec:** `docs/superpowers/specs/2026-10-13-feedback-round-design.md`, section "C3 Roles, removal and deleting a group" (approved 2026-10-13). Mock-up v2: https://claude.ai/artifact/GgYskSDAUMFMNvJoDGpM9x (source `.drafts/c3/c3-roles-mock.html`). Goldens: `.drafts/c3/golden.py` (independent of core).

## Global Constraints

- Money: integer minor units + ISO 4217 code, never a float (ADR-0002); every money string on screen comes from core's formatter.
- One owner. Only the owner makes an owner (the old owner becomes an admin). Owner or admin makes an admin; only the owner takes admin away. Only joined people (with an account) can be admins.
- Owner or admin removes members; nobody removes the owner; an admin never removes an admin (the owner can). A request naming the owner is refused; one naming an admin is approved only by the owner.
- No leaving. A member asks for their own removal ("Ask to be removed").
- A request is seen by the asker, the owner, the admins and the person named. One open request per person.
- Delete a group: owner only, allowed with open balances (the confirm names them); every member keeps a read-only copy.
- Redistribute: shares have the balance's sign, are nonzero, sum exactly to it; equal split = floor, leftover minor units one each by ascending participant id; zero shares dropped.
- `removal: true` on a cloud record is written only by Functions (the rules' `hasOnly` refuses it from phones); phones import a RemoveMember op or a Redistribution only when it carries it.
- skipti-core version 0.1.0 -> **0.2.0**.
- A person's sheet: Remove first; role actions below under "Role", each behind a confirm.
- Account controls absent, not disabled (D-126): Delete group is absent for non-owners; role rows absent for those who cannot use them.
- No new dependency. No em dashes in copy, comments or commit messages. Twins: same copy, order and behaviour.
- Commits: `[skip ci]`, explicit paths only, no attribution of any kind (CLAUDE.md §12). `git diff --cached --stat` before every commit.
- Never edit tracked files while a full gate runs; drafts in `.drafts/c3/`.
- Every Firestore/Storage rule change: a refusal test in `firebase/tests/` and a sabotage row.

## Cloud contract (every task reads this)

**`groups/{g}`** (Function-written fields; phones cannot write them: create `hasOnly` and update `changesOnly(['name','emoji'])` already refuse):
- `owner`: uid. Absent on groups made before D-210: `createdBy` stands in (`groups.ownerOf(group)`).
- `admins`: [uid].
- `former`: { uid: { at: Timestamp, by: uid } }.

**`groups/{g}/requests/{member}`** (Functions only; no client rule): `{ member, you: string|null, memberName, askerUid, askerName, at }`. `member` is the group member id (32 hex); `you` is the account participant id when the person joined.

**`users/{uid}/notices/{id}`** (written by Functions; the account reads and deletes): `{ kind, groupId, groupName, byName, memberId?, memberName?, role?, at, removedAt? }`, `kind` in `request | declined | removed | deleted | role`. A request notice's id is `request-{groupId}-{member}` on every recipient, so one delete per recipient clears it.

**Callables** (all `onCall(CALLABLE, ...)`; ids are 32 hex; names are `text(v, 80)`):

| Callable | Data | Who | Result |
|---|---|---|---|
| `groupRoles` | `{groupId}` | member | `{owner: you|null, admins: [you]}` |
| `setAdmin` | `{groupId, you, on, byName, groupName}` | on: owner or admin; off: owner | `{admins: [you]}` |
| `makeOwner` | `{groupId, you, byName, groupName}` | owner | `{owner: you}` |
| `requestRemoval` | `{groupId, member, you?, memberName, groupName, askerName}` | member | `{requested: true}` |
| `declineRemoval` | `{groupId, member, byName, groupName}` | owner or admin (owner if the person is an admin) | `{declined: true}` |
| `removeMember` | `{groupId, member, you?, memberName, groupName, byName, records}` | owner or admin (never the owner; admins not admins) | `{removed: true}` |
| `deleteGroup` | `{groupId, groupName, byName}` | owner | `{deleted: true}` |

`you` -> uid: `users where participantId == you` (exactly one), and that uid must be in `memberUids` (as `inviteKnown`). `records` (removeMember): 1 to 11 items, each `{id, type, hlc: {millis, counter, device}, deviceId, payload: {version, epoch, flags, nonce, blob}}`, `nonce` and `blob` base64; exactly one `type: "3"` whose blob JSON is `{"op":"removeMember","group":groupId,"member":member}`; zero to ten `type: "6"` whose blob JSON has `group == groupId` and `from == member`; `id == "${millis}-${counter}-${device}"`.

**Push** (`groups.noticePush`): `{ notification: {title: groupName or 'SKIPTI', body}, data: {kind: 'group', groupId}, android: {priority: 'high', notification: {channelId: 'group-activity', tag: groupId}} }`.

**Rules** (Task 4): a `former` uid may `get` the group document and `get, list` records with `createdAt <= former[uid].at`; `users/{uid}/notices` `get, list, delete` by the account; a phone can never write `removal`.

## Copy (twins; exact strings)

| Key | Text |
|---|---|
| owner pill / admin pill | `Owner` / `Admin` |
| sheet: remove | `Remove from {group}` |
| sheet: ask | `Ask to remove {name}` |
| own row | `Ask to be removed` |
| sheet section | `Role` |
| make admin row | `Make admin` / sub `{name} can add and remove members, and name admins` |
| remove admin row | `Remove admin` / sub `{name} stays as a member` |
| make owner row | `Make owner` / sub `There is one owner; you become an admin` |
| ask note | `Only the owner or an admin can remove someone. {name}, the owner and the admins will see your request.` |
| asked | `Request sent` |
| make admin dialog | `Make {name} an admin?` / `{name} will be able to add people, remove members, approve requests and name other admins. Only the owner can take it away.` / `Make {name} admin` / `Cancel` |
| remove admin dialog | `Remove {name} as admin?` / `{name} stays in the group as a member.` / `Remove admin` / `Cancel` |
| make owner dialog | `Make {name} the owner?` / `{name} will own {group}: names admins, removes anyone, can delete the group. You become an admin. Only {name} can undo this.` / `Make {name} owner` / `Cancel` |
| card, admins | `{asker} asked to remove {name}` / buttons `Decline`, `Review` |
| card, asker | `You asked to remove {name}` / `The owner or an admin will decide.` |
| card, person | `{asker} asked to remove you` / `The owner or an admin will decide.` / button `Settle up` (only when your balance here is not zero) |
| review sheet | `Remove {name} from {group}?` / `{asker} asked. {name} will get a notification and keeps a read-only copy of the group's history.` / `Continue to remove` / `Decline request` |
| plain remove | `Remove {name} from {group}?` / `{name} will get a notification and keeps a read-only copy of the group's history.` / `Remove` / `Cancel` (not joined: D-186's body `They are in no expense or payment here. You can add them again later.`) |
| balance screen | title `Remove {name}`; per currency `{name} owes {amt}` or `{name} is owed {amt}`; lede `Before {name} goes, decide what happens to it.`; `Settle first` / `Record {name}'s payments, then remove {name}.`; `Redistribute` / `Move {name}'s balance to people you choose. Their balances change; {name}'s goes to zero.`; `Next` |
| settle first confirm | `Record these payments?` / lines `{a} paid {b} {amt}` / `Record and remove {name}` / `Cancel` |
| redistribute | title `Redistribute`; lede `{name}'s balance goes to the people you tick. Their new balances:`; `Edit amounts` / `Split equally`; tally `Split equally`, `Adds up`, `{amt} left to assign`, `{amt} too much`, `Type an amount for each ticked person`, `Tick at least one person`; button `Remove {name}`; fine `Recorded in {group}'s history for everyone.` |
| history line | `{from}'s {amt} moved to {names}` (names: `A`, `A and B`, `A, B and C`) |
| group settings | title `Group settings`; rows `Name`, `Picture`, `Settles in`; `Delete group` / sub `Only you, the owner, can do this` |
| delete dialog (cloud) | `Delete {group} for everyone?` / `It's erased from SKIPTI's cloud and nobody can add to it again. Everyone keeps a read-only copy on their phone.` / `Still open` + `{name} owes {amt}`, `{name} is owed {amt}`, joined with ` · ` / `Delete for everyone` / `Cancel` |
| delete dialog (local) | `Delete {group}?` / `It's removed from this phone.` / `Delete` / `Cancel` |
| read-only banner | removed: `{by} removed you on {date}` / `This is your copy of {group}'s history. Nothing new reaches it, and nothing can be added.`; deleted: `{by} deleted this group on {date}` / `This is your copy. Nothing new can be added.` |
| read-only actions | `Export this group` / `Remove from this phone` |
| forget dialog | `Remove {group} from this phone?` / `Its history is erased from this phone only. Export it first if you want to keep it.` / `Remove from this phone` / `Cancel` |
| failures | PERMISSION_DENIED `Only the owner or an admin can do that.`; FAILED_PRECONDITION `That changed on another phone. Try again.`; NOT_FOUND `This group no longer exists.`; other: the existing offline copy |
| notice bodies (cloud) | request to owner/admins `{asker} asked to remove {name}`; to the person `{asker} asked to remove you`; declined to asker `{by} kept {name} in {group}`; to the person `{by} kept you in {group}`; removed `{by} removed you from {group}`; role admin `{by} made you an admin of {group}`; owner `{by} made you the owner of {group}`; deleted `{by} deleted {group}` |

## File map

| File | Responsibility |
|---|---|
| `core/src/norn/redistribution.rs` (create) | `Redistribution`, codec, `expand`, `equal`, `check` |
| `core/src/norn/mod.rs` (modify) | `pub mod redistribution;` |
| `core/src/straumr/mod.rs` (modify) | `RecordType::Redistribution = 0x06` |
| `core/src/ffi.rs` (modify) | `RedistributionInput`, `RedistributionTally`, FFI exports; balances/balance_line/history take redistributions; `ffi_snapshot_for_group`; `Store::forget_group` |
| `core/src/skrin/mod.rs` (modify) | `delete_group_records` |
| `core/src/flutningr/{mod,jsonio,ledger_csv}.rs` (modify) | `PortRedistribution`, schema v3, CSV rows |
| `core/Cargo.toml`, `Cargo.lock`, `core/skipti-core.cdx.json` | 0.2.0 |
| `ios/Skipti/Bindings/*`, `android/app/src/main/kotlin/uniffi/skipti_core/*` | regenerated |
| `firebase/functions/groups.js` (create) | pure role decisions, record checks, notices, pushes, hand-over |
| `firebase/functions/account.js`, `account-store.js` (modify) | `groupsPlan` hand-over; picture delete |
| `firebase/functions/index.js` (modify) | 7 callables; `acceptInvite` clears `former` |
| `firebase/firestore.rules` (modify) | former reads, notices |
| `firebase/tests/groups.test.mjs` (create), `groups-functions.test.mjs` (create), `rules.test.mjs`, `account.test.mjs` (modify) | tests |
| `ios/Skipti/App/Roles.swift` (create) | `RoleRules` (pure), `RolesCopy`, `GroupRoles` (cache + calls), `Notices` listener |
| `ios/Skipti/App/RemovalFlow.swift` (create) | the removal screens and `Removal` (records, call, local apply) |
| `ios/Skipti/App/GroupSettings.swift` (create) | group settings, delete, read-only banner, forget |
| `ios/Skipti/App/{CloudSync,LedgerStore,GroupStore,GroupPicker,OverviewScreen,SettleAll,BalanceScreen,SettleScreen,ExportFlow,Push,SkiptiApp}.swift` (modify) | wiring |
| `ios/Skipti.xcodeproj/project.pbxproj` | 3 app files + 1 test file |
| `ios/SkiptiTests/RolesTests.swift` (create) | twins |
| `android/.../Roles.kt`, `RemovalFlow.kt`, `GroupSettings.kt` (create) | twins |
| `android/.../{CloudSync,LedgerStore,GroupScreens,OverviewTab,SettleAll,SettleScreen,BalanceScreen,Exporter,Push,AppRoot}.kt` (modify) | wiring |
| `android/app/src/test/.../RolesTest.kt` (create) | twins |

---

### Task 1: Core: the Redistribution record and balances (Rust only)

**Files:**
- Create: `core/src/norn/redistribution.rs`
- Modify: `core/src/norn/mod.rs` (add `pub mod redistribution;`), `core/src/straumr/mod.rs:47-80`, `core/src/ffi.rs` (structs near :258, `build_ledger` :1109, `balances_compute` :1180, `balance_line` :737, `ffi_member_has_history` :1674, `ffi_balance_line` :1692, `ffi_balances_compute` :1469)
- Test: in `core/src/norn/redistribution.rs` (`#[cfg(test)] mod tests`) and `core/src/ffi.rs` `mod tests`

**Interfaces:**
- Produces (Rust, used by Task 2 and the shells through bindings):
  - `RecordType::Redistribution` (0x06)
  - `#[derive(uniffi::Record)] pub struct RedistributionInput { pub group: String, pub from: String, pub currency_code: String, pub shares: Vec<IdAmount> }`
  - `#[derive(uniffi::Record)] pub struct RedistributionTally { pub assigned_minor: i64, pub left_minor: i64, pub ok: bool }`
  - `ffi_redistribute_equal(balance_minor: i64, members: Vec<String>) -> Result<Vec<IdAmount>, CoreError>`
  - `ffi_redistribution_tally(balance_minor: i64, from: String, members: Vec<String>, shares: Vec<IdAmount>) -> RedistributionTally`
  - `ffi_encode_redistribution(r: RedistributionInput) -> Result<Vec<u8>, CoreError>` / `ffi_decode_redistribution(blob: Vec<u8>) -> Result<RedistributionInput, CoreError>`
  - `ffi_balances_compute(expenses, settlements, redistributions: Vec<RedistributionInput>)`, `ffi_balance_line(expenses, settlements, redistributions, viewer, group)`, `ffi_member_has_history(expenses, settlements, redistributions, group, member_id)` (the new parameter is third in all three)

- [ ] **Step 1: Record type.** In `core/src/straumr/mod.rs` add the variant after `ExpenseRevision` with the doc comment `/// D-210: a removed person's balance moved onto chosen members (norn::redistribution). Its own type: an older core skips it rather than misreading it as a settlement.`, `RecordType::Redistribution => 0x06` in `as_u8` and `0x06 => Some(RecordType::Redistribution)` in `from_u8`. Add to the existing round-trip test in that file (grep `from_u8(` in its tests) `assert_eq!(RecordType::from_u8(0x06), Some(RecordType::Redistribution)); assert_eq!(RecordType::from_u8(0x07), None);`.

- [ ] **Step 2: Write the failing tests** at the end of the new `core/src/norn/redistribution.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn p(n: u8) -> ParticipantId {
        let mut b = [0u8; 16];
        b[15] = n;
        b
    }

    // Goldens re-derived by .drafts/c3/golden.py (not by this code):
    // ids p(1) < p(2) < p(3) < p(4) < p(5) stand for prithvi, rahul, asha, sam, nina.
    #[test]
    fn equal_split_matches_the_python_goldens() {
        assert_eq!(
            equal(-100000, &[p(3), p(1), p(2)]).unwrap(),
            vec![(p(1), -33334), (p(2), -33333), (p(3), -33333)]
        );
        assert_eq!(equal(-100000, &[p(1), p(2)]).unwrap(), vec![(p(1), -50000), (p(2), -50000)]);
        assert_eq!(
            equal(1000, &[p(1), p(2), p(3)]).unwrap(),
            vec![(p(1), 334), (p(2), 333), (p(3), 333)]
        );
        // One minor unit over three: the zero shares are dropped.
        assert_eq!(equal(-1, &[p(3), p(2), p(1)]).unwrap(), vec![(p(1), -1)]);
        assert_eq!(equal(-500, &[p(5)]).unwrap(), vec![(p(5), -500)]);
    }

    #[test]
    fn equal_refuses_nothing_to_move_or_nobody_to_take_it() {
        assert!(equal(0, &[p(1)]).is_err());
        assert!(equal(-100, &[]).is_err());
        assert!(equal(-100, &[p(1), p(1)]).is_err());
    }

    #[test]
    fn check_holds_every_rule() {
        let from = p(4);
        let ok = vec![(p(1), -50000), (p(2), -30000), (p(3), -20000)];
        assert_eq!(check(-100000, from, &ok), Ok(()));
        // Sum short, sum over, wrong sign, zero, from itself, duplicate, empty.
        assert!(check(-100000, from, &[(p(1), -50000), (p(2), -30000)]).is_err());
        assert!(check(-100000, from, &[(p(1), -60000), (p(2), -50000)]).is_err());
        assert!(check(-100000, from, &[(p(1), -150000), (p(2), 50000)]).is_err());
        assert!(check(-100000, from, &[(p(1), -100000), (p(2), 0)]).is_err());
        assert!(check(-100000, from, &[(p(4), -100000)]).is_err());
        assert!(check(-100000, from, &[(p(1), -50000), (p(1), -50000)]).is_err());
        assert!(check(-100000, from, &[]).is_err());
        assert!(check(0, from, &[(p(1), 0)]).is_err());
    }

    #[test]
    fn applying_it_takes_from_to_zero_and_keeps_the_total() {
        // The mock's group, from the golden: after = 36666, 16667, -53333, 0, 0.
        let mut net = std::collections::BTreeMap::from([
            (p(1), 70000i128), (p(2), 50000), (p(3), -20000), (p(4), -100000), (p(5), 0),
        ]);
        let shares = equal(-100000, &[p(1), p(2), p(3)]).unwrap();
        for (payer, payee, amount) in settlements(p(4), &shares) {
            *net.get_mut(&payer).unwrap() += amount;
            *net.get_mut(&payee).unwrap() -= amount;
        }
        assert_eq!(net.values().copied().collect::<Vec<_>>(), vec![36666, 16667, -53333, 0, 0]);
    }

    #[test]
    fn the_codec_round_trips_and_refuses_strangers() {
        let r = Redistribution {
            group: "c".repeat(32),
            from: "d".repeat(32),
            currency: "INR".into(),
            shares: vec![Share { member: "a".repeat(32), amount: -33334 }],
        };
        let bytes = encode(&r).unwrap();
        assert_eq!(
            String::from_utf8(bytes.clone()).unwrap(),
            format!(
                "{{\"group\":\"{}\",\"from\":\"{}\",\"currency\":\"INR\",\"shares\":[{{\"member\":\"{}\",\"amount\":-33334}}]}}",
                "c".repeat(32), "d".repeat(32), "a".repeat(32)
            )
        );
        assert_eq!(decode(&bytes).unwrap(), r);
        assert!(decode(br#"{"group":"x","from":"y","currency":"INR","shares":[],"extra":1}"#).is_err());
    }
}
```

- [ ] **Step 3: Run to see them fail.** `cargo test -p skipti-core --lib norn::redistribution` -> does not compile (module missing). Then add `pub mod redistribution;` to `core/src/norn/mod.rs`.

- [ ] **Step 4: Implement** above the tests in `core/src/norn/redistribution.rs`:

```rust
//! D-210: a removed person's balance moved onto members the remover chose.
//!
//! `from` has a nonzero balance B in one currency (positive: owed to them).
//! Each share has B's sign, is nonzero, and the shares sum exactly to B:
//! each member's balance moves by their share and `from`'s by -B, so `from`
//! ends at zero and the group's total stays zero. Balances apply a share as
//! a settlement (`settlements`), so every view that already reads
//! settlements (net, pairwise, simplify) agrees with no new arithmetic.

use crate::hlutr::{distribute_weighted, ParticipantId};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Share {
    pub member: String,
    pub amount: i64,
}

/// The record's payload (type 0x06), JSON like group operations.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Redistribution {
    pub group: String,
    pub from: String,
    pub currency: String,
    pub shares: Vec<Share>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RedistributionError {
    Invalid(&'static str),
}

pub fn encode(r: &Redistribution) -> Result<Vec<u8>, RedistributionError> {
    serde_json::to_vec(r).map_err(|_| RedistributionError::Invalid("unencodable"))
}

pub fn decode(bytes: &[u8]) -> Result<Redistribution, RedistributionError> {
    serde_json::from_slice(bytes).map_err(|_| RedistributionError::Invalid("unreadable"))
}

/// Equal shares of `balance` over `members`: floor each, the leftover minor
/// units one each in ascending participant-id order (hlutr's largest
/// remainder with equal weights), `balance`'s sign applied, zeros dropped.
/// Sorted by id.
pub fn equal(
    balance: i128,
    members: &[ParticipantId],
) -> Result<Vec<(ParticipantId, i128)>, RedistributionError> {
    if balance == 0 {
        return Err(RedistributionError::Invalid("nothing to move"));
    }
    let mut sorted = members.to_vec();
    sorted.sort_unstable();
    if sorted.is_empty() || sorted.windows(2).any(|w| w[0] == w[1]) {
        return Err(RedistributionError::Invalid("members"));
    }
    let weights: Vec<(ParticipantId, i128)> = sorted.iter().map(|m| (*m, 1)).collect();
    let parts = distribute_weighted(balance.abs(), &weights)
        .map_err(|_| RedistributionError::Invalid("split"))?;
    Ok(parts
        .into_iter()
        .filter(|(_, v)| *v != 0)
        .map(|(m, v)| (m, if balance < 0 { -v } else { v }))
        .collect())
}

/// Every rule a set of shares must keep (typed amounts are checked here).
pub fn check(
    balance: i128,
    from: ParticipantId,
    shares: &[(ParticipantId, i128)],
) -> Result<(), RedistributionError> {
    if balance == 0 || shares.is_empty() {
        return Err(RedistributionError::Invalid("nothing to move"));
    }
    let mut seen = std::collections::BTreeSet::new();
    let mut sum: i128 = 0;
    for (m, v) in shares {
        if *m == from || !seen.insert(*m) {
            return Err(RedistributionError::Invalid("member"));
        }
        if *v == 0 || (*v < 0) != (balance < 0) {
            return Err(RedistributionError::Invalid("sign"));
        }
        sum = sum.checked_add(*v).ok_or(RedistributionError::Invalid("overflow"))?;
    }
    if sum != balance {
        return Err(RedistributionError::Invalid("sum"));
    }
    Ok(())
}

/// The settlements a redistribution is applied as, `(payer, payee, amount > 0)`
/// in norn's sense (`net[payer] += amount; net[payee] -= amount`). A share
/// s < 0 (from owed money): `from` pays the member |s|. s > 0: the member
/// pays `from` s.
pub fn settlements(
    from: ParticipantId,
    shares: &[(ParticipantId, i128)],
) -> Vec<(ParticipantId, ParticipantId, i128)> {
    shares
        .iter()
        .map(|(m, s)| if *s < 0 { (from, *m, -*s) } else { (*m, from, *s) })
        .collect()
}
```

- [ ] **Step 5: Run.** `cargo test -p skipti-core --lib norn::redistribution` -> 5 passed.

- [ ] **Step 6: FFI, failing tests first.** In `core/src/ffi.rs` `mod tests`, add (uses the existing `pid`, `inr`-style helpers; write `ExpenseInput`s literally):

```rust
    // ---- D-210: redistribution across the boundary ----

    fn grp() -> String { "c".repeat(32) }

    fn spent(payer: u8, minor: i64, shares: &[(u8, i64)]) -> ExpenseInput {
        ExpenseInput {
            payer: pid(payer),
            paid_minor: minor,
            currency_code: "INR".into(),
            shares: shares.iter().map(|(p, a)| IdAmount { id: pid(*p), amount_minor: *a }).collect(),
            group: Some(grp()),
            conversion: None,
        }
    }

    #[test]
    fn a_redistribution_moves_the_balance_and_every_view_agrees() {
        // Sam (4) owes 1000.00: Prithvi (1) paid 1000.00 for Sam alone.
        let expenses = vec![spent(1, 100000, &[(4, 100000)])];
        let shares = ffi_redistribute_equal(-100000, vec![pid(3), pid(1), pid(2)]).unwrap();
        assert_eq!(
            shares,
            vec![
                IdAmount { id: pid(1), amount_minor: -33334 },
                IdAmount { id: pid(2), amount_minor: -33333 },
                IdAmount { id: pid(3), amount_minor: -33333 },
            ]
        );
        let r = RedistributionInput { group: grp(), from: pid(4), currency_code: "INR".into(), shares };
        let lines = ffi_balance_line(expenses.clone(), vec![], vec![r.clone()], pid(4), Some(grp())).unwrap();
        assert_eq!(lines.iter().map(|l| l.viewer_net_minor).sum::<i64>(), 0, "Sam ends at zero");
        let all = ffi_balances_compute(expenses.clone(), vec![], vec![r.clone()]).unwrap();
        let net = |id: String| all[0].entries.iter().find(|e| e.id == id).map(|e| e.minor_units).unwrap_or(0);
        assert_eq!((net(pid(1)), net(pid(2)), net(pid(3)), net(pid(4))), (66666, -33333, -33333, 0));
        assert!(ffi_member_has_history(vec![], vec![], vec![r.clone()], grp(), pid(3)));
        assert!(ffi_member_has_history(vec![], vec![], vec![r], grp(), pid(4)));
        assert!(!ffi_member_has_history(vec![], vec![], vec![], grp(), pid(4)));
    }

    #[test]
    fn a_redistribution_in_another_group_does_not_touch_this_one() {
        let r = RedistributionInput {
            group: "e".repeat(32), from: pid(4), currency_code: "INR".into(),
            shares: vec![IdAmount { id: pid(1), amount_minor: -500 }],
        };
        let lines = ffi_balance_line(vec![], vec![], vec![r], pid(1), Some(grp())).unwrap();
        assert!(lines.is_empty());
    }

    #[test]
    fn the_tally_says_what_is_left_and_when_it_adds_up() {
        let s = |v: &[(u8, i64)]| v.iter().map(|(p, a)| IdAmount { id: pid(*p), amount_minor: *a }).collect::<Vec<_>>();
        let members = vec![pid(1), pid(2), pid(3)];
        assert_eq!(
            ffi_redistribution_tally(-100000, pid(4), members.clone(), s(&[(1, -50000), (2, -30000), (3, -20000)])),
            RedistributionTally { assigned_minor: -100000, left_minor: 0, ok: true }
        );
        assert_eq!(
            ffi_redistribution_tally(-100000, pid(4), members.clone(), s(&[(1, -50000), (2, -45000)])),
            RedistributionTally { assigned_minor: -95000, left_minor: -5000, ok: false }
        );
        // Someone not offered (5) or the person themselves (4): never ok.
        assert!(!ffi_redistribution_tally(-500, pid(4), members.clone(), s(&[(5, -500)])).ok);
        assert!(!ffi_redistribution_tally(-500, pid(4), members, s(&[(4, -500)])).ok);
    }

    #[test]
    fn the_redistribution_bytes_cross_the_boundary() {
        let r = RedistributionInput {
            group: grp(), from: pid(4), currency_code: "INR".into(),
            shares: vec![IdAmount { id: pid(1), amount_minor: -1 }],
        };
        assert_eq!(ffi_decode_redistribution(ffi_encode_redistribution(r.clone()).unwrap()).unwrap(), r);
        assert!(ffi_encode_redistribution(RedistributionInput { shares: vec![], ..r.clone() }).is_err());
        assert!(ffi_decode_redistribution(b"{}".to_vec()).is_err());
    }
```

The golden for `(66666, -33333, -33333, 0)`: Prithvi paid 100000 for Sam (net +100000, Sam -100000), then the shares move -33334/-33333/-33333 onto 1, 2, 3: 100000 - 33334 = 66666. (Re-derived by hand from `.drafts/c3/golden.py`'s `apply`; Rahul and Asha start at 0 here.)

- [ ] **Step 7: Implement the FFI.** Near `SettlementInput` (:258):

```rust
/// D-210: a removed person's balance moved onto chosen members (norn::redistribution).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RedistributionInput {
    pub group: String,
    pub from: String,
    pub currency_code: String,
    pub shares: Vec<IdAmount>,
}

/// D-210: typed shares against the balance they must add up to.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RedistributionTally {
    pub assigned_minor: i64,
    pub left_minor: i64,
    pub ok: bool,
}
```

Then the conversions and exports (place after `ffi_member_has_history`):

```rust
fn redistribution_shares(r: &RedistributionInput) -> Result<Vec<(ParticipantId, i128)>, CoreError> {
    r.shares.iter().map(|s| Ok((parse_participant(&s.id)?, s.amount_minor as i128))).collect()
}

/// Each redistribution as the settlements balances apply (norn::redistribution).
fn redistribution_settlements(rs: &[RedistributionInput]) -> Result<Vec<SettlementInput>, CoreError> {
    let mut out = Vec::new();
    for r in rs {
        let from = parse_participant(&r.from)?;
        for (payer, payee, amount) in crate::norn::redistribution::settlements(from, &redistribution_shares(r)?) {
            out.push(SettlementInput {
                from: hex_encode(&payer),
                to: hex_encode(&payee),
                amount_minor: narrow(amount)?,
                currency_code: r.currency_code.clone(),
                group: Some(r.group.clone()),
            });
        }
    }
    Ok(out)
}

#[uniffi::export]
pub fn ffi_redistribute_equal(balance_minor: i64, members: Vec<String>) -> Result<Vec<IdAmount>, CoreError> {
    let ids = members.iter().map(|m| parse_participant(m)).collect::<Result<Vec<_>, _>>()?;
    let parts = crate::norn::redistribution::equal(balance_minor as i128, &ids)
        .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    parts.into_iter().map(|(m, v)| Ok(IdAmount { id: hex_encode(&m), amount_minor: narrow(v)? })).collect()
}

#[uniffi::export]
pub fn ffi_redistribution_tally(
    balance_minor: i64,
    from: String,
    members: Vec<String>,
    shares: Vec<IdAmount>,
) -> RedistributionTally {
    let assigned: i128 = shares.iter().map(|s| s.amount_minor as i128).sum();
    let left = balance_minor as i128 - assigned;
    let offered = shares.iter().all(|s| members.contains(&s.id));
    let ok = offered
        && match (
            parse_participant(&from),
            shares.iter().map(|s| Ok((parse_participant(&s.id)?, s.amount_minor as i128))).collect::<Result<Vec<_>, CoreError>>(),
        ) {
            (Ok(f), Ok(v)) => crate::norn::redistribution::check(balance_minor as i128, f, &v).is_ok(),
            _ => false,
        };
    RedistributionTally {
        assigned_minor: narrow(assigned).unwrap_or(i64::MAX),
        left_minor: narrow(left).unwrap_or(i64::MAX),
        ok,
    }
}

#[uniffi::export]
pub fn ffi_encode_redistribution(r: RedistributionInput) -> Result<Vec<u8>, CoreError> {
    parse_participant(&r.group)?;
    let from = parse_participant(&r.from)?;
    Currency::parse(&r.currency_code)?;
    let shares = redistribution_shares(&r)?;
    let balance: i128 = shares.iter().map(|(_, v)| v).sum();
    crate::norn::redistribution::check(balance, from, &shares)
        .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    crate::norn::redistribution::encode(&crate::norn::redistribution::Redistribution {
        group: r.group,
        from: r.from,
        currency: r.currency_code,
        shares: r.shares.into_iter().map(|s| crate::norn::redistribution::Share { member: s.id, amount: s.amount_minor }).collect(),
    })
    .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))
}

#[uniffi::export]
pub fn ffi_decode_redistribution(blob: Vec<u8>) -> Result<RedistributionInput, CoreError> {
    let r = crate::norn::redistribution::decode(&blob).map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    let out = RedistributionInput {
        group: r.group,
        from: r.from,
        currency_code: r.currency,
        shares: r.shares.into_iter().map(|s| IdAmount { id: s.member, amount_minor: s.amount }).collect(),
    };
    parse_participant(&out.group)?;
    let from = parse_participant(&out.from)?;
    let shares = redistribution_shares(&out)?;
    let balance: i128 = shares.iter().map(|(_, v)| v).sum();
    crate::norn::redistribution::check(balance, from, &shares).map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    Ok(out)
}
```

Then thread `redistributions` through: `balances_compute(expenses, settlements, redistributions: &[RedistributionInput])` and `balance_line(expenses, settlements, redistributions, viewer, group)` each begin with `let mut settlements = settlements.to_vec(); settlements.extend(redistribution_settlements(redistributions)?);` and use that list (balance_line filters by group after extending, so a redistribution in another group is filtered with its settlements). `ffi_member_has_history` gains `redistributions: Vec<RedistributionInput>` third and ORs `|| redistributions.iter().filter(|r| r.group == group).any(|r| r.from == member_id || r.shares.iter().any(|s| s.id == member_id))`. Update the three `#[uniffi::export]` wrappers' signatures (third parameter) and every existing caller in `ffi.rs` tests (`cargo test` names them: pass `vec![]`). If `hex_encode` or `narrow` or `Currency` are not in scope at that point of `ffi.rs`, they are elsewhere in the file (`fn narrow` :306): use them as the file does.

- [ ] **Step 8: Run.** `cargo test -p skipti-core --lib` -> all pass (the count rises by 5 + 4 + the straumr assertion). `cargo clippy -p skipti-core --all-targets -- -D warnings` clean. `grep -rnE '\bf(32|64)\b' core/src/` prints nothing.

- [ ] **Step 9: Commit** (do not regenerate bindings yet; Task 2 does it once):

```bash
git add core/src/norn/redistribution.rs core/src/norn/mod.rs core/src/straumr/mod.rs core/src/ffi.rs
git diff --cached --stat
git commit -m "D-210: core: the Redistribution record (0x06), equal split, tally, and balances that apply it [skip ci]"
```

### Task 2: Core: export, one group's snapshot, forgetting a group, version 0.2.0, bindings

**Files:**
- Modify: `core/src/flutningr/mod.rs` (PortRedistribution, LedgerSnapshot :200, SCHEMA_VERSION :81, validate :490, canonical sort :593), `core/src/flutningr/jsonio.rs` (Envelope :30, upgrade_step :110), `core/src/flutningr/ledger_csv.rs` (rows after settlements :54), `core/src/skrin/mod.rs` (after `clear_records` :230), `core/src/ffi.rs` (`Store` methods near :2935, `ffi_snapshot_for_group`), `core/Cargo.toml`, `Cargo.lock`, `core/skipti-core.cdx.json`, bindings.

**Interfaces:**
- Consumes: Task 1's `RedistributionInput`.
- Produces: `PortRedistribution { from: String, currency_code: String, group: String, shares: Vec<ExpenseShare> }` (reuse `ExpenseShare {id, amount_minor}`); `LedgerSnapshot.redistributions: Vec<PortRedistribution>` (`#[serde(default)]`); `ffi_snapshot_for_group(snapshot: LedgerSnapshot, group: String) -> LedgerSnapshot`; `Store::forget_group(group: String) -> Result<(), StoreError>` (the existing `Store` object, beside `clear_records`); `skipti_core_version()` returns `"0.2.0"`.

- [ ] **Step 1: Failing tests.**
  - `flutningr` (mod.rs tests): a snapshot with one `PortRedistribution` (from `pid` 4, shares `[(1,-33334),(2,-33333),(3,-33333)]`, INR, group `c*32`) round-trips through `jsonio::export_json` / `import`; its JSON contains `"schema_version":3` and `"redistributions":[`; validation refuses shares that do not sum to a nonzero total of one sign, an empty `shares`, and `from` among the shares (reuse the validation error type the settlements check uses).
  - The FIXTURE_V1 test and any v2 fixture still import (upgrade steps 1 -> 2 -> 3); a v2 document gains `redistributions: []`.
  - `ledger_csv`: one row per share, `record_type` `redistribution`, `payer_or_from` = from, `to_or_empty` = the member, `amount` = the share's magnitude formatted as the settlement rows are, `group_or_empty` = the group. Pin the exact line for share `(1, -33334)`: write it by hand from the existing settlement row's format in the same test file.
  - `ffi_snapshot_for_group`: given two groups with expenses, settlements and redistributions in each plus personal expenses, the result holds only that group (its `PortGroup`, its records); personal expenses are dropped.
  - `skrin`: put 3 records (personal, group A, group B), `delete_group_records(A)`, `all_records()` holds personal + B.
- [ ] **Step 2:** `cargo test -p skipti-core --lib flutningr skrin` -> the new tests fail to compile.
- [ ] **Step 3: Implement.** `SCHEMA_VERSION = 3`; `upgrade_step(2, doc)` rewrites `schema_version` to 3 and adds `"redistributions": []` (follow the 1 -> 2 step's pattern exactly: parse the old envelope struct, build the new). `canonical_sorted` sorts redistributions by `(group, from, currency_code, shares)`. Validation: `from` and every share id are 32 hex, shares nonempty, every share nonzero and of one sign, `from` not among them, the group's currency matches when the group is in the snapshot. `skrin::delete_group_records(&self, group: &[u8; 16]) -> Result<(), SkrinError>`: `DELETE FROM records WHERE personal = 0 AND group_id = ?1`. `Store::forget_group(group: String)` parses the hex and calls it under the store lock, as `clear_records` does. `ffi_snapshot_for_group` filters `groups` by id, `expenses`, `settlements` and `redistributions` by `group == Some(g)` / `== g`.
- [ ] **Step 4:** `cargo test -p skipti-core --lib` passes; clippy clean.
- [ ] **Step 5: Version.** `core/Cargo.toml` `version = "0.2.0"`; `cargo build -p skipti-core` updates `Cargo.lock` (confirm `git diff Cargo.lock` changes only skipti-core's version line); `make sbom` regenerates `core/skipti-core.cdx.json` (confirm the diff is the version and timestamps only); add to the ffi tests `assert_eq!(skipti_core_version(), "0.2.0");`.
- [ ] **Step 6: Bindings.** `make bindings`; `git status` shows the Swift and Kotlin bindings changed. The shells do not compile until Tasks 5 and 7 add the new argument: that is expected; do not touch shell code here.
- [ ] **Step 7: Commit** core, lock, SBOM and both bindings:

```bash
git add core/src/flutningr/mod.rs core/src/flutningr/jsonio.rs core/src/flutningr/ledger_csv.rs core/src/skrin/mod.rs core/src/ffi.rs core/Cargo.toml Cargo.lock core/skipti-core.cdx.json ios/Skipti/Bindings android/app/src/main/kotlin/uniffi/skipti_core
git diff --cached --stat
git commit -m "D-210: core 0.2.0: redistributions in the export (schema 3), one group's snapshot, forgetting a group; bindings [skip ci]"
```

### Task 3: Cloud: the role decisions (pure)

**Files:**
- Create: `firebase/functions/groups.js`, `firebase/tests/groups.test.mjs`
- Modify: `firebase/functions/account.js` (`groupsPlan` :29-38), `firebase/tests/account.test.mjs` (:95, :110 deepEqual of the plan)

**Interfaces:**
- Produces (used by Task 4): `ownerOf(group)`, `adminsOf(group)`, `isAdmin(group, uid)`, `can(action, group, caller, target)`, `checkRemovalRecords(records, groupId, member)`, `noticeBody(kind, n)`, `noticePush(n)`, `notice(kind, fields)`, `requestNoticeId(groupId, member)`; `account.groupsPlan` returns `{leave, destroy, handOver: [{id, owner}]}`.

- [ ] **Step 1: Failing tests** `firebase/tests/groups.test.mjs`:

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import * as groups from '../functions/groups.js';

const g = (over = {}) => ({ memberUids: ['ow', 'ad', 'me', 'm2'], createdBy: 'ow', ...over });

test('the owner is `owner`, else whoever made the group', () => {
  assert.equal(groups.ownerOf(g()), 'ow');
  assert.equal(groups.ownerOf(g({ owner: 'ad' })), 'ad');
  assert.equal(groups.ownerOf({ memberUids: ['x'] }), null);
});

test('who may do what (the founder\'s rules, 2026-10-13)', () => {
  const x = g({ admins: ['ad'] });
  // Admins: owner or admin names one; only the owner takes it away.
  assert.equal(groups.can('admin-on', x, 'ow', 'me'), true);
  assert.equal(groups.can('admin-on', x, 'ad', 'me'), true);
  assert.equal(groups.can('admin-on', x, 'me', 'm2'), false);
  assert.equal(groups.can('admin-off', x, 'ow', 'ad'), true);
  assert.equal(groups.can('admin-off', x, 'ad', 'ad'), false);
  assert.equal(groups.can('admin-on', x, 'ow', 'stranger'), false);
  // One owner; only the owner hands over, and only to a member.
  assert.equal(groups.can('owner', x, 'ow', 'me'), true);
  assert.equal(groups.can('owner', x, 'ad', 'me'), false);
  assert.equal(groups.can('owner', x, 'ow', 'ow'), false);
  // Removing: never the owner; admins remove members, not admins.
  assert.equal(groups.can('remove', x, 'ad', 'me'), true);
  assert.equal(groups.can('remove', x, 'ad', null), true);
  assert.equal(groups.can('remove', x, 'ad', 'ow'), false);
  assert.equal(groups.can('remove', x, 'ow', 'ad'), true);
  assert.equal(groups.can('remove', g({ admins: ['ad', 'me'] }), 'ad', 'me'), false);
  assert.equal(groups.can('remove', x, 'me', 'm2'), false);
  // Asking: any member, about anyone but the owner, themselves included.
  assert.equal(groups.can('request', x, 'me', 'me'), true);
  assert.equal(groups.can('request', x, 'me', null), true);
  assert.equal(groups.can('request', x, 'me', 'ow'), false);
  assert.equal(groups.can('request', x, 'stranger', 'me'), false);
  // Declining follows removing.
  assert.equal(groups.can('decline', x, 'ad', 'me'), true);
  assert.equal(groups.can('decline', x, 'ad', 'ad'), false);
  assert.equal(groups.can('decline', x, 'me', 'm2'), false);
  // Deleting: the owner alone.
  assert.equal(groups.can('delete', x, 'ow', null), true);
  assert.equal(groups.can('delete', x, 'ad', null), false);
  assert.equal(groups.can('unknown', x, 'ow', null), false);
});

const G = 'c'.repeat(32), M = 'd'.repeat(32), D = 'e'.repeat(32);
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64');
const rec = (type, blob, n = 1) => ({
  id: `${1000 + n}-0-${D}`, type, hlc: { millis: 1000 + n, counter: 0, device: D }, deviceId: D,
  payload: { version: 1, epoch: 0, flags: 0, nonce: Buffer.from('RED\x01').toString('base64'), blob: b64(blob) },
});
const removal = rec('3', { op: 'removeMember', group: G, member: M }, 1);
const moved = rec('6', { group: G, from: M, currency: 'INR', shares: [{ member: 'a'.repeat(32), amount: -1 }] }, 2);

test('a removal carries exactly one removeMember for that person and only their redistributions', () => {
  assert.equal(groups.checkRemovalRecords([removal], G, M), null);
  assert.equal(groups.checkRemovalRecords([moved, removal], G, M), null);
  assert.equal(groups.checkRemovalRecords([], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([moved], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([removal, removal], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([rec('3', { op: 'removeMember', group: G, member: D })], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([rec('3', { op: 'renameGroup', group: G, name: 'x' })], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([removal, rec('6', { group: G, from: D, currency: 'INR', shares: [] }, 2)], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([removal, rec('1', {}, 2)], G, M), 'records');
  assert.equal(groups.checkRemovalRecords([{ ...removal, id: 'x' }], G, M), 'records');
  assert.equal(groups.checkRemovalRecords(Array.from({ length: 12 }, (_, i) => (i ? rec('6', { group: G, from: M, currency: 'INR', shares: [] }, i + 2) : removal)), G, M), 'records');
});

test('notices and their pushes say who did what, in the group\'s name', () => {
  const n = { groupId: G, groupName: 'Goa trip', byName: 'Prithvi', memberName: 'Sam' };
  assert.equal(groups.noticeBody('request', { ...n, byName: 'Asha', toPerson: false }), 'Asha asked to remove Sam');
  assert.equal(groups.noticeBody('request', { ...n, byName: 'Asha', toPerson: true }), 'Asha asked to remove you');
  assert.equal(groups.noticeBody('declined', { ...n, toPerson: false }), 'Prithvi kept Sam in Goa trip');
  assert.equal(groups.noticeBody('declined', { ...n, toPerson: true }), 'Prithvi kept you in Goa trip');
  assert.equal(groups.noticeBody('removed', n), 'Prithvi removed you from Goa trip');
  assert.equal(groups.noticeBody('role', { ...n, role: 'admin' }), 'Prithvi made you an admin of Goa trip');
  assert.equal(groups.noticeBody('role', { ...n, role: 'owner' }), 'Prithvi made you the owner of Goa trip');
  assert.equal(groups.noticeBody('deleted', n), 'Prithvi deleted Goa trip');
  const p = groups.noticePush({ kind: 'removed', ...n });
  assert.deepEqual(p.notification, { title: 'Goa trip', body: 'Prithvi removed you from Goa trip' });
  assert.deepEqual(p.data, { kind: 'group', groupId: G });
  assert.equal(p.android.notification.channelId, 'group-activity');
  assert.throws(() => groups.noticePush({ kind: 'removed', ...n, groupId: 'x' }));
  assert.equal(groups.requestNoticeId(G, M), `request-${G}-${M}`);
});
```

And in `firebase/tests/groups.test.mjs` also the hand-over (it lives in account.js but is a role rule):

```js
import * as account from '../functions/account.js';

test('purge hands a group on: the first admin in join order, else the first member left', () => {
  const plan = account.groupsPlan([
    { id: 'a', memberUids: ['me', 'x', 'y'], createdBy: 'me', admins: ['y'] },
    { id: 'b', memberUids: ['me', 'x'], owner: 'me' },
    { id: 'c', memberUids: ['x', 'me'], createdBy: 'x' },
    { id: 'd', memberUids: ['me'], createdBy: 'me' },
  ], 'me');
  assert.deepEqual(plan, {
    leave: ['a', 'b', 'c'],
    destroy: ['d'],
    handOver: [{ id: 'a', owner: 'y' }, { id: 'b', owner: 'x' }],
  });
});
```

Update `firebase/tests/account.test.mjs` :95 and :110: their expected plans gain `handOver: []` (or the hand-over their seeded data implies; read the seeds). Also run `node --test tests/groups.test.mjs` before implementing: it fails (module missing).

- [ ] **Step 2: Implement** `firebase/functions/groups.js`:

```js
// D-210 (C3): who may do what in a group, worked out without touching
// Firebase, so firebase/tests/groups.test.mjs runs every rule here directly.
// index.js reads the documents, asks these, and writes.

const HEX32 = /^[0-9a-f]{32}$/;
const MAX_RECORDS = 11; // one removeMember + at most ten currencies

export function ownerOf(group) {
  if (typeof group?.owner === 'string') return group.owner;
  return typeof group?.createdBy === 'string' ? group.createdBy : null;
}

export function adminsOf(group) {
  return Array.isArray(group?.admins) ? group.admins.filter((u) => typeof u === 'string') : [];
}

export function isAdmin(group, uid) {
  return adminsOf(group).includes(uid);
}

function members(group) {
  return Array.isArray(group?.memberUids) ? group.memberUids : [];
}

/** `target` is a uid, or null for someone with no account. */
export function can(action, group, caller, target) {
  const inGroup = (u) => members(group).includes(u);
  if (!inGroup(caller)) return false;
  const owner = ownerOf(group);
  const isOwner = caller === owner;
  const manages = isOwner || isAdmin(group, caller);
  switch (action) {
    case 'admin-on': return manages && target !== null && inGroup(target) && target !== owner;
    case 'admin-off': return isOwner && isAdmin(group, target);
    case 'owner': return isOwner && target !== null && target !== caller && inGroup(target);
    case 'request': return target !== owner;
    case 'remove':
    case 'decline':
      if (target === owner) return false;
      if (target !== null && isAdmin(group, target)) return isOwner;
      return manages;
    case 'delete': return isOwner;
    default: return false;
  }
}

function json(base64) {
  try { return JSON.parse(Buffer.from(base64, 'base64').toString('utf8')); } catch { return null; }
}

function recordShape(r) {
  const h = r?.hlc;
  return r && typeof r === 'object'
    && Number.isSafeInteger(h?.millis) && Number.isSafeInteger(h?.counter) && HEX32.test(h?.device ?? '')
    && r.id === `${h.millis}-${h.counter}-${h.device}`
    && HEX32.test(r.deviceId ?? '')
    && r.payload && typeof r.payload.blob === 'string' && typeof r.payload.nonce === 'string'
    && Number.isSafeInteger(r.payload.version) && Number.isSafeInteger(r.payload.epoch) && Number.isSafeInteger(r.payload.flags);
}

/** null when the records are exactly one removeMember of `member` in `groupId`
 *  plus that person's redistributions; otherwise 'records'. */
export function checkRemovalRecords(records, groupId, member) {
  if (!Array.isArray(records) || records.length < 1 || records.length > MAX_RECORDS) return 'records';
  let removals = 0;
  for (const r of records) {
    if (!recordShape(r)) return 'records';
    const blob = json(r.payload.blob);
    if (r.type === '3') {
      if (blob?.op !== 'removeMember' || blob.group !== groupId || blob.member !== member) return 'records';
      removals += 1;
    } else if (r.type === '6') {
      if (blob?.group !== groupId || blob.from !== member) return 'records';
    } else {
      return 'records';
    }
  }
  return removals === 1 ? null : 'records';
}

export function noticeBody(kind, n) {
  switch (kind) {
    case 'request': return n.toPerson ? `${n.byName} asked to remove you` : `${n.byName} asked to remove ${n.memberName}`;
    case 'declined': return n.toPerson ? `${n.byName} kept you in ${n.groupName}` : `${n.byName} kept ${n.memberName} in ${n.groupName}`;
    case 'removed': return `${n.byName} removed you from ${n.groupName}`;
    case 'role': return n.role === 'owner' ? `${n.byName} made you the owner of ${n.groupName}` : `${n.byName} made you an admin of ${n.groupName}`;
    case 'deleted': return `${n.byName} deleted ${n.groupName}`;
    default: throw new Error('unknown notice');
  }
}

export function noticePush(n) {
  if (!HEX32.test(n?.groupId ?? '')) throw new Error('groupId');
  return {
    notification: { title: n.groupName || 'SKIPTI', body: noticeBody(n.kind, n) },
    data: { kind: 'group', groupId: n.groupId },
    android: { priority: 'high', notification: { channelId: 'group-activity', tag: n.groupId } },
  };
}

export function requestNoticeId(groupId, member) {
  return `request-${groupId}-${member}`;
}
```

And `account.groupsPlan` (keep its doc comment, extend it with one line for the hand-over):

```js
export function groupsPlan(groups, uid) {
  const leave = [];
  const destroy = [];
  const handOver = [];
  for (const g of groups ?? []) {
    const members = Array.isArray(g?.memberUids) ? g.memberUids : [];
    if (!members.includes(uid)) continue;
    const rest = members.filter((m) => m !== uid);
    if (!rest.length) { destroy.push(g.id); continue; }
    leave.push(g.id);
    const owner = typeof g.owner === 'string' ? g.owner : g.createdBy;
    if (owner !== uid) continue;
    const admins = Array.isArray(g.admins) ? g.admins : [];
    handOver.push({ id: g.id, owner: rest.find((m) => admins.includes(m)) ?? rest[0] });
  }
  return { leave, destroy, handOver };
}
```

Note: `groups.can` is not imported by account.js (account.js stays dependency-free).

- [ ] **Step 3: Run** `cd firebase && node --test tests/groups.test.mjs` -> pass. (`account.test.mjs` needs the emulators: Task 4 runs it.)
- [ ] **Step 4: Commit**

```bash
git add firebase/functions/groups.js firebase/functions/account.js firebase/tests/groups.test.mjs firebase/tests/account.test.mjs
git diff --cached --stat
git commit -m "D-210: cloud: who may do what in a group, the removal records' shape, notices, the purge hand-over (pure) [skip ci]"
```

### Task 4: Cloud: the callables, the rules, purge, and the emulator tests

**Files:**
- Modify: `firebase/functions/index.js`, `firebase/functions/account-store.js`, `firebase/firestore.rules`
- Create: `firebase/tests/groups-functions.test.mjs`
- Modify: `firebase/tests/rules.test.mjs`, `firebase/tests/account.test.mjs`

**Interfaces:**
- Consumes: Task 3's `groups.*`, `account.groupsPlan`.
- Produces: the Cloud contract's 7 callables, notices and rules (the shells call them by name).

- [ ] **Step 1: Failing emulator tests** in `firebase/tests/groups-functions.test.mjs`. Copy the header of `functions.test.mjs` (lines 1-58: `PROJECT`, `FS`, `FN`, `idToken`, `call`, `seed`, `read`, `list`) rather than importing it (each test file stands alone). Extend `seed`'s `value()` with maps (`{ mapValue: { fields } }`) so `former` and `admins` can be seeded. Fixtures: group `G` with `memberUids ['ow','ad','me','sam']`, `createdBy 'ow'`, `admins ['ad']`, `name 'Goa trip'`; `users/{uid}` docs with `participantId` `'1'*32` (ow), `'2'*32` (ad), `'3'*32` (me), `'4'*32` (sam). Records built with the `rec` helper from Task 3 (copy it). Tests (each asserts the exact error status, as the existing suites do):
  1. `groupRoles`: `{owner: '1'*32, admins: ['2'*32]}` for any member; `PERMISSION_DENIED` for a stranger; an old group without `owner` reports its creator.
  2. `setAdmin`: `me` cannot (`PERMISSION_DENIED`); `ad` can make `me` admin (doc `admins` now includes `me`; `users/me/notices` has a `role` notice); `ad` cannot take `me`'s admin away; `ow` can; making the owner an admin is refused; an unknown `you` is `NOT_FOUND`.
  3. `makeOwner`: `ad` refused; `ow` -> `me`: `owner == 'me'`, `admins` includes `ow`, `me` gets a `role` owner notice.
  4. `requestRemoval`: a stranger refused; naming the owner refused (`PERMISSION_DENIED`); `me` asks about `sam`: `groups/G/requests/{member}` exists, `request-G-member` notices exist for `ow`, `ad`, `sam` and `me`; a second request about the same member is `ALREADY_EXISTS`; `me` may ask about themselves.
  5. `declineRemoval`: `me` refused; `ad` declines: the request and all four request notices are gone; `declined` notices for the asker and the person.
  6. `removeMember`: `me` refused; `ad` removing `ow` refused; `ad` removing another admin refused, `ow` allowed; bad records -> `INVALID_ARGUMENT`; a good removal: the records exist at `groups/G/records/{id}` with `removal: true`, `authorUid == 'ad'`, numeric `type`, Bytes payload; `memberUids` lacks `sam`; `former.sam.by == 'ad'` and `former.sam.at` equals every written record's `createdAt`; the open request and its notices are gone; `users/sam/notices` holds `removed` with `removedAt`; an unused invite (`invites/{h}` with `groupId G`, `memberId` member, `usedBy null`) and `users/sam/invites` copies for that member are deleted, a used one is kept. An unjoined person (no `you`): records written, `memberUids` unchanged, no `former`.
  7. `deleteGroup`: `ad` refused; `ow`: `groups/G` and its records and requests are gone (list returns empty); every other member has a `deleted` notice; with a Storage object at `groups/G/picture.jpg` (upload via the Storage emulator REST as `rules.test.mjs` `upload` does, as `owner`), it is gone after.
  8. `acceptInvite` after removal: a fresh invite for `sam` accepted by `sam` puts `sam` back in `memberUids` and removes `former.sam`.
  9. Purge: in `account.test.mjs`, a group owned by the purged account with an admin left: `owner` becomes that admin and `admins` no longer holds the purged uid; a destroyed group's picture is deleted.
  Rules tests in `rules.test.mjs` (template: invites-by-Function at :226-235, membership refusal at :252-256):
  10. a former member `get`s `groups/g1` (OK) and lists `groups/g1/records` with `where createdAt <= removedAt` (OK) but a list with no bound, or with a bound after `removedAt`, is `DENIED`; a stranger `get` is `DENIED`.
  11. `users/alice/notices/n1`: alice `get`/`list`/`delete` OK; alice `create` DENIED; bob `get` DENIED.
  12. a member creating a group record with `removal: true` is `DENIED`; with `owner`, `admins` or `former` on group create or update DENIED (update is already covered: add the three fields to the existing assertion).
  13. `groups/g1/requests/x`: member `get` DENIED, `create` DENIED.
- [ ] **Step 2:** `./scripts/firebase-rules-test.sh` -> the new tests fail (callables missing, rules missing). Machine free first (CLAUDE.md §4).
- [ ] **Step 3: Implement the rules.** In `firestore.rules`:

```
    // D-210: a removed person (groups/{g}.former) keeps reading what was
    // written up to their removal, nothing after; their phone's copy stays
    // whole. Only Functions write `former`.
    function formerAt(groupId) {
      return get(/databases/$(database)/documents/groups/$(groupId)).data.get('former', {}).get(request.auth.uid, null);
    }
```

`match /groups/{groupId}`: `allow get: if signedIn() && (request.auth.uid in resource.data.memberUids || request.auth.uid in resource.data.get('former', {}));` and keep `allow list: if signedIn() && request.auth.uid in resource.data.memberUids;` (split the existing `get, list`). Records: `allow get, list: if isMember(groupId) || (signedIn() && formerAt(groupId) != null && resource.data.createdAt <= formerAt(groupId).at);`. Users: `match /notices/{noticeId} { allow get, list, delete: if isUser(uid); }` with the comment `// D-210: what happened in your groups (a request, a removal, a role, a deletion): written by a Function, dismissed by the account.` Above the closing comment, add `groups/{g}/requests` to the Functions-only list. If test 10's bounded list is refused by the emulator (a rule over `resource.data.createdAt` needs the query to carry the bound), keep the rule and make the phones' catch-up query bounded (Tasks 5 and 7 already do); if the emulator refuses even the bounded query, STOP and report to the controller (fallback: a `formerRecords` callable).
- [ ] **Step 4: Implement the callables** in `index.js` (`import * as groups from './groups.js'; import { getStorage } from 'firebase-admin/storage';`). Shared helpers:

```js
// D-210: an account in a group, from the participant id a phone knows
// (claims), never a uid from the phone.
async function memberUid(group, you) {
  if (typeof you !== 'string' || !HEX32.test(you)) throw new HttpsError('invalid-argument', 'you is not valid');
  const found = await db.collection('users').where('participantId', '==', you).limit(2).get();
  if (found.size !== 1 || !(group.memberUids ?? []).includes(found.docs[0].id)) throw new HttpsError('not-found', 'No such person.');
  return found.docs[0].id;
}

async function participantOf(uid) {
  const d = await db.collection('users').doc(uid).get();
  return d.exists && typeof d.data().participantId === 'string' ? d.data().participantId : null;
}

/** Writes a notice to each uid and pushes it to their phones. */
async function tell(fn, uids, fields, id) {
  const notice = { ...fields, at: FieldValue.serverTimestamp() };
  await Promise.all(uids.map((u) => {
    const c = db.collection('users').doc(u).collection('notices');
    return id ? c.doc(id).set(notice) : c.add(notice);
  }));
  const phones = (await Promise.all(uids.map(async (u) => {
    const list = await db.collection('users').doc(u).collection('devices').get();
    return list.docs.map((d) => ({ uid: u, id: d.id, data: d.data() }));
  }))).flat();
  const sent = push.tokens(phones);
  if (!sent.length) return;
  const response = await getMessaging().sendEachForMulticast({ ...groups.noticePush(fields), tokens: sent.map((s) => s.token) })
    .catch((e) => { logger.warn(`${fn}: push threw`, { code: typeof e?.code === 'string' ? e.code : 'other' }); return null; });
  if (!response) return;
  report(fn, sent, response);
  await Promise.all(push.deadTokens(sent, response).map((s) =>
    db.collection('users').doc(s.uid).collection('devices').doc(s.id).update({ fcmToken: FieldValue.delete() }).catch(() => {})));
}
```

`tell` sends one push per call; for the request notice, call it twice (`toPerson: true` for the person, `false` for the others) since the body differs. Each callable: `signedIn`, validate ids (HEX32) and names (`text(v, 80)`), `memberGroup(groupId, uid)` (it refuses non-members), resolve `you` with `memberUid`, ask `groups.can(...)` -> `permission-denied` "Only the owner or an admin can do that." when false, then write in a transaction that re-reads the group (refuse `failed-precondition` "That changed on another phone. Try again." if the decision no longer holds), then `tell` outside the transaction. Specifics:
  - `groupRoles`: map `ownerOf` and `adminsOf` uids through `participantOf`; drop nulls.
  - `setAdmin`: `on` -> `admins: FieldValue.arrayUnion(target)`, notice `role` `{role: 'admin'}`; off -> `arrayRemove`, no notice. Also copy each open request's notice (`requests` docs) to a new admin with `toPerson: false` (one `tell` per open request, no push needed: write the notice docs directly).
  - `makeOwner`: `{ owner: target, admins: FieldValue.arrayUnion(uid) }` and `arrayRemove(target)` from admins in the same update (two field transforms on one field are not allowed: compute the new admins array from the read group instead).
  - `requestRemoval`: target uid via `you` when given (else null); refuse when `groups.can('request', ...)` is false; in the transaction refuse `already-exists` "Already asked." when `requests/{member}` exists; write `{member, you: you ?? null, memberName, askerUid: uid, askerName, at}`; then notices `request` (id `groups.requestNoticeId`) to owner + admins + asker (`toPerson: false`) and to the person's uid when joined (`toPerson: true`), each recipient once.
  - `declineRemoval`: refuse if no request (`not-found`); `can('decline', group, uid, target)`; delete the request; delete the request notice from owner, admins, asker, person; `declined` notices to asker and person (`toPerson` accordingly).
  - `removeMember`: `const bad = groups.checkRemovalRecords(data.records, groupId, member); if (bad) throw new HttpsError('invalid-argument', 'records are not valid');`; `can('remove', ...)`; in ONE transaction: re-read group and request, for each record `tx.create(groupRef.collection('records').doc(r.id), { type: r.type, hlc: r.hlc, authorUid: uid, deviceId: r.deviceId, createdAt: FieldValue.serverTimestamp(), payload: { version: r.payload.version, epoch: r.payload.epoch, flags: r.payload.flags, nonce: Buffer.from(r.payload.nonce, 'base64'), blob: Buffer.from(r.payload.blob, 'base64') }, removal: true })` (Buffers become Firestore Bytes; `create` refuses an existing id: `already-exists`), when the person has a uid `tx.update(groupRef, { memberUids: FieldValue.arrayRemove(target), admins: FieldValue.arrayRemove(target), [\`former.${target}\`]: { at: FieldValue.serverTimestamp(), by: uid } })`, delete the request. After: clear request notices; unused invites for `(groupId, member)` deleted from `invites/` and from `users/{target}/invites` (query `where('groupId','==',groupId).where('memberId','==',member)`, keep `usedBy` set ones); `tell('removeMember', [target], { kind: 'removed', groupId, groupName, byName, removedAt: <read back former.at> })` (read the group after the transaction to get the resolved timestamp).
  - `deleteGroup`: `can('delete', ...)`; read members; `tell` `deleted` to every member but the caller FIRST; then `db.recursiveDelete(groupRef)` and `getStorage().bucket().file(\`groups/${groupId}/picture.jpg\`).delete({ ignoreNotFound: true })`; then delete outstanding request notices for that group from every former member list member.
  - `acceptInvite`: in its transaction add `[\`former.${uid}\`]: FieldValue.delete()` to the `tx.update(groupRef, ...)`.
  - `account-store.purgeAccount`: pass `owner`, `createdBy`, `admins` into `groupsPlan`; for `plan.leave` update `{ memberUids: arrayRemove(uid), admins: arrayRemove(uid) }`; for each `plan.handOver` update `{ owner }`; for `plan.destroy` also delete `groups/{id}/picture.jpg` (`getStorage` passed in like `auth`? No: import `getStorage` in account-store.js and accept an optional `bucket` parameter defaulting to `getStorage().bucket()` so the test can pass the emulator's).
- [ ] **Step 5:** `./scripts/firebase-rules-test.sh` -> `RULES TESTS PASSED (N tests)`, nothing skipped. `cd firebase/functions && npm audit --audit-level=high` clean (no dependency change expected).
- [ ] **Step 6: Commit**

```bash
git add firebase/functions/index.js firebase/functions/account-store.js firebase/firestore.rules firebase/tests/groups-functions.test.mjs firebase/tests/rules.test.mjs firebase/tests/account.test.mjs
git diff --cached --stat
git commit -m "D-210: cloud: roles, removal requests, removeMember (atomic, removal: true), deleteGroup, notices, former reads, the purge hand-over [skip ci]"
```

### Task 5: iOS: the ledger and sync layer

**Files:**
- Create: `ios/Skipti/App/Roles.swift`
- Modify: `ios/Skipti/App/LedgerStore.swift`, `GroupStore.swift`, `CloudSync.swift`, `Push.swift`, `SkiptiApp.swift`, `ExportFlow.swift`, every `ffiBalanceLine(` / `ffiBalancesCompute(` / `ffiMemberHasHistory(` / `ffiOverview(` caller (`ffiOverview` gained `redistributions` right after `settlements` in the Task 1 fix: OverviewScreen.swift:222; BalanceScreen.swift:429, SettleAll.swift:42, OverviewScreen.swift:479, SettleScreen.swift:277/299/316, GroupStore.swift:219, the tests), `ios/Skipti.xcodeproj/project.pbxproj` (CLAUDE.md §7.1: four insertions per file, assert free IDs; app files copy People.swift's reference line)
- Create test: `ios/SkiptiTests/RolesTests.swift` (+ pbxproj, test target)

**Interfaces:**
- Consumes: bindings `RedistributionInput`, `ffiEncodeRedistribution`, `ffiDecodeRedistribution`, `ffiRedistributeEqual`, `ffiRedistributionTally`, `ffiSnapshotForGroup`, `SkiptiVault.forgetGroup(group:)`; the callables.
- Produces (used by Task 6):
  - `LedgerStore.Sync.redistributionType: UInt8 = 6`; `LedgerStore.loadRedistributions() throws -> [RedistributionInput]` (kv `ledger/redistributions`, rebuilt from type-6 records in `rebuildProjection`); every balance call passes it, in HLC order (millis, counter, device) so every phone shows the same pairwise cards.
  - `enum RoleRules` (pure): `struct Roles: Equatable { var owner: String?; var admins: [String] }` (account `you` ids); `enum Role { case owner, admin, member, unjoined }`; `static func role(of you: String?, in roles: Roles) -> Role`; `struct Offer: Equatable { var remove: Bool; var ask: Bool; var askSelf: Bool; var makeAdmin: Bool; var removeAdmin: Bool; var makeOwner: Bool }`; `static func offer(viewer: Role, target: Role, isSelf: Bool) -> Offer`; `static func canDelete(_ viewer: Role) -> Bool`; `static func requestCard(viewer: Role, viewerIsAsker: Bool, viewerIsPerson: Bool) -> RequestCard?` with `enum RequestCard { case decide, asker, person }`.
  - `final class GroupRoles` (`@MainActor`, `ObservableObject`, `shared`): `roles[groupID]`, `func refresh(_ groupID: String) async`, `func setAdmin(...) async throws`, `func makeOwner(...) async throws` (call the callables via `CloudFunctions.call`), caching in kv `cloud/roles/{g}` as JSON.
  - `final class Notices` (`@MainActor`, `ObservableObject`, `shared`), modelled on `IncomingInvites` (People.swift:423-484): `struct Item: Equatable { id, kind: Kind, groupId, groupName, byName, memberId?, memberName?, role?, at: Date, removedAt: Date? }`, `enum Kind: String { case request, declined, removed, deleted, role }`, `nonisolated static func item(id:data:) -> Item?` (validates kind and hex32 ids; refuses anything else), `listen(uid:)`, `stop()`, `dismiss(_:)`; started and stopped beside `IncomingInvites` in `CloudSync.listen()` / `stopListening()`.
  - `LedgerStore.readOnly() -> [String: ReadOnly]` with `struct ReadOnly: Codable, Equatable { var kind: String; var by: String; var at: Date }` (kv `ledger/readonly`), `markReadOnly(_ groupID:, _ ro:)`, `forgetGroup(_ groupID:) throws` (core `SkiptiVault.forgetGroup(group:)`, then drop the group from kv `ledger/groups`, `ledger/group-pictures`, expenses, settlements, redistributions, `ledger/readonly`, `cloud/roles/{g}`, `sync/cursor/{g}`, `sync/groups-up`).
  - `LedgerStore.snapshot(group:)` -> `ffiSnapshotForGroup(try snapshot(), group)`.

- [ ] **Step 1: Make it compile.** Add `redistributions: try store.loadRedistributions()` (or `[]` in pure tests) to every balance and history call. `make ios-app` builds again; existing tests pass.
- [ ] **Step 2: Failing tests** in `ios/SkiptiTests/RolesTests.swift`:

```swift
import XCTest
@testable import Skipti

final class RolesTests: XCTestCase {
    private let o = "1" + String(repeating: "0", count: 31), a = "2" + String(repeating: "0", count: 31)
    private let m = "3" + String(repeating: "0", count: 31)

    func testARoleComesFromTheCloudsList() {
        let roles = RoleRules.Roles(owner: o, admins: [a])
        XCTAssertEqual(RoleRules.role(of: o, in: roles), .owner)
        XCTAssertEqual(RoleRules.role(of: a, in: roles), .admin)
        XCTAssertEqual(RoleRules.role(of: m, in: roles), .member)
        XCTAssertEqual(RoleRules.role(of: nil, in: roles), .unjoined)
    }

    func testASheetOffersWhatTheViewerMayDo() {
        // The founder's rules (2026-10-13); twins: RolesTest.kt.
        typealias O = RoleRules.Offer
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .member, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: true, removeAdmin: false, makeOwner: true))
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .admin, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: false, removeAdmin: true, makeOwner: true))
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .unjoined, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .member, isSelf: false),
                       O(remove: true, ask: false, askSelf: false, makeAdmin: true, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .admin, isSelf: false),
                       O(remove: false, ask: true, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .owner, isSelf: false),
                       O(remove: false, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .member, target: .member, isSelf: false),
                       O(remove: false, ask: true, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .member, target: .member, isSelf: true),
                       O(remove: false, ask: false, askSelf: true, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .owner, target: .owner, isSelf: true),
                       O(remove: false, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertEqual(RoleRules.offer(viewer: .admin, target: .admin, isSelf: true),
                       O(remove: false, ask: false, askSelf: true, makeAdmin: false, removeAdmin: false, makeOwner: false))
        XCTAssertTrue(RoleRules.canDelete(.owner))
        XCTAssertFalse(RoleRules.canDelete(.admin))
    }

    func testARequestCardShowsToWhoeverItConcerns() {
        XCTAssertEqual(RoleRules.requestCard(viewer: .owner, viewerIsAsker: false, viewerIsPerson: false), .decide)
        XCTAssertEqual(RoleRules.requestCard(viewer: .admin, viewerIsAsker: true, viewerIsPerson: false), .decide)
        XCTAssertEqual(RoleRules.requestCard(viewer: .member, viewerIsAsker: true, viewerIsPerson: false), .asker)
        XCTAssertEqual(RoleRules.requestCard(viewer: .member, viewerIsAsker: false, viewerIsPerson: true), .person)
        XCTAssertEqual(RoleRules.requestCard(viewer: .admin, viewerIsAsker: false, viewerIsPerson: true), .person)
        XCTAssertNil(RoleRules.requestCard(viewer: .member, viewerIsAsker: false, viewerIsPerson: false))
    }

    func testANoticeIsReadOnlyInItsOwnShape() {
        let g = String(repeating: "c", count: 32)
        let good: [String: Any] = ["kind": "removed", "groupId": g, "groupName": "Goa trip", "byName": "Prithvi"]
        XCTAssertEqual(Notices.item(id: "n1", data: good)?.kind, .removed)
        XCTAssertNil(Notices.item(id: "n1", data: good.merging(["kind": "hacked"]) { $1 }))
        XCTAssertNil(Notices.item(id: "n1", data: good.merging(["groupId": "x"]) { $1 }))
        XCTAssertNil(Notices.item(id: "n1", data: good.merging(["groupName": 4]) { $1 }))
        XCTAssertTrue(PushRoute.isGroup(["kind": "group", "groupId": g]))
        XCTAssertFalse(PushRoute.isGroup(["kind": "group", "groupId": "x"]))
    }

    func testOnlyAFunctionWrittenRemovalComesIn() throws {
        let group = String(repeating: "c", count: 32), me = String(repeating: "a", count: 32)
        let them = String(repeating: "b", count: 32), sam = String(repeating: "d", count: 32)
        func record(_ type: UInt8, _ blob: Data) -> RecordWire {
            RecordWire(version: 1, recordType: type, groupId: group, epoch: 0, hlcMillis: 1, hlcCounter: 0,
                       hlcDeviceId: them, authorDeviceId: them, nonceSession: Data(), flags: 0, blob: blob)
        }
        let removal = record(3, try ffiEncodeGroupOp(op: .removeMember(groupId: group, memberId: sam)))
        let moved = record(6, try ffiEncodeRedistribution(r: RedistributionInput(
            group: group, from: sam, currencyCode: "INR", shares: [IdAmount(id: me, amountMinor: -500)])))
        let rename = record(3, try ffiEncodeGroupOp(op: .renameGroup(groupId: group, name: "Goa")))
        func refusal(_ r: RecordWire, _ removal: Bool) -> CloudRecords.Refusal? {
            CloudRecords.refusal(r, removal: removal, from: group, thisDevice: me)
        }
        XCTAssertNil(refusal(removal, true))
        XCTAssertNil(refusal(moved, true))
        XCTAssertEqual(refusal(removal, false), .unverifiedRemoval, "a phone wrote it, not the Function")
        XCTAssertEqual(refusal(moved, false), .unverifiedRemoval)
        XCTAssertNil(refusal(rename, false), "other operations need no mark")
    }

    func testTheWordsMatchAndroid() {
        XCTAssertEqual(RolesCopy.askNote(name: "Sam"), "Only the owner or an admin can remove someone. Sam, the owner and the admins will see your request.")
        XCTAssertEqual(RolesCopy.makeAdminTitle("Asha"), "Make Asha an admin?")
        XCTAssertEqual(RolesCopy.makeOwnerBody("Asha", group: "Goa trip"), "Asha will own Goa trip: names admins, removes anyone, can delete the group. You become an admin. Only Asha can undo this.")
        XCTAssertEqual(RolesCopy.moved(from: "Sam", amount: "₹1,000.00", to: ["Prithvi", "Rahul", "Asha"]), "Sam's ₹1,000.00 moved to Prithvi, Rahul and Asha")
        XCTAssertEqual(RolesCopy.moved(from: "Sam", amount: "₹5.00", to: ["Nina"]), "Sam's ₹5.00 moved to Nina")
        XCTAssertEqual(RolesCopy.moved(from: "Sam", amount: "₹5.00", to: ["A", "B"]), "Sam's ₹5.00 moved to A and B")
    }
}
```

- [ ] **Step 3:** `xcodebuild ... -only-testing:SkiptiTests/RolesTests` (CLAUDE.md §7.2, `-collect-test-diagnostics never`) -> fails to compile.
- [ ] **Step 4: Implement.**
  - `RoleRules.offer`: `remove` = viewer manages (owner or admin) and target is not owner and (target is not admin or viewer is owner) and not self. `ask` = not self, not remove, target not owner. `askSelf` = isSelf and viewer is not owner. `makeAdmin` = viewer manages, target is `.member`, not self. `removeAdmin` = viewer is owner, target is admin. `makeOwner` = viewer is owner, target is member or admin, not self. `requestCard`: managers see `.decide` unless they are the person (then `.person`); a non-manager sees `.asker` or `.person` or nothing.
  - `RolesCopy`: every string in the plan's Copy table, as functions of their placeholders (`askNote(name:)`, `makeAdminTitle(_:)`, `makeOwnerBody(_:group:)`, `moved(from:amount:to:)`, and so on). No string-literal SwiftUI titles with names (use `Text(verbatim:)`).
  - CloudSync `refusal`: add `case unverifiedRemoval`; the signature becomes `static func refusal(_ record: RecordWire, removal: Bool = false, from group: String, thisDevice: String) -> Refusal?` (existing callers keep compiling); `receive` passes `data["removal"] as? Bool == true` for each document; the refusal for `groupOpType` `.removeMember` and for `redistributionType` requires `removal`. Type 6 decodes with `ffiDecodeRedistribution` and must match the log's group (`.wrongGroup` otherwise).
  - Groups listener: keep document data. When a group id disappears from the snapshot, cancel that group's records listener (`groupListeners[id]?.remove()`), and nothing else (the notice explains why).
  - On a `removed` notice for group g: one-shot `getDocuments()` on `groups/g/records` `.whereField("createdAt", isGreaterThanOrEqualTo: cursor).whereField("createdAt", isLessThanOrEqualTo: Timestamp(date: removedAt)).order(by: "createdAt")`, then `receive(...)`; then `markReadOnly(g, ReadOnly(kind: "removed", by: byName, at: at))`; then `Notices.dismiss`. On `deleted`: `markReadOnly` with kind `deleted`, dismiss. `role` and `declined`: `GroupRoles.shared.refresh(g)` then dismiss. `request`: kept (it is the card) until the request is decided (the Function deletes it).
  - `pushAll`: never upload a record of a group in `readOnly()` (skip it, leave it pending; it is local only).
  - `rebuildProjection`: decode type-6 records into `ledger/redistributions` (newest first not needed: the list is a set by record id).
  - Push: `PushRoute.isGroup(_:)` (kind `group`, hex32 groupId) and `Push.openedGroup: String?`, set in `didReceive`; `SkiptiApp` opens `GroupPageScreen(groupID:)` as it does `PushedExpenseView` (a sheet with a NavigationStack).
  - `GroupStore.removeMember` stays for local-only groups (not in `sync/groups-up`); D-186's `removalRefusal` keeps `.you` and `.history` only for local-only groups; for shared groups Task 6 uses the new flow.
- [ ] **Step 5:** RolesTests pass; `make ios-app` green (the whole suite).
- [ ] **Step 6: Commit** (explicit paths: the new files, pbxproj, every modified Swift file).

### Task 6: iOS: the screens

**Files:**
- Create: `ios/Skipti/App/RemovalFlow.swift`, `ios/Skipti/App/GroupSettings.swift` (+ pbxproj)
- Modify: `ios/Skipti/App/GroupPicker.swift` (PeopleScreen :167-348), `OverviewScreen.swift` (GroupPageScreen :380-650), `SettleAll.swift` (sections exclude read-only groups), the Add expense group picker (exclude read-only groups), `ExportFlow.swift` (export one group)
- Test: extend `ios/SkiptiTests/RolesTests.swift`

**Interfaces:**
- Consumes: Task 5's `RoleRules`, `RolesCopy`, `GroupRoles`, `Notices`, `LedgerStore.readOnly/markReadOnly/forgetGroup/snapshot(group:)/loadRedistributions`, `ffiRedistributeEqual`, `ffiRedistributionTally`, `ffiEncodeRedistribution`, `ffiBalanceLine`.
- Produces: `enum Removal` (pure parts tested): `static func plan(lines: [BalanceLineOut], member: String) -> [Currency: Int64]` (the member's nonzero net per currency, from `ffiBalanceLine` with `viewer: member`); `static func settleFirst(lines:member:group:) -> [LedgerStore.SettlementDTO]` (one settlement per nonzero entry of the member's lines, direction from the sign, as `SettleAll.settlement(for:currency:in:)`); `static func records(store:group:member:redistributions:) throws -> [RecordWire]` (one type-6 record per currency plus one RemoveMember op, with fresh HLCs from the store's clock, nonce session `RED\u{1}` for type 6, flags 0); `static func payload(_ records: [RecordWire]) -> [[String: Any]]` (the Cloud contract's shape, base64 nonce and blob); `@MainActor static func remove(...) async throws` (cloud group: call `removeMember`, then write the same records into the vault through `CloudRecords.apply` and add their ids to `sync/uploaded` so `pushAll` never re-sends them; local-only group: append the records locally; both: rebuild the projection).

- [ ] **Step 1: Failing tests** (append to RolesTests): `Removal.plan` over a two-currency fixture (INR -100000, USD +500 for `pid(4)`) returns both; `Removal.settleFirst` for Sam owing Prithvi 70000 and Rahul 30000 returns two settlements `from Sam` with those amounts; `Removal.payload` of one record has `id == "\(millis)-\(counter)-\(device)"`, `type == "3"`, base64 `blob` decoding to the op JSON; the redistribute screen's state (`RedistributeModel`, a plain struct: ticked ids, typed text per id, `custom` flag) yields `ffiRedistributionTally` ok only when typed amounts add up (parse typed text with `ffiMoneyParseMinor`, sign applied from the balance).
- [ ] **Step 2: Implement the screens** to the mock-up v2 and the Copy table:
  - **People** (`PeopleScreen`): rows gain the pill (`Owner` gold-on-soft, `Admin` accent-on-soft: use existing tokens; add a gold token only if none fits, measured in AppearanceTests); tapping a row (shared group) opens the **person sheet** (`.sheet(item:)` owned by the screen, attached to the always-present List: CLAUDE.md §10.1): header avatar + name; group 1 Remove (`Remove from {group}` with the balance phrase) or Ask; your own row `Ask to be removed`; section `Role` with the rows `RoleRules.offer` allows, each opening its confirm (`.alert` with the Copy table's title/body/buttons). Rename moves to a `Rename` row in the sheet (keep D-186's rename path). Local-only groups keep D-186's cross and rename-on-tap exactly as today.
  - **Request cards** at the top of People and of the group page, from `Notices.shared.items` with `kind == .request` for this group, shaped by `RoleRules.requestCard`: `.decide` -> buttons Decline (calls `declineRemoval`) / Review (the review sheet); `.asker` and `.person` as the Copy table (person: `Settle up` switches to the Settle tab when their balance here is nonzero).
  - **Review sheet**, then **the balance screen** when the member's `Removal.plan` is nonempty, else the plain confirm. Settle first -> confirm listing `Removal.settleFirst` lines (amounts via core's formatter) -> append settlements (as SettleAll does), sync, then `Removal.remove` with no redistributions. Redistribute -> **RedistributeScreen** (one section per currency; ticks for every other non-removed member; equal shares via `ffiRedistributeEqual`; each row shows old and new balance via core's formatter; `Edit amounts` swaps the shares for text fields; the tally line and the Remove button from `ffiRedistributionTally`) -> `Removal.remove`. Errors map through the Copy table's failures.
  - **Group page**: a `Group settings` row beside Activity and People (shared and local groups). **GroupSettingsScreen**: Name (rename via `.renameGroup` op, existing GroupStore path), Picture (the existing `GroupPictureSheet`), Settles in (read only), and Delete group when `RoleRules.canDelete` (shared) or always (local-only). The cloud confirm lists open balances from `ffiBalanceLine` (all members' nonzero entries, via core formatting) and calls `deleteGroup`, then marks the group read-only on this phone too (`ReadOnly(kind: "deleted", by: <you>)`); the local confirm calls `forgetGroup`.
  - **Read-only group**: the group page shows the banner (Copy table) at the top; Add people, the user-plus, person sheets, request cards and Group settings are absent; `Export this group` runs the export flow with `store.snapshot(group:)`; `Remove from this phone` confirms then `forgetGroup` and pops to Groups. Groups list rows for read-only groups stay (their balance line still reads).
  - **History**: a type-6 record shows in the group's Activity as `RolesCopy.moved(...)` (names from the group's members, amount via core's formatter of the sum).
- [ ] **Step 3:** tests pass; `make ios-app` green.
- [ ] **Step 4: Look** (CLAUDE.md §8.8, WORKLOG "How C2 was run"): throwaway simulator, `--emulators`, three accounts via `--sync-emulator-proof` on the throwaway plus seeded Firestore docs; screenshots of every mock state into `.drafts/c3/look/`. Note every gap.
- [ ] **Step 5: Commit** (explicit paths).

### Task 7: Android: the ledger and sync layer (twin of Task 5)

**Files:**
- Create: `android/app/src/main/java/com/vardrlabs/skipti/Roles.kt`, test `android/app/src/test/java/com/vardrlabs/skipti/RolesTest.kt`
- Modify: `LedgerStore.kt` (kv keys :149-207, `rebuildGroups` :795, `snapshot` :834), `CloudSync.kt` (groups listener :298-307, `refusal` :466-519, `record` :433-450, `pushAll` :183), `Push.kt` (`PushRoute` :121-145, `show` :257-275), `AppRoot.kt` (push routes :814-827, `IncomingInvites.createState()` beside :120), every `ffiBalanceLine`/`ffiMemberHasHistory`/`ffiOverview` caller (`ffiOverview` gained `redistributions` right after `settlements`: OverviewTab.kt:174; OverviewTab.kt:437, SettleAll.kt, SettleScreen.kt, BalanceScreen.kt, LedgerStore.kt removalRefusal :1194).
- If a new JVM test reads a file at run time, declare it as a test input (CLAUDE.md §7, D-162).

**Interfaces:** the Kotlin twins of Task 5, same names: `object RoleRules { data class Roles(val owner: String?, val admins: List<String>); enum class Role { OWNER, ADMIN, MEMBER, UNJOINED }; data class Offer(...); fun role(...); fun offer(...); fun canDelete(...); enum class RequestCard { DECIDE, ASKER, PERSON }; fun requestCard(...) }`, `object RolesCopy`, `object GroupRoles`, `object Notices` (modelled on `IncomingInvites` People.kt:439-499: `items by mutableStateOf`, `createState()`, `item(id, data)`, `listen(uid)`, `stop()`, `dismiss(item)`), `LedgerStore.loadRedistributions()` (in HLC order, as iOS), `readOnly()`, `markReadOnly()`, `forgetGroup()`, `snapshot(group)`, `CloudSync.Refusal.UNVERIFIED_REMOVAL`, `PushRoute.isGroup`.

- [ ] **Step 1:** compile fixes (the new balance argument), `make android-core` FIRST (CLAUDE.md §7.2: `assemblePlayDebug` alone packages the old core).
- [ ] **Step 2: Failing tests** `RolesTest.kt`: the same five tests as `RolesTests.swift`, same inputs, same expected values, same copy strings (`the_words_match_ios`).
- [ ] **Step 3:** `cd android && zsh -lic './gradlew :app:testPlayDebugUnitTest --tests "com.vardrlabs.skipti.RolesTest"'` fails; implement; passes. Check the result XML's mtime.
- [ ] **Step 4:** `make android-app` and `make android-test` green.
- [ ] **Step 5: Commit.**

### Task 8: Android: the screens (twin of Task 6)

**Files:**
- Create: `RemovalFlow.kt`, `GroupSettings.kt`
- Modify: `GroupScreens.kt` (PeopleScreen :336-470; fix MemberCopy's KDoc to name GroupPicker.swift), `OverviewTab.kt` (GroupPage :390), `AppRoot.kt` (routes `group-settings/{g}`, `remove/{g}/{m}`, `redistribute/{g}/{m}`), `SettleAll.kt`, the add-expense group picker, `Exporter.kt`/`ExportScreen.kt`.
- Test: extend `RolesTest.kt` with Task 6's Step 1 twins.

- [ ] **Step 1:** failing twin tests, then implement the screens exactly as Task 6 Step 2 (same order, same copy, AlertDialogs for the confirms, a ModalBottomSheet for the person sheet; state that must survive rotation uses `rememberSaveable`, as D-209's review required).
- [ ] **Step 2:** `make android-app`, `make android-test` green.
- [ ] **Step 3: Look** on a throwaway AVD (`-gpu host`), and on `Pixel_10_Pro_XL` (API 37) for the sheet near the navigation bar; screenshots into `.drafts/c3/look/`; shut every emulator down after.
- [ ] **Step 4: Commit.**

### Task 9: Prove, record, merge, deploy

- [ ] **Step 1: Final whole-branch review** (Opus), one fix wave, re-review.
- [ ] **Step 2: Sabotage** (`.drafts/c3/sabotage.py`, the C2 runner's shape; expected red SETS). Rows at least:
  1. `equal`: leftover to the LAST id instead of the first -> `equal_split_matches_the_python_goldens` + the ffi golden.
  2. `check`: drop the sum test -> `check_holds_every_rule`, tally test.
  3. `settlements`: swap payer/payee for s < 0 -> applying test + ffi balances test.
  4. `balance_line` ignores redistributions -> `a_redistribution_moves_the_balance...`.
  5. `groups.can('remove')`: let admins remove admins -> groups.test + groups-functions test 6.
  6. `groups.can('owner')`: let admins make owners -> both suites.
  7. `groups.can('admin-off')`: let admins -> both.
  8. `checkRemovalRecords`: accept any member -> groups.test.
  9. `removeMember`: drop `removal: true` -> groups-functions test 6.
  10. rules: drop the `createdAt <= at` bound -> rules test 10.
  11. rules: notices `create` allowed -> rules test 11.
  12. iOS `refusal`: accept removeMember without `removal` -> `testOnlyAFunctionWrittenRemovalComesIn`.
  13. iOS `RoleRules.offer`: admins get `makeOwner` -> `testASheetOffersWhatTheViewerMayDo`.
  14. Android twins of 12 and 13.
  15. purge: hand over to the first member even when an admin exists -> the hand-over test.
  Each row: zero compile errors, exactly the expected red set, restore and `cmp`.
- [ ] **Step 3: Full gate** in the background, machine free (CLAUDE.md §4): `GATE PASSED`, nothing skipped (bindings freshness fails pre-commit only if bindings are uncommitted: they are committed in Task 2).
- [ ] **Step 4: Record** D-210 through `.drafts/common/record_unit.py` / `scripts/buildlog_edit.py`: the row, §3 evidence verbatim, the sabotage table, §4 residue (below), the changelog. `python3 scripts/check-buildlog.py`.
- [ ] **Step 5:** branch `c3-roles`, `merge --no-ff` to main, push, confirm no Actions run.
- [ ] **Step 6: Deploy** (the founder's yes, full commands): `firebase deploy --only functions:groupRoles,functions:setAdmin,functions:makeOwner,functions:requestRemoval,functions:declineRemoval,functions:removeMember,functions:deleteGroup,functions:acceptInvite,functions:purgeDeletedAccounts,firestore:rules --project skipti-26 --non-interactive`; CLOUD-SECURITY §2 rows (7 new callables, 2 updated, rules: former reads + notices), a G-nn for each doubt; `scripts/cloud-audit.sh` expectations updated; WORKLOG.

**§4 residue to record (known, not done):** leaving a group (founder: no); no grace period before a deleted group's cloud copy goes; 0.4.0 builds skip type 0x06 (testers update first); notices never expire; D-186 removals already in the cloud carry no `removal` field, so a phone that syncs a shared group from scratch no longer applies them (that person reappears there); a record added on a phone after its removal and before the notice arrives stays on that phone only; `removeMember` trusts the remover's `member`/`you` pairing (an authorised remover could pair them wrongly; the role checks still hold on the uid); display names in notices are the caller's words (not checked against the group).
