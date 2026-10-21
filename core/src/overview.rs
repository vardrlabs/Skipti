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

/// Gives each slice its whole basis points of the slices' total, summing to
/// 10,000 (largest remainder: floor every slice, then hand the points left
/// over to the largest remainders; ties: the earlier slice, which the caller
/// has sorted larger first). Slices are never negative here (both callers
/// refuse a negative amount first); a zero total leaves every slice at 0.
fn basis_points(categories: &mut [CategoryShareOut]) {
    let total: i128 = categories.iter().map(|c| c.amount_minor as i128).sum();
    if total > 0 {
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
        basis_points(&mut categories);
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
// D-248: "Just me" and "All my spending" (U10). One function for both views
// and both shells, so one golden table (.drafts/d248/goldens.py) pins both.
// ---------------------------------------------------------------------------

/// Which spending the page shows.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SpendingView {
    /// Your own ledger only: personal expenses (no group), each at its full
    /// amount.
    JustMe,
    /// Just me plus YOUR SHARE of every group expense (what you paid for
    /// others is a loan, not spending, so it never counts).
    All,
}

/// One live (not deleted) expense, as the shells hold it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpendingExpenseIn {
    /// The projected expense's id (`ProjectedExpenseOut.id`), carried to its row.
    pub id: String,
    pub expense: ExpenseInput,
    pub note: Option<String>,
    /// The expense's own category (the leaf id, as stored), carried to its row.
    pub category: Option<String>,
    /// The category its slice counts under: the shells pass the parent, as
    /// the group page does for `group_stats`; None is the "No category" slice.
    pub slice_category: Option<String>,
    /// When it happened, if set (`ProjectedExpenseOut.timestamp_ms`).
    pub timestamp_ms: Option<u64>,
    /// When it was added (`ProjectedExpenseOut.added_at_ms`): the date when
    /// `timestamp_ms` is absent.
    pub added_at_ms: u64,
}

/// What one currency's spending was, and where it went.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CurrencySpendingOut {
    pub currency_code: String,
    pub total_minor: i64,
    /// This currency's slices, largest first, basis points summing to 10,000.
    pub categories: Vec<CategoryShareOut>,
}

/// D-249 (decision 205): who you are in one group, as the Balance tab reads
/// it (`OverviewGroupIn.viewer`). All counts a group expense's share only
/// when it is held by YOUR id in THAT expense's group, so an id that means
/// you in one group can never count someone else's share in another (the
/// D-237 A1 shape: a group's maker can name any id as a person).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpendingGroupIn {
    pub group_id: String,
    /// Your id in this group (its `selfId`).
    pub viewer: String,
}

/// One expense that counts in the window.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpendingRowOut {
    pub id: String,
    /// `timestamp_ms`, else `added_at_ms`: the date the window was read on.
    pub date_ms: u64,
    pub note: Option<String>,
    /// The expense's own category (the leaf), as given.
    pub category: Option<String>,
    /// The currency it counts in: its converted one, if converted.
    pub currency_code: String,
    /// What counts: the full amount for a Just me expense, your share for a
    /// group one.
    pub amount_minor: i64,
    /// The group it belongs to; None for Just me.
    pub group: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SpendingOut {
    /// One per currency, never converted into each other, largest total
    /// first (ties by code). Empty when nothing counts.
    pub currencies: Vec<CurrencySpendingOut>,
    /// Every expense that counts, newest first (ties: the larger id first).
    /// A Just me expense of 0 is still listed (it was logged); a group
    /// expense is listed only when your share of it is above 0.
    pub rows: Vec<SpendingRowOut>,
}

/// D-248: what you spent in `[from_ms, until_ms)` (the shells pass the
/// phone's month bounds). Just me counts personal expenses (no group) at
/// their full amount; All adds your share of every group expense, read as
/// the share held by your id in that expense's own group (`groups`; a group
/// not listed counts nothing), and lists a group expense only when that share
/// is above zero. Settlements and redistributions are not inputs, so they never
/// count. A converted expense counts in its converted currency (its shares
/// are in it), as `group_stats` counts it. No floats (ADR-0002).
pub fn spending(
    expenses: &[SpendingExpenseIn],
    groups: &[SpendingGroupIn],
    from_ms: u64,
    until_ms: u64,
    view: SpendingView,
) -> Result<SpendingOut, CoreError> {
    for (i, g) in groups.iter().enumerate() {
        parse_participant(&g.viewer)?;
        if groups[..i].iter().any(|h| h.group_id == g.group_id) {
            return Err(CoreError::InvalidInput(
                "a group is listed twice".to_string(),
            ));
        }
    }
    if from_ms > until_ms {
        return Err(CoreError::InvalidInput(
            "the window ends before it starts".to_string(),
        ));
    }
    // (row, its slice)
    let mut counted: Vec<(SpendingRowOut, Option<String>)> = Vec::new();
    for s in expenses {
        let e = &s.expense;
        if e.paid_minor < 0 || e.shares.iter().any(|x| x.amount_minor < 0) {
            return Err(CoreError::InvalidInput("a negative expense".to_string()));
        }
        let date_ms = s.timestamp_ms.unwrap_or(s.added_at_ms);
        if date_ms < from_ms || date_ms >= until_ms {
            continue;
        }
        let (code, full) = match &e.conversion {
            Some(c) => (c.converted_currency.clone(), c.converted_minor),
            None => (e.currency_code.clone(), e.paid_minor),
        };
        if full < 0 {
            return Err(CoreError::InvalidInput("a negative expense".to_string()));
        }
        let amount = match (&e.group, view) {
            (None, _) => full,
            (Some(_), SpendingView::JustMe) => continue,
            (Some(group), SpendingView::All) => {
                let Some(you) = groups.iter().find(|g| &g.group_id == group) else {
                    continue;
                };
                let mut yours: i64 = 0;
                for x in e.shares.iter().filter(|x| x.id == you.viewer) {
                    yours = add(yours, x.amount_minor)?;
                }
                if yours == 0 {
                    continue;
                }
                yours
            }
        };
        counted.push((
            SpendingRowOut {
                id: s.id.clone(),
                date_ms,
                note: s.note.clone(),
                category: s.category.clone(),
                currency_code: code,
                amount_minor: amount,
                group: e.group.clone(),
            },
            s.slice_category.clone(),
        ));
    }

    let mut currencies: Vec<CurrencySpendingOut> = Vec::new();
    for (row, slice) in &counted {
        let i = match currencies
            .iter()
            .position(|c| c.currency_code == row.currency_code)
        {
            Some(i) => i,
            None => {
                currencies.push(CurrencySpendingOut {
                    currency_code: row.currency_code.clone(),
                    total_minor: 0,
                    categories: Vec::new(),
                });
                currencies.len() - 1
            }
        };
        let c = &mut currencies[i];
        c.total_minor = add(c.total_minor, row.amount_minor)?;
        match c.categories.iter_mut().find(|x| &x.category == slice) {
            Some(x) => x.amount_minor = add(x.amount_minor, row.amount_minor)?,
            None => c.categories.push(CategoryShareOut {
                category: slice.clone(),
                amount_minor: row.amount_minor,
                basis_points: 0,
            }),
        }
    }
    currencies.retain(|c| c.total_minor != 0);
    for c in &mut currencies {
        c.categories.retain(|x| x.amount_minor != 0);
        c.categories.sort_by(|a, b| {
            b.amount_minor
                .cmp(&a.amount_minor)
                .then_with(|| a.category.cmp(&b.category))
        });
        basis_points(&mut c.categories);
    }
    currencies.sort_by(|a, b| {
        b.total_minor
            .cmp(&a.total_minor)
            .then_with(|| a.currency_code.cmp(&b.currency_code))
    });

    let mut rows: Vec<SpendingRowOut> = counted.into_iter().map(|(r, _)| r).collect();
    rows.sort_by(|a, b| b.date_ms.cmp(&a.date_ms).then_with(|| b.id.cmp(&a.id)));
    Ok(SpendingOut { currencies, rows })
}

/// The "Your spending" card and page (D-248).
#[uniffi::export]
pub fn ffi_spending(
    expenses: Vec<SpendingExpenseIn>,
    groups: Vec<SpendingGroupIn>,
    from_ms: u64,
    until_ms: u64,
    view: SpendingView,
) -> Result<SpendingOut, CoreError> {
    spending(&expenses, &groups, from_ms, until_ms, view)
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

    // -----------------------------------------------------------------------
    // D-248: spending. Every number below is printed by
    // .drafts/d248/goldens.py (python3 -I), which derives the dates from
    // zoneinfo (Asia/Kolkata), the slices from exact Fractions and the
    // category ids from sha256; none is read back out of this code.
    // -----------------------------------------------------------------------

    const FOOD: &str = "80ed7c35199cb754fe9f4ded5d5eea82";
    const TRAVEL: &str = "2d07f6758ac067ac266ddb818b931c56";
    const ENT: &str = "2b3965b02573a0d9da7ca90ff06a50cc";
    const OCT_FROM: u64 = 1_790_793_000_000; // 2026-10-01 00:00 +05:30
    const OCT_UNTIL: u64 = 1_793_471_400_000; // 2026-11-01 00:00 +05:30
    const NOV_UNTIL: u64 = 1_796_063_400_000; // 2026-12-01 00:00 +05:30
    const J2_AT: u64 = 1_793_469_600_000; // 2026-10-31 23:30 +05:30

    #[allow(clippy::too_many_arguments)]
    fn spent(
        id: &str,
        group: Option<&str>,
        payer: &str,
        paid: i64,
        code: &str,
        shares: &[(&str, i64)],
        slice: Option<&str>,
        at: u64,
    ) -> SpendingExpenseIn {
        SpendingExpenseIn {
            id: id.to_string(),
            expense: ExpenseInput {
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
                group: group.map(str::to_string),
                conversion: None,
            },
            note: Some(format!("note {id}")),
            category: slice.map(|s| format!("leaf of {s}")),
            slice_category: slice.map(str::to_string),
            timestamp_ms: Some(at),
            // Added long before either window: the date read is the
            // expense's own (one row below has none and falls back).
            added_at_ms: 0,
        }
    }

    /// goldens.py's world: you are pid(1) in Just me and pid(0x11) in Goa.
    fn world() -> (Vec<SpendingExpenseIn>, Vec<SpendingGroupIn>) {
        let (me, you_goa, rahul, meera) = (pid(1), pid(0x11), pid(0x12), pid(0x13));
        let goa = pid(0xa0);
        let g = Some(goa.as_str());
        let mut j4 = spent(
            "J4",
            None,
            &me,
            1_200,
            "USD",
            &[(&me, 100_200)],
            None,
            1_792_467_000_000,
        );
        j4.expense.conversion = Some(ConversionInput {
            converted_minor: 100_200,
            converted_currency: "INR".into(),
            rate_num: 8_350,
            rate_den: 100,
            rate_timestamp_ms: 0,
        });
        let mut j6 = spent("J6", None, &me, 450, "EUR", &[(&me, 450)], Some(FOOD), 0);
        j6.timestamp_ms = None;
        j6.added_at_ms = 1_792_031_400_000;
        let expenses = vec![
            spent(
                "J1",
                None,
                &me,
                254_750,
                "INR",
                &[(&me, 254_750)],
                Some(FOOD),
                1_791_174_600_000,
            ),
            spent(
                "J2",
                None,
                &me,
                45_000,
                "INR",
                &[(&me, 45_000)],
                Some(TRAVEL),
                J2_AT,
            ),
            spent(
                "J3",
                None,
                &me,
                120_000,
                "INR",
                &[(&me, 120_000)],
                Some(FOOD),
                OCT_UNTIL,
            ),
            j4,
            spent(
                "J5",
                None,
                &me,
                1_800,
                "EUR",
                &[(&me, 1_800)],
                Some(ENT),
                1_791_959_400_000,
            ),
            j6,
            spent(
                "G1",
                g,
                &rahul,
                300_000,
                "INR",
                &[(&you_goa, 100_000), (&rahul, 100_000), (&meera, 100_000)],
                Some(FOOD),
                1_791_642_600_000,
            ),
            spent(
                "G2",
                g,
                &you_goa,
                90_000,
                "INR",
                &[(&you_goa, 30_000), (&rahul, 30_000), (&meera, 30_000)],
                Some(TRAVEL),
                1_791_689_400_000,
            ),
            spent(
                "G3",
                g,
                &you_goa,
                50_000,
                "INR",
                &[(&rahul, 50_000)],
                Some(TRAVEL),
                1_791_775_800_000,
            ),
            spent(
                "G4",
                g,
                &meera,
                3_000,
                "EUR",
                &[(&you_goa, 1_500), (&meera, 1_500)],
                Some(ENT),
                1_792_153_800_000,
            ),
        ];
        (
            expenses,
            vec![SpendingGroupIn {
                group_id: goa,
                viewer: you_goa,
            }],
        )
    }

    type Shape = Vec<(String, i64, Vec<(Option<String>, i64, u32)>)>;

    fn shape(out: &SpendingOut) -> (Shape, Vec<(String, String, i64)>) {
        (
            out.currencies
                .iter()
                .map(|c| {
                    (
                        c.currency_code.clone(),
                        c.total_minor,
                        c.categories
                            .iter()
                            .map(|x| (x.category.clone(), x.amount_minor, x.basis_points))
                            .collect(),
                    )
                })
                .collect(),
            out.rows
                .iter()
                .map(|r| (r.id.clone(), r.currency_code.clone(), r.amount_minor))
                .collect(),
        )
    }

    fn sl(c: Option<&str>, a: i64, b: u32) -> (Option<String>, i64, u32) {
        (c.map(str::to_string), a, b)
    }

    fn rw(id: &str, c: &str, a: i64) -> (String, String, i64) {
        (id.to_string(), c.to_string(), a)
    }

    #[test]
    fn just_me_counts_its_own_expenses_in_full_per_currency() {
        let (expenses, you) = world();
        let out = spending(&expenses, &you, OCT_FROM, OCT_UNTIL, SpendingView::JustMe).unwrap();
        let (currencies, rows) = shape(&out);
        assert_eq!(
            currencies,
            vec![
                (
                    "INR".to_string(),
                    399_950,
                    vec![
                        sl(Some(FOOD), 254_750, 6370),
                        sl(None, 100_200, 2505),
                        sl(Some(TRAVEL), 45_000, 1125),
                    ]
                ),
                (
                    "EUR".to_string(),
                    2_250,
                    vec![sl(Some(ENT), 1_800, 8000), sl(Some(FOOD), 450, 2000)]
                ),
            ]
        );
        assert_eq!(
            rows,
            vec![
                rw("J2", "INR", 45_000),
                rw("J4", "INR", 100_200),
                rw("J6", "EUR", 450),
                rw("J5", "EUR", 1_800),
                rw("J1", "INR", 254_750),
            ]
        );
        // A row carries what the page shows: its date (J6 has no date of its
        // own: when it was added), note, leaf category, and no group.
        let j6 = out.rows.iter().find(|r| r.id == "J6").unwrap();
        assert_eq!(j6.date_ms, 1_792_031_400_000);
        assert_eq!(j6.note.as_deref(), Some("note J6"));
        assert_eq!(j6.category, Some(format!("leaf of {FOOD}")));
        assert!(out.rows.iter().all(|r| r.group.is_none()));
    }

    #[test]
    fn all_my_spending_adds_only_your_share_of_group_expenses() {
        let (expenses, you) = world();
        let out = spending(&expenses, &you, OCT_FROM, OCT_UNTIL, SpendingView::All).unwrap();
        let (currencies, rows) = shape(&out);
        assert_eq!(
            currencies,
            vec![
                (
                    "INR".to_string(),
                    529_950,
                    vec![
                        sl(Some(FOOD), 354_750, 6694),
                        sl(None, 100_200, 1891),
                        sl(Some(TRAVEL), 75_000, 1415),
                    ]
                ),
                (
                    "EUR".to_string(),
                    3_750,
                    vec![sl(Some(ENT), 3_300, 8800), sl(Some(FOOD), 450, 1200)]
                ),
            ]
        );
        assert_eq!(
            rows,
            vec![
                rw("J2", "INR", 45_000),
                rw("J4", "INR", 100_200),
                rw("G4", "EUR", 1_500),
                rw("J6", "EUR", 450),
                rw("J5", "EUR", 1_800),
                rw("G2", "INR", 30_000),
                rw("G1", "INR", 100_000),
                rw("J1", "INR", 254_750),
            ]
        );
        // You paid G2's 900.00 for everyone: only your 300.00 is spending.
        // You paid G3 for Rahul alone: your share is 0, so it is not listed.
        let g2 = out.rows.iter().find(|r| r.id == "G2").unwrap();
        assert_eq!(g2.amount_minor, 30_000);
        assert_eq!(g2.group, Some(pid(0xa0)));
        assert!(out.rows.iter().all(|r| r.id != "G3"));
    }

    #[test]
    fn just_me_never_counts_a_group_expense() {
        let (expenses, you) = world();
        let groups: Vec<SpendingExpenseIn> = expenses
            .into_iter()
            .filter(|e| e.expense.group.is_some())
            .collect();
        let out = spending(&groups, &you, OCT_FROM, OCT_UNTIL, SpendingView::JustMe).unwrap();
        assert!(out.currencies.is_empty() && out.rows.is_empty());
        // The same expenses count in All (your shares: 1,000 + 300 INR, 15 EUR).
        let all = spending(&groups, &you, OCT_FROM, OCT_UNTIL, SpendingView::All).unwrap();
        assert_eq!(all.rows.len(), 3);
    }

    /// The month boundary, both ways: 23:30 on the last day is inside
    /// October and 00:00 on the 1st is not (the window's end is exclusive);
    /// the same 00:00 expense opens November (its start is inclusive).
    #[test]
    fn the_window_includes_its_start_and_excludes_its_end() {
        let (expenses, you) = world();
        let oct = spending(&expenses, &you, OCT_FROM, OCT_UNTIL, SpendingView::JustMe).unwrap();
        assert!(oct.rows.iter().any(|r| r.id == "J2"));
        assert!(oct.rows.iter().all(|r| r.id != "J3"));
        let nov = spending(&expenses, &you, OCT_UNTIL, NOV_UNTIL, SpendingView::JustMe).unwrap();
        let (currencies, rows) = shape(&nov);
        assert_eq!(
            currencies,
            vec![(
                "INR".to_string(),
                120_000,
                vec![sl(Some(FOOD), 120_000, 10_000)]
            )]
        );
        assert_eq!(rows, vec![rw("J3", "INR", 120_000)]);
        // A window ending exactly at J2 leaves J2 out; one a millisecond
        // later takes it in.
        let before = spending(&expenses, &you, J2_AT - 1, J2_AT, SpendingView::JustMe).unwrap();
        assert!(before.rows.is_empty() && before.currencies.is_empty());
        let at = spending(&expenses, &you, J2_AT, J2_AT + 1, SpendingView::JustMe).unwrap();
        assert_eq!(shape(&at).1, vec![rw("J2", "INR", 45_000)]);
        // An empty window counts nothing.
        let empty = spending(&expenses, &you, OCT_FROM, OCT_FROM, SpendingView::All).unwrap();
        assert!(empty.rows.is_empty() && empty.currencies.is_empty());
    }

    /// Decision 205 (D-249): All reads your share with each group's own
    /// "you". goldens.py's reuse case: you are pid(0x11) in Goa and
    /// pid(0x21) in Flat 4B, where the maker named Kabir pid(0x11). Only your
    /// 200.00 of F1 counts (the old rule, any of your ids in any group,
    /// counted 600.00), and Kabir's own F2 is not yours at all.
    #[test]
    fn all_counts_your_share_with_each_groups_own_you() {
        let (mut expenses, mut groups) = world();
        let (kabir, you_flat, asha) = (pid(0x11), pid(0x21), pid(0x22));
        let flat = pid(0xb0);
        expenses.push(spent(
            "F1",
            Some(&flat),
            &asha,
            60_000,
            "INR",
            &[(&kabir, 40_000), (&you_flat, 20_000)],
            Some(FOOD),
            1_791_898_200_000,
        ));
        expenses.push(spent(
            "F2",
            Some(&flat),
            &kabir,
            1_000,
            "EUR",
            &[(&kabir, 1_000)],
            Some(ENT),
            1_792_218_600_000,
        ));
        groups.push(SpendingGroupIn {
            group_id: flat.clone(),
            viewer: you_flat,
        });
        let out = spending(&expenses, &groups, OCT_FROM, OCT_UNTIL, SpendingView::All).unwrap();
        let (currencies, rows) = shape(&out);
        assert_eq!(
            currencies,
            vec![
                (
                    "INR".to_string(),
                    549_950,
                    vec![
                        sl(Some(FOOD), 374_750, 6814),
                        sl(None, 100_200, 1822),
                        sl(Some(TRAVEL), 75_000, 1364),
                    ]
                ),
                (
                    "EUR".to_string(),
                    3_750,
                    vec![sl(Some(ENT), 3_300, 8800), sl(Some(FOOD), 450, 1200)]
                ),
            ]
        );
        assert_eq!(
            rows,
            vec![
                rw("J2", "INR", 45_000),
                rw("J4", "INR", 100_200),
                rw("G4", "EUR", 1_500),
                rw("J6", "EUR", 450),
                rw("J5", "EUR", 1_800),
                rw("F1", "INR", 20_000),
                rw("G2", "INR", 30_000),
                rw("G1", "INR", 100_000),
                rw("J1", "INR", 254_750),
            ]
        );
        // Negative: Kabir's 400.00 of F1 and his own F2 never count as yours.
        assert_eq!(
            out.rows.iter().find(|r| r.id == "F1").unwrap().amount_minor,
            20_000
        );
        assert!(out.rows.iter().all(|r| r.id != "F2"));
        // A group with no "you" listed counts nothing: All is Just me.
        let none = spending(&expenses, &[], OCT_FROM, OCT_UNTIL, SpendingView::All).unwrap();
        let just_me = spending(
            &expenses,
            &groups,
            OCT_FROM,
            OCT_UNTIL,
            SpendingView::JustMe,
        )
        .unwrap();
        assert_eq!(none, just_me);
        // Just me rows stay yours whatever the groups say.
        assert_eq!(
            just_me,
            spending(&expenses, &[], OCT_FROM, OCT_UNTIL, SpendingView::JustMe).unwrap()
        );
    }

    #[test]
    fn spending_refuses_a_bad_window_a_bad_id_and_a_negative_amount() {
        let (expenses, you) = world();
        assert!(spending(&expenses, &you, OCT_UNTIL, OCT_FROM, SpendingView::All).is_err());
        assert!(spending(
            &expenses,
            &[SpendingGroupIn {
                group_id: pid(0xa0),
                viewer: "me".to_string(),
            }],
            OCT_FROM,
            OCT_UNTIL,
            SpendingView::All
        )
        .is_err());
        // Decision 205: one group, one "you"; a group listed twice is refused.
        let twice = vec![you[0].clone(), you[0].clone()];
        assert!(spending(&expenses, &twice, OCT_FROM, OCT_UNTIL, SpendingView::All).is_err());
        let me = pid(1);
        let neg = spent("N", None, &me, -5, "INR", &[], None, OCT_FROM);
        assert!(spending(&[neg], &you, OCT_FROM, OCT_UNTIL, SpendingView::JustMe).is_err());
        let neg_share = spent(
            "N",
            Some(&pid(0xa0)),
            &me,
            0,
            "INR",
            &[(&me, -5), (&pid(2), 5)],
            None,
            OCT_FROM,
        );
        assert!(spending(&[neg_share], &you, OCT_FROM, OCT_UNTIL, SpendingView::All).is_err());
        let mut neg_conv = spent("N", None, &me, 5, "USD", &[(&me, 5)], None, OCT_FROM);
        neg_conv.expense.conversion = Some(ConversionInput {
            converted_minor: -400,
            converted_currency: "INR".into(),
            rate_num: 80,
            rate_den: 1,
            rate_timestamp_ms: 0,
        });
        assert!(spending(&[neg_conv], &you, OCT_FROM, OCT_UNTIL, SpendingView::JustMe).is_err());
        // The export is the function itself.
        assert_eq!(
            ffi_spending(
                expenses.clone(),
                you.clone(),
                OCT_FROM,
                OCT_UNTIL,
                SpendingView::All
            ),
            spending(&expenses, &you, OCT_FROM, OCT_UNTIL, SpendingView::All)
        );
        // Nothing at all is not an error.
        let none = spending(&[], &[], OCT_FROM, OCT_UNTIL, SpendingView::All).unwrap();
        assert!(none.currencies.is_empty() && none.rows.is_empty());
    }
}
