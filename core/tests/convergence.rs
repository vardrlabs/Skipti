use skipti_core::fx::{convert, FxRate};
use skipti_core::hlutr::{compute, SplitSpec};
use skipti_core::money::Currency;
use skipti_core::norn::{balances, simplify_group, ExpenseRecord, Group, Ledger, SettlementRecord};

type Pid = [u8; 16];

fn pid(n: u8) -> Pid {
    let mut b = [0u8; 16];
    b[15] = n;
    b
}

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

fn build_scenario(seed: u64) -> (Vec<ExpenseRecord>, Vec<SettlementRecord>, Vec<Group>) {
    let mut rng = Rng(seed);
    let roster: Vec<Pid> = (1..=6).map(pid).collect();

    let groups = vec![
        Group {
            id: [0xA0; 16],
            settlement_currency: Currency::INR,
            members: roster.clone(),
        },
        Group {
            id: [0xB0; 16],
            settlement_currency: Currency::USD,
            members: roster.clone(),
        },
        Group {
            id: [0xC0; 16],
            settlement_currency: Currency::EUR,
            members: roster.clone(),
        },
    ];

    let rate_for = |from: Currency, to: Currency| -> FxRate {
        if from == to {
            return FxRate::new(from, to, 1, 1, 100).unwrap();
        }
        let seed_num: i128 = from
            .code()
            .bytes()
            .map(|b| b as i128)
            .sum::<i128>()
            .checked_mul(7_919)
            .unwrap()
            % 400_000;
        FxRate::new(from, to, 800_000 + seed_num.abs(), 1_000_000, 100).unwrap()
    };
    let rates = |from: Currency, to: Currency| -> FxRate { rate_for(from, to) };

    let currencies = [
        Currency::INR,
        Currency::parse("GBP").unwrap(),
        Currency::USD,
        Currency::JPY,
        Currency::EUR,
        Currency::parse("CHF").unwrap(),
    ];

    let mut expenses = Vec::new();
    for i in 0..600u32 {
        let g = &groups[rng.below(3) as usize];
        let payer = roster[rng.below(6) as usize];
        let original_ccy = currencies[rng.below(6) as usize];
        let amount_major: i128 = 1 + rng.below(50_000) as i128;
        let exp = original_ccy.exponent();
        let amount = if exp == 0 {
            amount_major * 100
        } else {
            amount_major * 10i128.pow(exp as u32)
        };
        let paid = skipti_core::money::Money::new(amount, original_ccy);
        let rate = rates(original_ccy, g.settlement_currency);
        let conversion = convert(paid, rate).unwrap();
        let effective_amount = conversion.converted.amount;

        let split = match rng.below(3) {
            0 => SplitSpec::Equal,
            1 => SplitSpec::Shares(
                roster
                    .iter()
                    .map(|p| (*p, 1 + rng.below(4)))
                    .collect::<Vec<(Pid, u64)>>(),
            ),
            _ => SplitSpec::Exact(
                [(payer, effective_amount)]
                    .into_iter()
                    .collect::<Vec<(Pid, i128)>>(),
            ),
        };
        let computed = compute(&split, conversion.converted, &g.members)
            .unwrap_or_else(|e| panic!("scenario generation failed at op {i}: {e}"));

        expenses.push(ExpenseRecord {
            id: (i as u64).to_be_bytes().repeat(2).try_into().unwrap(),
            version: 1,
            group: Some(g.id),
            payer,
            paid,
            conversion: Some(conversion),
            shares: computed.shares,
            timestamp_ms: i as u64 * 60_000,
        });
    }

    let mut settlements = Vec::new();
    for i in 0..12u32 {
        let g = &groups[(i % 3) as usize];
        settlements.push(SettlementRecord {
            id: (10_000u64 + i as u64)
                .to_be_bytes()
                .repeat(2)
                .try_into()
                .unwrap(),
            group: g.id,
            from: roster[rng.below(6) as usize],
            to: roster[rng.below(6) as usize],
            amount: skipti_core::money::Money::new(
                (1 + rng.below(9_000)) as i128,
                g.settlement_currency,
            ),
            timestamp_ms: 900_000_000 + i as u64 * 60_000,
        });
    }

    (expenses, settlements, groups)
}

fn apply_in_order(
    expenses: &[ExpenseRecord],
    settlements: &[SettlementRecord],
    groups: &[Group],
    order_seed: u64,
) -> Ledger {
    let mut ledger = Ledger {
        groups: groups.to_vec(),
        ..Default::default()
    };
    let mut combined: Vec<Op> = Vec::new();
    enum Op<'a> {
        E(&'a ExpenseRecord),
        S(&'a SettlementRecord),
    }
    for e in expenses {
        combined.push(Op::E(e));
    }
    for s in settlements {
        combined.push(Op::S(s));
    }

    let mut rng = Rng(order_seed | 1);
    for i in (1..combined.len()).rev() {
        let j = rng.below((i + 1) as u64) as usize;
        combined.swap(i, j);
    }

    for op in &combined {
        match op {
            Op::E(e) => ledger.apply_expense((*e).clone()).unwrap(),
            Op::S(s) => ledger.apply_settlement((*s).clone()).unwrap(),
        }
    }
    ledger
}

#[test]
fn gate_three_devices_converge_to_byte_identical_balances() {
    let (expenses, settlements, groups) = build_scenario(0xC0FFEE);

    let devices = [
        apply_in_order(&expenses, &settlements, &groups, 11),
        apply_in_order(&expenses, &settlements, &groups, 22),
        apply_in_order(&expenses, &settlements, &groups, 33),
    ];
    assert_eq!(devices.len(), 3);

    let canonical: Vec<Vec<u8>> = devices
        .iter()
        .map(|l| balances(l).to_canonical_bytes())
        .collect();
    assert_eq!(canonical[0], canonical[1]);
    assert_eq!(canonical[1], canonical[2]);
    assert!(!canonical[0].is_empty());

    for gid in [[0xA0u8; 16], [0xB0u8; 16], [0xC0u8; 16]] {
        let a = simplify_group(&devices[0], gid);
        let b = simplify_group(&devices[1], gid);
        let c = simplify_group(&devices[2], gid);
        assert_eq!(a, b);
        assert_eq!(b, c);
        for transfers in a.values() {
            assert!(transfers.len() <= 5, "at most n-1 transfers for n=6");
        }
    }
}

#[test]
fn gate_edits_and_reversed_order_still_converge() {
    let (mut expenses, settlements, groups) = build_scenario(0xABCDEF);

    let mut edited = expenses.clone();
    for e in edited.iter_mut().step_by(7) {
        e.version = 2;
        e.shares = vec![(e.payer, e.effective().amount)];
    }
    edited.truncate(edited.len());
    expenses = edited;

    let forward = apply_in_order(&expenses, &settlements, &groups, 101);
    let mut reversed_all: Vec<ExpenseRecord> = expenses.clone();
    reversed_all.reverse();
    let backward_ledger = Ledger {
        groups: groups.to_vec(),
        expenses: reversed_all,
        settlements: settlements.iter().rev().cloned().collect(),
    };

    assert_eq!(
        balances(&forward).to_canonical_bytes(),
        balances(&backward_ledger).to_canonical_bytes()
    );
}
