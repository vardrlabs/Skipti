use crate::fx::Conversion;
use crate::hlutr::ParticipantId;
use crate::money::{Currency, Money, MoneyError};
use core::fmt;
use std::collections::BTreeMap;

pub mod redistribution;

pub type GroupId = [u8; 16];
pub type ExpenseKey = [u8; 16];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LedgerError {
    Currency(MoneyError),
    ShareSumMismatch {
        expected: i128,
        got: i128,
    },
    NonMemberPayer,
    NonMemberShare,
    WrongSettlementCurrency {
        expected: &'static str,
        got: &'static str,
    },
    UnknownGroup,
    SettlementNonMember,
}

impl fmt::Display for LedgerError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            LedgerError::Currency(e) => write!(f, "{e}"),
            LedgerError::ShareSumMismatch { expected, got } => {
                write!(f, "shares sum to {got}, expense total is {expected}")
            }
            LedgerError::NonMemberPayer => write!(f, "payer is not a group member"),
            LedgerError::NonMemberShare => write!(f, "share references a non-member"),
            LedgerError::WrongSettlementCurrency { expected, got } => {
                write!(f, "group settles in {expected}, expense recorded in {got}")
            }
            LedgerError::UnknownGroup => write!(f, "expense references an unknown group"),
            LedgerError::SettlementNonMember => write!(f, "settlement references a non-member"),
        }
    }
}

impl std::error::Error for LedgerError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Group {
    pub id: GroupId,
    pub settlement_currency: Currency,
    pub members: Vec<ParticipantId>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExpenseRecord {
    pub id: ExpenseKey,
    pub version: u32,
    pub group: Option<GroupId>,
    pub payer: ParticipantId,
    pub paid: Money,
    pub conversion: Option<Conversion>,
    pub shares: Vec<(ParticipantId, i128)>,
    pub timestamp_ms: u64,
}

impl ExpenseRecord {
    pub fn effective(&self) -> Money {
        match &self.conversion {
            Some(c) => c.converted,
            None => self.paid,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettlementRecord {
    pub id: [u8; 16],
    pub group: GroupId,
    pub from: ParticipantId,
    pub to: ParticipantId,
    pub amount: Money,
    pub timestamp_ms: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Ledger {
    pub groups: Vec<Group>,
    pub expenses: Vec<ExpenseRecord>,
    pub settlements: Vec<SettlementRecord>,
}

impl Ledger {
    pub fn group(&self, id: GroupId) -> Option<&Group> {
        self.groups.iter().find(|g| g.id == id)
    }

    pub fn apply_expense(&mut self, e: ExpenseRecord) -> Result<(), LedgerError> {
        if let Some(gid) = e.group {
            let g = self.group(gid).ok_or(LedgerError::UnknownGroup)?;
            if e.effective().currency != g.settlement_currency {
                return Err(LedgerError::WrongSettlementCurrency {
                    expected: g.settlement_currency.code(),
                    got: e.effective().currency.code(),
                });
            }
            if !g.members.contains(&e.payer) {
                return Err(LedgerError::NonMemberPayer);
            }
            for (p, _) in &e.shares {
                if !g.members.contains(p) {
                    return Err(LedgerError::NonMemberShare);
                }
            }
        }
        if e.effective().amount < 0 {
            return Err(LedgerError::Currency(MoneyError::InvalidInput(
                "expense total must be non-negative",
            )));
        }
        let got = e.shares.iter().map(|(_, a)| a).sum::<i128>();
        if got != e.effective().amount {
            return Err(LedgerError::ShareSumMismatch {
                expected: e.effective().amount,
                got,
            });
        }
        match self.expenses.iter_mut().find(|x| x.id == e.id) {
            Some(existing) => {
                if e.version > existing.version {
                    *existing = e;
                }
            }
            None => self.expenses.push(e),
        }
        Ok(())
    }

    pub fn apply_settlement(&mut self, s: SettlementRecord) -> Result<(), LedgerError> {
        let g = self.group(s.group).ok_or(LedgerError::UnknownGroup)?;
        if !g.members.contains(&s.from) || !g.members.contains(&s.to) {
            return Err(LedgerError::SettlementNonMember);
        }
        if s.amount.currency != g.settlement_currency {
            return Err(LedgerError::WrongSettlementCurrency {
                expected: g.settlement_currency.code(),
                got: s.amount.currency.code(),
            });
        }
        self.settlements.push(s);
        Ok(())
    }
}

fn latest_versions<'a>(expenses: &'a [ExpenseRecord]) -> Vec<&'a ExpenseRecord> {
    let mut latest: BTreeMap<ExpenseKey, &'a ExpenseRecord> = BTreeMap::new();
    for e in expenses {
        match latest.get(&e.id) {
            Some(cur) if cur.version >= e.version => {}
            _ => {
                latest.insert(e.id, e);
            }
        }
    }
    latest.into_values().collect()
}

pub type BalanceKey = (&'static str, ParticipantId);

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Balances {
    pub net: BTreeMap<(String, ParticipantId), i128>,
}

impl Balances {
    pub fn to_canonical_bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        for ((ccy, pid), amt) in &self.net {
            out.extend_from_slice(ccy.as_bytes());
            out.push(0);
            out.extend_from_slice(pid);
            out.extend_from_slice(&amt.to_be_bytes());
        }
        out
    }

    pub fn per_currency(&self, code: &str) -> Vec<(ParticipantId, i128)> {
        self.net
            .iter()
            .filter(|((c, _), _)| c == code)
            .map(|((_, p), a)| (*p, *a))
            .collect()
    }
}

pub fn balances(ledger: &Ledger) -> Balances {
    let mut net: BTreeMap<(String, ParticipantId), i128> = BTreeMap::new();
    for e in latest_versions(&ledger.expenses) {
        let eff = e.effective();
        let code = eff.currency.code();
        *net.entry((code.to_string(), e.payer)).or_insert(0) += eff.amount;
        for (p, s) in &e.shares {
            *net.entry((code.to_string(), *p)).or_insert(0) -= s;
        }
    }
    for s in &ledger.settlements {
        let code = s.amount.currency.code();
        *net.entry((code.to_string(), s.from)).or_insert(0) += s.amount.amount;
        *net.entry((code.to_string(), s.to)).or_insert(0) -= s.amount.amount;
    }
    Balances { net }
}

pub fn pairwise(
    ledger: &Ledger,
) -> BTreeMap<String, BTreeMap<(ParticipantId, ParticipantId), i128>> {
    let mut out: BTreeMap<String, BTreeMap<(ParticipantId, ParticipantId), i128>> = BTreeMap::new();
    for e in latest_versions(&ledger.expenses) {
        let eff = e.effective();
        let code = eff.currency.code();
        let matrix = out.entry(code.to_string()).or_default();
        for (p, s) in &e.shares {
            if *s == 0 || *p == e.payer {
                continue;
            }
            *matrix.entry((*p, e.payer)).or_insert(0) += s;
        }
    }
    for s in &ledger.settlements {
        let code = s.amount.currency.code();
        let matrix = out.entry(code.to_string()).or_default();
        // A settlement reduces the payer's debt to the payee by exactly the
        // amount paid. It does NOT increase the payee's debt in return:
        // the earlier `(to, from) += amount` here double-counted and turned
        // a debt-clearing payment into a reversed debt (caught by the
        // settle-up screen, D-48).
        *matrix.entry((s.from, s.to)).or_insert(0) -= s.amount.amount;
        // A settlement past the payer's debt leaves (from, to) negative:
        // the payee now owes the payer the excess. `net_opposite_pairs`
        // turns that into the positive reverse entry. (D-210 removed a
        // pass that pre-moved negatives to the reverse key: it dropped any
        // existing reverse debt and then counted the excess twice.)
    }
    for matrix in out.values_mut() {
        net_opposite_pairs(matrix);
    }
    out
}

/// Nets opposite-direction pairs in a (debtor, creditor) -> amount matrix:
/// owing 300 one way and 100 the other is one debt of 200, not two debts.
/// A negative entry is a debt the other way. Zeros are dropped, so every
/// entry left is positive.
pub fn net_opposite_pairs(matrix: &mut BTreeMap<(ParticipantId, ParticipantId), i128>) {
    let keys: Vec<(ParticipantId, ParticipantId)> = matrix.keys().copied().collect();
    let mut netted: std::collections::BTreeSet<(ParticipantId, ParticipantId)> =
        std::collections::BTreeSet::new();
    for (a, b) in keys {
        if netted.contains(&(a, b)) || netted.contains(&(b, a)) {
            continue;
        }
        let ab = matrix.get(&(a, b)).copied().unwrap_or(0);
        let ba = matrix.get(&(b, a)).copied().unwrap_or(0);
        let net = ab - ba;
        matrix.remove(&(a, b));
        matrix.remove(&(b, a));
        if net > 0 {
            matrix.insert((a, b), net);
        } else if net < 0 {
            matrix.insert((b, a), -net);
        }
        netted.insert((a, b));
    }
    matrix.retain(|_, v| *v != 0);
}

/// D-210: takes `person` out of a (debtor, creditor) matrix of positive
/// amounts, for a person whose balance was redistributed. Everyone who owed
/// `person` now owes, in their place, the people `person` owed: debtors and
/// creditors are each taken in ascending participant id, the first debtor
/// paired with the first creditor for the smaller of what each has left (a
/// debtor who is also that creditor cancels out), then opposite pairs are
/// netted again. Every other net is unchanged. Returns false, leaving the
/// matrix untouched, when `person` still has a balance (what they are owed
/// differs from what they owe) or an entry is not positive.
pub fn collapse(
    matrix: &mut BTreeMap<(ParticipantId, ParticipantId), i128>,
    person: ParticipantId,
) -> bool {
    let mut debtors: Vec<(ParticipantId, i128)> = Vec::new();
    let mut creditors: Vec<(ParticipantId, i128)> = Vec::new();
    for ((d, c), amount) in matrix.iter() {
        if *d != person && *c != person {
            continue;
        }
        if *amount <= 0 {
            return false;
        }
        if *c == person {
            debtors.push((*d, *amount));
        } else {
            creditors.push((*c, *amount));
        }
    }
    let owed: i128 = debtors.iter().map(|(_, a)| a).sum();
    let owes: i128 = creditors.iter().map(|(_, a)| a).sum();
    if owed != owes {
        return false;
    }
    if debtors.is_empty() {
        return true;
    }
    debtors.sort_unstable_by_key(|(id, _)| *id);
    creditors.sort_unstable_by_key(|(id, _)| *id);
    matrix.retain(|(d, c), _| *d != person && *c != person);
    let (mut i, mut j) = (0, 0);
    while i < debtors.len() && j < creditors.len() {
        let m = debtors[i].1.min(creditors[j].1);
        if debtors[i].0 != creditors[j].0 {
            *matrix.entry((debtors[i].0, creditors[j].0)).or_insert(0) += m;
        }
        debtors[i].1 -= m;
        creditors[j].1 -= m;
        if debtors[i].1 == 0 {
            i += 1;
        }
        if creditors[j].1 == 0 {
            j += 1;
        }
    }
    net_opposite_pairs(matrix);
    true
}

/// D-218: cancels every directed cycle in a (debtor, creditor) matrix of
/// positive amounts, one at a time, each by its smallest edge (that amount
/// comes off every edge of the loop, and entries that reach 0 go). A loop
/// such as A owes B, B owes C, C owes A is nobody's real debt: each person
/// on it owes and is owed the same amount. So every net is unchanged, no
/// pair appears that was not there, and no amount grows.
///
/// Deterministic and independent of any viewer: the first cycle a
/// depth-first search finds is taken, starting at the lowest participant id
/// and following each person's creditors in ascending id; the first edge
/// into a person still on the search path closes the cycle. The search
/// starts afresh after every cancellation. Every round deletes at least one
/// entry, so it ends. Every entry must be positive and a pair may hold at
/// most one direction (what `pairwise` and `collapse` leave); a non-positive
/// entry is a programming error.
pub fn cancel_cycles(matrix: &mut BTreeMap<(ParticipantId, ParticipantId), i128>) {
    debug_assert!(
        matrix.values().all(|a| *a > 0),
        "cancel_cycles takes positive entries only"
    );
    while let Some(cycle) = find_cycle(matrix) {
        let smallest = cycle
            .iter()
            .map(|edge| matrix[edge])
            .min()
            .expect("a cycle has edges");
        for edge in cycle {
            let left = matrix[&edge] - smallest;
            if left == 0 {
                matrix.remove(&edge);
            } else {
                matrix.insert(edge, left);
            }
        }
    }
}

/// The first directed cycle, as its edges in order, or None. Iterative
/// depth-first search (no recursion depth to worry about).
fn find_cycle(
    matrix: &BTreeMap<(ParticipantId, ParticipantId), i128>,
) -> Option<Vec<(ParticipantId, ParticipantId)>> {
    let mut creditors: BTreeMap<ParticipantId, Vec<ParticipantId>> = BTreeMap::new();
    for (d, c) in matrix.keys() {
        // Keys come in ascending order, so each list is ascending.
        creditors.entry(*d).or_default().push(*c);
    }
    let mut finished: std::collections::BTreeSet<ParticipantId> = std::collections::BTreeSet::new();
    for start in creditors.keys().copied() {
        if finished.contains(&start) {
            continue;
        }
        // Each frame is a person on the path and how many of their
        // creditors have been looked at.
        let mut path: Vec<(ParticipantId, usize)> = vec![(start, 0)];
        while let Some(&(node, next)) = path.last() {
            let list = creditors.get(&node).map(Vec::as_slice).unwrap_or(&[]);
            if next >= list.len() {
                finished.insert(node);
                path.pop();
                continue;
            }
            if let Some(top) = path.last_mut() {
                top.1 += 1;
            }
            let to = list[next];
            if let Some(at) = path.iter().position(|(n, _)| *n == to) {
                let mut cycle: Vec<(ParticipantId, ParticipantId)> =
                    path[at..].windows(2).map(|w| (w[0].0, w[1].0)).collect();
                cycle.push((node, to));
                return Some(cycle);
            }
            if !finished.contains(&to) {
                path.push((to, 0));
            }
        }
    }
    None
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Transfer {
    pub from: ParticipantId,
    pub to: ParticipantId,
    pub amount: i128,
}

/// Greedy largest-debtor-to-largest-creditor per currency (SPEC §5.6).
/// Deterministic tie-breaks by ascending ParticipantId. Yields at most n-1
/// transfers and provably preserves every participant's net position.
pub fn simplify(net: &[(ParticipantId, i128)]) -> Vec<Transfer> {
    let mut positions: Vec<(ParticipantId, i128)> = net.to_vec();
    positions.retain(|(_, a)| *a != 0);
    positions.sort_unstable_by_key(|(id, _)| *id);

    let mut transfers = Vec::new();
    loop {
        let mut debtor: Option<usize> = None;
        let mut creditor: Option<usize> = None;
        for (i, (_, a)) in positions.iter().enumerate() {
            match debtor {
                Some(di) if positions[di].1 <= *a => {}
                _ if *a < 0 => debtor = Some(i),
                _ => {}
            }
            match creditor {
                Some(ci) if positions[ci].1 >= *a => {}
                _ if *a > 0 => creditor = Some(i),
                _ => {}
            }
        }
        let (d, c) = match (debtor, creditor) {
            (Some(d), Some(c)) => (d, c),
            _ => break,
        };
        let amount = positions[d].1.abs().min(positions[c].1);
        if amount == 0 {
            break;
        }
        transfers.push(Transfer {
            from: positions[d].0,
            to: positions[c].0,
            amount,
        });
        positions[d].1 += amount;
        positions[c].1 -= amount;
    }
    transfers
}

pub fn simplify_group(ledger: &Ledger, group_id: GroupId) -> BTreeMap<String, Vec<Transfer>> {
    let mut out = BTreeMap::new();
    for (code, matrix) in pairwise(ledger) {
        let _ = group_id;
        if matrix.is_empty() {
            continue;
        }
        let mut net: BTreeMap<ParticipantId, i128> = BTreeMap::new();
        for ((d, c), amount) in &matrix {
            *net.entry(*d).or_insert(0) -= amount;
            *net.entry(*c).or_insert(0) += amount;
        }
        let positions: Vec<(ParticipantId, i128)> = net.into_iter().collect();
        out.insert(code, simplify(&positions));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hlutr::{compute, SplitSpec};

    fn pid(n: u8) -> ParticipantId {
        let mut b = [0u8; 16];
        b[15] = n;
        b
    }

    fn group() -> Group {
        Group {
            id: [7; 16],
            settlement_currency: Currency::INR,
            members: vec![pid(1), pid(2), pid(3)],
        }
    }

    fn expense(
        id: u8,
        payer: ParticipantId,
        amount: i128,
        shares: Vec<(ParticipantId, i128)>,
    ) -> ExpenseRecord {
        ExpenseRecord {
            id: [id; 16],
            version: 1,
            group: Some([7; 16]),
            payer,
            paid: Money::new(amount, Currency::INR),
            conversion: None,
            shares,
            timestamp_ms: 0,
        }
    }

    #[test]
    fn balances_equal_split() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        let r = compute(
            &SplitSpec::Equal,
            Money::new(300, Currency::INR),
            &group().members,
        )
        .unwrap();
        l.apply_expense(expense(1, pid(1), 300, r.shares)).unwrap();
        let b = balances(&l);
        assert_eq!(
            b.per_currency("INR"),
            vec![(pid(1), 200), (pid(2), -100), (pid(3), -100)]
        );
    }

    #[test]
    fn edit_supersedes_by_version_regardless_of_apply_order() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        let mut v2 = expense(1, pid(1), 300, vec![(pid(1), 300)]);
        v2.version = 2;
        let v1 = expense(1, pid(1), 300, vec![(pid(1), 100), (pid(2), 200)]);
        l.apply_expense(v1.clone()).unwrap();
        l.apply_expense(v2.clone()).unwrap();

        let mut l2 = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        l2.apply_expense(v2).unwrap();
        l2.apply_expense(v1).unwrap();

        assert_eq!(balances(&l), balances(&l2));
    }

    #[test]
    fn share_sum_mismatch_is_rejected_in_production() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        let e = expense(1, pid(1), 300, vec![(pid(1), 299), (pid(2), 0)]);
        assert!(matches!(
            l.apply_expense(e),
            Err(LedgerError::ShareSumMismatch { .. })
        ));
    }

    #[test]
    fn settlement_reduces_the_debt() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        let r = compute(
            &SplitSpec::Equal,
            Money::new(300, Currency::INR),
            &group().members,
        )
        .unwrap();
        l.apply_expense(expense(1, pid(1), 300, r.shares)).unwrap();
        l.apply_settlement(SettlementRecord {
            id: [9; 16],
            group: [7; 16],
            from: pid(2),
            to: pid(1),
            amount: Money::new(100, Currency::INR),
            timestamp_ms: 0,
        })
        .unwrap();
        let b = balances(&l);
        assert_eq!(
            b.per_currency("INR"),
            vec![(pid(1), 100), (pid(2), 0), (pid(3), -100)]
        );
    }

    /// The invariant that would have caught the settlement double-count
    /// generically: each participant's net, derived from the pairwise
    /// matrix (creditor-in minus debtor-out), must equal their net from
    /// `balances()`. The old (to,from) += bug corrupted the matrix while
    /// leaving balances() correct: no existing test compared the two.
    #[test]
    fn pairwise_matrix_nets_match_balances() {
        let ada = pid(1);
        let priya = pid(2);
        let amit = pid(3);
        let mut l = Ledger {
            groups: vec![Group {
                id: [7; 16],
                settlement_currency: Currency::INR,
                members: vec![ada, priya, amit],
            }],
            ..Default::default()
        };
        // Mixed directions: two expenses and a settlement that nets one
        // pair to zero.
        for (n, (payer, shares)) in [
            (ada, vec![(priya, 148_000i128), (amit, 70_000)]),
            (priya, vec![(ada, 200_000)]),
            (amit, vec![(ada, 30_000)]),
        ]
        .into_iter()
        .enumerate()
        {
            let total: i128 = shares.iter().map(|(_, a)| a).sum();
            l.apply_expense(ExpenseRecord {
                id: [n as u8 + 1; 16],
                version: 1,
                group: Some([7; 16]),
                payer,
                paid: Money::new(total, Currency::INR),
                conversion: None,
                shares,
                timestamp_ms: n as u64 + 1,
            })
            .unwrap();
        }
        l.apply_settlement(SettlementRecord {
            id: [9; 16],
            group: [7; 16],
            from: ada,
            to: priya,
            amount: Money::new(52_000, Currency::INR),
            timestamp_ms: 9,
        })
        .unwrap();

        let matrix = &pairwise(&l)["INR"];
        let bal = balances(&l);
        let expected = bal.per_currency("INR");

        for (participant, expected_net) in &expected {
            // Net from the matrix: everything owed TO p minus everything p
            // owes.
            let owed_to: i128 = matrix
                .iter()
                .filter(|((_, creditor), _)| creditor == participant)
                .map(|(_, v)| *v)
                .sum();
            let owed_by: i128 = matrix
                .iter()
                .filter(|((debtor, _), _)| debtor == participant)
                .map(|(_, v)| *v)
                .sum();
            assert_eq!(
                owed_to - owed_by,
                *expected_net,
                "pairwise matrix disagrees with balances for {participant:?}"
            );
        }
    }

    #[test]
    fn pairwise_settlement_clears_the_net_debt_it_settles() {
        // The exact shape the settle-up screen hit (D-48): priya is owed
        // 200,000 while owing 148,000; ada settles the NET 52,000: the
        // pairwise matrix must end at ZERO between them, not carry a
        // reversed debt.
        let ada = pid(1);
        let priya = pid(2);
        let mut l = Ledger {
            groups: vec![Group {
                id: [7; 16],
                settlement_currency: Currency::INR,
                members: vec![ada, priya],
            }],
            ..Default::default()
        };
        l.apply_expense(ExpenseRecord {
            id: [1; 16],
            version: 1,
            group: Some([7; 16]),
            payer: ada,
            paid: Money::new(428_000, Currency::INR),
            conversion: None,
            shares: vec![(ada, 280_000), (priya, 148_000)],
            timestamp_ms: 1,
        })
        .unwrap();
        l.apply_expense(ExpenseRecord {
            id: [2; 16],
            version: 1,
            group: Some([7; 16]),
            payer: priya,
            paid: Money::new(200_000, Currency::INR),
            conversion: None,
            shares: vec![(ada, 200_000)],
            timestamp_ms: 2,
        })
        .unwrap();
        l.apply_settlement(SettlementRecord {
            id: [3; 16],
            group: [7; 16],
            from: ada,
            to: priya,
            amount: Money::new(52_000, Currency::INR),
            timestamp_ms: 3,
        })
        .unwrap();

        let matrix = &pairwise(&l)["INR"];
        assert!(
            matrix.is_empty(),
            "the settled net debt is GONE, not reversed: {matrix:?}"
        );
        // And the group plan agrees: nothing left to transfer.
        let plan = simplify_group(&l, [7; 16]);
        assert!(
            plan.get("INR").map(|t| t.is_empty()).unwrap_or(true),
            "nothing left to transfer: {plan:?}"
        );
    }

    #[test]
    fn simplification_preserves_net_and_stays_under_n_minus_one() {
        let net = vec![(pid(1), -500i128), (pid(2), 300), (pid(3), 200)];
        let transfers = simplify(&net);
        assert!(transfers.len() <= 2);
        let mut restored: Vec<(ParticipantId, i128)> =
            net.iter().map(|(id, a)| (*id, *a)).collect();
        for t in &transfers {
            let from = restored.iter_mut().find(|(id, _)| id == &t.from).unwrap();
            from.1 += t.amount;
            let to = restored.iter_mut().find(|(id, _)| id == &t.to).unwrap();
            to.1 -= t.amount;
        }
        assert!(restored.iter().all(|(_, a)| *a == 0));

        let mut overpaid = vec![(pid(1), 400i128), (pid(2), -500), (pid(3), 100)];
        let t2 = simplify(&overpaid);
        assert!(t2.len() <= 2);
        for t in &t2 {
            let from = overpaid.iter_mut().find(|(id, _)| id == &t.from).unwrap();
            from.1 += t.amount;
            let to = overpaid.iter_mut().find(|(id, _)| id == &t.to).unwrap();
            to.1 -= t.amount;
        }
        assert!(overpaid.iter().all(|(_, a)| *a == 0));
    }

    #[test]
    fn currencies_are_never_netted_across_each_other() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        let usd_expense = ExpenseRecord {
            id: [2; 16],
            version: 1,
            group: Some([7; 16]),
            payer: pid(1),
            paid: Money::new(100, Currency::USD),
            conversion: None,
            shares: vec![(pid(1), 0), (pid(2), 100)],
            timestamp_ms: 0,
        };
        assert!(matches!(
            l.apply_expense(usd_expense),
            Err(LedgerError::WrongSettlementCurrency { .. })
        ));
        let inr_expense = expense(
            3,
            pid(1),
            400,
            vec![(pid(1), 100), (pid(2), 100), (pid(3), 200)],
        );
        l.apply_expense(inr_expense).unwrap();
        let b = balances(&l);
        assert_eq!(b.per_currency("USD").len(), 0);
        assert_eq!(
            b.per_currency("INR"),
            vec![(pid(1), 300), (pid(2), -100), (pid(3), -200)]
        );
    }

    fn settle(l: &mut Ledger, id: u8, from: ParticipantId, to: ParticipantId, amount: i128) {
        l.apply_settlement(SettlementRecord {
            id: [id; 16],
            group: [7; 16],
            from,
            to,
            amount: Money::new(amount, Currency::INR),
            timestamp_ms: id as u64,
        })
        .unwrap();
    }

    fn inr(l: &Ledger) -> BTreeMap<(ParticipantId, ParticipantId), i128> {
        pairwise(l).remove("INR").unwrap_or_default()
    }

    /// Derived by hand. No debts; p1 pays p2 500. The raw matrix holds
    /// (p1, p2) = -500, which nets to "p2 owes p1 500". The old corrections
    /// loop also added (p2, p1) += 500, and the netting step then read
    /// -500 - 500 and wrote (p2, p1) = 1000: doubled.
    #[test]
    fn a_settlement_with_no_debt_behind_it_is_not_doubled() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        settle(&mut l, 1, pid(1), pid(2), 500);
        assert_eq!(inr(&l), BTreeMap::from([((pid(2), pid(1)), 500)]));
    }

    /// Derived by hand. p1 paid 300 for p2: (p2, p1) = 300. Then p1 pays p2
    /// 200: (p1, p2) = -200. p2 now owes p1 300 + 200 = 500. The old loop
    /// removed the existing (p2, p1) = 300, added 200, and the netting step
    /// read -200 - 200 = -400: the 300 was lost and the 200 doubled.
    #[test]
    fn a_settlement_against_a_reverse_debt_adds_to_it() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        l.apply_expense(expense(1, pid(1), 300, vec![(pid(2), 300)]))
            .unwrap();
        settle(&mut l, 2, pid(1), pid(2), 200);
        assert_eq!(inr(&l), BTreeMap::from([((pid(2), pid(1)), 500)]));
    }

    /// Derived by hand: the "Settle all" shape. p2 paid 1000 for p1
    /// ((p1, p2) = 1000), p3 paid 1000 for p2 ((p2, p3) = 1000), and the
    /// simplified plan has p1 pay p3 1000 directly ((p1, p3) = -1000).
    /// Every net is 0 (p1: -1000 + 1000, p2: +1000 - 1000, p3: +1000 - 1000).
    /// The model keeps pairs, not nets, so the honest pairwise view is the
    /// cycle p1 -> p2 -> p3 -> p1 of 1000 each (each person's in and out
    /// cancel). The old code made the last edge (p3, p1) = 2000, which also
    /// broke "matrix nets equal balances" for p1 and p3.
    #[test]
    fn a_settle_all_transfer_past_a_middle_person_leaves_an_even_cycle() {
        let mut l = Ledger {
            groups: vec![group()],
            ..Default::default()
        };
        l.apply_expense(expense(1, pid(2), 1000, vec![(pid(1), 1000)]))
            .unwrap();
        l.apply_expense(expense(2, pid(3), 1000, vec![(pid(2), 1000)]))
            .unwrap();
        settle(&mut l, 3, pid(1), pid(3), 1000);
        let m = inr(&l);
        assert_eq!(
            m,
            BTreeMap::from([
                ((pid(1), pid(2)), 1000),
                ((pid(2), pid(3)), 1000),
                ((pid(3), pid(1)), 1000),
            ])
        );
        for (p, net) in balances(&l).per_currency("INR") {
            let into: i128 = m.iter().filter(|((_, c), _)| *c == p).map(|(_, v)| v).sum();
            let out: i128 = m.iter().filter(|((d, _), _)| *d == p).map(|(_, v)| v).sum();
            assert_eq!(into - out, net, "matrix net for {p:?}");
        }
    }

    type Matrix = BTreeMap<(ParticipantId, ParticipantId), i128>;

    /// Derived by hand. F = p5 is owed 500 by p1 and 300 by p3, and owes p2
    /// 600 and p4 200 (in 800, out 800: net 0). p2 also owes p3 50.
    /// Debtors by id: p1 500, p3 300; creditors by id: p2 600, p4 200.
    /// Greedy: p1 -> p2 500 (p1 done, p2 has 100 left); p3 -> p2 100 (p2
    /// done, p3 has 200 left); p3 -> p4 200. Re-netting (p3, p2) 100 against
    /// (p2, p3) 50 leaves (p3, p2) 50. Nets before and after: p1 -500,
    /// p2 +550, p3 -250, p4 +200.
    #[test]
    fn collapse_routes_every_debtor_to_every_creditor_and_renets() {
        let f = pid(5);
        let mut m: Matrix = BTreeMap::from([
            ((pid(1), f), 500),
            ((pid(3), f), 300),
            ((f, pid(2)), 600),
            ((f, pid(4)), 200),
            ((pid(2), pid(3)), 50),
        ]);
        assert!(collapse(&mut m, f));
        assert_eq!(
            m,
            BTreeMap::from([
                ((pid(1), pid(2)), 500),
                ((pid(3), pid(2)), 50),
                ((pid(3), pid(4)), 200),
            ])
        );
    }

    /// Derived by hand. Debtors p9 100 and p2 100, creditors p8 100 and
    /// p3 100: ascending ids pair p2 with p3 and p9 with p8, never p2 with p8.
    #[test]
    fn collapse_pairs_debtors_and_creditors_in_ascending_id_order() {
        let f = pid(5);
        let mut m: Matrix = BTreeMap::from([
            ((pid(9), f), 100),
            ((pid(2), f), 100),
            ((f, pid(8)), 100),
            ((f, pid(3)), 100),
        ]);
        assert!(collapse(&mut m, f));
        assert_eq!(
            m,
            BTreeMap::from([((pid(2), pid(3)), 100), ((pid(9), pid(8)), 100)])
        );
    }

    /// Derived by hand. An un-netted matrix: p1 owes F 300 and F owes p1
    /// 100 and p2 200. Greedy: p1 -> p1 100 cancels (no self-debt); p1 -> p2
    /// 200. Result p1 owes p2 200 (p1 -200, p2 +200, as before).
    #[test]
    fn collapse_cancels_a_debtor_who_is_also_the_creditor() {
        let f = pid(5);
        let mut m: Matrix =
            BTreeMap::from([((pid(1), f), 300), ((f, pid(1)), 100), ((f, pid(2)), 200)]);
        assert!(collapse(&mut m, f));
        assert_eq!(m, BTreeMap::from([((pid(1), pid(2)), 200)]));
    }

    /// A person whose net is not zero is left in, untouched.
    #[test]
    fn collapse_leaves_a_person_with_a_balance_in_place() {
        let f = pid(5);
        let before: Matrix = BTreeMap::from([((pid(1), f), 300), ((f, pid(2)), 200)]);
        let mut m = before.clone();
        assert!(!collapse(&mut m, f));
        assert_eq!(m, before);
        // Nobody to collapse: nothing changes either way.
        let mut empty: Matrix = BTreeMap::from([((pid(1), pid(2)), 7)]);
        assert!(collapse(&mut empty, f));
        assert_eq!(empty, BTreeMap::from([((pid(1), pid(2)), 7)]));
    }

    // ---- D-218: cancel_cycles ----
    //
    // Goldens re-derived independently in .drafts/d218/cycles.py (written
    // from the plan's algorithm text alone, never from this code). A = p1,
    // B = p2, C = p3, D = p4.

    fn edges(list: &[(u8, u8, i128)]) -> Matrix {
        list.iter()
            .map(|(d, c, a)| ((pid(*d), pid(*c)), *a))
            .collect()
    }

    fn cancelled(list: &[(u8, u8, i128)]) -> Matrix {
        let mut m = edges(list);
        cancel_cycles(&mut m);
        m
    }

    /// G1 (.drafts/d218/cycles.py): the Settle all shape. A owes B 1000, B
    /// owes C 1000, C owes A 1000: every net is 0, the loop goes.
    #[test]
    fn cancel_cycles_clears_a_settle_all_loop() {
        assert_eq!(
            cancelled(&[(1, 2, 1000), (2, 3, 1000), (3, 1, 1000)]),
            Matrix::new()
        );
    }

    /// G2 (.drafts/d218/cycles.py): two loops share the edge (D, A) of 20.
    /// A-B-D-A cancels by 10, then A-C-D-A by 10: nothing is left, though
    /// no loop has all-equal edges.
    #[test]
    fn cancel_cycles_clears_loops_that_share_an_edge() {
        assert_eq!(
            cancelled(&[(1, 2, 10), (1, 3, 10), (2, 4, 10), (3, 4, 10), (4, 1, 20)]),
            Matrix::new()
        );
    }

    /// G3 (.drafts/d218/cycles.py): the loop A-B-C-A cancels by its smallest
    /// edge, 3; (A, B) keeps 2, the others go.
    #[test]
    fn cancel_cycles_cancels_a_loop_by_its_smallest_edge() {
        assert_eq!(
            cancelled(&[(1, 2, 5), (2, 3, 3), (3, 1, 3)]),
            edges(&[(1, 2, 2)])
        );
    }

    /// G4 (.drafts/d218/cycles.py): the loops A-D-C-A and B-D-C-B share
    /// (D, C) of 1, so the order they are taken in decides the result. The
    /// first loop found (start at the lowest id, lowest creditor first) is
    /// A-D-C-A, which takes the whole of (D, C), leaving (A, D) 1, (B, D) 2,
    /// (C, A) 1, (C, B) 3. Taking loops in descending id order would give
    /// (A, D) 2, (B, D) 1, (C, A) 2, (C, B) 2 instead.
    #[test]
    fn cancel_cycles_takes_cycles_in_ascending_id_order() {
        assert_eq!(
            cancelled(&[(1, 4, 2), (2, 4, 2), (3, 1, 2), (3, 2, 3), (4, 3, 1)]),
            edges(&[(1, 4, 1), (2, 4, 2), (3, 1, 1), (3, 2, 3)])
        );
    }

    /// G5 (.drafts/d218/cycles.py), a NEGATIVE: no loop, nothing changes.
    #[test]
    fn cancel_cycles_leaves_a_matrix_without_loops_alone() {
        let before = edges(&[(1, 2, 4), (2, 3, 7)]);
        let mut m = before.clone();
        cancel_cycles(&mut m);
        assert_eq!(m, before);
        let mut empty = Matrix::new();
        cancel_cycles(&mut empty);
        assert!(empty.is_empty());
    }

    fn nets_of(m: &Matrix) -> BTreeMap<ParticipantId, i128> {
        let mut n: BTreeMap<ParticipantId, i128> = BTreeMap::new();
        for ((d, c), a) in m {
            *n.entry(*d).or_insert(0) -= a;
            *n.entry(*c).or_insert(0) += a;
        }
        n.retain(|_, v| *v != 0);
        n
    }

    /// Independent of the implementation's search: repeatedly drop every
    /// node with no outgoing edge among the nodes left; a directed cycle
    /// exists exactly when some nodes can never be dropped.
    fn has_cycle(m: &Matrix) -> bool {
        let mut left: std::collections::BTreeSet<ParticipantId> = std::collections::BTreeSet::new();
        for (d, c) in m.keys() {
            left.insert(*d);
            left.insert(*c);
        }
        loop {
            let sink = left
                .iter()
                .copied()
                .find(|n| !m.keys().any(|(d, c)| d == n && left.contains(c)));
            match sink {
                Some(n) => {
                    left.remove(&n);
                }
                None => return !left.is_empty(),
            }
        }
    }

    /// 500 seeded 4 and 5 person matrices (a fixed-seed LCG, no `rand`):
    /// every net unchanged, no key that was absent, no amount larger, every
    /// amount positive, no loop left, and a second call changes nothing.
    #[test]
    fn cancel_cycles_keeps_every_net_and_adds_no_debt() {
        let mut state: u64 = 218;
        let mut next = move |bound: u64| -> u64 {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            (state >> 33) % bound
        };
        let mut with_loop = 0;
        for _ in 0..500 {
            let n = 4 + next(2) as u8;
            let mut raw = Matrix::new();
            for a in 1..=n {
                for b in 1..=n {
                    if a != b && next(2) == 0 {
                        raw.insert((pid(a), pid(b)), 1 + next(9) as i128);
                    }
                }
            }
            net_opposite_pairs(&mut raw);
            if has_cycle(&raw) {
                with_loop += 1;
            }
            let mut m = raw.clone();
            cancel_cycles(&mut m);
            assert_eq!(nets_of(&m), nets_of(&raw), "nets for {raw:?}");
            for (k, v) in &m {
                let before = raw.get(k).copied();
                assert!(before.is_some(), "new key {k:?} from {raw:?}");
                assert!(*v > 0 && *v <= before.unwrap(), "{k:?} in {raw:?}");
            }
            assert!(!has_cycle(&m), "a loop is left from {raw:?}");
            let mut again = m.clone();
            cancel_cycles(&mut again);
            assert_eq!(again, m, "not idempotent for {raw:?}");
        }
        // The generator must actually produce loops, or this proves little.
        assert!(with_loop > 100, "only {with_loop} of 500 had a loop");
    }
}
