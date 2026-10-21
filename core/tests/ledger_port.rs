//! Integration proof for the flutningr portage layer (SPEC §9.2/§9.5,
//! GUARANTEES #4): a populated ledger (group, settlement, converted
//! expense with frozen FX rate) survives export/import BYTE-IDENTICALLY,
//! and a Splitwise sheet migrates through the dry run into the same
//! state without ever touching a float or inventing a parser.

use skipti_core::flutningr::jsonio::export_json;
use skipti_core::flutningr::{
    apply_plan, export_flat_csv, import_json, plan_splitwise_import, CurrencyTotal, DateFormat,
    DuplicateAgainst, DuplicateHit, ExpenseShare, MemberBinding, PortConversion, PortExpense,
    PortGroup, PortRedistribution, PortSettlement, SplitwiseOptions,
};

const ADA: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";
const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
const CAROL: &str = "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1";
const GOA: &str = "d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2";

/// The deterministic default-tree id for Food & Drink · Groceries: the
/// same value on every device (sha256 of the stable path; merki pins it).
fn groceries_category() -> String {
    let tree = skipti_core::merki::CategoryTree::with_defaults();
    tree.categories
        .iter()
        .find(|c| c.name == "Groceries" && c.parent.is_some())
        .unwrap()
        .id
        .clone()
}

/// The populated ledger the round trip must preserve exactly.
fn populated_ledger() -> skipti_core::flutningr::LedgerSnapshot {
    skipti_core::flutningr::LedgerSnapshot {
        groups: vec![PortGroup {
            id: GOA.into(),
            name: "Goa \"trip\", Jan 2026".into(),
            settlement_currency: "INR".into(),
            members: vec![ADA.into(), BOB.into(), CAROL.into()],
        }],
        expenses: vec![
            // Converted expense with its FROZEN FX rate (§5.2):
            // ₹4,200 at 1/84 USD → $50.00 exactly.
            PortExpense {
                payer: ADA.into(),
                paid_minor: 420_000,
                currency_code: "INR".into(),
                group: Some(GOA.into()),
                conversion: Some(PortConversion {
                    converted_minor: 5_000,
                    converted_currency: "USD".into(),
                    rate_num: 1,
                    rate_den: 84,
                    rate_timestamp_ms: 1_768_171_200_000,
                }),
                shares: vec![
                    ExpenseShare {
                        id: ADA.into(),
                        amount_minor: 140_000,
                    },
                    ExpenseShare {
                        id: BOB.into(),
                        amount_minor: 140_000,
                    },
                    ExpenseShare {
                        id: CAROL.into(),
                        amount_minor: 140_000,
                    },
                ],
                note: Some("Villa, first \"half\"".into()),
                timestamp_ms: Some(20_459 * 86_400_000),
                // §8.1: the villa carries a real category: the stable id
                // from the default tree (Food & Drink · Groceries), pinned
                // through the v2 envelope round trip below.
                category: Some(groceries_category()),
            },
            // Zero-decimal currency, no note:
            PortExpense {
                payer: BOB.into(),
                paid_minor: 3_800,
                currency_code: "JPY".into(),
                group: Some(GOA.into()),
                conversion: None,
                shares: vec![
                    ExpenseShare {
                        id: ADA.into(),
                        amount_minor: 1_900,
                    },
                    ExpenseShare {
                        id: BOB.into(),
                        amount_minor: 1_900,
                    },
                ],
                note: None,
                timestamp_ms: Some(20_460 * 86_400_000 + 7_200_000),
                category: None,
            },
        ],
        settlements: vec![PortSettlement {
            from: BOB.into(),
            to: ADA.into(),
            amount_minor: 150_000,
            currency_code: "INR".into(),
            group: Some(GOA.into()),
        }],
        // D-210: Carol was removed owed ₹1,000; it moved to Ada and Bob.
        redistributions: vec![PortRedistribution {
            from: CAROL.into(),
            currency_code: "INR".into(),
            group: GOA.into(),
            shares: vec![
                ExpenseShare {
                    id: ADA.into(),
                    amount_minor: -50_000,
                },
                ExpenseShare {
                    id: BOB.into(),
                    amount_minor: -50_000,
                },
            ],
        }],
        // D-211: your name and picture.
        profile: Some(skipti_core::flutningr::PortProfile {
            name: Some("Ada".into()),
            emoji: None,
            photo_hex: Some("ffd8ff00".into()),
        }),
    }
}

/// A Splitwise export shaped like the real thing: BOM, CRLF, quoted commas,
/// an empty-cell member, a trap row, and the trailing summary row.
fn splitwise_sheet() -> String {
    [
        "\u{FEFF}Date,Description,Category,Cost,Currency,Alice,Bob,Carol\r\n",
        "2026-01-10,\"Dinner, seaside\",Food,60.00,USD,30.00,-30.00,\r\n",
        "2026-01-11,Tuk tuk,Transport,\"1,234.50\",INR,411.50,-411.50,\r\n",
        "2026-01-12,Scooter,,100.00,INR,34.00,-33.00,-33.00\r\n",
        "Total balance,,,,INR,64.50,-64.50,\r\n",
    ]
    .concat()
}

fn options() -> SplitwiseOptions {
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
        group: Some(GOA.into()),
    }
}

#[test]
fn json_export_round_trips_a_populated_ledger_byte_identically() {
    let ledger = populated_ledger();
    let j1 = export_json(&ledger).unwrap();

    let restored = import_json(&j1).unwrap();
    assert_eq!(restored, ledger, "imported state must equal the original");
    assert_eq!(
        restored.expenses[0].category.as_deref(),
        Some(groceries_category().as_str()),
        "§8.1 category survives the envelope losslessly"
    );

    let j2 = export_json(&restored).unwrap();
    assert_eq!(j1, j2, "re-export of imported state must be BYTE-identical");
    assert!(j1.starts_with("{\"format\":\"skipti-ledger\",\"schema_version\":4,"));

    // Accumulation order cannot change the artifact:
    let mut shuffled = ledger.clone();
    shuffled.expenses.reverse();
    shuffled.settlements.reverse();
    shuffled.groups.reverse();
    assert_eq!(export_json(&shuffled).unwrap(), j1);
}

#[test]
fn splitwise_migration_flows_through_the_dry_run_into_exportable_state() {
    let existing = populated_ledger();

    // THE DRY RUN. Nothing has been created anywhere yet:
    let plan = plan_splitwise_import(&splitwise_sheet(), &options(), &existing).unwrap();

    // Row 3 ("Tuk tuk") carries a grouping separator: refused, not rounded.
    // Row 4 does not net to zero: refused with that reason.
    // Row 5 is the summary row: skipped as a summary, never an expense.
    assert_eq!(plan.rejections.len(), 3, "{plan:?}");
    assert!(plan.rejections[0].reason.contains("digits and one dot"));
    assert_eq!(plan.rejections[0].row_number, 3);
    assert!(plan.rejections[1].reason.contains("nets to zero"));
    assert!(plan.rejections[2].reason.contains("summary row"));
    assert_eq!(plan.rejections[2].row_number, 5);

    // Exactly one faithful proposal, reconstructed to the penny:
    assert_eq!(plan.expenses.len(), 1);
    let e = &plan.expenses[0];
    assert_eq!(e.payer, ADA);
    assert_eq!(e.paid_minor, 6_000); // $60.00
    assert_eq!(
        e.shares,
        vec![
            ExpenseShare {
                id: ADA.into(),
                amount_minor: 3_000
            },
            ExpenseShare {
                id: BOB.into(),
                amount_minor: 3_000
            },
        ]
    );
    assert_eq!(e.note.as_deref(), Some("Dinner, seaside (Food)"));
    assert_eq!(e.timestamp_ms, Some(20_463 * 86_400_000)); // UTC midnight of 2026-01-10
    assert_eq!(e.group.as_deref(), Some(GOA));
    assert_eq!(
        plan.totals_by_currency,
        vec![CurrencyTotal {
            currency_code: "USD".into(),
            cost_minor: 6_000
        }]
    );

    // COMMIT AS ONE UNIT. The pure merge either returns everything or refuses:
    let merged = apply_plan(&existing, &plan).unwrap();
    assert_eq!(merged.expenses.len(), 3);
    assert_eq!(
        existing.expenses.len(),
        2,
        "input ledger untouched by planning/applying"
    );

    // And the migrated state exports and re-imports BYTE-identically too:
    let j1 = export_json(&merged).unwrap();
    let restored = import_json(&j1).unwrap();
    assert_eq!(restored, merged);
    assert_eq!(export_json(&restored).unwrap(), j1);
}

#[test]
fn duplicate_detection_covers_the_migration_case() {
    let existing = populated_ledger();
    // Same merchant (none), same calendar day (2026-01-07 = day 20460),
    // same amount as the JPY expense. Note the zero-decimal cells:
    // "3800.00" would be refused, not matched.
    let sheet = "\u{FEFF}Date,Description,Category,Cost,Currency,Alice,Bob,Carol\r\n\
         2026-01-07,,,3800,JPY,1900,-1900,\r\n";
    let mut o = options();
    o.date_format = DateFormat::IsoYmd;
    let plan = plan_splitwise_import(sheet, &o, &existing).unwrap();
    assert_eq!(plan.expenses.len(), 1);
    assert_eq!(
        plan.duplicates,
        vec![DuplicateHit {
            expense_index: 0,
            against: DuplicateAgainst::Existing { index: 1 },
        }]
    );
}

#[test]
fn flat_csv_is_a_view_built_from_the_same_validated_state() {
    let ledger = populated_ledger();
    let csv = export_flat_csv(&ledger).unwrap();
    // Text opens with the header; the §9.2 BOM is the file writer's job
    // (incident §5.15: a leading U+FEFF cannot survive the Swift lift).
    assert!(csv.starts_with("record_type,date_utc,currency,amount"));
    // INR settlement rendered in exact paise; KWD-style precision would show three.
    assert!(csv.contains("settlement,,INR,1500.00"), "{csv:?}");
}

/// A BudgetBakers Wallet statement shaped like the real thing: header first,
/// income row the ledger cannot represent, a signed-negative expense with
/// time-of-day, and an unknown-type trap.
fn budgetbakers_statement() -> String {
    [
        "\u{FEFF}Account,Category,Currency,Amount,Account Currency,Income/Expense,\
         Refund,Refund Currency,Note,Date\r\n",
        "Checking,Salary,INR,500000,,Income,,,Monthly pay,2026-01-05 09:00:00\r\n",
        "Cash,Groceries,INR,-2547.50,,Expense,,,Corner shop,2026-01-15 13:30:00\r\n",
        "Cash,,XXX,10,,Garbage,,,?,2026-01-03 00:00:00\r\n",
    ]
    .concat()
}

#[test]
fn budgetbakers_migration_flows_through_the_same_engine_as_splitwise() {
    let existing = populated_ledger();
    let options = skipti_core::flutningr::BudgetBakersOptions {
        self_participant: ADA.into(),
        group: Some(GOA.into()),
    };

    // THE DRY RUN. Same plan shape as Splitwise's, nothing created:
    let plan = skipti_core::flutningr::plan_budgetbakers_import(
        &budgetbakers_statement(),
        &options,
        &existing,
    )
    .unwrap();

    // Income and the unknown-typed row are rejected by name; only the
    // expense becomes a proposal:
    assert_eq!(plan.rejections.len(), 2, "{plan:?}");
    assert!(plan.rejections[0].reason.contains("income record"));
    assert_eq!(plan.rejections[0].row_number, 2);
    assert!(plan.rejections[1].reason.contains("unknown record type"));

    assert_eq!(plan.expenses.len(), 1);
    let e = &plan.expenses[0];
    assert_eq!(e.payer, ADA);
    assert_eq!(e.paid_minor, 254_750); // ₹2,547.50, exact paise
    assert_eq!(
        e.shares,
        vec![ExpenseShare {
            id: ADA.into(),
            amount_minor: 254_750
        }]
    );
    assert_eq!(e.note.as_deref(), Some("Corner shop (Groceries)"));
    assert_eq!(e.timestamp_ms, Some(20_468 * 86_400_000 + 48_600_000));
    assert_eq!(
        plan.totals_by_currency,
        vec![CurrencyTotal {
            currency_code: "INR".into(),
            cost_minor: 254_750
        }]
    );

    // Same all-or-nothing commit, then the migrated state exports and
    // re-imports BYTE-identically like any other ledger state:
    let merged = apply_plan(&existing, &plan).unwrap();
    assert_eq!(merged.expenses.len(), 3);
    let j1 = export_json(&merged).unwrap();
    assert_eq!(import_json(&j1).unwrap(), merged);
    assert_eq!(export_json(&import_json(&j1).unwrap()).unwrap(), j1);
}
