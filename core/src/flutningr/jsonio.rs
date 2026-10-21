//! The §9.2 JSON backup envelope: "complete, lossless, schema-versioned:
//! the format that makes the guarantee real."
//!
//! Versioning follows D-39's rule for the vault, applied to a file: the
//! format tag and schema version are the FIRST fields of the document, they
//! are checked BEFORE any record is interpreted. The two directions of
//! "unknown" are deliberately different:
//!
//! * NEWER than this build → refused as [`PortError::UnsupportedSchemaVersion`]
//!   (no downgrade, no best-effort): an old app must never half-read what it
//!   cannot fully understand, so a future file that renamed every field
//!   answers "unsupported version", never "malformed".
//! * OLDER than this build, down to [`super::SCHEMA_OLDEST_VERSION`] →
//!   UPGRADED on read through an explicit per-version step ([`upgrade_step`],
//!   D-90). §9.2 calls JSON "full backup and migration: the format that
//!   makes the guarantee real": a backup that stops importing after an app
//!   update is not one, and refusing older generations would defeat the point
//!   of versioning the envelope at all.

use super::{
    canonical_sorted, validate_snapshot, LedgerSnapshot, PortError, FORMAT_TAG,
    SCHEMA_OLDEST_VERSION, SCHEMA_VERSION,
};
use serde::{Deserialize, Serialize};

/// The full document. Field order here is byte order on the wire: the two
/// version keys lead, so the first thing in any export is its identity.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
struct Envelope {
    format: String,
    schema_version: u64,
    groups: Vec<super::PortGroup>,
    expenses: Vec<super::PortExpense>,
    settlements: Vec<super::PortSettlement>,
    redistributions: Vec<super::PortRedistribution>,
    /// D-211. Last, so a v3 document is a v4 one minus its final key.
    profile: Option<super::PortProfile>,
}

/// A v3 envelope: the format before D-211 added your profile. Its own type so
/// `deny_unknown_fields` refuses a v4-only field smuggled in behind a
/// downgraded version number, and so the 2 -> 3 step has a v3 to build.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
struct EnvelopeV3 {
    format: String,
    schema_version: u64,
    groups: Vec<super::PortGroup>,
    expenses: Vec<super::PortExpense>,
    settlements: Vec<super::PortSettlement>,
    redistributions: Vec<super::PortRedistribution>,
}

/// A v2 envelope: the format before D-210 added redistributions. Its own
/// type so `deny_unknown_fields` refuses a v3-only field smuggled in behind
/// a downgraded version number, and so the 1 -> 2 step has a v2 to build.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
struct EnvelopeV2 {
    format: String,
    schema_version: u64,
    groups: Vec<super::PortGroup>,
    expenses: Vec<super::PortExpense>,
    settlements: Vec<super::PortSettlement>,
}

/// Pass one: read ONLY the version keys. Deliberately lenient about the rest
/// of the document so a newer schema's reshaped records cannot preempt the
/// version refusal with a misleading parse error.
#[derive(Deserialize)]
struct HeaderPeek {
    format: String,
    schema_version: u64,
}

// ---------------------------------------------------------------------------
// Schema migrations: one explicit step per older generation (D-90)
// ---------------------------------------------------------------------------

/// A v1 envelope: the format before merki (D-86/D-88) added §8.1 categories.
/// Field-for-field today's record minus `category`. It is its own type so
/// `deny_unknown_fields` can refuse a v2-only field smuggled in behind a
/// downgraded version number: an expense claiming v1 while carrying a
/// category asserts something about its own history that was never true.
#[derive(Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
struct EnvelopeV1 {
    format: String,
    schema_version: u64,
    groups: Vec<super::PortGroup>,
    expenses: Vec<PortExpenseV1>,
    settlements: Vec<super::PortSettlement>,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
struct PortExpenseV1 {
    payer: String,
    paid_minor: i64,
    currency_code: String,
    #[serde(default)]
    group: Option<String>,
    #[serde(default)]
    conversion: Option<super::PortConversion>,
    shares: Vec<super::ExpenseShare>,
    #[serde(default)]
    note: Option<String>,
    #[serde(default)]
    timestamp_ms: Option<u64>,
}

impl From<PortExpenseV1> for super::PortExpense {
    fn from(e: PortExpenseV1) -> Self {
        // THE v1→v2 STEP, whole and explicit: categories did not exist yet,
        // so every upgraded expense is UNCATEGORISED, never guessed, never
        // back-filled from merchant text. Resolving labels against the tree
        // is the app's job at display time; the imported state says None.
        super::PortExpense {
            payer: e.payer,
            paid_minor: e.paid_minor,
            currency_code: e.currency_code,
            group: e.group,
            conversion: e.conversion,
            shares: e.shares,
            note: e.note,
            timestamp_ms: e.timestamp_ms,
            category: None,
        }
    }
}

/// Rewrites documents written by generation `from` into generation
/// `from + 1`, one explicit semantic step each. EVERY generation between
/// [`super::SCHEMA_OLDEST_VERSION`] and `SCHEMA_VERSION - 1` must have a row
/// here: the import driver walks these in ascending order, and the test
/// driver iterates the full accepted range over pinned fixtures, so a new
/// SCHEMA_VERSION without its step fails the gate loudly instead of silently
/// refusing files that used to import.
fn upgrade_step(from: u64, doc: &str) -> Result<String, PortError> {
    match from {
        1 => {
            let old: EnvelopeV1 = serde_json::from_str(doc)
                .map_err(|e| PortError::MalformedJson(format!("schema v1 record: {e}")))?;
            // The strict pass re-derives what the lenient header peek
            // believed: the document's own tag and version must agree with
            // it. A file whose two copies of its identity disagree was
            // mangled by something: refuse as corrupt, never upgrade.
            if old.format != FORMAT_TAG || old.schema_version != from {
                return Err(PortError::MalformedJson(
                    "v1 envelope header disagrees with the peeked identity".into(),
                ));
            }
            let upgraded = EnvelopeV2 {
                format: FORMAT_TAG.to_string(),
                schema_version: from + 1,
                groups: old.groups,
                expenses: old.expenses.into_iter().map(Into::into).collect(),
                settlements: old.settlements,
            };
            serde_json::to_string(&upgraded)
                .map_err(|e| PortError::MalformedJson(format!("v1 rewrite failed: {e}")))
        }
        2 => {
            let old: EnvelopeV2 = serde_json::from_str(doc)
                .map_err(|e| PortError::MalformedJson(format!("schema v2 record: {e}")))?;
            if old.format != FORMAT_TAG || old.schema_version != from {
                return Err(PortError::MalformedJson(
                    "v2 envelope header disagrees with the peeked identity".into(),
                ));
            }
            // THE v2→v3 STEP: redistributions did not exist yet, so every
            // upgraded ledger has none. Nothing else changes.
            let upgraded = EnvelopeV3 {
                format: FORMAT_TAG.to_string(),
                schema_version: from + 1,
                groups: old.groups,
                expenses: old.expenses,
                settlements: old.settlements,
                redistributions: vec![],
            };
            serde_json::to_string(&upgraded)
                .map_err(|e| PortError::MalformedJson(format!("v2 rewrite failed: {e}")))
        }
        3 => {
            let old: EnvelopeV3 = serde_json::from_str(doc)
                .map_err(|e| PortError::MalformedJson(format!("schema v3 record: {e}")))?;
            if old.format != FORMAT_TAG || old.schema_version != from {
                return Err(PortError::MalformedJson(
                    "v3 envelope header disagrees with the peeked identity".into(),
                ));
            }
            // THE v3→v4 STEP: your profile did not exist yet, so every
            // upgraded ledger has none. Nothing else changes.
            let upgraded = Envelope {
                format: FORMAT_TAG.to_string(),
                schema_version: from + 1,
                groups: old.groups,
                expenses: old.expenses,
                settlements: old.settlements,
                redistributions: old.redistributions,
                profile: None,
            };
            serde_json::to_string(&upgraded)
                .map_err(|e| PortError::MalformedJson(format!("v3 rewrite failed: {e}")))
        }
        other => Err(PortError::UnsupportedSchemaVersion {
            found: other,
            supported: SCHEMA_VERSION,
        }),
    }
}

/// Validates, canonicalises, and serialises the ledger as a versioned JSON
/// document. The output is deterministic: equal states produce byte-equal
/// files regardless of insertion order.
pub fn export_json(snapshot: &LedgerSnapshot) -> Result<String, PortError> {
    validate_snapshot(snapshot)?;
    let sorted = canonical_sorted(snapshot);
    let envelope = Envelope {
        format: FORMAT_TAG.to_string(),
        schema_version: SCHEMA_VERSION,
        groups: sorted.groups,
        expenses: sorted.expenses,
        settlements: sorted.settlements,
        redistributions: sorted.redistributions,
        profile: sorted.profile,
    };
    serde_json::to_string(&envelope)
        .map_err(|e| PortError::MalformedJson(format!("serialization failed: {e}")))
}

/// Parses a JSON export back into a validated snapshot. Refusals are typed
/// and ordered: syntax → format tag → schema version → record shape →
/// ledger integrity. Only a document that passes ALL of them yields state.
pub fn import_json(text: &str) -> Result<LedgerSnapshot, PortError> {
    let peek: HeaderPeek = match serde_json::from_str(text) {
        Ok(p) => p,
        Err(e) => {
            // Broken bytes vs. valid JSON that was never ours: different
            // answers to "is this corrupt?" and "is this someone else's file?"
            if serde_json::from_str::<serde::de::IgnoredAny>(text).is_ok() {
                return Err(PortError::WrongFormatTag(
                    "valid JSON without a skipti-ledger header".into(),
                ));
            }
            return Err(PortError::MalformedJson(e.to_string()));
        }
    };
    if peek.format != FORMAT_TAG {
        return Err(PortError::WrongFormatTag(format!(
            "expected {FORMAT_TAG:?}, found {:?}",
            peek.format
        )));
    }
    // Forward refusal first (D-39's newer-vault rule): anything above this
    // build's generation, and a nonsensical zero, is refused with the
    // version error, never a shape complaint from records we should not
    // even be reading.
    if peek.schema_version == 0 || peek.schema_version > SCHEMA_VERSION {
        return Err(PortError::UnsupportedSchemaVersion {
            found: peek.schema_version,
            supported: SCHEMA_VERSION,
        });
    }
    // Then walk OLDER generations forward one explicit step at a time until
    // the document is at the current generation (D-90). Each step is total:
    // a step that cannot parse its input answers MalformedJson naming the
    // schema it was working on.
    let mut doc_text = text.to_string();
    let mut at = peek.schema_version;
    while at < SCHEMA_VERSION {
        // The retired band: below SCHEMA_OLDEST_VERSION no step exists any
        // more, and a missing step must answer the version error, never
        // upgrade_step's shape complaint.
        if at < SCHEMA_OLDEST_VERSION {
            return Err(PortError::UnsupportedSchemaVersion {
                found: at,
                supported: SCHEMA_VERSION,
            });
        }
        doc_text = upgrade_step(at, &doc_text)?;
        at += 1;
    }
    let envelope: Envelope =
        serde_json::from_str(&doc_text).map_err(|e| PortError::MalformedJson(e.to_string()))?;
    // Every snapshot leaving this module is canonical: import ∘ export is
    // the identity on STATES, not merely on sets of records.
    let snapshot = super::canonical_sorted(&LedgerSnapshot {
        groups: envelope.groups,
        expenses: envelope.expenses,
        settlements: envelope.settlements,
        redistributions: envelope.redistributions,
        profile: envelope.profile,
    });
    validate_snapshot(&snapshot)?;
    Ok(snapshot)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::flutningr::{
        ExpenseShare, PortConversion, PortExpense, PortGroup, PortProfile, PortRedistribution,
        PortSettlement,
    };
    use crate::straumr::groups::PICTURE_MAX_BYTES;

    const ADA: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";
    const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
    const GROUP: &str = "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0";

    /// THE V1 FIXTURE (D-90): a byte-exact schema-v1 backup as the pre-merki
    /// app wrote them: identical in shape to today's envelope except
    /// `"schema_version":1` and NO category anywhere, because §8.1 did not
    /// exist yet. Pinned VERBATIM rather than produced by export_json: a
    /// regression in the writer must not teach the reader a self-consistent
    /// lie about what older files look like.
    const FIXTURE_V1: &str = r#"{"format":"skipti-ledger","schema_version":1,"groups":[{"id":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","name":"Goa \"trip\", 2026","settlement_currency":"INR","members":["a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"]}],"expenses":[{"payer":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","paid_minor":420000,"currency_code":"INR","group":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","conversion":{"converted_minor":5000,"converted_currency":"USD","rate_num":1,"rate_den":84,"rate_timestamp_ms":1768171200000},"shares":[{"id":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","amount_minor":210000},{"id":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","amount_minor":210000}],"note":"line\nbreak, \"quote\" and ₹ ₹ ₹","timestamp_ms":1768435200000}],"settlements":[{"from":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","to":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","amount_minor":100000,"currency_code":"INR","group":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0"}]}"#;

    /// THE V2 FIXTURE (D-210): a byte-exact schema-v2 backup as the build before
    /// redistributions wrote them: the v1 records plus one categorised
    /// expense, and no `redistributions` key. Pinned verbatim for the same
    /// reason as FIXTURE_V1.
    const FIXTURE_V2: &str = r#"{"format":"skipti-ledger","schema_version":2,"groups":[{"id":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","name":"Goa \"trip\", 2026","settlement_currency":"INR","members":["a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"]}],"expenses":[{"payer":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","paid_minor":420000,"currency_code":"INR","group":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","conversion":{"converted_minor":5000,"converted_currency":"USD","rate_num":1,"rate_den":84,"rate_timestamp_ms":1768171200000},"category":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","shares":[{"id":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","amount_minor":210000},{"id":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","amount_minor":210000}],"note":"line\nbreak, \"quote\" and ₹ ₹ ₹","timestamp_ms":1768435200000}],"settlements":[{"from":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","to":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","amount_minor":100000,"currency_code":"INR","group":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0"}]}"#;

    /// THE V3 FIXTURE (D-211): a byte-exact schema-v3 backup as the build before
    /// your profile wrote them: the v2 records plus one redistribution, and no
    /// `profile` key. Pinned verbatim for the same reason as FIXTURE_V1.
    const FIXTURE_V3: &str = r#"{"format":"skipti-ledger","schema_version":3,"groups":[{"id":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","name":"Goa \"trip\", 2026","settlement_currency":"INR","members":["a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"]}],"expenses":[{"payer":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","paid_minor":420000,"currency_code":"INR","group":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","conversion":{"converted_minor":5000,"converted_currency":"USD","rate_num":1,"rate_den":84,"rate_timestamp_ms":1768171200000},"category":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","shares":[{"id":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","amount_minor":210000},{"id":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","amount_minor":210000}],"note":"line\nbreak, \"quote\" and ₹ ₹ ₹","timestamp_ms":1768435200000}],"settlements":[{"from":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","to":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","amount_minor":100000,"currency_code":"INR","group":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0"}],"redistributions":[{"from":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","currency_code":"INR","group":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0","shares":[{"id":"a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0","amount_minor":-33334},{"id":"c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1","amount_minor":-33333}]}]}"#;

    fn populated() -> LedgerSnapshot {
        LedgerSnapshot {
            groups: vec![PortGroup {
                id: GROUP.into(),
                name: "Goa \"trip\", 2026".into(),
                settlement_currency: "INR".into(),
                members: vec![ADA.into(), BOB.into()],
            }],
            expenses: vec![PortExpense {
                payer: ADA.into(),
                paid_minor: 420_000,
                currency_code: "INR".into(),
                group: Some(GROUP.into()),
                category: None,
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
                        amount_minor: 210_000,
                    },
                    ExpenseShare {
                        id: BOB.into(),
                        amount_minor: 210_000,
                    },
                ],
                note: Some("line\nbreak, \"quote\" and ₹ ₹ ₹".into()),
                timestamp_ms: Some(1_768_435_200_000),
            }],
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
    fn envelope_leads_with_the_version_keys() {
        let doc = export_json(&populated()).unwrap();
        assert!(
            doc.starts_with(
                format!("{{\"format\":\"{FORMAT_TAG}\",\"schema_version\":{SCHEMA_VERSION},")
                    .as_str()
            ),
            "version keys must be the first bytes of the document"
        );
    }

    #[test]
    fn round_trip_is_lossless_and_byte_stable() {
        let s = populated();
        let j1 = export_json(&s).unwrap();
        let parsed = import_json(&j1).unwrap();
        assert_eq!(parsed, s, "import must reproduce the exact state");
        let j2 = export_json(&parsed).unwrap();
        assert_eq!(j1, j2, "re-exporting imported state must be byte-identical");
    }

    #[test]
    fn insertion_order_cannot_change_the_bytes() {
        let mut shuffled = populated();
        shuffled.expenses.reverse();
        shuffled.settlements.reverse();
        shuffled.groups.reverse();
        assert_eq!(export_json(&shuffled), export_json(&populated()));
    }

    #[test]
    fn wrong_format_tag_is_refused_before_shape() {
        let doc = export_json(&populated()).unwrap();
        let foreign = doc.replace(FORMAT_TAG, "someone-else");
        assert!(matches!(
            import_json(&foreign),
            Err(PortError::WrongFormatTag(_))
        ));
        // And a valid JSON object that was never ours at all:
        assert!(matches!(
            import_json("{\"hello\":1}"),
            Err(PortError::WrongFormatTag(_))
        ));
    }

    #[test]
    fn newer_schema_refuses_as_a_version_error_even_if_records_would_not_parse() {
        let doc = export_json(&populated()).unwrap();
        // Simulate a future file whose record shape ALSO changed: the
        // reported error must still be the version, not the shape.
        let future = doc
            .replace("\"schema_version\":4", "\"schema_version\":99")
            .replace("\"payer\"", "\"paid_by\"");
        assert_eq!(
            import_json(&future),
            Err(PortError::UnsupportedSchemaVersion {
                found: 99,
                supported: SCHEMA_VERSION
            })
        );
    }

    #[test]
    fn unknown_fields_and_trailing_garbage_are_rejected() {
        let doc = export_json(&populated()).unwrap();
        let injected = doc.replace(
            "\"settlements\":[",
            "\"attacker_note\":\"x\",\"settlements\":[",
        );
        assert!(matches!(
            import_json(&injected),
            Err(PortError::MalformedJson(_))
        ));
        assert!(matches!(
            import_json(format!("{doc}{}", " trailing").as_str()),
            Err(PortError::MalformedJson(_))
        ));
        // Valid JSON that was never a ledger at all answers "not ours",
        // not "corrupt", and true syntax garbage stays Malformed:
        assert!(matches!(
            import_json("[1,2,3]"),
            Err(PortError::WrongFormatTag(_))
        ));
        assert!(matches!(import_json("{"), Err(PortError::MalformedJson(_))));
    }

    #[test]
    fn export_validates_before_writing_anything() {
        let mut s = populated();
        s.expenses[0].shares[0].amount_minor += 1; // shares no longer sum to total
        assert_eq!(
            export_json(&s),
            Err(PortError::InvalidLedger(
                "shares sum to 420001, total is 420000".into()
            ))
        );
    }

    #[test]
    fn import_of_an_inconsistent_document_fails_closed_after_parsing() {
        let mut s = populated();
        s.expenses[0].conversion.as_mut().unwrap().converted_minor = 1; // not what the rate yields
        let doc = export_json(&s).unwrap_or_default(); // must fail...
        assert!(doc.is_empty(), "tampered state must not have exported");
        // ...so tamper at the TEXT level instead: a hand-edited frozen amount.
        let doc = export_json(&populated()).unwrap();
        let edited = doc.replace("\"converted_minor\":5000", "\"converted_minor\":1");
        assert!(matches!(
            import_json(&edited),
            Err(PortError::InvalidLedger(_))
        ));
    }

    /// The fixture text with the envelope version rewritten: the same
    /// records, claiming generation `gen`.
    fn fixture_for(gen: u64) -> String {
        let doc = FIXTURE_V1.replacen(
            "\"schema_version\":1",
            &format!("\"schema_version\":{gen}"),
            1,
        );
        // From generation 3 the envelope carries redistributions.
        if gen >= 3 {
            doc.trim_end_matches('}').to_string() + ",\"redistributions\":[]}"
        } else {
            doc
        }
    }

    #[test]
    fn v1_backup_imports_and_upgrades_to_the_current_generation() {
        let expected = populated();
        // The upgrade's ONLY semantic content: pre-category records arrive
        // UNCATEGORISED: a deliberate None, never a guess.
        let mut expected_uncategorised = expected.clone();
        expected_uncategorised.expenses[0].category = None;

        let imported = import_json(FIXTURE_V1).unwrap();
        assert_eq!(
            imported, expected_uncategorised,
            "the v1 fixture must reconstruct its exact original state"
        );

        // And the upgraded state re-exports as CURRENT bytes: the backup has
        // joined this build's generation (import ∘ export stays identity on
        // states across the migration boundary too).
        let j2 = export_json(&imported).unwrap();
        assert_eq!(j2, export_json(&expected).unwrap());
        assert!(j2.starts_with("{\"format\":\"skipti-ledger\",\"schema_version\":4,"));

        // The migration is total over every surviving field: note quoting,
        // the frozen conversion and timestamps all arrive intact.
        assert_eq!(
            imported.expenses[0].note.as_deref(),
            Some("line\nbreak, \"quote\" and ₹ ₹ ₹")
        );
        assert_eq!(
            imported.expenses[0]
                .conversion
                .as_ref()
                .unwrap()
                .converted_minor,
            5_000
        );
    }

    #[test]
    fn every_generation_from_oldest_through_current_imports() {
        for gen in SCHEMA_OLDEST_VERSION..=SCHEMA_VERSION {
            import_json(&fixture_for(gen))
                .unwrap_or_else(|e| panic!("generation {gen} is inside the accepted range: {e}"));
        }
        // The driver refuses everything it must not read: zero (nonsense),
        // one past current (an app update away), and far past current. Each
        // names BOTH numbers so "update the app" is the readable diagnosis.
        for gen in [0, SCHEMA_VERSION + 1, SCHEMA_VERSION + 97] {
            assert_eq!(
                import_json(&fixture_for(gen)),
                Err(PortError::UnsupportedSchemaVersion {
                    found: gen,
                    supported: SCHEMA_VERSION
                }),
                "generation {gen} must refuse as a version error"
            );
        }
    }

    fn with_redistribution() -> LedgerSnapshot {
        let mut s = populated();
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
                    id: "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1".into(),
                    amount_minor: -33_333,
                },
            ],
        });
        s
    }

    #[test]
    fn redistributions_round_trip_in_a_schema_4_document() {
        let s = with_redistribution();
        let j1 = export_json(&s).unwrap();
        assert!(j1.contains("\"schema_version\":4"), "{j1}");
        assert!(j1.contains("\"redistributions\":[{"), "{j1}");
        let parsed = import_json(&j1).unwrap();
        assert_eq!(parsed, s);
        assert_eq!(export_json(&parsed).unwrap(), j1);
        // An empty ledger of them still writes the key.
        assert!(export_json(&populated())
            .unwrap()
            .ends_with("\"redistributions\":[],\"profile\":null}"));
    }

    #[test]
    fn redistribution_order_cannot_change_the_bytes() {
        let mut two = with_redistribution();
        let mut second = two.redistributions[0].clone();
        second.from = ADA.into();
        second.shares = vec![ExpenseShare {
            id: BOB.into(),
            amount_minor: 5,
        }];
        two.redistributions.push(second);
        let mut flipped = two.clone();
        flipped.redistributions.reverse();
        assert_eq!(export_json(&two), export_json(&flipped));
        // Sorted by (group, from, ...): ADA's comes before BOB's.
        let parsed = import_json(&export_json(&two).unwrap()).unwrap();
        assert_eq!(parsed.redistributions[0].from, ADA);
    }

    #[test]
    fn a_v2_backup_upgrades_with_no_redistributions() {
        let imported = import_json(FIXTURE_V2).unwrap();
        assert!(imported.redistributions.is_empty());
        assert_eq!(
            imported.expenses[0].category.as_deref(),
            Some("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
        );
        assert_eq!(imported.settlements.len(), 1);
        let again = export_json(&imported).unwrap();
        assert!(again.starts_with("{\"format\":\"skipti-ledger\",\"schema_version\":4,"));
        assert!(again.ends_with("\"redistributions\":[],\"profile\":null}"));
        // The v1 fixture climbs both steps to the same place, minus the category.
        let from_v1 = import_json(FIXTURE_V1).unwrap();
        assert_eq!(from_v1.settlements, imported.settlements);
        assert!(from_v1.redistributions.is_empty());
    }

    fn with_profile(p: PortProfile) -> LedgerSnapshot {
        let mut s = populated();
        s.profile = Some(p);
        s
    }

    fn tim_photo() -> PortProfile {
        PortProfile {
            name: Some("Tim".into()),
            emoji: None,
            photo_hex: Some("ffd8ff00".into()),
        }
    }

    #[test]
    fn a_profile_round_trips_byte_stable_in_a_schema_4_document() {
        let s = with_profile(tim_photo());
        let j1 = export_json(&s).unwrap();
        assert!(j1.contains("\"schema_version\":4"), "{j1}");
        assert!(
            j1.ends_with(
                "\"profile\":{\"name\":\"Tim\",\"emoji\":null,\"photo_hex\":\"ffd8ff00\"}}"
            ),
            "{j1}"
        );
        let parsed = import_json(&j1).unwrap();
        assert_eq!(parsed, s);
        assert_eq!(export_json(&parsed).unwrap(), j1);
        // An emoji profile with no name round-trips too.
        let fox = with_profile(PortProfile {
            name: None,
            emoji: Some("\u{1F98A}".into()),
            photo_hex: None,
        });
        assert_eq!(import_json(&export_json(&fox).unwrap()).unwrap(), fox);
    }

    #[test]
    fn a_v3_backup_upgrades_to_v4_with_no_profile_and_nothing_else_changed() {
        let imported = import_json(FIXTURE_V3).unwrap();
        assert_eq!(imported.profile, None);
        assert_eq!(imported.redistributions.len(), 1);
        assert_eq!(imported.redistributions[0].shares[0].amount_minor, -33_334);
        assert_eq!(imported.settlements.len(), 1);
        assert_eq!(imported.expenses.len(), 1);
        assert_eq!(
            imported.expenses[0].category.as_deref(),
            Some("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
        );
        // The v4 envelope: the v3 header bumped, the v3 records, and the one
        // new key last. (Field order inside an expense is the writer's, not
        // the old fixture's, so the comparison is by prefix, tail and state.)
        let again = export_json(&imported).unwrap();
        assert!(again.starts_with("{\"format\":\"skipti-ledger\",\"schema_version\":4,"));
        let v3_redistributions = &FIXTURE_V3[FIXTURE_V3.find("\"redistributions\"").unwrap()..];
        assert_eq!(
            &again[again.find("\"redistributions\"").unwrap()..],
            format!(
                "{},\"profile\":null}}",
                v3_redistributions.trim_end_matches('}')
            )
        );
        assert_eq!(import_json(&again).unwrap(), imported);
        // The v2 fixture climbs all the way too.
        assert_eq!(import_json(FIXTURE_V2).unwrap().profile, None);
    }

    #[test]
    fn a_v3_document_cannot_smuggle_a_profile() {
        let smuggled = FIXTURE_V3.trim_end_matches('}').to_string()
            + ",\"profile\":{\"name\":\"Tim\",\"emoji\":null,\"photo_hex\":null}}";
        assert!(matches!(
            import_json(&smuggled),
            Err(PortError::MalformedJson(_))
        ));
    }

    #[test]
    fn a_profile_picture_that_breaks_the_records_rules_is_refused() {
        let refused = |p: PortProfile| {
            let s = with_profile(p);
            assert!(
                matches!(export_json(&s), Err(PortError::InvalidLedger(_))),
                "export must refuse {s:?}"
            );
        };
        let big = format!("ffd8ff{}", "00".repeat(PICTURE_MAX_BYTES));
        let exact = format!("ffd8ff{}", "00".repeat(PICTURE_MAX_BYTES - 3));
        // Not a JPEG, odd hex, uppercase hex, over the limit.
        for bad in ["89504e47", "ffd8ff0", "FFD8FF00", &big] {
            refused(PortProfile {
                name: None,
                emoji: None,
                photo_hex: Some(bad.to_string()),
            });
        }
        // Both an emoji and a photo.
        refused(PortProfile {
            name: None,
            emoji: Some("\u{1F98A}".into()),
            photo_hex: Some("ffd8ff00".into()),
        });
        // The size limit itself is allowed.
        let ok = with_profile(PortProfile {
            name: None,
            emoji: None,
            photo_hex: Some(exact),
        });
        assert!(export_json(&ok).is_ok());
    }

    #[test]
    fn a_hand_written_profile_is_refused_on_import_too() {
        let write = |profile: &str| {
            export_json(&populated())
                .unwrap()
                .replace("\"profile\":null", &format!("\"profile\":{profile}"))
        };
        assert!(import_json(&write(
            "{\"name\":\"Tim\",\"emoji\":null,\"photo_hex\":\"ffd8ff00\"}"
        ))
        .is_ok());
        for bad in [
            "{\"name\":null,\"emoji\":null,\"photo_hex\":\"89504e47\"}",
            "{\"name\":null,\"emoji\":\"x\",\"photo_hex\":\"ffd8ff00\"}",
            "{\"name\":\" Tim\",\"emoji\":null,\"photo_hex\":null}",
            "{\"name\":\"\",\"emoji\":null,\"photo_hex\":null}",
        ] {
            assert!(
                matches!(import_json(&write(bad)), Err(PortError::InvalidLedger(_))),
                "{bad}"
            );
        }
        // An unknown key in the profile is a shape error.
        assert!(matches!(
            import_json(&write(
                "{\"name\":null,\"emoji\":null,\"photo_hex\":null,\"extra\":1}"
            )),
            Err(PortError::MalformedJson(_))
        ));
    }

    #[test]
    fn a_profile_name_that_breaks_the_member_name_rules_is_refused() {
        let long = "a".repeat(65);
        for bad in ["", " Tim", "Tim ", "Ti\nm", long.as_str()] {
            let s = with_profile(PortProfile {
                name: Some(bad.to_string()),
                emoji: None,
                photo_hex: None,
            });
            assert!(
                matches!(export_json(&s), Err(PortError::InvalidLedger(_))),
                "{bad:?}"
            );
        }
        let sixty_four = "a".repeat(64);
        let s = with_profile(PortProfile {
            name: Some(sixty_four),
            emoji: None,
            photo_hex: None,
        });
        assert!(export_json(&s).is_ok());
    }

    #[test]
    fn a_v2_document_cannot_smuggle_redistributions() {
        let smuggled = FIXTURE_V2.trim_end_matches('}').to_string()
            + ",\"redistributions\":[{\"from\":\"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0\",\"currency_code\":\"INR\",\"group\":\"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0\",\"shares\":[]}]}";
        assert!(matches!(
            import_json(&smuggled),
            Err(PortError::MalformedJson(_))
        ));
    }

    #[test]
    fn a_downgraded_version_cannot_smuggle_current_generation_fields() {
        // A file claiming v1 whose expenses carry v2's category field: the
        // version number alone would accept it via the upgrade path, but the
        // v1 record type must not know categories at all.
        let smuggled = FIXTURE_V1.replace(
            "\"shares\":[",
            "\"category\":\"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1\",\"shares\":[",
        );
        assert!(matches!(
            import_json(&smuggled),
            Err(PortError::MalformedJson(_))
        ));
    }
}
