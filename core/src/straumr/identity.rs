//! One "you" per account (D-154; the founder, 2026-09-24: "one you, merge").
//!
//! Two devices of one account can each have minted their own "you" before
//! they met (someone used a phone and a tablet apart, then signed both in).
//! D-152's projection names the account's "you" (the earliest `you` record)
//! and reports the others; this module makes them ONE person in the ledger
//! people see. Records are never rewritten: the merge happens in the
//! projection, so every device that holds the same records shows the same
//! merged ledger, and nothing any device wrote is lost.
//!
//! The rules:
//! - an id that some device wrote as "you" reads as the account's "you";
//! - shares that collapse onto "you" are ADDED, in the position of the first,
//!   so the shares still sum to the amount paid;
//! - a settlement between two of your own ids is not a payment: it leaves the
//!   projected ledger (core refuses a settlement from someone to themselves,
//!   and it would move no balance anyway). Its record stays.
//!
//! D-229 runs the same merge the other way when WRITING into a group where
//! the account's "you" is not readable to the other members: `Yous::new(place,
//! mine)` reads every id of the account as its place there
//! (`ffi_write_as_expense`, `ffi_write_as_redistribution`).

use std::collections::{BTreeMap, BTreeSet};

/// The account's "you" and the other ids its devices wrote as "you".
///
/// D-158: and, per group, the other people who joined through an invite. A
/// joiner's app writes its own "you" id into a shared group; everyone else's
/// group knows that person as the member they claimed (D-157), so in THAT
/// group the joiner's "you" id reads as the member's id. Per group, because
/// one person can be a different member in each group they joined.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Yous {
    you: String,
    others: BTreeSet<String>,
    /// (group, their "you" id) -> the member id the group knows.
    aliases: BTreeMap<(String, String), String>,
}

impl Yous {
    pub fn new(you: &str, others: &[String]) -> Yous {
        Yous {
            you: you.to_string(),
            others: others.iter().filter(|o| *o != you).cloned().collect(),
            aliases: BTreeMap::new(),
        }
    }

    /// D-158: `(group, from, to)`: in `group`, `from` reads as `to`.
    ///
    /// D-237 (the Fable audit, A1): an id aliased to "you" in a group is
    /// this account's THERE: it leaves the global set, so it reads as you in
    /// that group and as itself everywhere else (a place this account
    /// claimed in one group is some other person's id in another).
    pub fn with_aliases(mut self, aliases: &[(String, String, String)]) -> Yous {
        for (group, from, to) in aliases {
            if from != to {
                if *to == self.you {
                    self.others.remove(from);
                }
                self.aliases
                    .insert((group.clone(), from.clone()), to.clone());
            }
        }
        self
    }

    pub fn you(&self) -> &str {
        &self.you
    }

    /// Nothing to merge: every read is left exactly as it was.
    pub fn is_single(&self) -> bool {
        self.others.is_empty() && self.aliases.is_empty()
    }

    /// The id as the ledger shows it, outside any group.
    pub fn id(&self, id: &str) -> String {
        self.id_in(None, id)
    }

    /// The id as the ledger shows it in `group`.
    pub fn id_in(&self, group: Option<&str>, id: &str) -> String {
        if self.others.contains(id) {
            return self.you.clone();
        }
        group
            .and_then(|g| self.aliases.get(&(g.to_string(), id.to_string())))
            .cloned()
            .unwrap_or_else(|| id.to_string())
    }

    /// Shares with every "you" id read as one, amounts added, each person in
    /// the position of their first share.
    pub fn shares(&self, shares: &[(String, i64)]) -> Vec<(String, i64)> {
        self.shares_in(None, shares)
    }

    /// `shares` as `group` reads them.
    pub fn shares_in(&self, group: Option<&str>, shares: &[(String, i64)]) -> Vec<(String, i64)> {
        let mut out: Vec<(String, i64)> = Vec::with_capacity(shares.len());
        for (id, amount) in shares {
            let id = self.id_in(group, id);
            match out.iter_mut().find(|(held, _)| *held == id) {
                // D-235 (core audit H1 e): never wraps. Ingest bounds every
                // amount (straumr::check), so this saturates only on a record
                // that slipped past it; a wrapped sum would flip a sign.
                Some((_, total)) => *total = total.saturating_add(*amount),
                None => out.push((id, *amount)),
            }
        }
        out
    }

    /// A settlement's two ends, or None when both are you (it leaves the
    /// projected ledger).
    pub fn settlement(&self, from: &str, to: &str) -> Option<(String, String)> {
        self.settlement_in(None, from, to)
    }

    /// A settlement's two ends as `group` reads them, or None when both are
    /// one person.
    pub fn settlement_in(
        &self,
        group: Option<&str>,
        from: &str,
        to: &str,
    ) -> Option<(String, String)> {
        let (from, to) = (self.id_in(group, from), self.id_in(group, to));
        (from != to).then_some((from, to))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(n: u8) -> String {
        format!("{n:032x}")
    }

    fn yous() -> Yous {
        Yous::new(&hex(1), &[hex(2), hex(3)])
    }

    #[test]
    fn other_yous_read_as_you_and_everyone_else_is_untouched() {
        let y = yous();
        assert_eq!(y.id(&hex(2)), hex(1));
        assert_eq!(y.id(&hex(3)), hex(1));
        assert_eq!(y.id(&hex(1)), hex(1));
        assert_eq!(y.id(&hex(9)), hex(9));
        assert!(!y.is_single());
        assert!(
            Yous::new(&hex(1), &[hex(1)]).is_single(),
            "you is never your own other"
        );
    }

    /// The amounts are added, in the first one's place, so the shares still
    /// sum to what was paid (the property every balance relies on).
    #[test]
    fn collapsing_shares_add_up_in_the_first_ones_place() {
        let shares = vec![
            (hex(9), 100),
            (hex(2), 250),
            (hex(1), 50),
            (hex(3), 25),
            (hex(8), 75),
        ];
        let merged = yous().shares(&shares);
        assert_eq!(merged, vec![(hex(9), 100), (hex(1), 325), (hex(8), 75)]);
        let before: i64 = shares.iter().map(|s| s.1).sum();
        let after: i64 = merged.iter().map(|s| s.1).sum();
        assert_eq!(before, after);
    }

    /// D-158: another person's "you" reads as the member they claimed, in
    /// that group only; their shares collapse like yours do.
    #[test]
    fn aliases_apply_in_their_own_group_only() {
        let y = Yous::new(&hex(1), &[])
            .with_aliases(&[(hex(9), hex(20), hex(2)), (hex(8), hex(20), hex(5))]);
        assert!(!y.is_single());
        assert_eq!(y.id_in(Some(&hex(9)), &hex(20)), hex(2));
        assert_eq!(y.id_in(Some(&hex(8)), &hex(20)), hex(5));
        assert_eq!(y.id_in(Some(&hex(7)), &hex(20)), hex(20));
        assert_eq!(y.id(&hex(20)), hex(20), "outside a group, nothing");
        assert_eq!(
            y.shares_in(Some(&hex(9)), &[(hex(2), 100), (hex(20), 50), (hex(1), 25)]),
            vec![(hex(2), 150), (hex(1), 25)]
        );
        assert_eq!(y.settlement_in(Some(&hex(9)), &hex(20), &hex(2)), None);
        assert!(Yous::new(&hex(1), &[])
            .with_aliases(&[(hex(9), hex(3), hex(3))])
            .is_single());
    }

    /// D-235 (probe P8): two of your ids with shares i64::MAX and 1 panicked
    /// in debug and wrapped to i64::MIN in the release core.
    #[test]
    fn collapsing_shares_never_wrap() {
        let merged = yous().shares(&[(hex(2), i64::MAX), (hex(3), 1)]);
        assert_eq!(merged, vec![(hex(1), i64::MAX)]);
        let merged = yous().shares(&[(hex(2), i64::MIN), (hex(3), -1)]);
        assert_eq!(merged, vec![(hex(1), i64::MIN)]);
    }

    /// D-237 (the Fable audit, A1): an id aliased to "you" in a group is this
    /// account's there and someone else's everywhere else, however it
    /// entered the global set (a place claimed in one group is a random id
    /// another group's maker may have copied).
    #[test]
    fn an_id_aliased_to_you_reads_as_you_in_its_group_only() {
        let y = Yous::new(&hex(1), &[hex(2), hex(5)]).with_aliases(&[(hex(9), hex(5), hex(1))]);
        assert_eq!(y.id_in(Some(&hex(9)), &hex(5)), hex(1), "in its group");
        assert_eq!(y.id_in(Some(&hex(8)), &hex(5)), hex(5), "another group");
        assert_eq!(y.id(&hex(5)), hex(5), "outside a group");
        assert_eq!(
            y.id_in(Some(&hex(8)), &hex(2)),
            hex(1),
            "a global other stays you everywhere"
        );
        assert_eq!(
            y.shares_in(Some(&hex(8)), &[(hex(5), 100), (hex(1), 50)]),
            vec![(hex(5), 100), (hex(1), 50)]
        );
        assert_eq!(
            y.shares_in(Some(&hex(9)), &[(hex(5), 100), (hex(1), 50)]),
            vec![(hex(1), 150)]
        );
        assert_eq!(
            y.settlement_in(Some(&hex(8)), &hex(5), &hex(1)),
            Some((hex(5), hex(1)))
        );
        assert_eq!(y.settlement_in(Some(&hex(9)), &hex(5), &hex(1)), None);
    }

    #[test]
    fn a_settlement_between_two_of_your_ids_leaves_the_ledger() {
        let y = yous();
        assert_eq!(y.settlement(&hex(2), &hex(1)), None);
        assert_eq!(y.settlement(&hex(2), &hex(3)), None);
        assert_eq!(y.settlement(&hex(2), &hex(9)), Some((hex(1), hex(9))));
        assert_eq!(y.settlement(&hex(9), &hex(8)), Some((hex(9), hex(8))));
    }
}
