//! D-180: Balance across every group, and one group's page (the founder's
//! canvas, 2026-09-26). The sums live here so neither shell adds money
//! (ADR-0002: integers, and every amount string still comes from
//! `money_format`). Totals are ONE PER CURRENCY and never converted (the
//! founder's decision, 2026-09-26).

use crate::ffi::{
    balance_line, parse_participant, CoreError, ExpenseInput, MemberWire, RedistributionInput,
    SettlementInput,
};

/// One group as the Balance tab sees it: its id, your id in it, and the
/// names of its people (for the People card).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OverviewGroupIn {
    pub group_id: String,
    pub viewer: String,
    pub members: Vec<MemberWire>,
    /// D-210: the people removed from this group (the projection's removed
    /// places), each with the name the shell has for them (its history name,
    /// or its own fallback). A removed person at 0 in a currency is taken out
    /// of that currency's People card amounts; one who still has a balance is
    /// named from here when `members` does not name them.
    pub removed: Vec<MemberWire>,
}

/// An amount in one currency. For a net, positive = money comes to you.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CurrencyAmountOut {
    pub currency_code: String,
    pub amount_minor: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupNetOut {
    pub group_id: String,
    /// Your net in this group, one per currency, largest first.
    pub nets: Vec<CurrencyAmountOut>,
}

/// One person across your groups, in one currency: positive = they owe you.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PersonNetOut {
    /// The name as the first group lists it.
    pub name: String,
    pub currency_code: String,
    pub net_minor: i64,
    /// The groups this person's amount comes from, in input order.
    pub group_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OverviewOut {
    /// Your net across every group, one per currency, largest first.
    pub totals: Vec<CurrencyAmountOut>,
    /// Every group given, in the order given.
    pub groups: Vec<GroupNetOut>,
    /// Everyone who owes you or whom you owe, largest first. People are
    /// matched across groups by name (case and surrounding spaces ignored)
    /// within one currency: a group's members are named, not identified,
    /// across groups.
    pub people: Vec<PersonNetOut>,
}

fn add(a: i64, b: i64) -> Result<i64, CoreError> {
    a.checked_add(b)
        .ok_or_else(|| CoreError::InvalidInput("amount overflow".to_string()))
}

/// Largest magnitude first; ties by currency code.
fn sort_amounts(v: &mut [CurrencyAmountOut]) {
    v.sort_by(|a, b| {
        b.amount_minor
            .unsigned_abs()
            .cmp(&a.amount_minor.unsigned_abs())
            .then_with(|| a.currency_code.cmp(&b.currency_code))
    });
}

/// Adds `amount` to the entry for `code`, creating it.
fn accumulate(v: &mut Vec<CurrencyAmountOut>, code: &str, amount: i64) -> Result<(), CoreError> {
    match v.iter_mut().find(|c| c.currency_code == code) {
        Some(c) => c.amount_minor = add(c.amount_minor, amount)?,
        None => v.push(CurrencyAmountOut {
            currency_code: code.to_string(),
            amount_minor: amount,
        }),
    }
    Ok(())
}

pub fn overview(
    groups: &[OverviewGroupIn],
    expenses: &[ExpenseInput],
    settlements: &[SettlementInput],
    redistributions: &[RedistributionInput],
) -> Result<OverviewOut, CoreError> {
    let mut totals: Vec<CurrencyAmountOut> = Vec::new();
    let mut per_group = Vec::with_capacity(groups.len());
    // (folded name, currency) -> person
    let mut people: Vec<(String, PersonNetOut)> = Vec::new();

    for g in groups {
        parse_participant(&g.group_id)?;
        parse_participant(&g.viewer)?;
        let removed: Vec<String> = g.removed.iter().map(|m| m.id.clone()).collect();
        let lines = balance_line(
            expenses,
            settlements,
            redistributions,
            &removed,
            &g.viewer,
            Some(&g.group_id),
        )?;
        let mut nets = Vec::new();
        for line in &lines {
            if line.viewer_net_minor != 0 {
                nets.push(CurrencyAmountOut {
                    currency_code: line.currency_code.clone(),
                    amount_minor: line.viewer_net_minor,
                });
                accumulate(&mut totals, &line.currency_code, line.viewer_net_minor)?;
            }
            for entry in &line.entries {
                if entry.id == g.viewer || entry.minor_units == 0 {
                    continue;
                }
                // A removed person is named from `removed` when `members`
                // (the screens' list) no longer names them.
                let name = g
                    .members
                    .iter()
                    .chain(g.removed.iter())
                    .find(|m| m.id == entry.id)
                    .map(|m| m.name.trim().to_string())
                    .ok_or_else(|| {
                        CoreError::InvalidInput(format!("no name for member {}", entry.id))
                    })?;
                let key = (name.to_lowercase(), line.currency_code.clone());
                match people
                    .iter_mut()
                    .find(|(k, p)| *k == key.0 && p.currency_code == key.1)
                {
                    Some((_, p)) => {
                        p.net_minor = add(p.net_minor, entry.minor_units)?;
                        if !p.group_ids.contains(&g.group_id) {
                            p.group_ids.push(g.group_id.clone());
                        }
                    }
                    None => people.push((
                        key.0,
                        PersonNetOut {
                            name,
                            currency_code: line.currency_code.clone(),
                            net_minor: entry.minor_units,
                            group_ids: vec![g.group_id.clone()],
                        },
                    )),
                }
            }
        }
        sort_amounts(&mut nets);
        per_group.push(GroupNetOut {
            group_id: g.group_id.clone(),
            nets,
        });
    }
    totals.retain(|t| t.amount_minor != 0);
    sort_amounts(&mut totals);
    let mut people: Vec<PersonNetOut> = people
        .into_iter()
        .map(|(_, p)| p)
        .filter(|p| p.net_minor != 0)
        .collect();
    people.sort_by(|a, b| {
        b.net_minor
            .unsigned_abs()
            .cmp(&a.net_minor.unsigned_abs())
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.currency_code.cmp(&b.currency_code))
    });
    Ok(OverviewOut {
        totals,
        groups: per_group,
        people,
    })
}

/// Balance across every group (D-180).
#[uniffi::export]
pub fn ffi_overview(
    groups: Vec<OverviewGroupIn>,
    expenses: Vec<ExpenseInput>,
    settlements: Vec<SettlementInput>,
    redistributions: Vec<RedistributionInput>,
) -> Result<OverviewOut, CoreError> {
    overview(&groups, &expenses, &settlements, &redistributions)
}

// ---------------------------------------------------------------------------
// A group's page: what was spent, your share, where the money went.
// ---------------------------------------------------------------------------

/// One category's slice of what a group spent. `basis_points` sum to
/// 10,000 across the slices (largest remainder, so no floats and no 99%).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CategoryShareOut {
    /// The category id, or None for expenses with none.
    pub category: Option<String>,
    pub amount_minor: i64,
    pub basis_points: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupStatsOut {
    /// Everything spent, per currency (a converted expense counts in the
    /// currency it was converted to), largest first.
    pub spent: Vec<CurrencyAmountOut>,
    /// Your shares of it, per currency, largest first.
    pub your_share: Vec<CurrencyAmountOut>,
    /// Where the money went, in the currency most was spent in; largest first.
    pub categories: Vec<CategoryShareOut>,
}

/// One of the group's (not deleted) expenses with its category.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StatsExpenseIn {
    pub expense: ExpenseInput,
    pub category: Option<String>,
}

pub fn group_stats(expenses: &[StatsExpenseIn], viewer: &str) -> Result<GroupStatsOut, CoreError> {
    parse_participant(viewer)?;
    let mut spent: Vec<CurrencyAmountOut> = Vec::new();
    let mut yours: Vec<CurrencyAmountOut> = Vec::new();
    let counted = |e: &ExpenseInput| -> (String, i64) {
        match &e.conversion {
            Some(c) => (c.converted_currency.clone(), c.converted_minor),
            None => (e.currency_code.clone(), e.paid_minor),
        }
    };
    for s in expenses {
        if s.expense.paid_minor < 0 {
            return Err(CoreError::InvalidInput("a negative expense".to_string()));
        }
        let (code, amount) = counted(&s.expense);
        accumulate(&mut spent, &code, amount)?;
        for share in s.expense.shares.iter().filter(|x| x.id == viewer) {
            accumulate(&mut yours, &code, share.amount_minor)?;
        }
    }
    spent.retain(|c| c.amount_minor != 0);
    yours.retain(|c| c.amount_minor != 0);
    sort_amounts(&mut spent);
    sort_amounts(&mut yours);

    let mut categories: Vec<CategoryShareOut> = Vec::new();
    if let Some(main) = spent.first() {
        for s in expenses {
            let (code, amount) = counted(&s.expense);
            if code != main.currency_code {
                continue;
            }
            match categories.iter_mut().find(|c| c.category == s.category) {
                Some(c) => c.amount_minor = add(c.amount_minor, amount)?,
                None => categories.push(CategoryShareOut {
                    category: s.category.clone(),
                    amount_minor: amount,
                    basis_points: 0,
                }),
            }
        }
        categories.retain(|c| c.amount_minor != 0);
        categories.sort_by(|a, b| {
            b.amount_minor
                .cmp(&a.amount_minor)
                .then_with(|| a.category.cmp(&b.category))
        });
        let total: i128 = categories.iter().map(|c| c.amount_minor as i128).sum();
        if total > 0 {
            // Largest remainder: floor every slice, then hand the points
            // left over to the largest remainders (ties: the larger slice,
            // already first).
            let mut rema: Vec<(usize, i128)> = Vec::new();
            let mut given: i128 = 0;
            for (i, c) in categories.iter_mut().enumerate() {
                let exact = c.amount_minor as i128 * 10_000;
                c.basis_points = (exact / total) as u32;
                given += exact / total;
                rema.push((i, exact % total));
            }
            rema.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
            for (i, _) in rema.into_iter().take((10_000 - given) as usize) {
                categories[i].basis_points += 1;
            }
        }
    }
    Ok(GroupStatsOut {
        spent,
        your_share: yours,
        categories,
    })
}

/// A group's page (D-180).
#[uniffi::export]
pub fn ffi_group_stats(
    expenses: Vec<StatsExpenseIn>,
    viewer: String,
) -> Result<GroupStatsOut, CoreError> {
    group_stats(&expenses, &viewer)
}

// ---------------------------------------------------------------------------
// D-181: what one expense does to you, for the Expenses tab's right column.
// ---------------------------------------------------------------------------

/// Positive: you lent that much (you paid more than your share); negative:
/// you owe it (your share of what someone else paid); zero: not involved.
/// In the currency the expense counts in (its converted one, if converted:
/// shares are in that currency).
pub fn your_effect(e: &ExpenseInput, viewer: &str) -> Result<CurrencyAmountOut, CoreError> {
    parse_participant(viewer)?;
    let (code, paid) = match &e.conversion {
        Some(c) => (c.converted_currency.clone(), c.converted_minor),
        None => (e.currency_code.clone(), e.paid_minor),
    };
    let mut effect: i64 = if e.payer == viewer { paid } else { 0 };
    for s in e.shares.iter().filter(|s| s.id == viewer) {
        effect = effect
            .checked_sub(s.amount_minor)
            .ok_or_else(|| CoreError::InvalidInput("amount overflow".to_string()))?;
    }
    Ok(CurrencyAmountOut {
        currency_code: code,
        amount_minor: effect,
    })
}

/// The Expenses tab's "you lent" / "you owe" (D-181).
#[uniffi::export]
pub fn ffi_your_effect(
    expense: ExpenseInput,
    viewer: String,
) -> Result<CurrencyAmountOut, CoreError> {
    your_effect(&expense, &viewer)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::{ConversionInput, IdAmount};

    fn pid(n: u8) -> String {
        format!("{:032x}", n)
    }

    fn exp(
        payer: &str,
        paid: i64,
        code: &str,
        group: &str,
        shares: &[(&str, i64)],
    ) -> ExpenseInput {
        ExpenseInput {
            payer: payer.to_string(),
            paid_minor: paid,
            currency_code: code.to_string(),
            shares: shares
                .iter()
                .map(|(id, a)| IdAmount {
                    id: id.to_string(),
                    amount_minor: *a,
                })
                .collect(),
            group: Some(group.to_string()),
            conversion: None,
        }
    }

    fn member(id: &str, name: &str) -> MemberWire {
        MemberWire {
            id: id.to_string(),
            name: name.to_string(),
        }
    }

    /// The canvas's world, re-derived by hand in .drafts/d180/golden.py:
    /// Goa (you 1, Rahul 2, Meera 3): you paid 1,200.00 split three ways
    /// (400 each); Rahul paid 300.00 split you/Rahul (150 each).
    /// You: +800 - 150 = +650.00; Rahul owes you 400 - 150 = 250.00;
    /// Meera owes you 400.00.
    /// Flat (you 11, Meera 13: another id, the same name): Meera paid
    /// 90.00 split evenly: you owe her 45.00.
    /// Paris (you 21, Ana 22): Ana paid €64.00 split evenly: you owe €32.00.
    #[test]
    fn the_overview_sums_per_currency_and_matches_people_by_name() {
        let (goa, flat, paris) = (pid(0xa0), pid(0xb0), pid(0xc0));
        let expenses = vec![
            exp(
                &pid(1),
                120_000,
                "INR",
                &goa,
                &[(&pid(1), 40_000), (&pid(2), 40_000), (&pid(3), 40_000)],
            ),
            exp(
                &pid(2),
                30_000,
                "INR",
                &goa,
                &[(&pid(1), 15_000), (&pid(2), 15_000)],
            ),
            exp(
                &pid(13),
                9_000,
                "INR",
                &flat,
                &[(&pid(11), 4_500), (&pid(13), 4_500)],
            ),
            exp(
                &pid(22),
                6_400,
                "EUR",
                &paris,
                &[(&pid(21), 3_200), (&pid(22), 3_200)],
            ),
        ];
        let groups = vec![
            OverviewGroupIn {
                group_id: goa.clone(),
                viewer: pid(1),
                members: vec![
                    member(&pid(1), "Prithvi"),
                    member(&pid(2), "Rahul"),
                    member(&pid(3), "Meera"),
                ],
                removed: vec![],
            },
            OverviewGroupIn {
                group_id: flat.clone(),
                viewer: pid(11),
                members: vec![member(&pid(11), "Prithvi"), member(&pid(13), " meera ")],
                removed: vec![],
            },
            OverviewGroupIn {
                group_id: paris.clone(),
                viewer: pid(21),
                members: vec![member(&pid(21), "Prithvi"), member(&pid(22), "Ana")],
                removed: vec![],
            },
        ];
        let out = overview(&groups, &expenses, &[], &[]).unwrap();
        let amt = |c: &str, a: i64| CurrencyAmountOut {
            currency_code: c.into(),
            amount_minor: a,
        };
        // INR: +65,000 - 4,500 = +60,500; EUR: -3,200. Never converted.
        assert_eq!(out.totals, vec![amt("INR", 60_500), amt("EUR", -3_200)]);
        assert_eq!(out.groups[0].nets, vec![amt("INR", 65_000)]);
        assert_eq!(out.groups[1].nets, vec![amt("INR", -4_500)]);
        assert_eq!(out.groups[2].nets, vec![amt("EUR", -3_200)]);
        // Meera: +40,000 in Goa, -4,500 in Flat: +35,500, both groups.
        let names: Vec<(&str, i64, usize)> = out
            .people
            .iter()
            .map(|p| (p.name.as_str(), p.net_minor, p.group_ids.len()))
            .collect();
        assert_eq!(
            names,
            vec![
                ("Meera", 35_500, 2),
                ("Rahul", 25_000, 1),
                ("Ana", -3_200, 1)
            ]
        );
        assert_eq!(out.people[0].group_ids, vec![goa, flat]);
    }

    /// D-218, L1 (.drafts/d218/cycles.py): B paid 1,000 for you (A), C paid
    /// 1,000 for B, and Settle all had you pay C 1,000. Everyone is at 0, so
    /// the People card list and the totals are empty (before D-218 it showed
    /// B -1,000 and C +1,000 for you).
    #[test]
    fn a_zero_net_loop_leaves_no_people_card() {
        let g = pid(0xa0);
        let groups = vec![OverviewGroupIn {
            group_id: g.clone(),
            viewer: pid(1),
            members: vec![
                member(&pid(1), "Me"),
                member(&pid(2), "Bea"),
                member(&pid(3), "Cy"),
            ],
            removed: vec![],
        }];
        let expenses = vec![
            exp(&pid(2), 1_000, "INR", &g, &[(&pid(1), 1_000)]),
            exp(&pid(3), 1_000, "INR", &g, &[(&pid(2), 1_000)]),
        ];
        let settlements = vec![SettlementInput {
            from: pid(1),
            to: pid(3),
            amount_minor: 1_000,
            currency_code: "INR".into(),
            group: Some(g.clone()),
        }];
        let out = overview(&groups, &expenses, &settlements, &[]).unwrap();
        assert!(out.people.is_empty(), "{:?}", out.people);
        assert!(out.totals.is_empty(), "{:?}", out.totals);
        assert!(out.groups[0].nets.is_empty());
    }

    /// D-218, L2 (.drafts/d218/cycles.py): you owe B 1,000, B owes C 600,
    /// and you pay C 600. You owe B 400 and nobody else: one card, B, -400.
    #[test]
    fn a_partial_loop_leaves_one_people_card() {
        let g = pid(0xa0);
        let groups = vec![OverviewGroupIn {
            group_id: g.clone(),
            viewer: pid(1),
            members: vec![
                member(&pid(1), "Me"),
                member(&pid(2), "Bea"),
                member(&pid(3), "Cy"),
            ],
            removed: vec![],
        }];
        let expenses = vec![
            exp(&pid(2), 1_000, "INR", &g, &[(&pid(1), 1_000)]),
            exp(&pid(3), 600, "INR", &g, &[(&pid(2), 600)]),
        ];
        let settlements = vec![SettlementInput {
            from: pid(1),
            to: pid(3),
            amount_minor: 600,
            currency_code: "INR".into(),
            group: Some(g.clone()),
        }];
        let out = overview(&groups, &expenses, &settlements, &[]).unwrap();
        assert_eq!(out.people.len(), 1, "{:?}", out.people);
        assert_eq!(out.people[0].name, "Bea");
        assert_eq!(out.people[0].net_minor, -400);
        assert_eq!(
            out.totals,
            vec![CurrencyAmountOut {
                currency_code: "INR".into(),
                amount_minor: -400
            }]
        );
    }

    #[test]
    fn an_empty_or_settled_world_has_no_totals_and_no_people() {
        let g = pid(0xa0);
        let groups = vec![OverviewGroupIn {
            group_id: g.clone(),
            viewer: pid(1),
            members: vec![member(&pid(1), "Me"), member(&pid(2), "Rahul")],
            removed: vec![],
        }];
        let out = overview(&groups, &[], &[], &[]).unwrap();
        assert!(out.totals.is_empty() && out.people.is_empty());
        assert_eq!(out.groups.len(), 1);
        // Rahul paid for you, you paid him back: settled.
        let settled = overview(
            &groups,
            &[exp(&pid(2), 5_000, "INR", &g, &[(&pid(1), 5_000)])],
            &[SettlementInput {
                from: pid(1),
                to: pid(2),
                amount_minor: 5_000,
                currency_code: "INR".into(),
                group: Some(g.clone()),
            }],
            &[],
        )
        .unwrap();
        assert!(settled.totals.is_empty() && settled.people.is_empty());
        assert!(settled.groups[0].nets.is_empty());
    }

    /// D-210: Prithvi (1) paid 1,000.00 for Sam (4) alone: Prithvi +100,000,
    /// Sam -100,000. Sam's -100,000 is redistributed equally onto 1, 2, 3
    /// (shares -33,334, -33,333, -33,333; .drafts/c3/golden.py "mock").
    /// Afterwards Prithvi +66,666, Rahul (2) -33,333, Asha (3) -33,333, Sam 0.
    /// From Prithvi's frame: his total and group net are +66,666. From Sam's
    /// frame: no total and no group net.
    #[test]
    fn the_overview_applies_a_redistribution() {
        use crate::ffi::{ffi_redistribute_equal, RedistributionInput};
        let g = pid(0xa0);
        let expenses = vec![exp(&pid(1), 100_000, "INR", &g, &[(&pid(4), 100_000)])];
        let shares = ffi_redistribute_equal(-100_000, vec![pid(1), pid(2), pid(3)]).unwrap();
        assert_eq!(
            shares.iter().map(|s| s.amount_minor).collect::<Vec<_>>(),
            vec![-33_334, -33_333, -33_333]
        );
        let r = RedistributionInput {
            group: g.clone(),
            from: pid(4),
            currency_code: "INR".into(),
            shares,
        };
        let members = vec![
            member(&pid(1), "Prithvi"),
            member(&pid(2), "Rahul"),
            member(&pid(3), "Asha"),
            member(&pid(4), "Sam"),
        ];
        let amt = |a: i64| CurrencyAmountOut {
            currency_code: "INR".into(),
            amount_minor: a,
        };
        let view = |viewer: u8| {
            overview(
                &[OverviewGroupIn {
                    group_id: g.clone(),
                    viewer: pid(viewer),
                    members: members.clone(),
                    removed: vec![],
                }],
                &expenses,
                &[],
                std::slice::from_ref(&r),
            )
            .unwrap()
        };
        let prithvi = view(1);
        assert_eq!(prithvi.totals, vec![amt(66_666)]);
        assert_eq!(prithvi.groups[0].nets, vec![amt(66_666)]);
        // The People card is pairwise, with Sam collapsed out (Fix 2, by
        // hand): Sam owed Prithvi 66,666 and was owed 33,333 each by Rahul
        // and Asha, so each of them now owes Prithvi 33,333; ties by name.
        let person = |name: &str, a: i64| PersonNetOut {
            name: name.into(),
            currency_code: "INR".into(),
            net_minor: a,
            group_ids: vec![g.clone()],
        };
        assert_eq!(
            prithvi.people,
            vec![person("Asha", 33_333), person("Rahul", 33_333)]
        );
        let sam = view(4);
        assert!(sam.totals.is_empty());
        assert!(sam.groups[0].nets.is_empty());
        assert!(sam.people.is_empty(), "{:?}", sam.people);
        // Without the redistribution Sam still owes everything.
        let before = overview(
            &[OverviewGroupIn {
                group_id: g.clone(),
                viewer: pid(4),
                members,
                removed: vec![],
            }],
            &expenses,
            &[],
            &[],
        )
        .unwrap();
        assert_eq!(before.totals, vec![amt(-100_000)]);
    }

    /// D-210 (final review C1), by hand as in the ffi test of the same
    /// world: Asha (3) paid 10000 for Sam (4), Sam paid 10000 for Prithvi
    /// (1). Sam is removed (not in `members`, named only in `removed`). At
    /// 0, Sam is collapsed out: Prithvi's card is Asha -10000. With a
    /// balance (Asha also paid 500 for Sam alone: Sam -500), Sam is named
    /// from `removed` and Home still loads.
    #[test]
    fn a_removed_person_is_collapsed_at_zero_and_named_when_owing() {
        let g = pid(0xa0);
        let mut expenses = vec![
            exp(&pid(3), 10_000, "INR", &g, &[(&pid(4), 10_000)]),
            exp(&pid(4), 10_000, "INR", &g, &[(&pid(1), 10_000)]),
        ];
        let view = |viewer: u8, expenses: &[ExpenseInput]| {
            overview(
                &[OverviewGroupIn {
                    group_id: g.clone(),
                    viewer: pid(viewer),
                    members: vec![member(&pid(1), "Prithvi"), member(&pid(3), "Asha")],
                    removed: vec![member(&pid(4), "Sam")],
                }],
                expenses,
                &[],
                &[],
            )
            .unwrap()
        };
        let person = |name: &str, a: i64| PersonNetOut {
            name: name.into(),
            currency_code: "INR".into(),
            net_minor: a,
            group_ids: vec![g.clone()],
        };
        assert_eq!(view(1, &expenses).people, vec![person("Asha", -10_000)]);
        assert_eq!(view(3, &expenses).people, vec![person("Prithvi", 10_000)]);
        expenses.push(exp(&pid(3), 500, "INR", &g, &[(&pid(4), 500)]));
        // Sam -500 now: pairwise (Sam, Asha) 10500, (Prithvi, Sam) 10000.
        assert_eq!(
            view(3, &expenses).people,
            vec![person("Sam", 10_500)],
            "Sam keeps a card, named from removed"
        );
    }

    #[test]
    fn a_member_with_no_name_or_a_bad_id_is_refused() {
        let g = pid(0xa0);
        let e = vec![exp(&pid(2), 5_000, "INR", &g, &[(&pid(1), 5_000)])];
        let nameless = vec![OverviewGroupIn {
            group_id: g.clone(),
            viewer: pid(1),
            members: vec![member(&pid(1), "Me")],
            removed: vec![],
        }];
        assert!(overview(&nameless, &e, &[], &[]).is_err());
        let bad = vec![OverviewGroupIn {
            group_id: "goa".into(),
            viewer: pid(1),
            members: vec![],
            removed: vec![],
        }];
        assert!(overview(&bad, &e, &[], &[]).is_err());
    }

    /// .drafts/d180/golden.py: in INR, food 300.00 + 900.00 (a €10.00
    /// expense converted to ₹900.00 counts at its converted amount), stay
    /// 700.00, no category 133.00: 2,033.00 in all. Basis points
    /// 1,200/2,033 = 5902.6.., 700/2,033 = 3443.1.., 133/2,033 = 654.2..;
    /// the floors sum to 9,999 and the one point left goes to the largest
    /// remainder (food): 5903 + 3443 + 654 = 10,000. A USD expense with no
    /// conversion stays USD and is not in the INR categories.
    #[test]
    fn group_stats_split_what_was_spent_into_whole_basis_points() {
        let g = pid(0xa0);
        let (me, rahul) = (pid(1), pid(2));
        let mut converted = exp(&rahul, 1_000, "EUR", &g, &[(&me, 45_000), (&rahul, 45_000)]);
        converted.conversion = Some(ConversionInput {
            converted_minor: 90_000,
            converted_currency: "INR".into(),
            rate_num: 90,
            rate_den: 1,
            rate_timestamp_ms: 0,
        });
        let s = |e: ExpenseInput, c: Option<&str>| StatsExpenseIn {
            expense: e,
            category: c.map(str::to_string),
        };
        let stats = group_stats(
            &[
                s(
                    exp(&me, 30_000, "INR", &g, &[(&me, 15_000), (&rahul, 15_000)]),
                    Some("food"),
                ),
                s(converted, Some("food")),
                s(
                    exp(
                        &rahul,
                        70_000,
                        "INR",
                        &g,
                        &[(&me, 35_000), (&rahul, 35_000)],
                    ),
                    Some("stay"),
                ),
                s(exp(&me, 13_300, "INR", &g, &[(&me, 13_300)]), None),
                s(
                    exp(&me, 2_500, "USD", &g, &[(&me, 1_250), (&rahul, 1_250)]),
                    Some("food"),
                ),
            ],
            &me,
        )
        .unwrap();
        let amt = |c: &str, a: i64| CurrencyAmountOut {
            currency_code: c.into(),
            amount_minor: a,
        };
        assert_eq!(stats.spent, vec![amt("INR", 203_300), amt("USD", 2_500)]);
        // 15,000 + 45,000 + 35,000 + 13,300 = 108,300; USD 1,250.
        assert_eq!(
            stats.your_share,
            vec![amt("INR", 108_300), amt("USD", 1_250)]
        );
        let got: Vec<(Option<&str>, i64, u32)> = stats
            .categories
            .iter()
            .map(|c| (c.category.as_deref(), c.amount_minor, c.basis_points))
            .collect();
        assert_eq!(
            got,
            vec![
                (Some("food"), 120_000, 5903),
                (Some("stay"), 70_000, 3443),
                (None, 13_300, 654)
            ]
        );
    }

    #[test]
    fn group_stats_refuse_a_bad_viewer_and_a_negative_expense() {
        let g = pid(0xa0);
        assert!(group_stats(&[], "me").is_err());
        let neg = StatsExpenseIn {
            expense: exp(&pid(1), -5, "INR", &g, &[]),
            category: None,
        };
        assert!(group_stats(&[neg], &pid(1)).is_err());
        let empty = group_stats(&[], &pid(1)).unwrap();
        assert!(empty.spent.is_empty() && empty.categories.is_empty());
    }

    /// Worked by hand: you paid 1,200.00 split three ways (400 each): you
    /// lent 800.00. Rahul paid 300.00 split you/Rahul: you owe 150.00.
    /// Meera paid for Rahul alone: not involved, 0. A €10.00 expense
    /// converted to ₹900.00, you paid, split evenly: you lent ₹450.00.
    #[test]
    fn your_effect_is_what_you_paid_less_your_share() {
        let g = pid(0xa0);
        let (me, rahul, meera) = (pid(1), pid(2), pid(3));
        let lent = your_effect(
            &exp(
                &me,
                120_000,
                "INR",
                &g,
                &[(&me, 40_000), (&rahul, 40_000), (&meera, 40_000)],
            ),
            &me,
        )
        .unwrap();
        assert_eq!(
            (lent.currency_code.as_str(), lent.amount_minor),
            ("INR", 80_000)
        );
        let owe = your_effect(
            &exp(
                &rahul,
                30_000,
                "INR",
                &g,
                &[(&me, 15_000), (&rahul, 15_000)],
            ),
            &me,
        )
        .unwrap();
        assert_eq!(owe.amount_minor, -15_000);
        let none = your_effect(&exp(&meera, 5_000, "INR", &g, &[(&rahul, 5_000)]), &me).unwrap();
        assert_eq!(none.amount_minor, 0);
        let mut converted = exp(&me, 1_000, "EUR", &g, &[(&me, 45_000), (&rahul, 45_000)]);
        converted.conversion = Some(ConversionInput {
            converted_minor: 90_000,
            converted_currency: "INR".into(),
            rate_num: 90,
            rate_den: 1,
            rate_timestamp_ms: 0,
        });
        let c = your_effect(&converted, &me).unwrap();
        assert_eq!((c.currency_code.as_str(), c.amount_minor), ("INR", 45_000));
        assert!(your_effect(&converted, "me").is_err());
    }
}
