//! Expense revisions (D-141; SPEC §5.7, §6.1): an edit or a tombstone of an
//! expense that already exists, and the ONE projection both shells use to turn
//! the record set into the ledger people see.
//!
//! SPEC decided the rules; this module only makes them executable:
//! - an edit is a new record, never an overwrite; the creation and every
//!   earlier revision stay in the record set until compaction (§5.7);
//! - a delete is a tombstone: the `deleted` field set to true, so Undo is the
//!   same field set back to false;
//! - each field takes the value from the revision with the greatest HLC that
//!   set it, so a category change on one phone and a note change on another
//!   both survive (§6.1);
//! - money is ONE register (payer, amount, currency, conversion and shares only
//!   make sense together: the shares must sum to the amount), and two money
//!   edits that did not see each other with different values are a CONFLICT the
//!   shells must put to the person: "Keep ₹1,200 or ₹1,250?". Balances use the
//!   last writer meanwhile, so every device still agrees byte for byte.
//!
//! Order independence is the property that matters: every device applies the
//! same records in whatever order they arrived and must reach the same ledger.
//! `project` therefore sorts everything by identity first and never looks at
//! arrival order.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;

/// A record's identity: its HLC, ordered (millis, counter, device), the same
/// total order last-writer-wins uses. `device` is 32 lowercase hex.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct VersionId {
    pub millis: u64,
    pub counter: u32,
    pub device: String,
}

impl VersionId {
    /// `millis:counter:device`, the shells' existing record identity string.
    pub fn render(&self) -> String {
        format!("{}:{}:{}", self.millis, self.counter, self.device)
    }

    pub fn parse(text: &str) -> Option<VersionId> {
        let mut parts = text.split(':');
        let millis = parts.next()?.parse::<u64>().ok()?;
        let counter = parts.next()?.parse::<u32>().ok()?;
        let device = parts.next()?;
        if parts.next().is_some() || !is_device_hex(device) {
            return None;
        }
        Some(VersionId {
            millis,
            counter,
            device: device.to_string(),
        })
    }
}

fn is_device_hex(s: &str) -> bool {
    s.len() == 32
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// The money register: everything that moves a balance, as one value.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MoneyValue {
    pub payer: String,
    pub paid_minor: i64,
    pub currency_code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub conversion: Option<ConversionValue>,
    pub shares: Vec<ShareValue>,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConversionValue {
    pub converted_minor: i64,
    pub converted_currency: String,
    pub rate_num: i64,
    pub rate_den: i64,
    pub rate_timestamp_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ShareValue {
    pub id: String,
    pub amount_minor: i64,
}

/// A revision's payload. Absent = unchanged. An empty `note` or `category`
/// clears it. Unknown fields are REFUSED (deny_unknown_fields): a revision
/// from a newer build is reported as unreadable rather than half-applied.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Revision {
    /// The identity of the expense's CREATION record.
    pub target: String,
    /// Every revision of this expense the editing device had applied. R1
    /// happened before R2 exactly when R1 is in R2's `seen`: each device
    /// lists everything it had, so no transitive walk is needed.
    #[serde(default)]
    pub seen: Vec<String>,
    /// The editor's participant id, for "last edited by" (§5.7).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub editor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub money: Option<MoneyValue>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deleted: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RevisionError {
    /// The payload is not a revision this build can read.
    Unreadable(String),
    /// A revision that changes nothing, or names a malformed identity.
    Invalid(String),
}

impl fmt::Display for RevisionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RevisionError::Unreadable(m) => write!(f, "unreadable revision: {m}"),
            RevisionError::Invalid(m) => write!(f, "invalid revision: {m}"),
        }
    }
}

fn validate(rev: &Revision) -> Result<(), RevisionError> {
    if VersionId::parse(&rev.target).is_none() {
        return Err(RevisionError::Invalid(format!("target {:?}", rev.target)));
    }
    if let Some(bad) = rev.seen.iter().find(|s| VersionId::parse(s).is_none()) {
        return Err(RevisionError::Invalid(format!("seen {bad:?}")));
    }
    if rev.money.is_none() && rev.note.is_none() && rev.category.is_none() && rev.deleted.is_none()
    {
        return Err(RevisionError::Invalid("changes nothing".into()));
    }
    // D-235 (decision 110 a): free text is bounded, so no revision outgrows
    // the cloud's record cap.
    for text in [&rev.note, &rev.category, &rev.editor]
        .into_iter()
        .flatten()
    {
        super::check::check_text(text).map_err(|e| RevisionError::Invalid(format!("{e}")))?;
    }
    // D-235 (decision 103): new money keeps the rules a creation keeps, so a
    // co-member's edit cannot unbalance the ledger or stop an export. Checked
    // on encode, decode and so in `project` (a bad edit is `rejected`).
    if let Some(money) = &rev.money {
        super::check::check_money(money)
            .map_err(|e| RevisionError::Invalid(format!("money: {e}")))?;
    }
    Ok(())
}

/// The exact bytes a revision record carries. Deterministic: field order is
/// the struct's, and `seen` is sorted, so two devices encoding the same
/// revision write the same bytes.
pub fn encode(rev: &Revision) -> Result<Vec<u8>, RevisionError> {
    validate(rev)?;
    let mut rev = rev.clone();
    rev.seen.sort();
    rev.seen.dedup();
    serde_json::to_vec(&rev).map_err(|e| RevisionError::Unreadable(e.to_string()))
}

pub fn decode(bytes: &[u8]) -> Result<Revision, RevisionError> {
    let rev: Revision =
        serde_json::from_slice(bytes).map_err(|e| RevisionError::Unreadable(e.to_string()))?;
    validate(&rev)?;
    Ok(rev)
}

/// An expense's creation record, as the shell decoded it.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Creation {
    pub id: VersionId,
    pub money: MoneyValue,
    pub group: Option<String>,
    pub note: Option<String>,
    pub category: Option<String>,
    pub timestamp_ms: Option<u64>,
    pub added_by: Option<String>,
}

/// A revision record as it sits in the vault: its identity and raw payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RevisionRecord {
    pub id: VersionId,
    pub bytes: Vec<u8>,
    /// D-158: the record's scope (its header's group id, None = personal).
    /// A revision applies only to an expense in the same scope: records now
    /// arrive from other people through a group's log, and a member of one
    /// group must not be able to edit or delete an expense in another group
    /// or a personal one.
    pub scope: Option<String>,
}

/// One expense as people see it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Projected {
    /// The creation record's identity: the expense's stable id.
    pub id: String,
    pub money: MoneyValue,
    pub group: Option<String>,
    pub note: Option<String>,
    pub category: Option<String>,
    pub timestamp_ms: Option<u64>,
    pub added_by: Option<String>,
    /// The creation's HLC millis: when it entered the ledger.
    pub added_at_ms: u64,
    pub deleted: bool,
    pub last_edited_at_ms: Option<u64>,
    pub last_edited_by: Option<String>,
    /// Every revision id applied, sorted: the next edit's `seen`.
    pub seen: Vec<String>,
    /// Empty, or ≥ 2 distinct money values that did not see each other,
    /// ordered by revision identity. The shells must ask which to keep.
    pub money_conflict: Vec<MoneyValue>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Projection {
    /// In creation-identity order (the ledger's order today).
    pub expenses: Vec<Projected>,
    /// Revision ids whose payload could not be read (a newer build, or damage).
    /// Reported, never silently dropped.
    pub rejected: Vec<String>,
    /// Revision ids whose target has not arrived yet; applied once it does.
    pub pending: Vec<String>,
}

/// Records → ledger. Pure and order-independent: the same set of records in
/// any order gives the same projection.
pub fn project(creations: &[Creation], revisions: &[RevisionRecord]) -> Projection {
    let mut created: BTreeMap<VersionId, &Creation> = BTreeMap::new();
    for c in creations {
        // Idempotent by identity, like the vault's own record index. Two
        // different creations can never share an HLC; if a damaged set ever
        // did, the first by value wins so the result is still deterministic.
        created
            .entry(c.id.clone())
            .and_modify(|kept| {
                if c < *kept {
                    *kept = c;
                }
            })
            .or_insert(c);
    }

    let mut decoded: BTreeMap<VersionId, Revision> = BTreeMap::new();
    let mut out = Projection::default();
    let mut unique: BTreeMap<VersionId, &RevisionRecord> = BTreeMap::new();
    for r in revisions {
        unique
            .entry(r.id.clone())
            .and_modify(|kept| {
                if r.bytes < kept.bytes {
                    *kept = r;
                }
            })
            .or_insert(r);
    }
    for (id, record) in &unique {
        match decode(&record.bytes) {
            Ok(rev) => {
                decoded.insert(id.clone(), rev);
            }
            Err(_) => out.rejected.push(id.render()),
        }
    }

    let mut by_target: BTreeMap<VersionId, Vec<(&VersionId, &Revision)>> = BTreeMap::new();
    for (id, rev) in &decoded {
        let target = VersionId::parse(&rev.target).expect("validated");
        match created.get(&target) {
            // D-158: out of scope is refused and reported, never applied.
            Some(c) if c.group != unique[id].scope => out.rejected.push(id.render()),
            Some(_) => by_target.entry(target).or_default().push((id, rev)),
            None => out.pending.push(id.render()),
        }
    }

    for (cid, creation) in &created {
        let revs = by_target.get(cid).map(Vec::as_slice).unwrap_or(&[]);
        // D-235 (decision 104 amended): the last writer of a field is CAUSAL.
        // A revision that saw another (directly or through revisions in
        // between) supersedes it for every field it writes, whatever the two
        // stamps say; among the field's writers that nothing else here saw
        // (the heads), the greatest identity wins, as before. So a phone whose
        // clock stayed behind a far-future or maximum stamp (the 24 h receive
        // bound) can still edit after it, and a maximum-stamped edit cannot
        // freeze an expense.
        let causal = Causal::new(revs);
        let last = |pick: fn(&Revision) -> bool| causal.last_writer(revs, pick);
        let money = last(|r| r.money.is_some())
            .map(|(_, r)| r.money.clone().expect("picked"))
            .unwrap_or_else(|| creation.money.clone());
        let note = match last(|r| r.note.is_some()) {
            Some((_, r)) => r.note.clone().filter(|n| !n.is_empty()),
            None => creation.note.clone(),
        };
        let category = match last(|r| r.category.is_some()) {
            Some((_, r)) => r.category.clone().filter(|c| !c.is_empty()),
            None => creation.category.clone(),
        };
        let deleted = last(|r| r.deleted.is_some())
            .map(|(_, r)| r.deleted.expect("picked"))
            .unwrap_or(false);
        let newest = causal.last_writer(revs, |_| true);

        out.expenses.push(Projected {
            id: cid.render(),
            money,
            group: creation.group.clone(),
            note,
            category,
            timestamp_ms: creation.timestamp_ms,
            added_by: creation.added_by.clone(),
            added_at_ms: cid.millis,
            deleted,
            last_edited_at_ms: newest.map(|(id, _)| id.millis),
            last_edited_by: newest.and_then(|(_, r)| r.editor.clone()),
            seen: revs.iter().map(|(id, _)| id.render()).collect(),
            money_conflict: money_conflict(revs, &causal),
        });
    }
    out.rejected.sort();
    out.pending.sort();
    out
}

/// D-235: the happened-before relation among one expense's revisions: R1
/// before R2 when R1 is in R2's `seen`, or in the `seen` of a revision in R2's
/// `seen`, and so on (only revisions present here count; a `seen` entry this
/// phone does not hold has nothing to carry). Pure and order-independent:
/// built from the set alone.
struct Causal {
    /// For each revision (by its index in identity order), the indices of
    /// the revisions it saw directly. Never itself.
    parents: Vec<Vec<usize>>,
}

impl Causal {
    fn new(revs: &[(&VersionId, &Revision)]) -> Causal {
        let index: BTreeMap<&VersionId, usize> = revs
            .iter()
            .enumerate()
            .map(|(i, (id, _))| (*id, i))
            .collect();
        let parents = revs
            .iter()
            .enumerate()
            .map(|(i, (_, rev))| {
                let mut p: Vec<usize> = rev
                    .seen
                    .iter()
                    .filter_map(|s| VersionId::parse(s))
                    .filter_map(|v| index.get(&v).copied())
                    .filter(|&j| j != i)
                    .collect();
                p.sort_unstable();
                p.dedup();
                p
            })
            .collect();
        Causal { parents }
    }

    /// The indices in `writers` that no member of `writers` saw, directly or
    /// transitively. If every writer was seen by another (only a cycle of
    /// `seen` lists can do that, and no honest phone writes one), all of them:
    /// the result is never empty when `writers` is not.
    fn heads(&self, writers: &[usize]) -> Vec<usize> {
        let mut seen = vec![false; self.parents.len()];
        let mut stack: Vec<usize> = writers
            .iter()
            .flat_map(|&w| self.parents[w].iter().copied())
            .collect();
        while let Some(i) = stack.pop() {
            if !seen[i] {
                seen[i] = true;
                stack.extend(self.parents[i].iter().copied());
            }
        }
        let heads: Vec<usize> = writers.iter().copied().filter(|&w| !seen[w]).collect();
        if heads.is_empty() {
            writers.to_vec()
        } else {
            heads
        }
    }

    /// The last writer of the field `pick` reads: the head with the greatest
    /// identity (`revs` is in identity order).
    fn last_writer<'a>(
        &self,
        revs: &'a [(&'a VersionId, &'a Revision)],
        pick: fn(&Revision) -> bool,
    ) -> Option<&'a (&'a VersionId, &'a Revision)> {
        let writers: Vec<usize> = (0..revs.len()).filter(|&i| pick(revs[i].1)).collect();
        self.heads(&writers).into_iter().max().map(|i| &revs[i])
    }
}

/// The money values of the money-setting revisions that no other
/// money-setting revision saw (the heads; D-235: directly or through the
/// revisions in between), deduplicated, in identity order. Fewer than two
/// distinct values is no conflict.
fn money_conflict(revs: &[(&VersionId, &Revision)], causal: &Causal) -> Vec<MoneyValue> {
    let setters: Vec<usize> = (0..revs.len())
        .filter(|&i| revs[i].1.money.is_some())
        .collect();
    let mut heads: Vec<MoneyValue> = Vec::new();
    for i in causal.heads(&setters) {
        let value = revs[i].1.money.clone().expect("filtered");
        if !heads.contains(&value) {
            heads.push(value);
        }
    }
    if heads.len() < 2 {
        Vec::new()
    } else {
        heads
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dev(n: u8) -> String {
        format!("{n:032x}")
    }

    fn vid(millis: u64, counter: u32, device: u8) -> VersionId {
        VersionId {
            millis,
            counter,
            device: dev(device),
        }
    }

    fn money(amount: i64) -> MoneyValue {
        let half = amount / 2;
        MoneyValue {
            payer: dev(1),
            paid_minor: amount,
            currency_code: "INR".into(),
            conversion: None,
            shares: vec![
                ShareValue {
                    id: dev(1),
                    amount_minor: half,
                },
                ShareValue {
                    id: dev(2),
                    amount_minor: amount - half,
                },
            ],
        }
    }

    fn creation(id: &VersionId, amount: i64) -> Creation {
        Creation {
            id: id.clone(),
            money: money(amount),
            group: Some(dev(9)),
            note: Some("dinner".into()),
            category: Some("food.restaurant".into()),
            timestamp_ms: Some(id.millis),
            added_by: Some(dev(1)),
        }
    }

    fn change(target: &VersionId, seen: &[&VersionId]) -> Revision {
        Revision {
            target: target.render(),
            seen: seen.iter().map(|v| v.render()).collect(),
            editor: None,
            money: None,
            note: None,
            category: None,
            deleted: None,
        }
    }

    fn rec(id: &VersionId, rev: Revision) -> RevisionRecord {
        RevisionRecord {
            id: id.clone(),
            bytes: encode(&rev).expect("valid"),
            scope: Some(dev(9)),
        }
    }

    fn only(p: &Projection) -> &Projected {
        assert_eq!(p.expenses.len(), 1, "{p:?}");
        &p.expenses[0]
    }

    // ---- the codec ----------------------------------------------------------

    /// Written out by hand: field order is the struct's, `seen` is sorted,
    /// absent fields are omitted, `seen` is always present.
    #[test]
    fn a_revision_encodes_to_exactly_these_bytes() {
        let mut r = change(&vid(1000, 0, 1), &[&vid(1100, 0, 2), &vid(1050, 0, 1)]);
        r.editor = Some(dev(3));
        r.note = Some("taxi".into());
        let expected = concat!(
            r#"{"target":"1000:0:00000000000000000000000000000001","#,
            r#""seen":["1050:0:00000000000000000000000000000001","#,
            r#""1100:0:00000000000000000000000000000002"],"#,
            r#""editor":"00000000000000000000000000000003","note":"taxi"}"#
        );
        let bytes = encode(&r).unwrap();
        assert_eq!(String::from_utf8(bytes.clone()).unwrap(), expected);
        let back = decode(&bytes).unwrap();
        assert_eq!(
            back.seen[0],
            vid(1050, 0, 1).render(),
            "decoded seen is the sorted list"
        );
        assert_eq!(back.note.as_deref(), Some("taxi"));
    }

    #[test]
    fn a_money_revision_uses_the_ledgers_field_names() {
        let mut r = change(&vid(1000, 0, 1), &[]);
        r.money = Some(money(900));
        let text = String::from_utf8(encode(&r).unwrap()).unwrap();
        assert!(
            text.contains(concat!(
                r#""money":{"payer":"00000000000000000000000000000001","paidMinor":900,"#,
                r#""currencyCode":"INR","shares":[{"id":"00000000000000000000000000000001","#,
                r#""amountMinor":450},{"id":"00000000000000000000000000000002","amountMinor":450}]}"#
            )),
            "{text}"
        );
    }

    #[test]
    fn unreadable_or_empty_revisions_are_refused_with_a_reason() {
        let target = vid(1000, 0, 1).render();
        let cases: &[(&str, bool)] = &[
            ("not json", true),
            (
                r#"{"target":"1000:0:00000000000000000000000000000001","note":"x","date":1}"#,
                true,
            ),
            (
                r#"{"target":"1000:0:00000000000000000000000000000001"}"#,
                false,
            ),
            (r#"{"target":"1000:0:XYZ","note":"x"}"#, false),
            (
                r#"{"target":"1000:0:00000000000000000000000000000001","seen":["nope"],"note":"x"}"#,
                false,
            ),
        ];
        for (text, unreadable) in cases {
            match decode(text.as_bytes()) {
                Err(RevisionError::Unreadable(_)) => assert!(*unreadable, "{text}"),
                Err(RevisionError::Invalid(_)) => assert!(!*unreadable, "{text}"),
                Ok(r) => panic!("must refuse {text}: {r:?}"),
            }
        }
        assert!(target.len() > 2);
        assert!(
            encode(&change(&vid(1000, 0, 1), &[])).is_err(),
            "a revision that changes nothing"
        );
    }

    // ---- the projection -----------------------------------------------------

    #[test]
    fn creations_alone_project_as_themselves_in_identity_order() {
        let a = vid(2000, 0, 2);
        let b = vid(1000, 3, 1);
        let p = project(&[creation(&a, 600), creation(&b, 900)], &[]);
        assert_eq!(p.expenses.len(), 2);
        assert_eq!(p.expenses[0].id, "1000:3:00000000000000000000000000000001");
        assert_eq!(p.expenses[0].money, money(900));
        assert_eq!(p.expenses[0].note.as_deref(), Some("dinner"));
        assert_eq!(p.expenses[0].added_at_ms, 1000);
        assert!(!p.expenses[0].deleted);
        assert!(p.expenses[0].seen.is_empty());
        assert!(p.expenses[0].money_conflict.is_empty());
        assert_eq!(p.expenses[0].last_edited_at_ms, None);
        assert_eq!(p.expenses[1].id, a.render());
    }

    /// SPEC §6.1: "a category change on one device and a note change on
    /// another both survive".
    #[test]
    fn a_note_edit_and_a_category_edit_on_two_phones_both_survive() {
        let c = vid(1000, 0, 1);
        let (e1, e2) = (vid(2000, 0, 1), vid(2000, 0, 2));
        let mut n = change(&c, &[]);
        n.note = Some("taxi to airport".into());
        let mut k = change(&c, &[]);
        k.category = Some("transport.taxi".into());
        let p = project(&[creation(&c, 900)], &[rec(&e1, n), rec(&e2, k)]);
        let e = only(&p);
        assert_eq!(e.note.as_deref(), Some("taxi to airport"));
        assert_eq!(e.category.as_deref(), Some("transport.taxi"));
        assert_eq!(e.seen, vec![e1.render(), e2.render()]);
    }

    /// The same field edited twice without either seeing the other: the
    /// greater HLC wins: millis first, then counter, then device.
    #[test]
    fn the_same_field_goes_to_the_later_hlc() {
        let c = vid(1000, 0, 1);
        for (x, y, winner) in [
            (vid(2000, 0, 9), vid(2001, 0, 1), "second"),
            (vid(2000, 5, 1), vid(2000, 4, 9), "first"),
            (vid(2000, 1, 1), vid(2000, 1, 2), "second"),
        ] {
            let mut first = change(&c, &[]);
            first.note = Some("first".into());
            let mut second = change(&c, &[]);
            second.note = Some("second".into());
            let p = project(&[creation(&c, 900)], &[rec(&x, first), rec(&y, second)]);
            assert_eq!(only(&p).note.as_deref(), Some(winner), "{x:?} vs {y:?}");
        }
    }

    /// D-235 round 2 (decision 104 amended): the causal last writer, pinned
    /// from `.drafts/d235/a/causal_goldens.py` (an independent derivation by
    /// transitive closure). Per scenario: (name, revisions as (label, millis,
    /// counter, device, seen labels, money, note, category, deleted)), then
    /// the expected money, note, category, deleted, newest label and conflict.
    #[test]
    fn d235_the_last_writer_is_causal_whatever_the_stamps() {
        type Row = (
            &'static str,
            u64,
            u32,
            u8,
            &'static [&'static str],
            Option<i64>,
            Option<&'static str>,
            Option<&'static str>,
            Option<bool>,
        );
        type Want = (
            i64,
            Option<&'static str>,
            Option<&'static str>,
            bool,
            &'static str,
            &'static [i64],
        );
        const MAX_MS: u64 = i64::MAX as u64;
        let now = 1_790_000_000_000u64;
        let cases: Vec<(&str, Vec<Row>, Want)> = vec![
            (
                "far_future",
                vec![
                    (
                        "A",
                        9_000_000_000_000,
                        0,
                        2,
                        &[],
                        Some(2000),
                        Some("future"),
                        None,
                        None,
                    ),
                    ("B", now, 0, 3, &["A"], Some(3000), Some("now"), None, None),
                ],
                (3000, Some("now"), Some("food.restaurant"), false, "B", &[]),
            ),
            (
                "max_stamp",
                vec![
                    (
                        "A",
                        MAX_MS,
                        u32::MAX,
                        2,
                        &[],
                        Some(2000),
                        None,
                        None,
                        Some(true),
                    ),
                    ("B", now, 0, 3, &["A"], Some(3000), None, None, Some(false)),
                ],
                (
                    3000,
                    Some("dinner"),
                    Some("food.restaurant"),
                    false,
                    "B",
                    &[],
                ),
            ),
            (
                "concurrent",
                vec![
                    ("A", 2000, 0, 1, &[], Some(1200), None, None, None),
                    ("B", 2500, 0, 2, &[], Some(1250), None, None, None),
                ],
                (
                    1250,
                    Some("dinner"),
                    Some("food.restaurant"),
                    false,
                    "B",
                    &[1200, 1250],
                ),
            ),
            (
                "unseen_loses",
                vec![
                    ("A", 5000, 0, 2, &[], None, Some("x"), None, None),
                    ("B", 3000, 0, 3, &[], None, Some("y"), None, None),
                ],
                (1200, Some("x"), Some("food.restaurant"), false, "A", &[]),
            ),
            (
                "transitive",
                vec![
                    ("A", 9000, 0, 2, &[], None, Some("a"), None, None),
                    (
                        "B",
                        1500,
                        0,
                        3,
                        &["A"],
                        None,
                        None,
                        Some("transport.taxi"),
                        None,
                    ),
                    ("C", 1200, 0, 4, &["B"], None, Some("c"), None, None),
                ],
                (1200, Some("c"), Some("transport.taxi"), false, "C", &[]),
            ),
            (
                "cycle",
                vec![
                    ("X1", 3000, 0, 5, &["X2"], None, Some("one"), None, None),
                    ("X2", 3100, 0, 6, &["X1"], None, Some("two"), None, None),
                ],
                (1200, Some("two"), Some("food.restaurant"), false, "X2", &[]),
            ),
            (
                "self_seen",
                vec![
                    ("X", 3000, 0, 5, &["X"], None, Some("self"), None, None),
                    ("Y", 2000, 0, 6, &[], None, Some("other"), None, None),
                ],
                (1200, Some("self"), Some("food.restaurant"), false, "X", &[]),
            ),
            (
                "causal_and_concurrent",
                vec![
                    ("A", 9000, 0, 2, &[], Some(2000), None, None, None),
                    ("B", 2000, 0, 3, &["A"], Some(3000), None, None, None),
                    ("D", 2500, 0, 4, &[], Some(4000), None, None, None),
                ],
                (
                    4000,
                    Some("dinner"),
                    Some("food.restaurant"),
                    false,
                    "D",
                    &[3000, 4000],
                ),
            ),
        ];
        let c = vid(1000, 0, 1);
        for (name, rows, want) in cases {
            let id_of = |label: &str| {
                let r = rows.iter().find(|r| r.0 == label).expect("label");
                vid(r.1, r.2, r.3)
            };
            let mut records: Vec<RevisionRecord> = rows
                .iter()
                .map(|r| {
                    let seen: Vec<VersionId> = r.4.iter().map(|l| id_of(l)).collect();
                    let mut rev = change(&c, &seen.iter().collect::<Vec<_>>());
                    rev.money = r.5.map(money);
                    rev.note = r.6.map(str::to_string);
                    rev.category = r.7.map(str::to_string);
                    rev.deleted = r.8;
                    rev.editor = Some(dev(r.3));
                    rec(&id_of(r.0), rev)
                })
                .collect();
            for pass in 0..2 {
                if pass == 1 {
                    records.reverse();
                }
                let p = project(&[creation(&c, 1200)], &records);
                let e = only(&p);
                assert_eq!(e.money, money(want.0), "{name} money");
                assert_eq!(e.note.as_deref(), want.1, "{name} note");
                assert_eq!(e.category.as_deref(), want.2, "{name} category");
                assert_eq!(e.deleted, want.3, "{name} deleted");
                let newest = id_of(want.4);
                assert_eq!(e.last_edited_at_ms, Some(newest.millis), "{name} newest");
                assert_eq!(
                    e.last_edited_by.as_deref(),
                    Some(newest.device.as_str()),
                    "{name} editor"
                );
                let conflict: Vec<MoneyValue> = want.5.iter().map(|a| money(*a)).collect();
                assert_eq!(e.money_conflict, conflict, "{name} conflict");
                assert_eq!(e.seen.len(), rows.len(), "{name} seen");
            }
        }
    }

    /// D-235 round 3 (decision 110 b): a phone whose stored HLC is three
    /// days ahead wrote an edit there, then stamps its next edit at wall +
    /// 23 h (lowered, new era). The next edit saw the first, so it wins.
    #[test]
    fn d235_an_edit_after_a_lowered_clock_still_supersedes_what_it_saw() {
        let wall = 1_790_000_000_000u64;
        let day = 24 * 60 * 60 * 1000u64;
        let mut clock = crate::straumr::Hlc {
            millis: wall + 3 * day,
            counter: 5,
            device_id: [0; 16],
        };
        let first = vid(clock.millis, clock.counter, 1);
        clock.tick(wall);
        assert!(clock.millis <= wall + crate::straumr::MAX_OWN_LEAD_MS);
        let second = vid(clock.millis, clock.counter, 1);
        assert!(second < first, "the lowered stamp sorts below");
        let c = vid(1000, 0, 1);
        let mut a = change(&c, &[]);
        a.money = Some(money(2000));
        a.note = Some("ahead".into());
        let mut b = change(&c, &[&first]);
        b.money = Some(money(3000));
        b.note = Some("lowered".into());
        let p = project(&[creation(&c, 1200)], &[rec(&first, a), rec(&second, b)]);
        let e = only(&p);
        assert_eq!(e.money, money(3000));
        assert_eq!(e.note.as_deref(), Some("lowered"));
        assert!(e.money_conflict.is_empty());
        assert_eq!(e.last_edited_at_ms, Some(second.millis));
    }

    /// D-235 (decision 110 a): a revision's free text is at most 1,000
    /// characters; at the bound, in the worst JSON form, the record is far
    /// under the cloud's 192 KiB cap; past it, encode refuses and the
    /// projection rejects.
    #[test]
    fn d235_a_revisions_free_text_is_bounded() {
        use crate::straumr::check::TEXT_MAX_CHARS;
        let c = vid(1000, 0, 1);
        let worst = "\u{1}".repeat(TEXT_MAX_CHARS);
        let mut full = change(&c, &[]);
        full.note = Some(worst.clone());
        full.category = Some(worst.clone());
        full.editor = Some(worst);
        full.money = Some(money(1200));
        let bytes = encode(&full).expect("at the bound");
        assert!(bytes.len() < 32 * 1024, "{} bytes", bytes.len());
        let long = "x".repeat(TEXT_MAX_CHARS + 1);
        for field in 0..3 {
            let mut r = change(&c, &[]);
            match field {
                0 => r.note = Some(long.clone()),
                1 => r.category = Some(long.clone()),
                _ => {
                    r.editor = Some(long.clone());
                    r.note = Some("x".into());
                }
            }
            assert!(
                matches!(encode(&r), Err(RevisionError::Invalid(_))),
                "{field}"
            );
            let raw = serde_json::to_vec(&r).unwrap();
            assert!(
                matches!(decode(&raw), Err(RevisionError::Invalid(_))),
                "{field}"
            );
            let e = vid(2000, 0, 2);
            let p = project(
                &[creation(&c, 900)],
                &[RevisionRecord {
                    id: e.clone(),
                    bytes: raw,
                    scope: Some(dev(9)),
                }],
            );
            assert_eq!(p.rejected, vec![e.render()], "{field}");
        }
    }

    #[test]
    fn an_empty_note_clears_it() {
        let c = vid(1000, 0, 1);
        let mut r = change(&c, &[]);
        r.note = Some(String::new());
        r.category = Some(String::new());
        let p = project(&[creation(&c, 900)], &[rec(&vid(2000, 0, 1), r)]);
        assert_eq!(only(&p).note, None);
        assert_eq!(only(&p).category, None);
    }

    #[test]
    fn a_money_edit_moves_the_money_and_names_its_editor() {
        let c = vid(1000, 0, 1);
        let e = vid(2000, 0, 2);
        let mut r = change(&c, &[]);
        r.money = Some(money(1250));
        r.editor = Some(dev(2));
        let p = project(&[creation(&c, 1200)], &[rec(&e, r)]);
        assert_eq!(only(&p).money, money(1250));
        assert_eq!(only(&p).last_edited_at_ms, Some(2000));
        assert_eq!(only(&p).last_edited_by.as_deref(), Some(dev(2).as_str()));
        assert!(only(&p).money_conflict.is_empty());
    }

    /// SPEC §6.1: "Two devices set different amounts. Keep ₹1,200 or
    /// ₹1,250?": surfaced, never silently resolved. Balances meanwhile use
    /// the last writer, so every device still computes the same numbers.
    #[test]
    fn two_money_edits_that_did_not_see_each_other_are_a_conflict() {
        let c = vid(1000, 0, 1);
        let (a, b) = (vid(2000, 0, 1), vid(2500, 0, 2));
        let mut ra = change(&c, &[]);
        ra.money = Some(money(1200));
        let mut rb = change(&c, &[]);
        rb.money = Some(money(1250));
        let p = project(&[creation(&c, 1000)], &[rec(&b, rb), rec(&a, ra)]);
        assert_eq!(only(&p).money_conflict, vec![money(1200), money(1250)]);
        assert_eq!(only(&p).money, money(1250), "last writer meanwhile");
    }

    #[test]
    fn concurrent_money_edits_that_agree_are_no_conflict() {
        let c = vid(1000, 0, 1);
        let mut ra = change(&c, &[]);
        ra.money = Some(money(1250));
        let rb = ra.clone();
        let p = project(
            &[creation(&c, 1000)],
            &[rec(&vid(2000, 0, 1), ra), rec(&vid(2000, 0, 2), rb)],
        );
        assert!(only(&p).money_conflict.is_empty());
    }

    #[test]
    fn a_money_edit_that_saw_the_other_is_no_conflict() {
        let c = vid(1000, 0, 1);
        let (a, b) = (vid(2000, 0, 1), vid(1900, 0, 2));
        let mut ra = change(&c, &[]);
        ra.money = Some(money(1200));
        // b's clock is BEHIND a's, but b saw a: the later edit in causal
        // terms, no conflict. D-235 round 2: and b's value wins (before, LWW
        // picked a's by HLC and b's edit silently reverted).
        let mut rb = change(&c, &[&a]);
        rb.money = Some(money(1250));
        let p = project(&[creation(&c, 1000)], &[rec(&a, ra), rec(&b, rb)]);
        assert!(only(&p).money_conflict.is_empty());
        assert_eq!(only(&p).money, money(1250));
        assert_eq!(only(&p).last_edited_at_ms, Some(1900));
    }

    #[test]
    fn keeping_one_amount_resolves_the_conflict() {
        let c = vid(1000, 0, 1);
        let (a, b, k) = (vid(2000, 0, 1), vid(2000, 0, 2), vid(3000, 0, 1));
        let mut ra = change(&c, &[]);
        ra.money = Some(money(1200));
        let mut rb = change(&c, &[]);
        rb.money = Some(money(1250));
        let mut keep = change(&c, &[&a, &b]);
        keep.money = Some(money(1200));
        let p = project(
            &[creation(&c, 1000)],
            &[rec(&a, ra), rec(&b, rb), rec(&k, keep)],
        );
        assert!(only(&p).money_conflict.is_empty());
        assert_eq!(only(&p).money, money(1200));
    }

    #[test]
    fn delete_is_a_tombstone_and_undo_brings_it_back() {
        let c = vid(1000, 0, 1);
        let (d, u) = (vid(2000, 0, 1), vid(3000, 0, 1));
        let mut del = change(&c, &[]);
        del.deleted = Some(true);
        let deleted = project(&[creation(&c, 900)], &[rec(&d, del.clone())]);
        assert!(only(&deleted).deleted);
        let mut undo = change(&c, &[&d]);
        undo.deleted = Some(false);
        let back = project(&[creation(&c, 900)], &[rec(&d, del), rec(&u, undo)]);
        assert!(!only(&back).deleted);
        assert_eq!(
            only(&back).money,
            money(900),
            "the expense itself is untouched"
        );
    }

    #[test]
    fn a_revision_that_arrives_before_its_expense_waits_for_it() {
        let c = vid(1000, 0, 1);
        let e = vid(2000, 0, 2);
        let mut r = change(&c, &[]);
        r.note = Some("taxi".into());
        let early = project(&[], &[rec(&e, r.clone())]);
        assert!(early.expenses.is_empty());
        assert_eq!(early.pending, vec![e.render()]);
        let later = project(&[creation(&c, 900)], &[rec(&e, r)]);
        assert!(later.pending.is_empty());
        assert_eq!(only(&later).note.as_deref(), Some("taxi"));
    }

    #[test]
    fn an_unreadable_revision_is_reported_and_changes_nothing() {
        let c = vid(1000, 0, 1);
        let bad = RevisionRecord {
            id: vid(2000, 0, 2),
            bytes: br#"{"target":"1000:0:00000000000000000000000000000001","deleted":true,"x":1}"#
                .to_vec(),
            scope: Some(dev(9)),
        };
        let p = project(&[creation(&c, 900)], &[bad]);
        assert_eq!(p.rejected, vec![vid(2000, 0, 2).render()]);
        assert!(
            !only(&p).deleted,
            "a revision this build cannot read is not applied"
        );
    }

    /// D-158: a revision that arrives with another scope than its expense
    /// (another group's log, or the personal log for a group's expense) is
    /// refused and reported. The same revision in the right scope applies.
    #[test]
    fn a_revision_from_another_scope_is_refused() {
        let c = vid(1000, 0, 1);
        let mut delete = change(&c, &[]);
        delete.deleted = Some(true);
        for scope in [None, Some(dev(8))] {
            let foreign = RevisionRecord {
                scope: scope.clone(),
                ..rec(&vid(2000, 0, 2), delete.clone())
            };
            let p = project(&[creation(&c, 900)], &[foreign]);
            assert_eq!(p.rejected, vec![vid(2000, 0, 2).render()], "{scope:?}");
            assert!(!only(&p).deleted, "{scope:?}");
            assert!(only(&p).seen.is_empty());
        }
        let p = project(&[creation(&c, 900)], &[rec(&vid(2000, 0, 2), delete)]);
        assert!(p.rejected.is_empty());
        assert!(only(&p).deleted);
    }

    #[test]
    fn repeated_records_change_nothing() {
        let c = vid(1000, 0, 1);
        let e = vid(2000, 0, 2);
        let mut r = change(&c, &[]);
        r.note = Some("taxi".into());
        let once = project(&[creation(&c, 900)], &[rec(&e, r.clone())]);
        let twice = project(
            &[creation(&c, 900), creation(&c, 900)],
            &[rec(&e, r.clone()), rec(&e, r)],
        );
        assert_eq!(once, twice);
    }

    /// SPEC §16.4's acceptance: three devices, offline edits, applied in any
    /// order, converge to the same ledger. 300 seeded shuffles of a scenario
    /// with concurrent notes, a money conflict and its resolution, a delete
    /// and an undo, and a revision whose expense is also present.
    #[test]
    fn three_devices_converge_whatever_order_the_records_arrive_in() {
        let (c1, c2, c3) = (vid(1000, 0, 1), vid(1000, 0, 2), vid(1001, 0, 3));
        let creations = vec![creation(&c1, 900), creation(&c2, 600), creation(&c3, 300)];
        let mut revs = Vec::new();
        let mut add =
            |id: VersionId, target: &VersionId, seen: &[&VersionId], f: &dyn Fn(&mut Revision)| {
                let mut r = change(target, seen);
                f(&mut r);
                revs.push(rec(&id, r));
                id
            };
        let a1 = add(vid(2000, 0, 1), &c1, &[], &|r| r.note = Some("a".into()));
        let b1 = add(vid(2000, 0, 2), &c1, &[], &|r| {
            r.category = Some("x.y".into())
        });
        let m1 = add(vid(2100, 0, 1), &c1, &[&a1], &|r| {
            r.money = Some(money(1200))
        });
        let m2 = add(vid(2100, 1, 3), &c1, &[&b1], &|r| {
            r.money = Some(money(1250))
        });
        add(vid(2200, 0, 2), &c1, &[&a1, &b1, &m1, &m2], &|r| {
            r.money = Some(money(1250))
        });
        let d = add(vid(2300, 0, 3), &c2, &[], &|r| r.deleted = Some(true));
        add(vid(2400, 0, 1), &c2, &[&d], &|r| r.deleted = Some(false));
        add(vid(2450, 0, 2), &c3, &[], &|r| r.deleted = Some(true));
        add(vid(2450, 0, 3), &c3, &[], &|r| r.note = Some("c3".into()));
        let m3 = add(vid(2500, 0, 1), &c3, &[], &|r| r.money = Some(money(301)));
        let _ = m3;
        add(vid(2500, 0, 2), &c3, &[], &|r| r.money = Some(money(302)));

        let reference = project(&creations, &revs);
        assert!(reference.expenses[0].money_conflict.is_empty(), "resolved");
        assert_eq!(reference.expenses[0].money, money(1250));
        assert!(!reference.expenses[1].deleted, "undone");
        assert!(reference.expenses[2].deleted);
        assert_eq!(
            reference.expenses[2].money_conflict,
            vec![money(301), money(302)]
        );

        let mut state: u64 = 0x2545_f491_4f6c_dd1d;
        let mut next = move || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        for _ in 0..300 {
            let mut cs = creations.clone();
            let mut rs = revs.clone();
            for i in (1..cs.len()).rev() {
                cs.swap(i, (next() % (i as u64 + 1)) as usize);
            }
            for i in (1..rs.len()).rev() {
                rs.swap(i, (next() % (i as u64 + 1)) as usize);
            }
            assert_eq!(project(&cs, &rs), reference);
        }
    }
}
