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
        sum = sum
            .checked_add(*v)
            .ok_or(RedistributionError::Invalid("overflow"))?;
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
        .map(|(m, s)| {
            if *s < 0 {
                (from, *m, -*s)
            } else {
                (*m, from, *s)
            }
        })
        .collect()
}

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
        assert_eq!(
            equal(-100000, &[p(1), p(2)]).unwrap(),
            vec![(p(1), -50000), (p(2), -50000)]
        );
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
            (p(1), 70000i128),
            (p(2), 50000),
            (p(3), -20000),
            (p(4), -100000),
            (p(5), 0),
        ]);
        let shares = equal(-100000, &[p(1), p(2), p(3)]).unwrap();
        for (payer, payee, amount) in settlements(p(4), &shares) {
            *net.get_mut(&payer).unwrap() += amount;
            *net.get_mut(&payee).unwrap() -= amount;
        }
        assert_eq!(
            net.values().copied().collect::<Vec<_>>(),
            vec![36666, 16667, -53333, 0, 0]
        );
    }

    #[test]
    fn the_codec_round_trips_and_refuses_strangers() {
        let r = Redistribution {
            group: "c".repeat(32),
            from: "d".repeat(32),
            currency: "INR".into(),
            shares: vec![Share {
                member: "a".repeat(32),
                amount: -33334,
            }],
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
        assert!(
            decode(br#"{"group":"x","from":"y","currency":"INR","shares":[],"extra":1}"#).is_err()
        );
    }
}
