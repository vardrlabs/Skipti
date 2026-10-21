//! The Splitwise profile of the §9.5 dry-run engine (`plan`).
//!
//! Everything format-independent (the plan shape, duplicate flags,
//! reconciliation totals, all-or-nothing apply) lives in [`super::plan`];
//! this module holds ONLY what Splitwise's own export layout dictates:
//!
//! A pinned shape (corroborated by two independent importer implementations,
//! 2026-07): a header row `Date,Description,Category,Cost,Currency,<one
//! column per member>`, then one row per expense where each member cell
//! holds that member's SIGNED NET of the expense: positive means they owe
//! their share, negative means they paid (so the cells of every row sum to
//! zero). The export carries real-world quirks handled explicitly: UTF-8
//! BOM, CRLF endings, quoted fields with commas, and a trailing
//! "Total balance" summary row that must never become an expense.
//!
//! Reconstruction is exact, not guessed: single-payer rows yield owes via
//! payer_owe = cost − \|payer cell\| (others keep their cells); multi-payer
//! rows are NAMED and refused rather than approximated; rows that don't net
//! to zero are rejected with spreadsheet-line reasons ([`super::plan`]'s
//! RowRejection tracks physical end-lines so numbers match what users see).
//!
//! Money discipline is inherited, not reimplemented: every decimal cell
//! routes through the SAME parser the add-expense flow uses
//! (`ffi::money_parse_minor`) via [`super::plan::parse_money_cell`]:
//! "1.005" INR REFUSED rather than rounded, KWD parses to 1005, zero-decimal
//! JPY refuses fractions, quoted grouping separators ("1,234.56") refused
//! outright. No float anywhere (ADR-0002); no second parser (D-49/D-51).

use super::plan::{add_total, combine_note, finish_plan};
use super::{
    csvio, parse_date_to_midnight_ms, DateFormat, ExpenseShare, LedgerSnapshot, MemberBinding,
    PortError, PortExpense,
};
use crate::money::Currency;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SplitwiseOptions {
    pub date_format: DateFormat,
    pub members: Vec<MemberBinding>,
    /// Group the imported expenses attach to. Created beforehand through the
    /// normal validated path (`ffi_create_group`): settlement currency is a
    /// human decision (§5.2), not something an importer guesses. `None`
    /// imports as personal expenses.
    pub group: Option<String>,
}

// ---------------------------------------------------------------------------
// Header mapping: Splitwise's own column conventions
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy)]
struct Columns {
    date: usize,
    description: usize,
    category: usize,
    cost: usize,
    currency: usize,
}

const REQUIRED_HEADERS: [&str; 5] = ["date", "description", "category", "cost", "currency"];
/// Headers that belong to OTHER Splitwise exports (the account-wide one has
/// Notes/Details/Repeats/Frequency). If one shows up where a member column
/// should be, the file is a different format than assumed: refuse rather
/// than invent a member called "Notes".
const NON_MEMBER_HEADERS: [&str; 9] = [
    "date",
    "description",
    "category",
    "cost",
    "currency",
    "notes",
    "details",
    "repeats",
    "frequency",
];

fn clean_cell(s: &str) -> &str {
    s.trim()
}

fn map_header(header: &[String]) -> Result<Columns, PortError> {
    let norm: Vec<String> = header
        .iter()
        .map(|h| clean_cell(h).to_ascii_lowercase())
        .collect();
    let find = |name: &str| {
        norm.iter()
            .position(|h| h == name)
            .ok_or_else(|| PortError::SheetStructure(format!("missing required column {name:?}")))
    };
    let date = find(REQUIRED_HEADERS[0])?;
    let description = find(REQUIRED_HEADERS[1])?;
    let category = find(REQUIRED_HEADERS[2])?;
    let cost = find(REQUIRED_HEADERS[3])?;
    let currency = find(REQUIRED_HEADERS[4])?;
    // Everything left of Currency must be one of the five known columns.
    let fixed: Vec<usize> = [date, description, category, cost, currency].to_vec();
    for (i, name) in header.iter().enumerate().take(currency + 1) {
        if !fixed.contains(&i) {
            return Err(PortError::SheetStructure(format!(
                "unexpected column {name:?} before Currency: this does not look like a Splitwise group export"
            )));
        }
    }
    if currency + 1 >= header.len() {
        return Err(PortError::SheetStructure(
            "no member columns after Currency".into(),
        ));
    }
    Ok(Columns {
        date,
        description,
        category,
        cost,
        currency,
    })
}

fn member_columns(header: &[String], cols: &Columns) -> Result<Vec<(usize, String)>, PortError> {
    let mut out = Vec::new();
    let mut seen = std::collections::BTreeSet::new();
    for (i, raw) in header.iter().enumerate().skip(cols.currency + 1) {
        let name = clean_cell(raw);
        if name.is_empty() {
            return Err(PortError::SheetStructure(format!(
                "blank member column name at position {i}"
            )));
        }
        let key = name.to_ascii_lowercase();
        if !seen.insert(key.clone()) {
            return Err(PortError::SheetStructure(format!(
                "duplicate member column {name:?}"
            )));
        }
        if NON_MEMBER_HEADERS.contains(&key.as_str()) {
            return Err(PortError::SheetStructure(format!(
                "column {name:?} after Currency is part of a different Splitwise export layout"
            )));
        }
        out.push((i, name.to_string()));
    }
    Ok(out)
}

fn resolve_bindings(
    bindings: &[MemberBinding],
    members: &[(usize, String)],
) -> Result<Vec<(usize, String)>, PortError> {
    let mut seen_names = std::collections::BTreeSet::new();
    for b in bindings {
        let key = b.name.trim().to_ascii_lowercase();
        if !seen_names.insert(key) {
            return Err(PortError::SheetStructure(format!(
                "two participant bindings for member {:?}",
                b.name
            )));
        }
    }
    let mut resolved = Vec::with_capacity(members.len());
    let mut missing = Vec::new();
    for (col, name) in members {
        let key = name.trim().to_ascii_lowercase();
        match bindings
            .iter()
            .find(|b| b.name.trim().to_ascii_lowercase() == key)
        {
            Some(b) => resolved.push((*col, b.participant_id.clone())),
            None => missing.push(name.clone()),
        }
    }
    if !missing.is_empty() {
        return Err(PortError::UnmappedMembers(missing));
    }
    Ok(resolved)
}

// ---------------------------------------------------------------------------
// Row → proposal: Splitwise's signed-net reconstruction
// ---------------------------------------------------------------------------

fn build_expense(
    record: &[String],
    expected_width: usize,
    cols: &Columns,
    bindings: &[(usize, String)],
    options: &SplitwiseOptions,
) -> Result<PortExpense, String> {
    if record.len() != expected_width {
        return Err(format!(
            "expected {expected_width} fields, found {}",
            record.len()
        ));
    }
    let cell = |i: usize| clean_cell(record.get(i).map(String::as_str).unwrap_or_default());

    let timestamp_ms = parse_date_to_midnight_ms(cell(cols.date), options.date_format)
        .map_err(|e| e.to_string())?;

    let description = cell(cols.description);
    let category = cell(cols.category);
    let note = combine_note(description, category);

    let code = require_currency_code(cell(cols.currency))?;

    let cost_raw = cell(cols.cost);
    let cost_minor = super::plan::parse_money_cell(cost_raw, code)?.ok_or("blank cost")?;
    if cost_minor == 0 {
        return Err("zero-cost row".into());
    }

    // Signed member cells in Splitwise's own convention: "When they pay for
    // an expense or make a payment, the number is positive. When they
    // borrow money for an expense or receive a payment, the number is
    // negative." (kb.splitwise.com, "How can I double check my balances?",
    // read 2026-09-25). The row nets to zero. D-69 had the signs reversed,
    // so every imported payer was the wrong person (corrected D-166).
    let mut signed: Vec<(String, i64)> = Vec::with_capacity(bindings.len());
    for (col, holder) in bindings {
        let name = holder.clone();
        let value = match super::plan::parse_money_cell(cell(*col), code) {
            Ok(v) => v.unwrap_or(0),
            Err(e) => return Err(format!("share for {name:?}: {e}")),
        };
        signed.push((name, value));
    }
    let net: i128 = signed.iter().map(|(_, v)| *v as i128).sum();
    if net != 0 {
        return Err(format!(
            "member cells sum to {net}, but a Splitwise expense row nets to zero"
        ));
    }
    let payers: Vec<usize> = signed
        .iter()
        .enumerate()
        .filter_map(|(i, (_, v))| (*v > 0).then_some(i))
        .collect();
    let payer_idx = match payers.as_slice() {
        [only] => *only,
        [] => return Err("no payer recorded in row".into()),
        many => {
            return Err(format!(
                "{} payers recorded ({}): multi-payer rows are not imported",
                many.len(),
                many.iter()
                    .map(|i| signed[*i].0.clone())
                    .collect::<Vec<_>>()
                    .join(", ")
            ))
        }
    };
    // The payer's cell is what the others owe them: cost minus their own share.
    let payer_paid = signed[payer_idx].1 as i128;
    if payer_paid > cost_minor as i128 {
        return Err(format!(
            "payer cell {} exceeds cost {}",
            payer_paid, cost_minor
        ));
    }
    let payer_owe = cost_minor as i128 - payer_paid;

    let mut shares = Vec::with_capacity(signed.len());
    for (i, (holder, _)) in signed.iter().enumerate() {
        // Everyone else borrowed their share: a negative cell.
        let owe: i128 = if i == payer_idx {
            payer_owe
        } else {
            -(signed[i].1 as i128)
        };
        if owe != 0 {
            shares.push(ExpenseShare {
                id: holder.clone(),
                amount_minor: i64::try_from(owe).map_err(|_| "share out of range".to_string())?,
            });
        }
    }

    Ok(PortExpense {
        payer: bindings[payer_idx].1.clone(),
        paid_minor: cost_minor,
        currency_code: code.to_string(),
        group: options.group.clone(),
        category: None,
        conversion: None,
        shares,
        note,
        timestamp_ms: Some(timestamp_ms),
    })
}

fn require_currency_code(raw: &str) -> Result<&'static str, String> {
    let currency = Currency::parse(raw).map_err(|e| format!("currency {raw:?}: {e}"))?;
    Ok(currency.code())
}

// ---------------------------------------------------------------------------
// THE DRY RUN: Splitwise legs over the shared engine
// ---------------------------------------------------------------------------

/// Parses the sheet, maps every member to a participant, and returns what
/// WOULD be created, creating nothing. Rows that cannot become faithful
/// expenses are listed with reasons instead of being guessed into existence;
/// duplicates are flagged, not dropped; totals are provided for
/// reconciliation against Splitwise's own numbers before any commit.
pub fn plan_import(
    csv_text: &str,
    options: &SplitwiseOptions,
    existing: &LedgerSnapshot,
) -> Result<super::plan::ImportPlan, PortError> {
    super::validate_snapshot(existing)?;
    let records =
        csvio::parse_csv_with_lines(csv_text).map_err(|e| PortError::CsvSyntax(e.to_string()))?;
    let mut rows = records.into_iter();
    let header = rows
        .next()
        .ok_or_else(|| PortError::SheetStructure("sheet has no header row".into()))?
        .fields;
    let cols = map_header(&header)?;
    let members = member_columns(&header, &cols)?;
    let bindings = resolve_bindings(&options.members, &members)?;

    let mut expenses = Vec::new();
    let mut rejections = Vec::new();
    let mut totals: std::collections::BTreeMap<String, i64> = std::collections::BTreeMap::new();

    for record in rows {
        // The number the user sees when they open the file in a spreadsheet.
        let sheet_row = record.end_line as u32;
        if record.fields.iter().all(|f| f.trim().is_empty()) {
            continue;
        }
        if record
            .fields
            .first()
            .map(|f| f.trim().eq_ignore_ascii_case("total balance"))
            .unwrap_or(false)
        {
            rejections.push(super::plan::RowRejection {
                row_number: sheet_row,
                reason: "Splitwise summary row: net balances, not an expense".into(),
            });
            continue;
        }
        match build_expense(&record.fields, header.len(), &cols, &bindings, options) {
            Ok(expense) => {
                add_total(&mut totals, &expense.currency_code, expense.paid_minor)?;
                expenses.push(expense);
            }
            Err(reason) => rejections.push(super::plan::RowRejection {
                row_number: sheet_row,
                reason,
            }),
        }
    }
    Ok(finish_plan(expenses, rejections, totals, existing))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::flutningr::{
        CurrencyTotal, DateFormat, DuplicateAgainst, DuplicateHit, PortSettlement,
    };

    const ADA: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";
    const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
    const CAROL: &str = "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1";

    fn opts(group: Option<&str>) -> SplitwiseOptions {
        SplitwiseOptions {
            date_format: DateFormat::IsoYmd,
            members: vec![
                MemberBinding {
                    name: "Alice".into(),
                    participant_id: ADA.into(),
                },
                MemberBinding {
                    name: "Bob".into(),
                    participant_id: BOB.into(),
                },
                MemberBinding {
                    name: "Carol".into(),
                    participant_id: CAROL.into(),
                },
            ],
            group: group.map(str::to_string),
        }
    }

    fn sheet(body: &str) -> String {
        // Written exactly like a real export: BOM, CRLF, header first.
        format!("\u{FEFF}Date,Description,Category,Cost,Currency,Alice,Bob,Carol\r\n{body}")
    }

    fn empty_ledger() -> LedgerSnapshot {
        LedgerSnapshot::default()
    }

    /// D-166: Splitwise's own help page: "When they pay for an expense …
    /// the number is positive. When they borrow money for an expense … the
    /// number is negative." Bob pays a 90.00 cabin split three ways: Bob's
    /// cell is +60.00 (paid 90, his own share 30), the others -30.00 each.
    /// Golden written by hand from that sentence, not from the old fixtures.
    #[test]
    fn the_positive_cell_is_the_payer_as_splitwise_documents() {
        let text = sheet("2026-09-18,Cabin,,90.00,USD,-30.00,60.00,-30.00\r\n");
        let plan = plan_import(&text, &opts(Some("group-of-three")), &empty_ledger()).unwrap();
        assert_eq!(plan.rejections.len(), 0, "{plan:?}");
        let e = &plan.expenses[0];
        assert_eq!(e.payer, BOB, "the one positive cell paid");
        assert_eq!(e.paid_minor, 9000);
        let mut shares: Vec<(String, i64)> = e
            .shares
            .iter()
            .map(|s| (s.id.clone(), s.amount_minor))
            .collect();
        shares.sort();
        let mut want = vec![
            (ADA.to_string(), 3000),
            (BOB.to_string(), 3000),
            (CAROL.to_string(), 3000),
        ];
        want.sort();
        assert_eq!(shares, want);
        // Two positive cells: two people paid, which is refused, not guessed.
        let two = sheet("2026-09-18,Cabin,,90.00,USD,30.00,30.00,-60.00\r\n");
        let plan = plan_import(&two, &opts(Some("group-of-three")), &empty_ledger()).unwrap();
        assert!(plan.expenses.is_empty());
        assert!(plan.rejections[0].reason.contains("2 payers"), "{plan:?}");
        assert_eq!(
            plan.rejections[0].reason,
            format!("2 payers recorded ({ADA}, {BOB}): multi-payer rows are not imported")
        );
    }

    #[test]
    fn golden_single_payer_reconstruction_is_exact() {
        // ₹45.50-equivalent USD dinner: Alice paid, owes 15.16 herself:
        // the classic largest-remainder shape, reconstructed exactly.
        let text = sheet("2026-01-15,Dinner,Dining,45.50,USD,30.34,-15.17,-15.17\r\n");
        let plan = plan_import(&text, &opts(Some("group-of-three")), &empty_ledger()).unwrap();
        assert_eq!(plan.rejections.len(), 0, "{plan:?}");
        assert_eq!(plan.expenses.len(), 1);
        let e = &plan.expenses[0];
        assert_eq!(e.payer, ADA);
        assert_eq!(e.paid_minor, 4550);
        assert_eq!(
            e.shares,
            vec![
                ExpenseShare {
                    id: ADA.into(),
                    amount_minor: 1516
                },
                ExpenseShare {
                    id: BOB.into(),
                    amount_minor: 1517
                },
                ExpenseShare {
                    id: CAROL.into(),
                    amount_minor: 1517
                },
            ]
        );
        assert_eq!(e.note.as_deref(), Some("Dinner (Dining)"));
        assert_eq!(e.timestamp_ms, Some(20_468 * 86_400_000));
        assert_eq!(e.group.as_deref(), Some("group-of-three"));
        assert_eq!(
            plan.totals_by_currency,
            vec![CurrencyTotal {
                currency_code: "USD".into(),
                cost_minor: 4550
            }]
        );
        assert!(plan.duplicates.is_empty());
    }

    #[test]
    fn decimal_traps_behave_exactly_like_the_entry_flow() {
        // "0.1" → 10 minor units, positive:
        let ok = sheet("2026-01-15,Gum,,0.1,INR,0.05,-0.03,-0.02\r\n");
        let plan = plan_import(&ok, &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses[0].paid_minor, 10);

        // "1.005" in a 2-decimal currency is REFUSED, never rounded:
        let over = sheet("2026-01-15,X,,1.005,INR,0.335,-0.335,-0.335\r\n");
        let plan = plan_import(&over, &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses.len(), 0);
        assert!(
            plan.rejections[0].reason.contains("decimal places"),
            "{plan:?}"
        );

        // The SAME string in a 3-decimal currency parses to 1005:
        let kwd = sheet("2026-01-15,X,,1.005,KWD,0.666,-0.333,-0.333\r\n");
        let plan = plan_import(&kwd, &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses[0].paid_minor, 1005);

        // Zero-decimal currency: integer fine, fractional refused:
        let jpy_ok = sheet("2026-01-15,Ramen,,1005,JPY,670,-335,-335\r\n");
        let plan = plan_import(&jpy_ok, &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses[0].paid_minor, 1005);
        let jpy_bad = sheet("2026-01-15,Ramen,,1005.5,JPY,671,-336,-336\r\n");
        let plan = plan_import(&jpy_bad, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.expenses.is_empty());
        assert!(plan.rejections[0].reason.contains("decimal places"));
    }

    #[test]
    fn grouping_separators_are_refused_even_when_properly_quoted() {
        // RFC 4180 delivers the field intact; the money parser must still say no.
        let text = sheet("2026-01-15,Laptop,,\"1,234.56\",USD,411.52,-411.52,\r\n");
        let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.expenses.is_empty());
        assert!(
            plan.rejections[0].reason.contains("digits and one dot"),
            "{plan:?}"
        );
    }

    #[test]
    fn hostile_rows_reject_with_reasons_instead_of_guessing() {
        let body = [
            // Two payers in one row:
            "2026-01-15,Shared taxi,Transport,30.00,USD,15.00,15.00,-30.00",
            // Nobody paid:
            "2026-01-16,Phantom,,30.00,USD,,,",
            // Cells do not net to zero:
            "2026-01-17,Rounding drift,,30.00,USD,10.01,-10.00,-10.00",
            // Blank cost:
            "2026-01-18,No amount,,,USD,10,-5,-5",
            // Unknown currency fails closed:
            "2026-01-19,Mystery,,30.00,XXX,10,-10,-10",
            // Ragged row (missing trailing cells):
            "2026-01-20,Short row,,30.00,USD,30.00",
            // Impossible date for the chosen format:
            "2026-02-30,Time travel,,30.00,USD,10,-10,-10",
        ]
        .join("\r\n");
        let text = sheet(&format!("{body}\r\n"));
        let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.expenses.is_empty());
        assert_eq!(plan.rejections.len(), 7);
        let reasons: Vec<&str> = plan.rejections.iter().map(|r| r.reason.as_str()).collect();
        assert!(reasons[0].contains("multi-payer"), "{reasons:?}");
        assert!(reasons[1].contains("no payer"), "{reasons:?}");
        assert!(reasons[2].contains("nets to zero"), "{reasons:?}");
        assert!(reasons[3].contains("blank cost"), "{reasons:?}");
        assert!(reasons[4].contains("currency"), "{reasons:?}");
        assert!(reasons[5].contains("fields"), "{reasons:?}");
        // Spreadsheet-visible row numbers (header = 1, first data row = 2):
        assert_eq!(plan.rejections[0].row_number, 2);
        assert_eq!(plan.rejections[6].row_number, 8);
    }

    #[test]
    fn summary_rows_and_blank_lines_never_become_expenses() {
        let body = "2026-01-15,Dinner,Dining,45.50,USD,30.34,-15.17,-15.17\r\n\
                    \r\n\
                    Total balance,,,,USD,30.34,-15.17,-15.17\r\n";
        let plan = plan_import(&sheet(body), &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses.len(), 1);
        assert_eq!(plan.rejections.len(), 1);
        assert!(plan.rejections[0].reason.contains("summary row"));
        assert_eq!(
            plan.rejections[0].reason,
            "Splitwise summary row: net balances, not an expense"
        );
        assert_eq!(plan.rejections[0].row_number, 4);
    }

    #[test]
    fn unmapped_members_refuse_the_whole_run_up_front() {
        let mut o = opts(None);
        o.members.pop(); // Carol has no participant
        let err = plan_import(&sheet(""), &o, &empty_ledger()).unwrap_err();
        assert_eq!(err, PortError::UnmappedMembers(vec!["Carol".into()]));
    }

    #[test]
    fn foreign_layouts_are_named_not_mangled() {
        // The account-wide export's columns appearing after ours:
        let text = "\u{FEFF}Date,Description,Category,Cost,Currency,Alice,Notes\r\n2026-01-15,Dinner,,45.50,USD,45.50,hi\r\n";
        let err = plan_import(text, &opts(None), &empty_ledger()).unwrap_err();
        assert!(
            matches!(err, PortError::SheetStructure(ref m) if m.contains("different Splitwise export"))
        );
        // Missing a required column entirely:
        let text = "\u{FEFF}Date,Description,Cost,Currency,Alice,Bob\r\n2026-01-15,Dinner,45.50,USD,22.75,-22.75\r\n";
        let err = plan_import(text, &opts(None), &empty_ledger()).unwrap_err();
        assert!(matches!(err, PortError::SheetStructure(ref m) if m.contains("category")));
        // A column that is not one of the five before Currency:
        let text = "\u{FEFF}Date,Description,Notes,Category,Cost,Currency,Alice,Bob\r\n";
        let err = plan_import(text, &opts(None), &empty_ledger()).unwrap_err();
        assert_eq!(
            err,
            PortError::SheetStructure(
                "unexpected column \"Notes\" before Currency: this does not look like a Splitwise group export"
                    .into()
            )
        );
        // Two participants bound to one member name:
        let mut o = opts(None);
        o.members.push(MemberBinding {
            name: "alice".into(),
            participant_id: BOB.into(),
        });
        let err = plan_import(&sheet(""), &o, &empty_ledger()).unwrap_err();
        assert!(
            matches!(err, PortError::SheetStructure(ref m) if m.contains("two participant bindings"))
        );
    }

    #[test]
    fn duplicates_flag_through_the_shared_engine() {
        // Detection itself is pinned engine-side (plan.rs); this proves the
        // Splitwise legs into it: against-ledger and in-file both fire.
        let mut existing = empty_ledger();
        existing.settlements.push(PortSettlement {
            from: BOB.into(),
            to: ADA.into(),
            amount_minor: 1,
            currency_code: "USD".into(),
            group: None,
        });
        existing.expenses.push(PortExpense {
            payer: ADA.into(),
            paid_minor: 4550,
            currency_code: "USD".into(),
            group: None,
            conversion: None,
            shares: vec![ExpenseShare {
                id: ADA.into(),
                amount_minor: 4550,
            }],
            note: Some("dinner".into()),
            timestamp_ms: Some(20_468 * 86_400_000),
            category: None,
        });
        // Same amount+date+merchant (case-insensitive note) → FLAGGED:
        let body = "2026-01-15,DINNER,,45.50,USD,45.50,-45.50,\r\n";
        let plan = plan_import(&sheet(body), &opts(None), &existing).unwrap();
        assert_eq!(
            plan.duplicates,
            vec![DuplicateHit {
                expense_index: 0,
                against: DuplicateAgainst::Existing { index: 0 }
            }]
        );

        // Same merchant+date, DIFFERENT amount → not a duplicate:
        let body = "2026-01-15,Dinner,,50.00,USD,50.00,,\r\n";
        let plan = plan_import(&sheet(body), &opts(None), &existing).unwrap();
        assert!(plan.duplicates.is_empty());

        // Same amount+merchant, DIFFERENT date → not a duplicate:
        let body = "2026-01-16,Dinner,,45.50,USD,45.50,-45.50,\r\n";
        let plan = plan_import(&sheet(body), &opts(None), &existing).unwrap();
        assert!(plan.duplicates.is_empty());

        // Same row twice inside ONE file flags against the first copy while
        // BOTH proposals survive (a flag never removes):
        let body = "2026-01-15,Dinner,,45.50,USD,45.50,-45.50,\r\n\
                    2026-01-15,Dinner,,45.50,USD,45.50,-45.50,\r\n";
        let plan = plan_import(&sheet(body), &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses.len(), 2);
        assert_eq!(
            plan.duplicates,
            vec![DuplicateHit {
                expense_index: 1,
                against: DuplicateAgainst::InFile { index: 0 }
            }]
        );
    }

    #[test]
    fn personal_import_carries_no_group() {
        let body = "2026-01-15,Dinner,,45.50,USD,45.50,-45.50,\r\n";
        let plan = plan_import(&sheet(body), &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses[0].group, None);
        let with_group = plan_import(&sheet(body), &opts(Some("g")), &empty_ledger()).unwrap();
        assert_eq!(with_group.expenses[0].group.as_deref(), Some("g"));
    }
}
