use crate::money::{Money, MoneyError};
use core::fmt;

pub type ParticipantId = [u8; 16];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SplitError {
    EmptyParticipants,
    DuplicateParticipant,
    UnknownParticipant,
    NonPositiveWeight,
    NegativeTotal,
    PercentMustSumToTenThousand(u128),
    ExactSumMismatch { expected: i128, got: i128 },
    DeltasExceedTotal,
    LineAmountNonPositive,
    EmptyLineAssignment,
    TotalMismatch { expected: i128, got: i128 },
    Currency(MoneyError),
}

impl fmt::Display for SplitError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SplitError::EmptyParticipants => write!(f, "at least one participant is required"),
            SplitError::DuplicateParticipant => write!(f, "participant listed more than once"),
            SplitError::UnknownParticipant => write!(f, "split references a non-member"),
            SplitError::NonPositiveWeight => write!(f, "shares and percentages must be positive"),
            SplitError::NegativeTotal => write!(f, "expense total must be non-negative"),
            SplitError::PercentMustSumToTenThousand(got) => {
                write!(f, "percentages must sum to 100% (10000 bps), got {got} bps")
            }
            SplitError::ExactSumMismatch { expected, got } => {
                write!(f, "exact amounts sum to {got}, total is {expected}")
            }
            SplitError::DeltasExceedTotal => write!(f, "adjustments exceed the expense total"),
            SplitError::LineAmountNonPositive => write!(f, "itemised lines need positive amounts"),
            SplitError::EmptyLineAssignment => write!(f, "itemised line has no assignees"),
            SplitError::TotalMismatch { expected, got } => {
                write!(f, "shares sum to {got}, total is {expected}")
            }
            SplitError::Currency(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for SplitError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ItemLine {
    pub amount: i128,
    pub participants: Vec<ParticipantId>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SavedSplit {
    pub id: [u8; 16],
    pub name: String,
    pub spec: SplitSpec,
}

impl SavedSplit {
    pub fn apply(
        &self,
        total: Money,
        members: &[ParticipantId],
    ) -> Result<SplitResult, SplitError> {
        compute(&self.spec, total, members)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SplitSpec {
    Equal,
    Exact(Vec<(ParticipantId, i128)>),
    Percentage(Vec<(ParticipantId, u128)>),
    Shares(Vec<(ParticipantId, u64)>),
    Adjustment(Vec<(ParticipantId, i128)>),
    Itemised {
        lines: Vec<ItemLine>,
        tax: i128,
        tip: i128,
    },
    PayerOnly(ParticipantId),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SplitResult {
    pub total: Money,
    pub shares: Vec<(ParticipantId, i128)>,
}

impl SplitResult {
    pub fn share_of(&self, p: ParticipantId) -> i128 {
        self.shares
            .iter()
            .find(|(id, _)| *id == p)
            .map(|(_, amt)| *amt)
            .unwrap_or(0)
    }
}

fn check_unique(ids: &[ParticipantId]) -> Result<(), SplitError> {
    let mut seen = ids.to_vec();
    seen.sort_unstable();
    seen.dedup();
    if seen.len() != ids.len() {
        return Err(SplitError::DuplicateParticipant);
    }
    Ok(())
}

fn check_membership(
    entries_ids: impl Iterator<Item = ParticipantId>,
    members: &[ParticipantId],
) -> Result<(), SplitError> {
    let ids: Vec<ParticipantId> = entries_ids.collect();
    check_unique(&ids)?;
    for id in &ids {
        if !members.contains(id) {
            return Err(SplitError::UnknownParticipant);
        }
    }
    Ok(())
}

/// Largest-remainder distribution over integer weights.
///
/// Deterministic on every platform: bases are floor(total*w/W); leftover
/// minor units go to the largest remainders, ties broken by ascending
/// ParticipantId bytes, so every device computes an identical result with no
/// coordination (SPEC §5.5).
pub fn distribute_weighted(
    total: i128,
    weights: &[(ParticipantId, i128)],
) -> Result<Vec<(ParticipantId, i128)>, SplitError> {
    if weights.is_empty() {
        return Err(SplitError::EmptyParticipants);
    }
    if total < 0 {
        return Err(SplitError::NegativeTotal);
    }
    let mut order: Vec<usize> = (0..weights.len()).collect();
    order.sort_unstable_by_key(|&i| weights[i].0);

    let sum_w: i128 = weights.iter().map(|(_, w)| *w).sum();
    if sum_w <= 0 || total.checked_mul(sum_w).is_none() {
        return Err(SplitError::NonPositiveWeight);
    }

    let mut slots: Vec<(i128, i128, ParticipantId)> = Vec::with_capacity(weights.len());
    let mut allocated: i128 = 0;
    for &idx in &order {
        let (id, w) = weights[idx];
        let product = total.checked_mul(w).ok_or(SplitError::NegativeTotal)?;
        allocated += product / sum_w;
        slots.push((product / sum_w, product % sum_w, id));
    }

    let mut leftover = total - allocated;
    let mut slot_order: Vec<usize> = (0..slots.len()).collect();
    slot_order.sort_unstable_by(|&a, &b| {
        slots[b]
            .1
            .cmp(&slots[a].1)
            .then_with(|| slots[a].2.cmp(&slots[b].2))
    });
    for &slot in &slot_order {
        if leftover == 0 {
            break;
        }
        slots[slot].0 += 1;
        leftover -= 1;
    }
    debug_assert_eq!(leftover, 0);

    let mut out: Vec<(ParticipantId, i128)> =
        slots.into_iter().map(|(base, _, id)| (id, base)).collect();
    out.sort_unstable_by_key(|(id, _)| *id);
    Ok(out)
}

fn equal_weights(members: &[ParticipantId]) -> Vec<(ParticipantId, i128)> {
    members.iter().map(|p| (*p, 1i128)).collect()
}

/// Computes one expense's split. `members` is the full participant roster
/// (group members, or the sole user for personal expenses). Every share list
/// is asserted in production to sum exactly to the total.
pub fn compute(
    spec: &SplitSpec,
    total: Money,
    members: &[ParticipantId],
) -> Result<SplitResult, SplitError> {
    if members.is_empty() {
        return Err(SplitError::EmptyParticipants);
    }
    check_unique(members)?;
    if total.amount < 0 {
        return Err(SplitError::NegativeTotal);
    }
    let t = total.amount;

    let shares: Vec<(ParticipantId, i128)> = match spec {
        SplitSpec::Equal => distribute_weighted(t, &equal_weights(members))?,
        SplitSpec::Exact(entries) => {
            check_membership(entries.iter().map(|(id, _)| *id), members)?;
            let got = entries.iter().map(|(_, a)| a).sum::<i128>();
            if got != t {
                return Err(SplitError::ExactSumMismatch { expected: t, got });
            }
            if entries.iter().any(|(_, a)| *a < 0) {
                return Err(SplitError::NegativeTotal);
            }
            let mut sorted = entries.clone();
            sorted.sort_unstable_by_key(|(id, _)| *id);
            sorted
        }
        SplitSpec::Percentage(entries) => {
            check_membership(entries.iter().map(|(id, _)| *id), members)?;
            let bps_sum = entries.iter().map(|(_, b)| b).sum::<u128>();
            if bps_sum != 10_000 {
                return Err(SplitError::PercentMustSumToTenThousand(bps_sum));
            }
            let weights: Vec<(ParticipantId, i128)> =
                entries.iter().map(|(id, b)| (*id, *b as i128)).collect();
            distribute_weighted(t, &weights)?
        }
        SplitSpec::Shares(entries) => {
            check_membership(entries.iter().map(|(id, _)| *id), members)?;
            if entries.iter().any(|(_, w)| *w == 0) {
                return Err(SplitError::NonPositiveWeight);
            }
            let weights: Vec<(ParticipantId, i128)> =
                entries.iter().map(|(id, w)| (*id, *w as i128)).collect();
            distribute_weighted(t, &weights)?
        }
        SplitSpec::Adjustment(deltas) => {
            check_membership(deltas.iter().map(|(id, _)| *id), members)?;
            let delta_sum = deltas.iter().map(|(_, d)| d).sum::<i128>();
            let remaining = t - delta_sum;
            if remaining < 0 {
                return Err(SplitError::DeltasExceedTotal);
            }
            let base = distribute_weighted(remaining, &equal_weights(members))?;
            let mut merged = base;
            for (id, d) in deltas {
                let entry = merged.iter_mut().find(|(mid, _)| mid == id).unwrap();
                entry.1 += d;
            }
            // D-235 (core audit L1): a delta that takes a person below zero
            // is refused, as Exact refuses the same shares: no split writes
            // a negative share (and ingest refuses one, straumr::check).
            if merged.iter().any(|(_, a)| *a < 0) {
                return Err(SplitError::NegativeTotal);
            }
            merged
        }
        SplitSpec::Itemised { lines, tax, tip } => {
            let mut acc: Vec<(ParticipantId, i128)> = Vec::new();
            let mut running: i128 = 0;
            for line in lines {
                if line.amount <= 0 {
                    return Err(SplitError::LineAmountNonPositive);
                }
                if line.participants.is_empty() {
                    return Err(SplitError::EmptyLineAssignment);
                }
                check_membership(line.participants.iter().copied(), members)?;
                let lw: Vec<(ParticipantId, i128)> =
                    line.participants.iter().map(|p| (*p, 1i128)).collect();
                for (p, s) in distribute_weighted(line.amount, &lw)? {
                    match acc.iter_mut().find(|(id, _)| id == &p) {
                        Some(entry) => entry.1 += s,
                        None => acc.push((p, s)),
                    }
                }
                running += line.amount;
            }
            let extras = tax + tip;
            if extras < 0 {
                return Err(SplitError::NegativeTotal);
            }
            if extras > 0 && !acc.is_empty() {
                let ew: Vec<(ParticipantId, i128)> =
                    acc.iter().map(|(id, _)| (*id, 1i128)).collect();
                for (p, s) in distribute_weighted(extras, &ew)? {
                    match acc.iter_mut().find(|(id, _)| id == &p) {
                        Some(entry) => entry.1 += s,
                        None => acc.push((p, s)),
                    }
                }
            }
            running += extras;
            if running != t {
                return Err(SplitError::TotalMismatch {
                    expected: t,
                    got: running,
                });
            }
            acc
        }
        SplitSpec::PayerOnly(payer) => {
            if !members.contains(payer) {
                return Err(SplitError::UnknownParticipant);
            }
            vec![(*payer, t)]
        }
    };

    let got = shares.iter().map(|(_, a)| a).sum::<i128>();
    if got != t {
        return Err(SplitError::TotalMismatch { expected: t, got });
    }
    let mut sorted = shares;
    sorted.sort_unstable_by_key(|(id, _)| *id);
    Ok(SplitResult {
        total,
        shares: sorted,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::money::Currency;

    fn pid(n: u8) -> ParticipantId {
        let mut b = [0u8; 16];
        b[15] = n;
        b
    }

    #[test]
    fn hundred_split_three_ways_extra_unit_to_lowest_uuid() {
        let members = [pid(3), pid(1), pid(2)];
        let r = compute(
            &SplitSpec::Equal,
            Money::new(10_000, Currency::INR),
            &members,
        )
        .unwrap();
        assert_eq!(
            r.shares,
            vec![(pid(1), 3_334), (pid(2), 3_333), (pid(3), 3_333)]
        );
    }

    #[test]
    fn exact_mismatch_is_rejected() {
        let spec = SplitSpec::Exact(vec![(pid(1), 5_000), (pid(2), 4_999)]);
        let err = compute(&spec, Money::new(10_000, Currency::INR), &[pid(1), pid(2)]).unwrap_err();
        assert_eq!(
            err,
            SplitError::ExactSumMismatch {
                expected: 10_000,
                got: 9_999
            }
        );
    }

    #[test]
    fn percentage_must_sum_to_ten_thousand_bps() {
        let spec = SplitSpec::Percentage(vec![(pid(1), 6_000), (pid(2), 3_000)]);
        let err = compute(&spec, Money::new(1_000, Currency::INR), &[pid(1), pid(2)]).unwrap_err();
        assert_eq!(err, SplitError::PercentMustSumToTenThousand(9_000));
    }

    #[test]
    fn shares_weights_two_two_one_deterministic() {
        let spec = SplitSpec::Shares(vec![(pid(2), 2), (pid(1), 2), (pid(3), 1)]);
        let r = compute(
            &spec,
            Money::new(1_000, Currency::INR),
            &[pid(1), pid(2), pid(3)],
        )
        .unwrap();
        assert_eq!(r.share_of(pid(1)), 400);
        assert_eq!(r.share_of(pid(2)), 400);
        assert_eq!(r.share_of(pid(3)), 200);
    }

    #[test]
    fn adjustment_adds_delta_on_top_of_equal_base() {
        let spec = SplitSpec::Adjustment(vec![(pid(1), -500)]);
        let r = compute(&spec, Money::new(3_000, Currency::INR), &[pid(1), pid(2)]).unwrap();
        assert_eq!(r.shares, vec![(pid(1), 1_250), (pid(2), 1_750)]);
        assert_eq!(r.shares.iter().map(|(_, a)| a).sum::<i128>(), 3_000);
    }

    #[test]
    fn adjustment_refuses_a_delta_that_takes_a_share_below_zero() {
        // Probe P9: 30.00 between two, -40.00 on one: remaining 70.00, base
        // 35.00 each, then 35.00 - 40.00 = -5.00. Exact refuses [-500, 3500].
        let spec = SplitSpec::Adjustment(vec![(pid(1), -4_000)]);
        assert_eq!(
            compute(&spec, Money::new(3_000, Currency::INR), &[pid(1), pid(2)]),
            Err(SplitError::NegativeTotal)
        );
        // The edge: a delta that brings a share to exactly zero is kept.
        let spec = SplitSpec::Adjustment(vec![(pid(1), -3_000)]);
        let r = compute(&spec, Money::new(3_000, Currency::INR), &[pid(1), pid(2)]).unwrap();
        assert_eq!(r.shares, vec![(pid(1), 0), (pid(2), 3_000)]);
    }

    #[test]
    fn itemised_lines_with_tax_and_tip_preserve_total() {
        let spec = SplitSpec::Itemised {
            lines: vec![
                ItemLine {
                    amount: 900,
                    participants: vec![pid(1)],
                },
                ItemLine {
                    amount: 600,
                    participants: vec![pid(2), pid(3)],
                },
            ],
            tax: 150,
            tip: 250,
        };
        let r = compute(
            &spec,
            Money::new(1_900, Currency::INR),
            &[pid(1), pid(2), pid(3)],
        )
        .unwrap();
        let got = r.shares.iter().map(|(_, a)| a).sum::<i128>();
        assert_eq!(got, 1_900);
        assert_eq!(r.share_of(pid(1)), 900 + 134);
        assert_eq!(r.share_of(pid(2)) + r.share_of(pid(3)), 600 + 266);
    }

    #[test]
    fn payer_only_assigns_everything_to_the_payer() {
        let r = compute(
            &SplitSpec::PayerOnly(pid(2)),
            Money::new(7_777, Currency::INR),
            &[pid(1), pid(2)],
        )
        .unwrap();
        assert_eq!(r.share_of(pid(2)), 7_777);
        assert_eq!(r.share_of(pid(1)), 0);
    }

    #[test]
    fn non_member_reference_is_rejected() {
        let spec = SplitSpec::Exact(vec![(pid(9), 100)]);
        assert_eq!(
            compute(&spec, Money::new(100, Currency::INR), &[pid(1)]).unwrap_err(),
            SplitError::UnknownParticipant
        );
    }
}
