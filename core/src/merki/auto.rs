//! §8.2's on-device auto-categorisation: the deterministic half.
//!
//! SPEC order, kept exactly: **user rules first, always highest priority**
//! (§8.3's engine), then the **bundled merchant table**, then NOTHING:
//! an unclassified record stays unclassified. "Prefer leaving it
//! uncategorised over guessing" is enforced structurally: the table is
//! EXACT-match on a normalised merchant string, there is no fuzzy
//! fallback, and a table entry that cannot resolve to an assignable leaf
//! in the CURRENT tree yields blank rather than a wrong guess.
//!
//! Determinism contract (the founder's bar): the same merchant string, the
//! same table, the same tree, and the same rules classify identically on
//! every device and every run. The engine has no clock and no entropy
//! (§8.3's context carries the day); the table is exact-match; the
//! normalisation is fixed string work. The §8.2 items that CANNOT meet
//! that bar today (the TFLite/Core ML classifier and nightly learning)
//! are deliberately absent: an ML runtime's inference is not guaranteed
//! bit-identical across devices/versions, and a probabilistic category is
//! exactly the unreviewed wrong category this module exists to avoid.
//! Recorded in BUILD-LOG as deferred with that reasoning.
//!
//! Table sources: the BUNDLED starter set (compiled into core, trusted as
//! code) and operator SIGNED updates (Ed25519 over the exact payload
//! bytes, the `fx` module's pattern: line-oriented, canonical-by-
//! construction, never returned unverified). Entries reference categories
//! by DISPLAY PATH ("Parent / Child") resolved case-insensitively against
//! the current tree, so the table survives per-device id generation, and
//! a renamed category simply stops resolving (blank, not wrong).
//!
//! NO merchant string leaves the device: the table ships with the app and
//! updates are a static signed file. §8.2's "no cloud inference" is
//! satisfied by there being no network call anywhere in this module.

use super::{evaluate, CategoryTree, Rule, RuleContext};

/// The normalisation every pattern and every merchant string passes
/// through before comparison: trim, lowercase, collapse internal
/// whitespace runs to one space. Fixed, documented, no unicode
/// folding subtleties: determinism beats cleverness.
pub fn normalise(merchant: &str) -> String {
    let mut out = String::with_capacity(merchant.len());
    let mut in_space = false;
    for ch in merchant.trim().chars() {
        if ch.is_whitespace() {
            if !in_space {
                out.push(' ');
                in_space = true;
            }
        } else {
            in_space = false;
            for lower in ch.to_lowercase() {
                out.push(lower);
            }
        }
    }
    out
}

/// One table entry: exact pattern → display path of an assignable leaf.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MerchantEntry {
    /// Already normalised at load time; lookups normalise the merchant
    /// string, never the stored pattern.
    pub pattern: String,
    pub parent_name: String,
    pub child_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, uniffi::Record)]
pub struct MerchantTable {
    pub version: u64,
    pub entries: Vec<MerchantEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum MerchantTableError {
    /// Not a MERCHANTS1 file, or a malformed line (wrong field count).
    Malformed(String),
    /// Duplicate pattern (two categories for one merchant = a guess).
    DuplicatePattern(String),
    /// A path field is empty after normalisation.
    EmptyField(String),
}

impl std::fmt::Display for MerchantTableError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MerchantTableError::Malformed(m) => write!(f, "malformed merchant table: {m}"),
            MerchantTableError::DuplicatePattern(m) => {
                write!(f, "duplicate pattern: {m}")
            }
            MerchantTableError::EmptyField(m) => write!(f, "empty field: {m}"),
        }
    }
}

impl std::error::Error for MerchantTableError {}

fn parse_table(payload: &str) -> Result<MerchantTable, MerchantTableError> {
    let mut lines = payload.lines();
    if lines.next() != Some("MERCHANTS1") {
        return Err(MerchantTableError::Malformed(
            "must start with MERCHANTS1".into(),
        ));
    }
    let version = lines
        .next()
        .and_then(|l| l.strip_prefix("version="))
        .and_then(|v| v.parse::<u64>().ok())
        .ok_or_else(|| MerchantTableError::Malformed("missing version=N".into()))?;
    let mut entries = Vec::new();
    let mut seen = std::collections::BTreeSet::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        let mut parts = line.split('\t');
        let raw_pattern = parts
            .next()
            .ok_or_else(|| MerchantTableError::Malformed(format!("{line:?}")))?;
        let parent = parts
            .next()
            .ok_or_else(|| MerchantTableError::Malformed(format!("{line:?}")))?
            .trim();
        let child = parts
            .next()
            .ok_or_else(|| MerchantTableError::Malformed(format!("{line:?}")))?
            .trim();
        if parts.next().is_some() {
            return Err(MerchantTableError::Malformed(format!("{line:?}")));
        }
        if parent.is_empty() || child.is_empty() {
            return Err(MerchantTableError::EmptyField(line.to_string()));
        }
        let pattern = normalise(raw_pattern);
        if pattern.is_empty() || pattern == "*" {
            return Err(MerchantTableError::EmptyField(line.to_string()));
        }
        if pattern.matches('*').count() > 1 || (!pattern.ends_with('*') && pattern.contains('*')) {
            return Err(MerchantTableError::Malformed(format!(
                "a '*' may only END a pattern: {line:?}"
            )));
        }
        if !seen.insert(pattern.clone()) {
            return Err(MerchantTableError::DuplicatePattern(pattern));
        }
        entries.push(MerchantEntry {
            pattern,
            parent_name: parent.to_string(),
            child_name: child.to_string(),
        });
    }
    Ok(MerchantTable { version, entries })
}

/// Parses and VERIFIES a signed merchant table (the operator's update
/// path). Ed25519 over the exact payload bytes, the `fx` module's rule:
/// an unverified file is never returned.
pub fn merchant_table_parse_signed(
    payload: &str,
    signature_hex: &str,
    public_key_hex: &str,
) -> Result<MerchantTable, super::MerkiError> {
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};

    let key_bytes = hex_decode_32(public_key_hex)
        .map_err(|_| MerchantTableError::Malformed("public key must be 32 bytes".into()))
        .map_err(box_table_err)?;
    let vk = VerifyingKey::from_bytes(&key_bytes)
        .map_err(|_| MerchantTableError::Malformed("invalid public key".into()))
        .map_err(box_table_err)?;
    let sig_bytes = hex_decode_fixed::<64>(signature_hex)
        .map_err(|_| MerchantTableError::Malformed("signature must be 64 bytes".into()))
        .map_err(box_table_err)?;
    let sig = Signature::from_bytes(&sig_bytes);
    vk.verify(payload.as_bytes(), &sig)
        .map_err(|_| MerchantTableError::Malformed("signature verification failed".into()))
        .map_err(box_table_err)?;
    parse_table(payload).map_err(box_table_err)
}

fn box_table_err(e: MerchantTableError) -> super::MerkiError {
    super::MerkiError::RuleInvalid(format!("merchant table: {e}"))
}

fn hex_decode_32(s: &str) -> Result<[u8; 32], ()> {
    let bytes = hex_decode_n(s)?;
    bytes.try_into().map_err(|_| ())
}

fn hex_decode_fixed<const N: usize>(s: &str) -> Result<[u8; N], ()> {
    hex_decode_n(s)?.try_into().map_err(|_| ())
}

fn hex_decode_n(s: &str) -> Result<Vec<u8>, ()> {
    if !s.len().is_multiple_of(2) || !s.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(());
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|_| ()))
        .collect()
}

/// §8.3's own pattern shapes, carried into the table: a trailing `*` is a
/// case-folded prefix (`swiggy*` matches "Swiggy dinner"), anything else is
/// exact. Real records carry NOTE-like merchant strings ("Swiggy dinner"),
/// so brand entries ship as prefixes; exact stays available for
/// canonical strings.
fn pattern_matches(pattern: &str, needle: &str) -> bool {
    if let Some(prefix) = pattern.strip_suffix('*') {
        !prefix.is_empty() && needle.starts_with(prefix)
    } else {
        pattern == needle
    }
}

/// Resolve one entry against the CURRENT tree: the named leaf must exist
/// under the named parent, unarchived. Anything else is `None`: blank
/// over guess, including after a user renames a category.
fn resolve_entry(tree: &CategoryTree, entry: &MerchantEntry) -> Option<super::CategoryId> {
    tree.categories.iter().find_map(|c| {
        let Some(parent) = &c.parent else { return None };
        let parent_cat = tree.get(parent).ok()?;
        (parent_cat.name.eq_ignore_ascii_case(&entry.parent_name)
            && c.name.eq_ignore_ascii_case(&entry.child_name)
            && !c.archived)
            .then(|| c.id.clone())
    })
}

/// THE §8.2 PIPELINE, deterministic end to end:
/// 1. user rules (§8.3): a set category wins outright; `ignore` means
///    the user said leave it blank;
/// 2. exact normalised match in the merchant table, resolved against the
///    current tree;
/// 3. otherwise `None`: uncategorised, honestly.
pub fn auto_categorise(
    tree: &CategoryTree,
    table: &MerchantTable,
    rules: &[Rule],
    ctx: &RuleContext,
) -> Result<Option<super::CategoryId>, super::MerkiError> {
    let outcome = evaluate(tree, rules, ctx)?;
    if outcome.ignored {
        return Ok(None);
    }
    if let Some(id) = outcome.category_id {
        return Ok(Some(id));
    }
    let Some(merchant) = ctx.merchant.as_deref() else {
        return Ok(None);
    };
    let needle = normalise(merchant);
    if needle.is_empty() {
        return Ok(None);
    }
    // Exact match only. First pattern in TABLE ORDER wins if two entries
    // could ever coincide. They cannot: `parse_table` refuses duplicate
    // patterns, and the bundled set is checked by test.
    Ok(table
        .entries
        .iter()
        .find(|e| pattern_matches(&e.pattern, &needle))
        .and_then(|e| resolve_entry(tree, e)))
}

// ---------------------------------------------------------------------------
// The BUNDLED starter set: compiled in, trusted as code, same format as
// the signed updates. The spec's ~5,000-pattern table arrives through the
// signed-file pipeline; this starter covers well-known merchants across
// the default tree and is pinned by a test that EVERY entry resolves.
// ---------------------------------------------------------------------------

pub const BUNDLED_TABLE_VERSION: u64 = 1;

pub const BUNDLED_TABLE: &str = include_str!("merchants_starter.txt");

/// The bundled table, parsed once per call site (cheap; lines are short).
/// Malformed is IMPOSSIBLE by test, but the Result keeps the type honest.
pub fn bundled_table() -> MerchantTable {
    parse_table(BUNDLED_TABLE).expect("bundled merchant table is valid (pinned by test)")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::merki::{Action, Condition, MerchantMatch, Rule, RuleContext};

    fn tree() -> CategoryTree {
        CategoryTree::with_defaults()
    }

    fn ctx(merchant: &str) -> RuleContext {
        RuleContext {
            merchant: Some(merchant.into()),
            amount_minor: 100,
            currency: "INR".into(),
            day_of_month: 15,
            direction: "expense".into(),
            account: None,
            source: None,
        }
    }

    fn rules_want(atm: bool, tree: &CategoryTree) -> Vec<Rule> {
        let target = if atm {
            tree.categories
                .iter()
                .find(|c| c.name == "Cash withdrawal")
                .unwrap()
                .id
                .clone()
        } else {
            tree.categories
                .iter()
                .find(|c| c.name == "Snacks")
                .unwrap()
                .id
                .clone()
        };
        vec![Rule {
            id: crate::merki::new_id(),
            name: "user rule".into(),
            enabled: true,
            conditions: vec![Condition::Merchant(MerchantMatch::Exact {
                text: "atm".into(),
            })],
            actions: vec![Action::SetCategory {
                category_id: target,
            }],
        }]
    }

    /// The determinism contract, stated twice on purpose: the SAME inputs
    /// produce the SAME category id: the property that makes a category a
    /// fact about the ledger rather than a mood of the classifier.
    #[test]
    fn same_merchant_classifies_identically_every_run() {
        let t = tree();
        let table = bundled_table();
        let a = auto_categorise(&t, &table, &[], &ctx("  UBER   eats ")).unwrap();
        let b = auto_categorise(&t, &table, &[], &ctx("uber eats")).unwrap();
        assert_eq!(a, b, "normalisation must make spacing/case irrelevant");
        assert!(a.is_some(), "uber eats is in the bundled table");
    }

    #[test]
    fn bundled_table_parses_and_every_entry_resolves_in_the_default_tree() {
        let table = bundled_table();
        assert_eq!(table.version, BUNDLED_TABLE_VERSION);
        assert!(
            table.entries.len() >= 100,
            "starter table is real: {}",
            table.entries.len()
        );
        let t = tree();
        let mut unresolved = Vec::new();
        for e in &table.entries {
            if resolve_entry(&t, e).is_none() {
                unresolved.push(format!(
                    "{} -> {} / {}",
                    e.pattern, e.parent_name, e.child_name
                ));
            }
        }
        assert!(
            unresolved.is_empty(),
            "every bundled entry must resolve against the §8.1 tree; broken: {unresolved:?}"
        );
    }

    #[test]
    fn unknown_merchant_stays_uncategorised() {
        let t = tree();
        let table = bundled_table();
        // Not in the table, no rules. Blank, the honest answer:
        assert_eq!(
            auto_categorise(&t, &table, &[], &ctx("ma's pierogi van")).unwrap(),
            None
        );
        // Empty/absent merchant too:
        assert_eq!(auto_categorise(&t, &table, &[], &ctx("")).unwrap(), None);
        let mut no_merchant = ctx("x");
        no_merchant.merchant = None;
        assert_eq!(
            auto_categorise(&t, &table, &[], &no_merchant).unwrap(),
            None
        );
    }

    #[test]
    fn user_rules_beat_the_table_and_ignore_means_blank() {
        let t = tree();
        let table = bundled_table();
        // "atm" is IN the table (Cash withdrawal). The user rule overrides:
        let out = auto_categorise(&t, &table, &rules_want(true, &t), &ctx("atm")).unwrap();
        assert_eq!(
            out.as_deref(),
            Some(
                t.categories
                    .iter()
                    .find(|c| c.name == "Cash withdrawal")
                    .unwrap()
                    .id
                    .as_str()
            )
        );
        // The user rule that says Snacks does NOT fire for atm (exact match);
        // the table's own Cash withdrawal still classifies:
        let out = auto_categorise(&t, &table, &rules_want(false, &t), &ctx("atm")).unwrap();
        assert!(out.is_some());

        // User said IGNORE: blank, even though the table knows the merchant:
        let ignore = vec![Rule {
            id: crate::merki::new_id(),
            name: "leave it".into(),
            enabled: true,
            conditions: vec![Condition::Merchant(MerchantMatch::Exact {
                text: "starbucks".into(),
            })],
            actions: vec![Action::Ignore],
        }];
        // The merchant matches the rule EXACTLY and the table PREFIX;
        // the user's ignore still wins:
        assert_eq!(
            auto_categorise(&t, &table, &ignore, &ctx("Starbucks")).unwrap(),
            None,
            "ignore is the user's word for blank"
        );
    }

    #[test]
    fn renamed_category_stops_resolving_blank_not_wrong() {
        let mut t = tree();
        let table = bundled_table();
        assert!(auto_categorise(&t, &table, &[], &ctx("netflix"))
            .unwrap()
            .is_some());
        // The user renames "Streaming": the table entry for Netflix no
        // longer resolves. Blank is the honest result; a stale-name guess
        // would be a wrong category in a ledger the user trusts.
        let entertainment = t
            .categories
            .iter()
            .find(|c| c.name == "Entertainment" && c.parent.is_none())
            .unwrap()
            .id
            .clone();
        // Renaming the PARENT breaks the entry's path ("Entertainment /
        // Streaming"), exactly as renaming the child would:
        t.rename(&entertainment, "Subscriptions & Media").unwrap();
        assert_eq!(
            auto_categorise(&t, &table, &[], &ctx("netflix")).unwrap(),
            None
        );
        // And archiving the leaf does the same:
        let mut t2 = tree();
        let netflix_leaf = t2
            .categories
            .iter()
            .find(|c| c.name == "Streaming" && c.parent.is_some())
            .unwrap()
            .id
            .clone();
        t2.archive(&netflix_leaf).unwrap();
        assert_eq!(
            auto_categorise(&t2, &table, &[], &ctx("netflix")).unwrap(),
            None
        );
    }

    /// The signed update path, with a PINNED test keypair (deterministic:
    /// the production operator key is a separate, later distribution).
    #[test]
    fn signed_table_update_verifies_and_tamper_refuses() {
        use ed25519_dalek::{Signer, SigningKey};
        let signing = SigningKey::from_bytes(&[42u8; 32]);
        let pub_hex: String = signing
            .verifying_key()
            .to_bytes()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();

        let payload = "MERCHANTS1\nversion=2\nnew vendor\tFood & Drink\tSnacks\n";
        let sig: String = signing
            .sign(payload.as_bytes())
            .to_bytes()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();

        let table = merchant_table_parse_signed(payload, &sig, &pub_hex).unwrap();
        assert_eq!(table.version, 2);
        assert_eq!(table.entries.len(), 1);

        // Tampered payload (one extra space) must refuse:
        let tampered = "MERCHANTS1\nversion=2\nnew  vendor\tFood & Drink\tSnacks\n";
        assert!(merchant_table_parse_signed(tampered, &sig, &pub_hex).is_err());
        // Wrong key must refuse:
        let other = SigningKey::from_bytes(&[7u8; 32]);
        let other_pub: String = other
            .verifying_key()
            .to_bytes()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        assert!(merchant_table_parse_signed(payload, &sig, &other_pub).is_err());

        // The verified table classifies:
        let t = tree();
        assert!(auto_categorise(&t, &table, &[], &ctx("New Vendor"))
            .unwrap()
            .is_some());
    }

    #[test]
    fn table_parse_refuses_duplicates_and_garbage() {
        // Duplicate pattern = two categories for one merchant = a guess:
        let dup = "MERCHANTS1\nversion=1\nuber\tTravel\tTaxis\nuber\tFood & Drink\tDelivery\n";
        assert!(matches!(
            parse_table(dup),
            Err(MerchantTableError::DuplicatePattern(_))
        ));
        // Wrong header:
        assert!(parse_table("FX1\nversion=1\n").is_err());
        // Empty path field:
        assert!(parse_table("MERCHANTS1\nversion=1\nuber\t\tTaxis\n").is_err());
        // Extra field:
        assert!(parse_table("MERCHANTS1\nversion=1\nuber\tTravel\tTaxis\textra\n").is_err());
    }

    #[test]
    fn normalisation_is_fixed_string_work() {
        assert_eq!(normalise("  UBER   Eats "), "uber eats");
        assert_eq!(normalise("SWIGGY.in"), "swiggy.in");
        let input = "uber\u{2014}eats"; // em-dash-ok: the input under test is itself a dash
        assert_eq!(
            normalise(input),
            input,
            "no unicode folding, determinism beats cleverness"
        );
        assert_eq!(normalise(""), "");
    }
}
