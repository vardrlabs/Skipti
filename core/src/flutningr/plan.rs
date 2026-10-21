//! The §9.5 import ENGINE: the part every profile shares.
//!
//! SPEC §9.5's contract is format-independent and lives here, once:
//!
//! * **Dry run first**: a plan touches nothing; it returns proposed
//!   expenses, per-row rejections WITH reasons, per-currency reconciliation
//!   totals, and duplicate flags.
//! * **Duplicates flagged by amount + calendar date + merchant**: all three
//!   must match. A flag NEVER removes a proposal; deciding is the confirm
//!   UI's job.
//! * **Rollback of the whole import as one unit**: [`apply_plan`] is a pure
//!   merge that validates the ENTIRE merged ledger before returning it. A
//!   refused import leaves the input untouched; there is no partial apply to
//!   undo, at any profile.
//!
//! What profiles supply is the per-app knowledge this module deliberately
//! lacks: which columns mean what (`splitwise` maps header NAMES; the
//! BudgetBakers profile maps fixed INDICES), how members are encoded, and how
//! a row becomes a faithful expense. Every decimal still routes through the
//! SAME parser as the add-expense flow (`ffi::money_parse_minor`), because that rule
//! belongs to no single format (D-49/D-51), and no float anywhere
//! (ADR-0002). The engine grew its second consumer before gaining any further
//! abstraction: two shapes say more than one and speculate less than three.

use super::{validate_snapshot, LedgerSnapshot, PortError, PortExpense};
use crate::ffi::money_parse_minor;

/// Maps one foreign member name onto a SKIPTI participant id. Names are
/// matched case-insensitively after trimming; the binding list is supplied by
/// the shell because identity lives there, not in a competitor's file.
/// Profiles whose sheets carry no member columns (single-user statements)
/// simply do not use bindings.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MemberBinding {
    pub name: String,
    pub participant_id: String,
}

/// What a duplicate was matched against: an expense already in the ledger,
/// or an earlier proposal from THIS import file. When the ledger already
/// holds the same key, every file copy flags against it. The human decides.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum DuplicateAgainst {
    Existing { index: u32 },
    InFile { index: u32 },
}

/// A flag, never a removal: this proposed expense matches another by amount
/// + calendar date + merchant.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DuplicateHit {
    pub expense_index: u32,
    pub against: DuplicateAgainst,
}

/// One row the dry run refuses to turn into an expense. `row_number` is the
/// SPREADSHEET row (header = row 1), so it matches what the user sees when
/// they open the exported file, for every profile.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RowRejection {
    pub row_number: u32,
    pub reason: String,
}

/// Sum of accepted costs per currency, the reconciliation preview: these are
/// the numbers to cross-check against the source app's own totals BEFORE
/// committing anything.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CurrencyTotal {
    pub currency_code: String,
    pub cost_minor: i64,
}

/// The complete dry-run result. Nothing here has been created anywhere.
/// Format-independent by construction: a plan cannot reveal which sheet
/// produced it.
#[derive(Debug, Clone, PartialEq, Eq, Default, uniffi::Record)]
pub struct ImportPlan {
    pub expenses: Vec<PortExpense>,
    pub duplicates: Vec<DuplicateHit>,
    pub rejections: Vec<RowRejection>,
    pub totals_by_currency: Vec<CurrencyTotal>,
}

// ---------------------------------------------------------------------------
// Duplicate detection: amount + date + merchant, all three
// ---------------------------------------------------------------------------

type DupKey = (i64, u64, String);

fn dup_key_of(e: &PortExpense) -> Option<DupKey> {
    let ts = e.timestamp_ms?;
    let day = ts / 86_400_000;
    let merchant = e.note.as_deref().unwrap_or_default().trim().to_lowercase();
    Some((e.paid_minor, day, merchant))
}

pub(crate) fn detect_duplicates(plan: &mut ImportPlan, existing: &LedgerSnapshot) {
    let existing_keys: std::collections::BTreeSet<DupKey> =
        existing.expenses.iter().filter_map(dup_key_of).collect();
    let mut first_in_file: std::collections::BTreeMap<DupKey, u32> =
        std::collections::BTreeMap::new();
    let mut hits = Vec::new();
    for (idx, e) in plan.expenses.iter().enumerate() {
        let Some(key) = dup_key_of(e) else { continue };
        let against_existing = if existing_keys.contains(&key) {
            existing
                .expenses
                .iter()
                .position(|x| dup_key_of(x).as_ref() == Some(&key))
                .map(|p| DuplicateAgainst::Existing { index: p as u32 })
        } else {
            None
        };
        let against = against_existing.or_else(|| {
            first_in_file
                .get(&key)
                .map(|first| DuplicateAgainst::InFile { index: *first })
        });
        match against {
            Some(against) => hits.push(DuplicateHit {
                expense_index: idx as u32,
                against,
            }),
            None => {
                first_in_file.insert(key, idx as u32);
            }
        }
    }
    plan.duplicates = hits;
}

// ---------------------------------------------------------------------------
// Shared row-glue used by the per-profile interpreters
// ---------------------------------------------------------------------------

/// Parses one decimal cell through THE money parser the add-expense flow uses;
/// blank cells yield `None` (semantic emptiness is the caller's call).
/// "1.005" in INR is refused rather than rounded, "1,234.56" is refused
/// outright, zero-decimal currencies reject fractional input, identical to
/// what a user typing into the form gets (D-49/D-51).
pub(crate) fn parse_money_cell(raw: &str, code: &str) -> Result<Option<i64>, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    money_parse_minor(trimmed, code)
        .map(Some)
        .map_err(|e| format!("{trimmed:?}: {e}"))
}

/// Accumulates one accepted cost into the per-currency totals.
pub(crate) fn add_total(
    totals: &mut std::collections::BTreeMap<String, i64>,
    currency_code: &str,
    minor: i64,
) -> Result<(), PortError> {
    let t = totals.entry(currency_code.to_string()).or_insert(0);
    *t = t
        .checked_add(minor)
        .ok_or_else(|| PortError::InvalidLedger("currency total overflow".into()))?;
    Ok(())
}

/// Assembles accepted proposals + rejections + totals into a finished plan
/// and flags duplicates against the existing ledger AND within the file.
pub(crate) fn finish_plan(
    expenses: Vec<PortExpense>,
    rejections: Vec<RowRejection>,
    totals: std::collections::BTreeMap<String, i64>,
    existing: &LedgerSnapshot,
) -> ImportPlan {
    let mut plan = ImportPlan {
        expenses,
        duplicates: Vec::new(),
        rejections,
        totals_by_currency: Vec::new(),
    };
    detect_duplicates(&mut plan, existing);
    plan.totals_by_currency = totals
        .into_iter()
        .map(|(currency_code, cost_minor)| CurrencyTotal {
            currency_code,
            cost_minor,
        })
        .collect();
    plan
}

/// The note preserves what the source app knew: the primary label verbatim,
/// with the category appended in parentheses when present and distinct:
/// visible, honest, editable. Categories stay TEXT until Phase 6 builds the
/// real tree. Shared by both profiles so notes migrate identically.
pub(crate) fn combine_note(label: &str, category: &str) -> Option<String> {
    match (label.is_empty(), category.is_empty()) {
        (true, true) => None,
        (true, false) => Some(category.to_string()),
        (false, true) => Some(label.to_string()),
        (false, false) => {
            if label.eq_ignore_ascii_case(category) {
                Some(label.to_string())
            } else {
                Some(format!("{label} ({category})"))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The commit half of §9.5's all-or-nothing contract
// ---------------------------------------------------------------------------

/// "Rollback of the entire import as one unit": a PURE merge of every
/// proposal into the ledger, validated as a whole. Either the complete merged
/// state comes back or an error does and the input is provably untouched:
/// there is no partial application to undo. The shell persists the returned
/// state in ONE vault transaction, which is where durability-level atomicity
/// lives. Profile-independent: apply never learns where a plan came from.
pub fn apply_plan(
    existing: &LedgerSnapshot,
    plan: &ImportPlan,
) -> Result<LedgerSnapshot, PortError> {
    // D-235 round 2 (review M1): every expense an import WRITES keeps the
    // rules ingest holds a record to (`straumr::check`), so an import can
    // never put in the ledger a record other members' phones would refuse.
    // Only the plan's new expenses: what the ledger already holds is export's
    // to judge (`validate_snapshot`), never refused here.
    for (n, e) in plan.expenses.iter().enumerate() {
        check_written(e).map_err(|err| {
            PortError::InvalidLedger(format!("imported expense {}: {err}", n + 1))
        })?;
    }
    let mut merged = existing.clone();
    merged.expenses.extend(plan.expenses.iter().cloned());
    let merged = super::canonical_sorted(&merged);
    validate_snapshot(&merged)?;
    Ok(merged)
}

/// An imported expense through the ingest rules.
fn check_written(e: &PortExpense) -> Result<(), crate::straumr::check::CheckError> {
    use crate::straumr::revision::{ConversionValue, MoneyValue, ShareValue};
    crate::straumr::check::check_group(e.group.as_deref())?;
    // D-235 (decision 110 a): an imported note or category keeps the
    // free-text bound (a Splitwise description can be any length).
    for text in [&e.note, &e.category].into_iter().flatten() {
        crate::straumr::check::check_text(text)?;
    }
    crate::straumr::check::check_money(&MoneyValue {
        payer: e.payer.clone(),
        paid_minor: e.paid_minor,
        currency_code: e.currency_code.clone(),
        conversion: e.conversion.as_ref().map(|c| ConversionValue {
            converted_minor: c.converted_minor,
            converted_currency: c.converted_currency.clone(),
            rate_num: c.rate_num,
            rate_den: c.rate_den,
            rate_timestamp_ms: c.rate_timestamp_ms,
        }),
        shares: e
            .shares
            .iter()
            .map(|s| ShareValue {
                id: s.id.clone(),
                amount_minor: s.amount_minor,
            })
            .collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::flutningr::{ExpenseShare, PortSettlement};

    const ADA: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";
    const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

    fn expense(minor: i64, note: Option<&str>, day_ms: u64) -> PortExpense {
        PortExpense {
            payer: ADA.into(),
            paid_minor: minor,
            currency_code: "USD".into(),
            group: None,
            conversion: None,
            shares: vec![ExpenseShare {
                id: ADA.into(),
                amount_minor: minor,
            }],
            note: note.map(str::to_string),
            timestamp_ms: Some(day_ms),
            category: None,
        }
    }

    #[test]
    fn empty_ledger_and_plain_plan_apply_cleanly() {
        let existing = LedgerSnapshot::default();
        let plan = finish_plan(
            vec![expense(4550, Some("dinner"), 20_468 * 86_400_000)],
            Vec::new(),
            std::collections::BTreeMap::from([("USD".into(), 4550)]),
            &existing,
        );
        assert_eq!(
            plan.totals_by_currency,
            vec![CurrencyTotal {
                currency_code: "USD".into(),
                cost_minor: 4550
            }]
        );
        assert!(plan.duplicates.is_empty());
    }

    /// D-235 round 2 (review M1): an import never writes what ingest would
    /// refuse. Each broken rule refuses the apply; a clean plan applies; a
    /// record the ledger ALREADY holds is not judged here (only export's
    /// `validate_snapshot` is), so an old vault can still import.
    #[test]
    fn d235_applying_a_plan_holds_its_expenses_to_the_ingest_rules() {
        let plan_of = |e: PortExpense| ImportPlan {
            expenses: vec![e],
            duplicates: Vec::new(),
            rejections: Vec::new(),
            totals_by_currency: Vec::new(),
        };
        let clean = expense(4550, Some("dinner"), 20_468 * 86_400_000);
        assert!(apply_plan(&LedgerSnapshot::default(), &plan_of(clean.clone())).is_ok());
        let mut negative = clean.clone();
        negative.shares = vec![
            ExpenseShare {
                id: ADA.into(),
                amount_minor: -450,
            },
            ExpenseShare {
                id: BOB.into(),
                amount_minor: 5000,
            },
        ];
        let mut duplicate = clean.clone();
        duplicate.shares = vec![
            ExpenseShare {
                id: ADA.into(),
                amount_minor: 2275,
            },
            ExpenseShare {
                id: ADA.into(),
                amount_minor: 2275,
            },
        ];
        let mut upper = clean.clone();
        upper.payer = ADA.to_uppercase();
        let mut converted = clean.clone();
        converted.conversion = Some(crate::flutningr::PortConversion {
            converted_minor: 379_167,
            converted_currency: "INR".into(),
            rate_num: 250,
            rate_den: 3,
            rate_timestamp_ms: 0,
        });
        let mut past_bound = clean.clone();
        past_bound.paid_minor = crate::straumr::check::MAX_AMOUNT_MINOR + 1;
        past_bound.shares[0].amount_minor = past_bound.paid_minor;
        let mut bad_group = clean.clone();
        bad_group.group = Some(BOB.to_uppercase());
        // D-235 (decision 110 a): a note past the free-text bound.
        let mut long_note = clean.clone();
        long_note.note = Some("x".repeat(crate::straumr::check::TEXT_MAX_CHARS + 1));
        let mut at_bound = clean.clone();
        at_bound.note = Some("x".repeat(crate::straumr::check::TEXT_MAX_CHARS));
        assert!(apply_plan(&LedgerSnapshot::default(), &plan_of(at_bound)).is_ok());
        for (name, e) in [
            ("negative share", negative.clone()),
            ("duplicate holder", duplicate),
            ("uppercase payer", upper),
            ("amount-changing conversion", converted),
            ("past the bound", past_bound),
            ("uppercase group", bad_group),
            ("note past the bound", long_note),
        ] {
            assert!(
                matches!(
                    apply_plan(&LedgerSnapshot::default(), &plan_of(e)),
                    Err(PortError::InvalidLedger(_))
                ),
                "{name}"
            );
        }
        // The ledger already holding a negative share (a 0.4.0 Adjustment)
        // does not stop a clean import.
        let existing = LedgerSnapshot {
            expenses: vec![negative],
            ..LedgerSnapshot::default()
        };
        assert_eq!(
            apply_plan(&existing, &plan_of(clean))
                .unwrap()
                .expenses
                .len(),
            2
        );
    }

    #[test]
    fn applying_a_plan_carries_the_existing_redistributions_through() {
        // D-210: an import adds expenses; redistributions already in the
        // ledger ride along unchanged, like settlements.
        let carol = "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1";
        let group = "d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2";
        let mut existing = LedgerSnapshot::default();
        existing
            .redistributions
            .push(crate::flutningr::PortRedistribution {
                from: carol.into(),
                currency_code: "USD".into(),
                group: group.into(),
                shares: vec![ExpenseShare {
                    id: ADA.into(),
                    amount_minor: -100,
                }],
            });
        let plan = finish_plan(
            vec![expense(500, None, 20_460 * 86_400_000)],
            Vec::new(),
            Default::default(),
            &existing,
        );
        let merged = apply_plan(&existing, &plan).unwrap();
        assert_eq!(merged.redistributions, existing.redistributions);
        assert_eq!(merged.expenses.len(), 1);
    }

    #[test]
    fn duplicates_flag_on_all_three_of_amount_date_and_merchant() {
        // Engine-level pin, independent of any profile: the key needs amount
        // AND calendar day AND merchant. The JPY day 20460 matches on amount+
        // day only when the merchant also matches.
        let mut existing = LedgerSnapshot::default();
        existing.settlements.push(PortSettlement {
            from: BOB.into(),
            to: ADA.into(),
            amount_minor: 1,
            currency_code: "USD".into(),
            group: None,
        });
        existing
            .expenses
            .push(expense(3800, None, 20_460 * 86_400_000));

        let same_all = expense(3800, None, 20_460 * 86_400_000);
        let plan = finish_plan(vec![same_all], Vec::new(), Default::default(), &existing);
        assert_eq!(
            plan.duplicates,
            vec![DuplicateHit {
                expense_index: 0,
                against: DuplicateAgainst::Existing { index: 0 }
            }]
        );

        // Different merchant → not flagged:
        let other_merchant = expense(3800, Some("lunch"), 20_460 * 86_400_000);
        let plan = finish_plan(
            vec![other_merchant],
            Vec::new(),
            Default::default(),
            &existing,
        );
        assert!(plan.duplicates.is_empty());

        // Same merchant+day, different amount → not flagged:
        let other_amount = expense(3900, None, 20_460 * 86_400_000);
        let plan = finish_plan(
            vec![other_amount],
            Vec::new(),
            Default::default(),
            &existing,
        );
        assert!(plan.duplicates.is_empty());

        // Same merchant+amount, different day → not flagged:
        let other_day = expense(3800, None, 20_461 * 86_400_000);
        let plan = finish_plan(vec![other_day], Vec::new(), Default::default(), &existing);
        assert!(plan.duplicates.is_empty());

        // A proposal without a timestamp can never be duplicate-flagged;
        // matched on LESS than all three would be guessing:
        let mut timeless = expense(3800, None, 0);
        timeless.timestamp_ms = None;
        let plan = finish_plan(vec![timeless], Vec::new(), Default::default(), &existing);
        assert!(plan.duplicates.is_empty());
    }

    #[test]
    fn repeats_inside_one_file_flag_against_the_first_copy() {
        let existing = LedgerSnapshot::default();
        let plan = finish_plan(
            vec![
                expense(4550, Some("Dinner"), 20_468 * 86_400_000),
                expense(4550, Some("dinner"), 20_468 * 86_400_000), // case-insensitive
            ],
            Vec::new(),
            Default::default(),
            &existing,
        );
        assert_eq!(plan.expenses.len(), 2, "a flag never removes a proposal");
        assert_eq!(
            plan.duplicates,
            vec![DuplicateHit {
                expense_index: 1,
                against: DuplicateAgainst::InFile { index: 0 }
            }]
        );
    }

    #[test]
    fn apply_is_all_or_nothing_and_leaves_the_input_untouched() {
        let existing = LedgerSnapshot::default();
        let plan = finish_plan(
            vec![expense(4550, None, 20_468 * 86_400_000)],
            Vec::new(),
            Default::default(),
            &existing,
        );
        let merged = apply_plan(&existing, &plan).unwrap();
        assert_eq!(merged.expenses.len(), 1);
        assert_eq!(existing.expenses.len(), 0, "input must be untouched");

        // Re-applying duplicates (a fresh decision each time), exactly why
        // the confirm UI owns idempotency and duplicates stay FLAGGED:
        let again = apply_plan(&merged, &plan).unwrap();
        assert_eq!(again.expenses.len(), 2);

        // An invalid proposal refuses the WHOLE merge:
        let mut poisoned = plan.clone();
        poisoned.expenses[0].shares.clear();
        let err = apply_plan(&existing, &poisoned);
        assert!(matches!(err, Err(PortError::InvalidLedger(_))));
        assert_eq!(
            existing.expenses.len(),
            0,
            "refused apply leaves nothing behind"
        );
    }

    #[test]
    fn money_cells_obey_the_entry_flow_discipline() {
        assert_eq!(parse_money_cell("45.50", "USD").unwrap(), Some(4550));
        assert_eq!(parse_money_cell(" -30.34 ", "USD").unwrap(), Some(-3034));
        assert_eq!(parse_money_cell("", "USD").unwrap(), None);
        assert_eq!(parse_money_cell("   ", "JPY").unwrap(), None);
        assert_eq!(parse_money_cell("0.1", "INR").unwrap(), Some(10));
        assert!(parse_money_cell("1.005", "INR").is_err(), "never rounded");
        assert_eq!(parse_money_cell("1.005", "KWD").unwrap(), Some(1005));
        assert!(
            parse_money_cell("1,234.56", "USD").is_err(),
            "separators refused"
        );
        assert!(
            parse_money_cell("1005.5", "JPY").is_err(),
            "zero-decimal refused"
        );
        assert_eq!(parse_money_cell("-1900", "JPY").unwrap(), Some(-1900));
    }

    #[test]
    fn combine_note_matches_d69_splitwise_shape() {
        assert_eq!(combine_note("", ""), None);
        assert_eq!(combine_note("", "Food"), Some("Food".into()));
        assert_eq!(combine_note("Dinner", ""), Some("Dinner".into()));
        assert_eq!(combine_note("Food", "food"), Some("Food".into()));
        assert_eq!(
            combine_note("Dinner", "Dining"),
            Some("Dinner (Dining)".into())
        );
    }
}
