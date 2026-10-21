//! The §9.2 flat CSV export: "one flat file, RFC 4180, UTF-8 with BOM:
//! universal, scriptable."
//!
//! This is deliberately a VIEW, not the backup. One row per record, every
//! amount a plain decimal string in its currency's exact precision (rendered
//! by integer arithmetic, never a float), dates ISO 8601 UTC. Shares and
//! frozen FX rates do not fit a flat row; losslessness is the JSON
//! envelope's job ([`super::jsonio`]) and GUARANTEES #4 rests on that one,
//! not on this view.
//!
//! D-248: `category` is the LAST column (appended, so a script that reads the
//! older columns by position keeps working): the expense's category NAME from
//! the app's category tree, "Parent / Leaf" for a sub-category (two parents
//! share leaf names, Rentals and Insurance), the parent's name for a parent,
//! and empty when there is none or the tree does not know the id (blank,
//! never a guess, as the apps label rows). Settlement and redistribution rows
//! leave it empty. Nothing imports this file (the JSON envelope is the
//! backup), so a file written before the column existed needs no reader.

use super::{
    canonical_sorted, csvio, minor_to_plain_decimal, timestamp_ms_to_iso8601, validate_snapshot,
    LedgerSnapshot, PortError,
};
use crate::merki::CategoryTree;
use crate::money::Currency;

/// The flat file's fixed column order.
pub const FLAT_HEADER: [&str; 9] = [
    "record_type",
    "date_utc",
    "currency",
    "amount",
    "description",
    "payer_or_from",
    "to_or_empty",
    "group_or_empty",
    "category",
];

/// The `category` cell (D-248): see the module doc.
fn category_cell(tree: &CategoryTree, id: Option<&str>) -> String {
    let Some(id) = id else {
        return String::new();
    };
    let Some(c) = tree.categories.iter().find(|c| c.id == id) else {
        return String::new();
    };
    match &c.parent {
        None => c.name.clone(),
        Some(p) => match tree.categories.iter().find(|x| &x.id == p) {
            Some(parent) => format!("{} / {}", parent.name, c.name),
            None => String::new(),
        },
    }
}

/// Validates, canonicalises, and renders the whole ledger as one flat CSV:
/// expense rows (payer filled, `to` empty), settlement rows (`from`/`to`
/// filled) and one redistribution row per share. A redistribution row follows
/// the settlement convention: its endpoints are picked by the share's sign and
/// its amount is the positive magnitude. A share below zero (`from` owed money)
/// is `from` paying the member, so the row reads `from`, member. A share above
/// zero is the member paying `from`, so the row reads member, `from`. Rows are
/// sorted canonically so equal states export byte-identically.
pub fn export_flat_csv(snapshot: &LedgerSnapshot) -> Result<String, PortError> {
    validate_snapshot(snapshot)?;
    let sorted = canonical_sorted(snapshot);
    // The only tree the apps use (both shells label rows from it).
    let tree = CategoryTree::with_defaults();
    let mut rows = Vec::new();
    rows.push(FLAT_HEADER.iter().map(|s| s.to_string()).collect());
    for e in &sorted.expenses {
        let exp = Currency::parse(&e.currency_code)
            .map_err(|err| PortError::InvalidLedger(format!("expense currency: {err}")))?
            .exponent();
        rows.push(vec![
            "expense".into(),
            e.timestamp_ms
                .map(timestamp_ms_to_iso8601)
                .unwrap_or_default(),
            e.currency_code.clone(),
            minor_to_plain_decimal(e.paid_minor, exp),
            e.note.clone().unwrap_or_default(),
            e.payer.clone(),
            String::new(),
            e.group.clone().unwrap_or_default(),
            category_cell(&tree, e.category.as_deref()),
        ]);
    }
    for s in &sorted.settlements {
        let exp = Currency::parse(&s.currency_code)
            .map_err(|err| PortError::InvalidLedger(format!("settlement currency: {err}")))?
            .exponent();
        // Settlements carry no wall-clock time in the ledger model yet; the
        // cell stays empty rather than inventing a date.
        rows.push(vec![
            "settlement".into(),
            String::new(),
            s.currency_code.clone(),
            minor_to_plain_decimal(s.amount_minor, exp),
            String::new(),
            s.from.clone(),
            s.to.clone(),
            s.group.clone().unwrap_or_default(),
            String::new(),
        ]);
    }
    for r in &sorted.redistributions {
        let exp = Currency::parse(&r.currency_code)
            .map_err(|err| PortError::InvalidLedger(format!("redistribution currency: {err}")))?
            .exponent();
        for sh in &r.shares {
            let magnitude = sh.amount_minor.checked_abs().ok_or_else(|| {
                PortError::InvalidLedger("redistribution share out of range".into())
            })?;
            // Endpoints by the share's sign, as for a reversed settlement: a
            // negative share is `from` paying the member, a positive one is
            // the member paying `from`. The amount is always the magnitude.
            let (payer_or_from, to_or_empty) = if sh.amount_minor.signum() > 0 {
                (sh.id.clone(), r.from.clone())
            } else {
                (r.from.clone(), sh.id.clone())
            };
            rows.push(vec![
                "redistribution".into(),
                String::new(),
                r.currency_code.clone(),
                minor_to_plain_decimal(magnitude, exp),
                String::new(),
                payer_or_from,
                to_or_empty,
                r.group.clone(),
                String::new(),
            ]);
        }
    }
    Ok(csvio::write_csv(&rows))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::flutningr::{
        ExpenseShare, PortConversion, PortExpense, PortGroup, PortRedistribution, PortSettlement,
    };

    const ADA: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";
    const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
    const GROUP: &str = "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0";

    fn snapshot() -> LedgerSnapshot {
        LedgerSnapshot {
            groups: vec![PortGroup {
                id: GROUP.into(),
                name: "Goa Trip".into(),
                settlement_currency: "INR".into(),
                members: vec![ADA.into(), BOB.into()],
            }],
            expenses: vec![
                PortExpense {
                    payer: ADA.into(),
                    paid_minor: 1234,
                    currency_code: "KWD".into(), // three decimals must survive
                    group: None,
                    category: None,
                    conversion: Some(PortConversion {
                        // 1234 fils × 271 = 334414 paise, exactly what
                        // fx::convert produces from this rational rate.
                        converted_minor: 334_414,
                        converted_currency: "INR".into(),
                        rate_num: 271,
                        rate_den: 1,
                        rate_timestamp_ms: 1_768_171_200_000,
                    }),
                    shares: vec![ExpenseShare {
                        id: ADA.into(),
                        amount_minor: 1234,
                    }],
                    note: Some("taxi, \"night\" ride".into()),
                    timestamp_ms: Some(20_468 * 86_400_000 + 3_660_000),
                },
                PortExpense {
                    payer: BOB.into(),
                    paid_minor: 1005,
                    currency_code: "JPY".into(), // zero decimals: no dot at all
                    group: Some(GROUP.into()),
                    conversion: None,
                    shares: vec![ExpenseShare {
                        id: BOB.into(),
                        amount_minor: 1005,
                    }],
                    note: None,
                    timestamp_ms: None,
                    category: None,
                },
            ],
            settlements: vec![PortSettlement {
                from: BOB.into(),
                to: ADA.into(),
                amount_minor: 100_000,
                currency_code: "INR".into(),
                group: Some(GROUP.into()),
            }],
            redistributions: vec![],
            profile: None,
        }
    }

    #[test]
    fn flat_csv_carries_exact_decimals_dates_and_quoting() {
        let text = export_flat_csv(&snapshot()).unwrap();
        // The text opens with the header directly; the BOM is prepended by
        // whichever shell writes the file (§9.2, incident §5.15).
        assert!(text.starts_with(FLAT_HEADER.join(",").as_str()));
        assert!(text.contains("\r\n"));
        // KWD three-decimal rendering:
        assert!(text.contains("1.234"), "{text:?}");
        // JPY zero-decimal rendering (no dot anywhere in the amount cell):
        assert!(text.contains(",1005,"), "{text:?}");
        // ISO 8601 UTC date from the timestamp (+3,660,000 ms = 01:01:00):
        assert!(text.contains("2026-01-15T01:01:00Z"), "{text:?}");
        // Settlements keep direction in columns, date honestly empty:
        // (D-248: the category cell after the group is empty on a settlement.)
        assert!(text.ends_with(&format!("settlement,,INR,1000.00,,{BOB},{ADA},{GROUP},")));
        // The hostile note round-trips through our own reader:
        let parsed = csvio::parse_csv(&text).unwrap();
        assert!(parsed
            .iter()
            .any(|r| r.get(4) == Some(&"taxi, \"night\" ride".to_string())));
    }

    #[test]
    fn a_redistribution_is_one_row_per_share() {
        // BOB's balance in the INR group moved: ADA takes 333.34 of it, and
        // a third person 333.33. The row format is the settlement row's
        // (record_type, empty date, currency, decimal, empty description,
        // from, to, group), written by hand here, not read back.
        let carol = "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1";
        let mut s = snapshot();
        s.redistributions.push(PortRedistribution {
            from: BOB.into(),
            currency_code: "INR".into(),
            group: GROUP.into(),
            shares: vec![
                ExpenseShare {
                    id: ADA.into(),
                    amount_minor: -33_334,
                },
                ExpenseShare {
                    id: carol.into(),
                    amount_minor: -33_333,
                },
            ],
        });
        // The other sign: ADA is owed 500.00 in the INR group and BOB takes a
        // positive share. A positive share is the member paying `from`, so
        // the row is BOB then ADA, as a settlement from BOB to ADA would be.
        s.redistributions.push(PortRedistribution {
            from: ADA.into(),
            currency_code: "INR".into(),
            group: GROUP.into(),
            shares: vec![ExpenseShare {
                id: BOB.into(),
                amount_minor: 50_000,
            }],
        });
        let text = export_flat_csv(&s).unwrap();
        // D-248: each row ends with the empty category cell.
        let line1 = format!("redistribution,,INR,333.34,,{BOB},{ADA},{GROUP},\r\n");
        let line2 = format!("redistribution,,INR,333.33,,{BOB},{carol},{GROUP},");
        let positive = format!("redistribution,,INR,500.00,,{BOB},{ADA},{GROUP},\r\n");
        assert!(text.contains(&line1), "{text:?}");
        assert!(text.contains(&positive), "{text:?}");
        // The last row has no trailing line break (write_csv's own shape).
        assert!(text.ends_with(&line2), "{text:?}");
        assert_eq!(text.matches("redistribution,").count(), 3);
        // After the settlement rows, in the file's order.
        assert!(text.find("settlement,").unwrap() < text.find(&line1).unwrap());
        // An invalid redistribution (shares that do not add up) never exports.
        s.redistributions[0].shares.clear();
        assert!(matches!(
            export_flat_csv(&s),
            Err(PortError::InvalidLedger(_))
        ));
    }

    /// D-248: the header, written out by hand, and the category cell. The
    /// ids are merki's stable default ids, re-derived independently in
    /// .drafts/d248/goldens.py (sha256 of "skipti/category/<slug>/<slug>",
    /// first 16 bytes): Rentals sits under Travel AND Stay, so the cell names
    /// the parent too. A parent names itself; an id the tree does not know is
    /// blank, never a guess; no category is blank.
    #[test]
    fn the_category_column_comes_last_and_names_the_category() {
        let text = export_flat_csv(&snapshot()).unwrap();
        assert!(text.starts_with(
            "record_type,date_utc,currency,amount,description,payer_or_from,\
             to_or_empty,group_or_empty,category\r\n"
        ));
        let cases = [
            (
                Some("33791e3101f1720e9eeca534ee0bbfa3"),
                "Food & Drink / Groceries",
            ),
            (Some("61c092f2663a0ae60570a8683a5ec279"), "Travel / Rentals"),
            (Some("fb457c045c1d3bb6cbd1fa0d3f5fde2d"), "Stay / Rentals"),
            (Some("80ed7c35199cb754fe9f4ded5d5eea82"), "Food & Drink"),
            (Some("ffffffffffffffffffffffffffffffff"), ""),
            (None, ""),
        ];
        for (id, cell) in cases {
            let mut s = snapshot();
            s.expenses.truncate(1);
            s.expenses[0].category = id.map(str::to_string);
            let text = export_flat_csv(&s).unwrap();
            let rows = csvio::parse_csv(&text).unwrap();
            let expense = rows.iter().find(|r| r[0] == "expense").unwrap();
            assert_eq!(expense.len(), FLAT_HEADER.len());
            assert_eq!(expense[8], cell, "{id:?}");
            // Personal: the group cell is empty (Just me).
            assert_eq!(expense[7], "");
        }
    }

    #[test]
    fn canonical_order_makes_export_byte_stable() {
        let mut shuffled = snapshot();
        shuffled.expenses.reverse();
        shuffled.settlements.reverse();
        shuffled.groups.reverse();
        assert_eq!(
            export_flat_csv(&shuffled).unwrap(),
            export_flat_csv(&snapshot()).unwrap()
        );
    }
}
