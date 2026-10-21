//! The Wallet by BudgetBakers profile of the §9.5 dry-run engine (`plan`).
//!
//! A statement-style sheet is the OPPOSITE shape to Splitwise's group export,
//! which is exactly why it proves the engine: one expense per row belonging
//! to the importing user themselves: no member columns, no signed nets, and
//! an explicit Income/Expense column carrying the row's direction.
//!
//! Pinned from two independent public implementations that both consume real
//! Wallet CSV exports (reached 2026-08-26; vendor help-centre articles
//! confirm the export exists via Android/web but publish no column list):
//!
//! * **Ivy Wallet** (`Ivy-Apps/ivy-wallet`, CSVMapper.kt
//!   `walletByBudgetBakers()` + CSVImporter.kt): resolves columns BY FIXED
//!   INDEX over a sheet whose first line is unconditionally the header:
//!   account=0, category=1, currency=2, amount=3, income/expense type=5,
//!   title/note=8, date=9, and matches the type cell by lowercase
//!   substring: "income", "expense", or "transfer".
//! * **import-cashew** (`Ilpaperino1994/import-cashew`, logic.py): addresses
//!   the same records BY NAME (`account`, `category`, `amount`, `currency`,
//!   `note`, `payee`, `date` plus a transfer flag) and pins the datetime
//!   form as `YYYY-MM-DD HH:MM:SS`. That extra columns beyond these exist
//!   (payee, transfer) is why the width rule here is ≥10, never ==10.
//!
//! Name-access and index-access corroborate each other only if both tools
//! describe the same files; both are actively-used migration paths.
//!
//! Deliberate divergences from Ivy's leniency, recorded rather than silent:
//!
//! * Ivy strips thousands separators from amount cells; here they are
//!   REFUSED, like every other profile: "1,234.56" could be one-two-three
//!   or twelve in another locale's reading of the same bytes, and D-69 set
//!   the rule that silent re-meaning loses to a visible rejection.
//! * Ivy defaults a BLANK type cell to Expense; here a blank or unknown type
//!   is REJECTED: guessing direction in a money path is how an expense
//!   becomes an income. When Wallet ships such cells in practice, the
//!   profile gets revisited with evidence, not propped up by a default.
//! * INCOME rows and ACCOUNT TRANSFERS cannot enter this ledger model (a
//!   personal receipt is neither an expense nor a person-to-person
//!   settlement), so they are rejected per-row with that named reason.
//!
//! Account identity, subcategory, payee and other trailing columns are not
//! mapped: SKIPTI imports what its ledger can represent faithfully.

use super::plan::{add_total, combine_note, finish_plan};
use super::{
    csvio, parse_datetime_to_ms, DateFormat, ExpenseShare, LedgerSnapshot, PortError, PortExpense,
};
use crate::ffi::parse_participant;
use crate::money::Currency;

/// The importing user maps every row onto THEMSELVES: a Wallet export is
/// their personal statement, and identity lives in the shell, not in the
/// file. `group` attaches the expenses to an existing group when supplied;
/// `None` imports as personal expenses.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BudgetBakersOptions {
    pub self_participant: String,
    pub group: Option<String>,
}

// Column indices pinned by Ivy Wallet's walletByBudgetBakers() mapping.
const COL_ACCOUNT: usize = 0;
const COL_CATEGORY: usize = 1;
const COL_CURRENCY: usize = 2;
const COL_AMOUNT: usize = 3;
const COL_TYPE: usize = 5;
const COL_TITLE: usize = 8;
const COL_DATE: usize = 9;

/// Every mapped column, in index order: drives rejection reasons so a bad
/// sheet learns WHICH pinned column could not be read.
const MAPPED_COLUMNS: [(&str, usize); 7] = [
    ("account", COL_ACCOUNT),
    ("category", COL_CATEGORY),
    ("currency", COL_CURRENCY),
    ("amount", COL_AMOUNT),
    ("income/expense type", COL_TYPE),
    ("note", COL_TITLE),
    ("date", COL_DATE),
];

/// Rows shorter than this cannot hold every mapped column (date sits at
/// index 9). Real exports carry MORE than ten columns (payee, transfer
/// flag…), so extras are ignored rather than refused.
fn min_width() -> usize {
    COL_DATE + 1
}

fn unreadable_mapped_column(width: usize) -> &'static str {
    MAPPED_COLUMNS
        .iter()
        .find(|(_, i)| *i >= width)
        .map(|(name, _)| *name)
        .unwrap_or("?")
}

/// The row's direction. Matches Ivy's lowercase-substring semantics for the
/// three values its importer acts on; anything else (including blank) is
/// rejected rather than defaulted (see the module notes above).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Direction {
    Income,
    Expense,
    Transfer,
}

fn direction_of(cell: &str) -> Result<Direction, String> {
    let t = cell.trim().to_ascii_lowercase();
    if t.is_empty() {
        return Err("blank income/expense indicator".into());
    }
    // Order matters semantically only through wallet vocabulary overlap:
    // no pinned value contains another, so first-match is safe on all three.
    if t.contains("expense") {
        Ok(Direction::Expense)
    } else if t.contains("income") {
        Ok(Direction::Income)
    } else if t.contains("transfer") {
        Ok(Direction::Transfer)
    } else {
        Err(format!(
            "unknown record type {cell:?}: expected Income, Expense or Transfer"
        ))
    }
}

fn build_expense(record: &[String], options: &BudgetBakersOptions) -> Result<PortExpense, String> {
    let cell = |i: usize| {
        record
            .get(i)
            .map(|s| s.trim())
            .unwrap_or_default()
            .to_string()
    };

    if record.len() < min_width() {
        return Err(format!(
            "expected at least {} fields, found {}: the {} column cannot be read",
            min_width(),
            record.len(),
            unreadable_mapped_column(record.len())
        ));
    }

    match direction_of(&cell(COL_TYPE))? {
        Direction::Income => {
            return Err("income record: representable as neither expense nor settlement".into())
        }
        Direction::Transfer => {
            return Err("account transfer between the user's own accounts".into())
        }
        Direction::Expense => {}
    }

    let currency_cell = cell(COL_CURRENCY);
    let code = Currency::parse(&currency_cell)
        .map_err(|e| format!("currency {currency_cell:?}: {e}"))?
        .code()
        .to_string();

    // Amount sign is owned by the type column, never by the cell: exported
    // expense amounts appear signed either way depending on settings, so the
    // magnitude is taken after parsing and the direction comes from above.
    let raw_amount = cell(COL_AMOUNT);
    let Some(signed_minor) = super::plan::parse_money_cell(&raw_amount, &code)? else {
        return Err("blank amount".into());
    };
    let magnitude = (signed_minor as i128)
        .checked_abs()
        .ok_or("amount out of range")?;
    if magnitude == 0 {
        return Err("zero-amount row".into());
    }
    let paid_minor = i64::try_from(magnitude).map_err(|_| "amount out of range".to_string())?;

    let timestamp_ms =
        parse_datetime_to_ms(&cell(COL_DATE), DateFormat::IsoYmdHms).map_err(|e| e.to_string())?;

    let note = combine_note(&cell(COL_TITLE), &cell(COL_CATEGORY));
    let payer = options.self_participant.clone();

    Ok(PortExpense {
        payer: payer.clone(),
        paid_minor,
        currency_code: code,
        group: options.group.clone(),
        category: None,
        conversion: None,
        shares: vec![ExpenseShare {
            id: payer,
            amount_minor: paid_minor,
        }],
        note,
        timestamp_ms: Some(timestamp_ms),
    })
}

/// THE DRY RUN for a BudgetBakers Wallet statement. Nothing is created:
/// proposals, per-row rejections with reasons, reconciliation totals and
/// duplicate flags come back together, and [`super::plan::apply_plan`] still
/// commits everything as one unit or nothing at all.
///
/// Row numbering follows the same convention as Splitwise: the skipped
/// header is spreadsheet row 1, the first data row is row 2.
pub fn plan_import(
    csv_text: &str,
    options: &BudgetBakersOptions,
    existing: &LedgerSnapshot,
) -> Result<super::plan::ImportPlan, PortError> {
    super::validate_snapshot(existing)?;
    // Identity fails fast like Splitwise's unresolved members do: an invalid
    // participant id poisons EVERY accepted row, so refuse before rows run.
    parse_participant(&options.self_participant).map_err(|e| {
        PortError::SheetStructure(format!(
            "self_participant is not a valid participant id: {e}"
        ))
    })?;

    let records =
        csvio::parse_csv_with_lines(csv_text).map_err(|e| PortError::CsvSyntax(e.to_string()))?;
    let mut rows = records.into_iter();
    // Ivy's importer skips the first line unconditionally (.withSkipLines(1)):
    // a Wallet export always carries its header there.
    rows.next()
        .ok_or_else(|| PortError::SheetStructure("sheet has no header row".into()))?;

    let mut expenses = Vec::new();
    let mut rejections = Vec::new();
    let mut totals: std::collections::BTreeMap<String, i64> = std::collections::BTreeMap::new();

    for record in rows {
        let sheet_row = record.end_line as u32;
        if record.fields.iter().all(|f| f.trim().is_empty()) {
            continue;
        }
        match build_expense(&record.fields, options) {
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
    use crate::flutningr::{apply_plan, CurrencyTotal, DuplicateAgainst, DuplicateHit};

    const ADA: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";

    fn opts(group: Option<&str>) -> BudgetBakersOptions {
        BudgetBakersOptions {
            self_participant: ADA.into(),
            group: group.map(str::to_string),
        }
    }

    /// Sheet exactly as a real export arrives: header first, comma-separated,
    /// ten mapped/trailing columns where later ones carry payee-like data.
    fn sheet(body: &str) -> String {
        format!(
            "\u{FEFF}Account,Category,Currency,Amount,Account Currency,Income/Expense,\
             Refund,Refund Currency,Note,Date\r\n{body}"
        )
    }

    fn empty_ledger() -> LedgerSnapshot {
        LedgerSnapshot::default()
    }

    #[test]
    fn golden_expense_row_becomes_a_personal_expense_to_the_penny() {
        // Signed-negative expense (Wallet's usual outgoing form), time kept:
        // Account(0) Category(1) Currency(2) Amount(3) AcctCurrency(4)
        // Income/Expense(5) Refund(6) RefundCurrency(7) Note(8) Date(9):
        let text =
            sheet("Cash,Groceries,INR,-2547.50,,Expense,,,Corner shop,2026-01-15 13:30:00\r\n");
        let plan = plan_import(&text, &opts(Some("g")), &empty_ledger()).unwrap();
        assert_eq!(plan.rejections.len(), 0, "{plan:?}");
        assert_eq!(plan.expenses.len(), 1);
        let e = &plan.expenses[0];
        assert_eq!(e.payer, ADA);
        assert_eq!(e.paid_minor, 254_750); // ₹2,547.50 in paise, exact
        assert_eq!(
            e.shares,
            vec![ExpenseShare {
                id: ADA.into(),
                amount_minor: 254_750
            }]
        );
        assert_eq!(e.note.as_deref(), Some("Corner shop (Groceries)"));
        // Independent value: day 20468 midnight + 13h30m.
        assert_eq!(e.timestamp_ms, Some(20_468 * 86_400_000 + 48_600_000));
        assert_eq!(e.currency_code, "INR");
        assert_eq!(e.group.as_deref(), Some("g"));
        assert_eq!(
            plan.totals_by_currency,
            vec![CurrencyTotal {
                currency_code: "INR".into(),
                cost_minor: 254_750
            }]
        );
        assert!(plan.duplicates.is_empty());

        // Positive-signed expense accepts identically (sign owned by the
        // type column, never the cell):
        let text = sheet("Cash,Food,USD,12.34,,Expense,,,,2026-01-16 08:00:00\r\n");
        let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses[0].paid_minor, 1234);
        assert_eq!(plan.expenses[0].note.as_deref(), Some("Food"));
    }

    #[test]
    fn money_cells_obey_the_entry_flow_and_type_column_owns_direction() {
        // Over-precise refused:
        let text = sheet("A,C,INR,1.005,,Expense,,,,2026-01-15 00:00:00\r\n");
        let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.expenses.is_empty());
        assert!(plan.rejections[0].reason.contains("decimal places"));

        // Thousands separator refused (divergence from Ivy's silent strip):
        let text = sheet("A,C,USD,\"1,234.00\",,Expense,,,,2026-01-15 00:00:00\r\n");
        let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
        assert!(
            plan.rejections[0].reason.contains("digits and one dot"),
            "{plan:?}"
        );

        // Zero-decimal discipline holds:
        let ok = sheet("A,C,JPY,1005,,Expense,,,,2026-01-15 00:00:00\r\n");
        assert_eq!(
            plan_import(&ok, &opts(None), &empty_ledger())
                .unwrap()
                .expenses[0]
                .paid_minor,
            1005
        );
        let bad = sheet("A,C,JPY,1005.5,,Expense,,,,2026-01-15 00:00:00\r\n");
        let plan = plan_import(&bad, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.expenses.is_empty());

        // Blank / zero amounts:
        let blank = sheet("A,C,USD,,,Expense,,,,2026-01-15 00:00:00\r\n");
        let plan = plan_import(&blank, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.rejections[0].reason.contains("blank amount"));
        let zero = sheet("A,C,USD,0.00,,Expense,,,,2026-01-15 00:00:00\r\n");
        let plan = plan_import(&zero, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.rejections[0].reason.contains("zero-amount"));
    }

    #[test]
    fn income_transfer_and_unknown_types_are_rejected_by_name() {
        let body = [
            "Checking,Salary,INR,500000,,Income,,,Monthly pay,2026-01-01 09:00:00",
            "Cash,,,,,Transfer,,,ATM withdrawal,2026-01-02 10:11:12",
            "Cash,,XXX,10,,Garbage,,,?,2026-01-03 00:00:00",
            "Cash,,USD,5,, ,,,blank-ish type,2026-01-04 00:00:00",
        ]
        .join("\r\n");
        let text = sheet(&format!("{body}\r\n"));
        let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.expenses.is_empty(), "{plan:?}");
        assert_eq!(plan.rejections.len(), 4);
        let reasons: Vec<&str> = plan.rejections.iter().map(|r| r.reason.as_str()).collect();
        assert!(reasons[0].contains("income record"), "{reasons:?}");
        assert!(reasons[1].contains("account transfer"), "{reasons:?}");
        assert!(reasons[2].contains("unknown record type"), "{reasons:?}");
        // D-228: the whole reason, as the shells show it (no dash):
        assert_eq!(
            reasons[0],
            "income record: representable as neither expense nor settlement"
        );
        assert_eq!(
            reasons[2],
            "unknown record type \"Garbage\": expected Income, Expense or Transfer"
        );
        assert!(
            reasons[3].contains("blank income/expense indicator"),
            "{reasons:?}"
        );
        // Header consumed unconditionally → data begins at spreadsheet row 2:
        assert_eq!(plan.rejections[0].row_number, 2);
        assert_eq!(plan.rejections[3].row_number, 5);
    }

    #[test]
    fn dates_match_the_pinned_layout_or_are_refused() {
        // Exactly YYYY-MM-DD HH:MM:SS per import-cashew's strptime pin:
        let good = sheet("A,,EUR,3.50,,Expense,,,x,2026-02-28 23:59:59\r\n");
        let plan = plan_import(&good, &opts(None), &empty_ledger()).unwrap();
        // Independently computed: 2026-02-28 is day 20512; +86399 s.
        assert_eq!(
            plan.expenses[0].timestamp_ms,
            Some(20_512 * 86_400_000 + 86_399_000)
        );

        // Sub-second precision is NOT part of the pinned layout. Refuse:
        let micros = sheet("A,,EUR,3.50,,Expense,,,x,2026-02-28 23:59:59.123\r\n");
        let plan = plan_import(&micros, &opts(None), &empty_ledger()).unwrap();
        assert!(plan.rejections[0].reason.contains("does not match"));

        // Leap second and impossible clock refused, impossible date refused:
        for bad in [
            "2026-02-28 12:60:00",
            "2026-02-28 24:00:00",
            "2026-02-30 12:00:00",
            "15/1/2026 13:30:00",
            "2026-01-15T13:30:00",
        ] {
            let text = sheet(format!("A,,EUR,3.50,,Expense,,,x,{bad}\r\n").as_str());
            let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
            assert!(
                plan.rejections[0].reason.contains("does not match"),
                "{bad}: {plan:?}"
            );
        }
    }

    #[test]
    fn unknown_currencies_and_ragged_rows_fail_closed_per_row() {
        let body = [
            // Unknown currency code:
            "A,,XXX,10,,Expense,,,?,2026-01-15 00:00:00",
            // Blank currency cell:
            "A,,,10,,Expense,,,?,2026-01-15 00:00:00",
            // Nine fields, date column missing entirely:
            "A,,USD,10,,Expense,,,short",
        ]
        .join("\r\n");
        let text = sheet(&format!("{body}\r\n"));
        let plan = plan_import(&text, &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.rejections.len(), 3);
        assert!(plan.rejections[0].reason.contains("currency"), "{plan:?}");
        assert!(plan.rejections[1].reason.contains("currency"), "{plan:?}");
        // Nine fields cannot reach the date column (index 9). The reason
        // names it:
        assert!(
            plan.rejections[2].reason.contains("at least 10"),
            "{plan:?}"
        );
        assert!(
            plan.rejections[2]
                .reason
                .contains("the date column cannot be read"),
            "{plan:?}"
        );
        assert_eq!(
            plan.rejections[2].reason,
            "expected at least 10 fields, found 9: the date column cannot be read"
        );

        // Extra trailing columns are tolerated, matching real exports:
        let wide = sheet("A,,USD,10.00,,Expense,,,cafe receipt,2026-02-01 07:45:00,\"recipient \"\"quoted\"\"\",more,tail\r\n");
        let plan = plan_import(&wide, &opts(None), &empty_ledger()).unwrap();
        assert_eq!(plan.expenses.len(), 1, "{plan:?}");
    }

    #[test]
    fn self_participant_must_be_valid_before_any_row_runs() {
        let err = plan_import(
            &sheet("A,,USD,10,,Expense,,,n,2026-01-15 00:00:00"),
            &BudgetBakersOptions {
                self_participant: "nothex".into(),
                group: None,
            },
            &empty_ledger(),
        )
        .unwrap_err();
        assert!(
            matches!(err, PortError::SheetStructure(ref m) if m.contains("self_participant")),
            "{err}"
        );
    }

    #[test]
    fn duplicates_flag_through_the_shared_engine_and_apply_stays_all_or_nothing() {
        // Existing ledger holds the JPY snack on day 20460 with note "snack":
        let mut existing = empty_ledger();
        existing.expenses.push(PortExpense {
            payer: ADA.into(),
            paid_minor: 3800,
            currency_code: "JPY".into(),
            group: None,
            conversion: None,
            shares: vec![ExpenseShare {
                id: ADA.into(),
                amount_minor: 3800,
            }],
            note: Some("snack".into()),
            timestamp_ms: Some(20_460 * 86_400_000),
            category: None,
        });

        // Same amount+day+merchant case-insensitively → flagged, not removed:
        // Category cell left empty so the proposal's note is exactly
        // "SNACK". Combining one would rightly break the merchant match:
        let text = sheet("Cash,,JPY,-3800,,Expense,,,SNACK,2026-01-07 21:14:00\r\n");
        let plan = plan_import(&text, &opts(None), &existing).unwrap();
        assert_eq!(plan.expenses.len(), 1);
        assert_eq!(
            plan.duplicates,
            vec![DuplicateHit {
                expense_index: 0,
                against: DuplicateAgainst::Existing { index: 0 }
            }]
        );

        // Commit as one unit, canonical output, input untouched:
        let merged = apply_plan(&existing, &plan).unwrap();
        assert_eq!(merged.expenses.len(), 2);
        assert_eq!(existing.expenses.len(), 1);
    }
}
