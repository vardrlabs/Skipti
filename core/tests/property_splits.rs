use skipti_core::hlutr::{compute, SplitSpec};
use skipti_core::money::{Currency, Money};

fn pid(n: u64) -> [u8; 16] {
    let mut b = [0u8; 16];
    b[8..].copy_from_slice(&n.to_be_bytes());
    b
}

fn members(n: usize) -> Vec<[u8; 16]> {
    (0..n as u64).map(pid).collect()
}

fn assert_equal_split_properties(total: i128, roster: &[[u8; 16]]) {
    let r = compute(&SplitSpec::Equal, Money::new(total, Currency::INR), roster)
        .unwrap_or_else(|e| panic!("total {total} n {}: {e}", roster.len()));
    let got: i128 = r.shares.iter().map(|(_, a)| a).sum();
    assert_eq!(got, total, "shares must sum exactly to total");
    let amounts: Vec<i128> = r.shares.iter().map(|(_, a)| *a).collect();
    let min = amounts.iter().min().unwrap();
    let max = amounts.iter().max().unwrap();
    assert!(
        max - min <= 1,
        "no two shares may differ by more than one minor unit"
    );
    let mut ids: Vec<[u8; 16]> = r.shares.iter().map(|(id, _)| *id).collect();
    ids.sort_unstable();
    assert_eq!(ids, {
        let mut m = roster.to_vec();
        m.sort_unstable();
        m
    });
}

#[test]
fn equal_split_sweep_sampled_totals_all_group_sizes() {
    let mut total: i128 = 1;
    while total <= 20_000 {
        for n in 2..=50usize {
            assert_equal_split_properties(total, &members(n));
        }
        total += if total < 100 {
            1
        } else if total < 1_000 {
            7
        } else {
            97
        };
    }
}

#[test]
#[ignore = "exhaustive SPEC 14.2 sweep; run in release via `make test`"]
fn equal_split_exhaustive_spec_range() {
    for total in 1..=1_000_000i128 {
        for n in 2..=50usize {
            assert_equal_split_properties(total, &members(n));
        }
    }
}

#[test]
fn results_are_identical_regardless_of_roster_input_order() {
    let forward = members(9);
    let mut backward = forward.clone();
    backward.reverse();
    for total in [1i128, 2, 3, 7, 100, 999, 12345, 1_000_000] {
        let a = compute(
            &SplitSpec::Equal,
            Money::new(total, Currency::INR),
            &forward,
        )
        .unwrap();
        let b = compute(
            &SplitSpec::Equal,
            Money::new(total, Currency::INR),
            &backward,
        )
        .unwrap();
        assert_eq!(a.shares, b.shares);
    }
}

#[test]
fn weighted_splits_sum_exactly_across_a_large_random_space() {
    let mut seed: u64 = 0x5EED_2026_0823;
    let mut rng = move || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed
    };
    for _ in 0..20_000 {
        let n = 2 + (rng() as usize) % 49;
        let roster = members(n);
        let total = 1 + (rng() % 10_000_000) as i128;
        let spec = match rng() % 4 {
            0 => SplitSpec::Shares(roster.iter().map(|p| (*p, 1 + (rng() % 50))).collect()),
            1 => {
                let mut left: u128 = 10_000;
                let mut entries = Vec::new();
                for (i, p) in roster.iter().enumerate() {
                    let take = if i == n - 1 {
                        left
                    } else {
                        1 + (rng() as u128) % (left / 3).max(1)
                    };
                    let take = take.min(left);
                    entries.push((*p, take));
                    left -= take;
                    if left == 0 {
                        break;
                    }
                }
                if left != 0 {
                    continue;
                }
                SplitSpec::Percentage(entries)
            }
            2 => SplitSpec::Adjustment({
                let d = (rng() % (total as u64 / 4).max(1)) as i128;
                vec![(roster[0], d)]
            }),
            _ => SplitSpec::Exact({
                let weights: Vec<([u8; 16], i128)> = roster
                    .iter()
                    .map(|p| (*p, 1 + (rng() % 1_000) as i128))
                    .collect();
                skipti_core::hlutr::distribute_weighted(total, &weights).unwrap()
            }),
        };
        let r = compute(&spec, Money::new(total, Currency::INR), &roster)
            .unwrap_or_else(|e| panic!("{e}"));
        let got: i128 = r.shares.iter().map(|(_, a)| a).sum();
        assert_eq!(got, total);
    }
}
