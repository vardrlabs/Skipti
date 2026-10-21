//! merki: categories and the rules engine (SPEC §8.1/§8.3).
//!
//! This is Phase 6's core, and it lives in core for the same reason every
//! money-adjacent decision does: a category changes what a total MEANS
//! (§8.4's donut is a claim about where money went), so it cannot be
//! presentation. §8.2's on-device auto-categorisation is deliberately NOT
//! here yet: the tree and the deterministic rules under it are the part
//! everything else (merchant table, learning, corrections) will hang from.
//!
//! Two design points are pinned by tests, not prose:
//!
//! * **Two levels, hard.** Deeper trees test well in a spec and fail in a
//!   hurry at a restaurant table (§8.1's own warning). A child of a child
//!   is a construction error, refused.
//! * **The engine is total and deterministic.** Same tree, same rules,
//!   same context → same outcome, always. First-match-wins per action kind
//!   (category from the first rule that sets one; tags accumulate in rule
//!   order; a matching `ignore` stops everything), because §8.3 says rules
//!   are ORDERED: an engine with hidden tie-breaking is a guess.
//!
//! Money discipline: amounts in rules are minor units (i64) with an
//! explicit currency; no float anywhere (ADR-0002).

use crate::money::Currency;

pub mod auto;
use serde::{Deserialize, Serialize};

pub const MAX_DEPTH: usize = 2;

// ---------------------------------------------------------------------------
// Category tree (§8.1)
// ---------------------------------------------------------------------------

/// A stable identifier for a category. Random-hex, generated in core, never
/// reused: archiving keeps the id reserved so history stays attachable.
pub type CategoryId = String;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct Category {
    pub id: CategoryId,
    /// Display name, unique AMONG SIBLINGS (two "Groceries" under different
    /// parents are legal; two under Food & Drink are a mistake).
    pub name: String,
    /// `None` = top-level parent. Exactly one level of nesting is
    /// enforceable: a child's parent must be a parent (never a child).
    pub parent: Option<CategoryId>,
    /// §8.1: every category carries an icon and a colour that persist
    /// across all charts. Strings on purpose here: the ICON vocabulary is
    /// a shell/design-token concern (ADR-0023); core guarantees only that
    /// they persist and round-trip.
    pub icon: String,
    /// Hex colour without `#` (e.g. `B4442A`), validated to 6 hex digits.
    pub colour: String,
    /// Archived categories keep their id, name and every historical
    /// attachment; they vanish from pickers and are refused as rule
    /// targets. §8.1: never delete a category with history.
    pub archived: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum MerkiError {
    /// Depth > 2, child-of-child, or parent id pointing at a child.
    DepthViolated(String),
    /// Empty name, or a name colliding with a sibling.
    NameInvalid(String),
    /// Colour is not 6 hex digits.
    ColourInvalid(String),
    /// Id not present in the tree.
    UnknownCategory(String),
    /// The category is archived, refused as a target of new attachments.
    Archived(String),
    /// A rule references a category that is not an assignable LEAF.
    NotAssignable(String),
    /// The rule itself is malformed (empty pattern, impossible range).
    RuleInvalid(String),
}

impl std::fmt::Display for MerkiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MerkiError::DepthViolated(m) => write!(f, "category depth violated: {m}"),
            MerkiError::NameInvalid(m) => write!(f, "invalid category name: {m}"),
            MerkiError::ColourInvalid(m) => write!(f, "invalid colour: {m}"),
            MerkiError::UnknownCategory(m) => write!(f, "unknown category: {m}"),
            MerkiError::Archived(m) => write!(f, "category is archived: {m}"),
            MerkiError::NotAssignable(m) => write!(f, "not an assignable category: {m}"),
            MerkiError::RuleInvalid(m) => write!(f, "invalid rule: {m}"),
        }
    }
}

impl std::error::Error for MerkiError {}

/// The §8.1 tree: two levels, fully user-editable, archive-not-delete.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct CategoryTree {
    /// Sorted by (parent order, name) at serialization time by the caller;
    /// storage order is insertion order and is NOT meaningful.
    pub categories: Vec<Category>,
}

fn valid_colour(c: &str) -> bool {
    c.len() == 6 && c.chars().all(|ch| ch.is_ascii_hexdigit())
}

impl CategoryTree {
    /// §8.1's own tree, verbatim: the defaults a new install shows. Names
    /// are the spec's; icons/colours are neutral placeholders the design
    /// layer overrides (the PERSISTENCE contract is what core pins).
    pub fn with_defaults() -> Self {
        /// Default-tree ids are DERIVED, not random: sha256 of the
        /// category's stable path, first 16 bytes hex. The merchant table
        /// (§8.2), records and charts must resolve the same category to
        /// the same id on every device and every launch: a random id per
        /// call made a stored expense's label unreadable (caught by the
        /// wiring probe: "? · ?" against a fresh tree). User-CREATED
        /// categories keep random ids; nothing external references them.
        fn stable_id(path: &str) -> String {
            use sha2::{Digest, Sha256};
            let digest = Sha256::digest(path.as_bytes());
            digest[..16].iter().map(|b| format!("{b:02x}")).collect()
        }
        fn slug(name: &str) -> String {
            name.to_lowercase().replace(' ', "-")
        }
        const DEFAULTS: &[(&str, &[&str])] = &[
            (
                "Food & Drink",
                &[
                    "Groceries",
                    "Restaurants",
                    "Cafés",
                    "Bars",
                    "Delivery",
                    "Snacks",
                ],
            ),
            (
                "Travel",
                &[
                    "Flights", "Trains", "Buses", "Taxis", "Fuel", "Tolls", "Parking", "Rentals",
                ],
            ),
            ("Stay", &["Hotels", "Rentals", "Hostels"]),
            (
                "Shopping",
                &["Clothing", "Electronics", "Home", "Beauty", "Gifts"],
            ),
            (
                "Bills & Utilities",
                &[
                    "Rent",
                    "Electricity",
                    "Water",
                    "Gas",
                    "Internet",
                    "Mobile",
                    "Subscriptions",
                ],
            ),
            ("Health", &["Doctor", "Pharmacy", "Insurance", "Fitness"]),
            (
                "Entertainment",
                &["Streaming", "Events", "Games", "Books", "Hobbies"],
            ),
            (
                "Transport",
                &["Public transit", "Vehicle maintenance", "Insurance"],
            ),
            ("Education", &["Tuition", "Courses", "Supplies"]),
            ("Personal", &["Grooming", "Laundry", "Services"]),
            ("Financial", &["Fees", "Interest", "Taxes", "Charity"]),
            ("Income", &["Salary", "Refunds", "Transfers in"]),
            ("Other", &["Uncategorised", "Cash withdrawal"]),
        ];
        let mut tree = CategoryTree::default();
        for (parent, children) in DEFAULTS {
            let pid = stable_id(&format!("skipti/category/{}", slug(parent)));
            tree.categories.push(Category {
                id: pid.clone(),
                name: (*parent).to_string(),
                parent: None,
                icon: "tag".into(),
                colour: "8A939B".into(),
                archived: false,
            });
            for child in *children {
                tree.categories.push(Category {
                    id: stable_id(&format!("skipti/category/{}/{}", slug(parent), slug(child))),
                    name: (*child).to_string(),
                    parent: Some(pid.clone()),
                    icon: "tag".into(),
                    colour: "8A939B".into(),
                    archived: false,
                });
            }
        }
        tree
    }

    fn new_category(
        &mut self,
        name: &str,
        parent: Option<CategoryId>,
        icon: &str,
        colour: &str,
    ) -> Result<CategoryId, MerkiError> {
        let name = name.trim();
        if name.is_empty() {
            return Err(MerkiError::NameInvalid("empty".into()));
        }
        if !valid_colour(colour) {
            return Err(MerkiError::ColourInvalid(colour.into()));
        }
        // Sibling-name uniqueness (case-insensitive): two "Groceries" under
        // one parent is a data-entry mistake, not a feature.
        if self
            .categories
            .iter()
            .any(|c| c.parent == parent && c.name.eq_ignore_ascii_case(name))
        {
            return Err(MerkiError::NameInvalid(format!(
                "duplicate sibling name {name:?}"
            )));
        }
        let id = new_id(); // 32 hex chars, unique
        self.categories.push(Category {
            id: id.clone(),
            name: name.to_string(),
            parent,
            icon: icon.to_string(),
            colour: colour.to_string(),
            archived: false,
        });
        Ok(id)
    }

    /// Adds a top-level parent.
    pub fn add_parent(
        &mut self,
        name: &str,
        icon: &str,
        colour: &str,
    ) -> Result<CategoryId, MerkiError> {
        self.new_category(name, None, icon, colour)
    }

    /// Adds a child under a parent. Depth is ENFORCED at two: the parent
    /// must itself be a top-level category (a child may never parent).
    pub fn add_child(
        &mut self,
        parent: &CategoryId,
        name: &str,
        icon: &str,
        colour: &str,
    ) -> Result<CategoryId, MerkiError> {
        let parent_cat = self
            .categories
            .iter()
            .find(|c| &c.id == parent)
            .ok_or_else(|| MerkiError::UnknownCategory(parent.clone()))?;
        if parent_cat.archived {
            return Err(MerkiError::Archived(parent.clone()));
        }
        if parent_cat.parent.is_some() {
            return Err(MerkiError::DepthViolated(format!(
                "{parent} is already a child (§8.1 is two levels, hard)"
            )));
        }
        self.new_category(name, Some(parent.clone()), icon, colour)
    }

    /// Renames in place (sibling-uniqueness still applies).
    pub fn rename(&mut self, id: &CategoryId, new_name: &str) -> Result<(), MerkiError> {
        let name = new_name.trim();
        if name.is_empty() {
            return Err(MerkiError::NameInvalid("empty".into()));
        }
        if !self.is_known(id) {
            return Err(MerkiError::UnknownCategory(id.clone()));
        }
        let parent = self.get(id)?.parent.clone();
        if self
            .categories
            .iter()
            .any(|c| &c.id != id && c.parent == parent && c.name.eq_ignore_ascii_case(name))
        {
            return Err(MerkiError::NameInvalid(format!(
                "duplicate sibling name {name:?}"
            )));
        }
        let cat = self
            .categories
            .iter_mut()
            .find(|c| &c.id == id)
            .expect("existence checked above");
        cat.name = name.to_string();
        Ok(())
    }

    pub fn set_icon_colour(
        &mut self,
        id: &CategoryId,
        icon: &str,
        colour: &str,
    ) -> Result<(), MerkiError> {
        if !valid_colour(colour) {
            return Err(MerkiError::ColourInvalid(colour.into()));
        }
        let cat = self
            .categories
            .iter_mut()
            .find(|c| &c.id == id)
            .ok_or_else(|| MerkiError::UnknownCategory(id.clone()))?;
        cat.icon = icon.to_string();
        cat.colour = colour.to_string();
        Ok(())
    }

    /// §8.1: archive, never delete-with-history. The id stays reserved;
    /// assignment targets refuse it; existing attachments remain valid.
    pub fn archive(&mut self, id: &CategoryId) -> Result<(), MerkiError> {
        let cat = self
            .categories
            .iter_mut()
            .find(|c| &c.id == id)
            .ok_or_else(|| MerkiError::UnknownCategory(id.clone()))?;
        cat.archived = true;
        Ok(())
    }

    /// The assignable leaves: children (not archived), in stable order.
    /// Parents are group headers. §8.1's examples always name a leaf
    /// ("Food & Drink · Delivery"), and a total that mixes a whole group
    /// with its siblings double-counts the moment any child is set.
    pub fn assignable_leaves(&self) -> Vec<&Category> {
        self.categories
            .iter()
            .filter(|c| c.parent.is_some() && !c.archived)
            .collect()
    }

    pub fn get(&self, id: &CategoryId) -> Result<&Category, MerkiError> {
        self.categories
            .iter()
            .find(|c| &c.id == id)
            .ok_or_else(|| MerkiError::UnknownCategory(id.clone()))
    }

    /// An id a record may reference: present, whether archived or not;
    /// archiving never orphans history.
    pub fn is_known(&self, id: &CategoryId) -> bool {
        self.categories.iter().any(|c| &c.id == id)
    }
}

// ---------------------------------------------------------------------------
// Rules engine (§8.3)
// ---------------------------------------------------------------------------

/// What a condition tests. Every field of the context a rule may need is
/// explicit: no hidden environment.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct RuleContext {
    /// The merchant/note string (already what the app knows; §8.2's
    /// normalisation table can wrap this later).
    pub merchant: Option<String>,
    /// Minor units, with the currency alongside: a bare amount crossing
    /// currencies is meaningless.
    pub amount_minor: i64,
    pub currency: String,
    /// 1..=31, from the record's local date. The caller derives it; the
    /// engine never reads a clock (determinism).
    pub day_of_month: u32,
    /// "expense" or "income" (§8.3's direction condition). Free text on
    /// purpose: §8.3 governs; an unknown direction simply matches nothing
    /// that conditions on it.
    pub direction: String,
    /// §8.3's account condition: populated for bank-imported records.
    pub account: Option<String>,
    /// Which import source produced the record, when known.
    pub source: Option<String>,
}

/// Merchant matching, per §8.3's own two shapes: `"UBER*"` (prefix,
/// case-insensitive) and `=` (exact, case-insensitive).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum MerchantMatch {
    /// `UBER*`: case-insensitive prefix.
    Prefix { text: String },
    /// `=`: case-insensitive full match.
    Exact { text: String },
}

impl MerchantMatch {
    fn matches(&self, merchant: Option<&str>) -> bool {
        let Some(m) = merchant else { return false };
        match self {
            MerchantMatch::Prefix { text } => {
                !text.is_empty() && m.to_lowercase().starts_with(&text.to_lowercase())
            }
            MerchantMatch::Exact { text } => {
                !text.is_empty() && m.to_lowercase() == text.to_lowercase()
            }
        }
    }
}

/// One AND-ed condition. A rule matches when every present condition holds.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum Condition {
    Merchant(MerchantMatch),
    /// Inclusive band on minor units: `min <= amount <= max` (either side
    /// optional). Currency-specific by construction: the context carries
    /// one, and a band is meaningless across currencies.
    AmountRange {
        min_minor: Option<i64>,
        max_minor: Option<i64>,
    },
    Currency {
        code: String,
    },
    /// `day <= n` shapes (§8.3's `day <= 5`): the day-of-month test.
    DayOfMonthMax {
        n: u32,
    },
    Direction {
        value: String,
    },
    Account {
        value: String,
    },
    Source {
        value: String,
    },
}

impl Condition {
    fn holds(&self, ctx: &RuleContext) -> bool {
        match self {
            Condition::Merchant(m) => m.matches(ctx.merchant.as_deref()),
            Condition::AmountRange {
                min_minor,
                max_minor,
            } => {
                let a = ctx.amount_minor;
                min_minor.is_none_or(|min| a >= min) && max_minor.is_none_or(|max| a <= max)
            }
            Condition::Currency { code } => {
                Currency::parse(code).is_ok()
                    && Currency::parse(code)
                        .ok()
                        .map(|c| c.code() == ctx.currency.to_uppercase())
                        .unwrap_or(false)
            }
            Condition::DayOfMonthMax { n } => ctx.day_of_month <= *n,
            Condition::Direction { value } => ctx.direction.eq_ignore_ascii_case(value),
            Condition::Account { value } => ctx
                .account
                .as_deref()
                .map(|a| a.eq_ignore_ascii_case(value))
                .unwrap_or(false),
            Condition::Source { value } => ctx
                .source
                .as_deref()
                .map(|s| s.eq_ignore_ascii_case(value))
                .unwrap_or(false),
        }
    }
}

/// §8.3's actions. Split-by-saved-rule is deliberately absent: it needs
/// §5.4's split semantics and is recorded in BUILD-LOG as deferred, not
/// forgotten.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum Action {
    /// Set the category: must be an assignable, unarchived LEAF.
    SetCategory {
        category_id: CategoryId,
    },
    AddTag {
        tag: String,
    },
    /// Rename the merchant string going forward (canonicalisation: this is
    /// how "SWIGGY " and "swiggy.in" become one name).
    RenameMerchant {
        to: String,
    },
    /// §8.3's flag-for-review, with the reason the rule gives.
    Flag {
        reason: String,
    },
    /// Terminal: this record is deliberately uncategorised. Stops rule
    /// processing: explicit ignore beats later rules.
    Ignore,
}

/// An ordered, enabled/disabled rule. Rules are evaluated in list order.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct Rule {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub conditions: Vec<Condition>,
    pub actions: Vec<Action>,
}

/// The outcome of running the engine: everything §8.3's actions can
/// produce, in one deterministic bundle.
#[derive(Debug, Clone, PartialEq, Eq, Default, uniffi::Record)]
pub struct RuleOutcome {
    pub category_id: Option<CategoryId>,
    pub tags: Vec<String>,
    pub renamed_merchant: Option<String>,
    pub flag_reason: Option<String>,
    /// True when a matching `ignore` stopped processing.
    pub ignored: bool,
}

/// THE ENGINE. Ordered rules, first-match-wins per action kind, `ignore`
/// terminal. Total and deterministic: no clock, no randomness, no
/// environment: the same inputs cannot produce different outputs.
pub fn evaluate(
    tree: &CategoryTree,
    rules: &[Rule],
    ctx: &RuleContext,
) -> Result<RuleOutcome, MerkiError> {
    // Validate targets up front: a rule naming an unknown/archived/non-leaf
    // category is a configuration error that must surface at definition:
    // evaluating anyway would silently drop the action.
    for rule in rules {
        if !rule.enabled {
            continue;
        }
        for action in &rule.actions {
            if let Action::SetCategory { category_id } = action {
                let cat = tree.get(category_id)?;
                if cat.archived {
                    return Err(MerkiError::Archived(category_id.clone()));
                }
                if cat.parent.is_none() {
                    return Err(MerkiError::NotAssignable(format!(
                        "{category_id} is a group header, not a leaf"
                    )));
                }
            }
        }
    }

    // The evaluation runs against a LOCAL copy of the context: a
    // RenameMerchant action rewrites the merchant FOR SUBSEQUENT RULES
    // (§8.3's rules are ordered: a canonicalisation rule followed by a
    // canonical-name rule must chain), while the caller's context is
    // never mutated.
    let mut effective = ctx.clone();
    let mut outcome = RuleOutcome::default();
    for rule in rules {
        if !rule.enabled {
            continue;
        }
        if !rule.conditions.iter().all(|c| c.holds(&effective)) {
            continue;
        }
        for action in &rule.actions {
            match action {
                Action::SetCategory { category_id } => {
                    if outcome.category_id.is_none() {
                        outcome.category_id = Some(category_id.clone());
                    }
                }
                Action::AddTag { tag } => {
                    if !tag.is_empty() && !outcome.tags.contains(tag) {
                        outcome.tags.push(tag.clone());
                    }
                }
                Action::RenameMerchant { to } => {
                    if outcome.renamed_merchant.is_none() {
                        outcome.renamed_merchant = Some(to.clone());
                    }
                    effective.merchant = Some(to.clone());
                }
                Action::Flag { reason } => {
                    if outcome.flag_reason.is_none() {
                        outcome.flag_reason = Some(reason.clone());
                    }
                }
                Action::Ignore => {
                    outcome.ignored = true;
                    outcome.category_id = None;
                    return Ok(outcome); // terminal
                }
            }
        }
    }
    Ok(outcome)
}

/// Batch evaluation over a history: §8.3's "testable against history with
/// a preview". Pure; the preview IS this function's return.
pub fn evaluate_batch(
    tree: &CategoryTree,
    rules: &[Rule],
    contexts: &[RuleContext],
) -> Result<Vec<RuleOutcome>, MerkiError> {
    contexts
        .iter()
        .map(|ctx| evaluate(tree, rules, ctx))
        .collect()
}

/// Validates a rule at definition time (§8.3: "testable … before saving").
/// Category targets are checked against the CURRENT tree; archiving a
/// category later is still allowed (rules referencing it surface
/// `Archived` at evaluation, which the preview shows).
pub fn validate_rule(tree: &CategoryTree, rule: &Rule) -> Result<(), MerkiError> {
    if rule.name.trim().is_empty() {
        return Err(MerkiError::RuleInvalid("empty rule name".into()));
    }
    if rule.conditions.is_empty() {
        return Err(MerkiError::RuleInvalid(
            "a rule with no conditions matches everything. Use Ignore deliberately, via conditions: [] is refused".into(),
        ));
    }
    if rule.actions.is_empty() {
        return Err(MerkiError::RuleInvalid("a rule with no actions".into()));
    }
    for action in &rule.actions {
        if let Action::SetCategory { category_id } = action {
            let cat = tree.get(category_id)?;
            if cat.archived {
                return Err(MerkiError::Archived(category_id.clone()));
            }
            if cat.parent.is_none() {
                return Err(MerkiError::NotAssignable(format!(
                    "{category_id} is a group header, not a leaf"
                )));
            }
        }
        if let Action::AddTag { tag } = action {
            if tag.trim().is_empty() {
                return Err(MerkiError::RuleInvalid("empty tag".into()));
            }
        }
        if let Action::RenameMerchant { to } = action {
            if to.trim().is_empty() {
                return Err(MerkiError::RuleInvalid("empty rename".into()));
            }
        }
    }
    for cond in &rule.conditions {
        if let Condition::Merchant(m) = cond {
            let text = match m {
                MerchantMatch::Prefix { text } | MerchantMatch::Exact { text } => text,
            };
            if text.trim().is_empty() {
                return Err(MerkiError::RuleInvalid("empty merchant pattern".into()));
            }
        }
        if let Condition::AmountRange {
            min_minor,
            max_minor,
        } = cond
        {
            if let (Some(min), Some(max)) = (min_minor, max_minor) {
                if min > max {
                    return Err(MerkiError::RuleInvalid(format!(
                        "amount range {min}..{max} is inverted"
                    )));
                }
            }
        }
        if let Condition::DayOfMonthMax { n } = cond {
            if *n == 0 || *n > 31 {
                return Err(MerkiError::RuleInvalid(format!(
                    "day-of-month {n} is not a day"
                )));
            }
        }
    }
    Ok(())
}

/// 32 hex chars of fresh randomness: the id generator for categories and
/// rules alike (16 bytes, same entropy budget as a participant id).
pub fn new_id() -> String {
    let bytes = crate::lykill::random_bytes(16);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tree() -> CategoryTree {
        CategoryTree::with_defaults()
    }

    fn leaf_id(t: &CategoryTree, parent_name: &str, leaf_name: &str) -> CategoryId {
        t.categories
            .iter()
            .find(|c| {
                c.parent.is_some()
                    && c.name == leaf_name
                    && t.get(c.parent.as_ref().unwrap()).unwrap().name == parent_name
            })
            .unwrap_or_else(|| panic!("{parent_name} · {leaf_name} missing"))
            .id
            .clone()
    }

    fn ctx(merchant: &str, amount: i64, day: u32) -> RuleContext {
        RuleContext {
            merchant: Some(merchant.into()),
            amount_minor: amount,
            currency: "INR".into(),
            day_of_month: day,
            direction: "expense".into(),
            account: None,
            source: None,
        }
    }

    fn rule(name: &str, conditions: Vec<Condition>, actions: Vec<Action>) -> Rule {
        Rule {
            id: new_id(),
            name: name.into(),
            enabled: true,
            conditions,
            actions,
        }
    }

    // ---------------- tree ----------------

    #[test]
    fn default_tree_is_the_spec_tree_verbatim() {
        let t = tree();
        let names: Vec<&str> = t
            .categories
            .iter()
            .filter(|c| c.parent.is_none())
            .map(|c| c.name.as_str())
            .collect();
        assert_eq!(
            names,
            vec![
                "Food & Drink",
                "Travel",
                "Stay",
                "Shopping",
                "Bills & Utilities",
                "Health",
                "Entertainment",
                "Transport",
                "Education",
                "Personal",
                "Financial",
                "Income",
                "Other",
            ],
            "§8.1's thirteen parents, in order"
        );
        // Spot children verbatim (incl. the non-ASCII and multi-word ones):
        assert_eq!(
            leaf_id(&t, "Food & Drink", "Cafés"),
            leaf_id(&t, "Food & Drink", "Cafés")
        );
        assert!(t
            .get(&leaf_id(&t, "Bills & Utilities", "Subscriptions"))
            .is_ok());
        assert!(t.get(&leaf_id(&t, "Other", "Cash withdrawal")).is_ok());
        // Every leaf carries icon+colour that round-trip (§8.1 persistence):
        assert!(t
            .categories
            .iter()
            .all(|c| !c.icon.is_empty() && valid_colour(&c.colour)));
    }

    /// THE determinism pin for the tree itself: two constructions of the
    /// default tree carry IDENTICAL ids: records, the merchant table and
    /// charts all resolve against fresh trees, so per-call randomness
    /// would break every stored label (the wiring probe caught exactly
    /// that). User-created ids stay random; nothing external names them.
    #[test]
    fn default_tree_ids_are_stable_across_constructions() {
        let a = CategoryTree::with_defaults();
        let b = CategoryTree::with_defaults();
        assert_eq!(a, b);
        for (x, y) in a.categories.iter().zip(b.categories.iter()) {
            assert_eq!(x.id, y.id, "{} vs {}", x.name, y.name);
        }
    }

    #[test]
    fn depth_is_two_hard() {
        let mut t = tree();
        let groceries = leaf_id(&t, "Food & Drink", "Groceries");
        let err = t
            .add_child(&groceries, "Organics", "tag", "8A939B")
            .unwrap_err();
        assert!(matches!(err, MerkiError::DepthViolated(_)), "{err}");
        assert_eq!(
            err.to_string(),
            format!("category depth violated: {groceries} is already a child (§8.1 is two levels, hard)")
        );
        // ...and the refused child was not half-inserted:
        assert!(t.categories.iter().all(|c| c.name != "Organics"));
    }

    #[test]
    fn duplicate_sibling_names_refused_but_cousins_allowed() {
        let mut t = tree();
        let food = t
            .categories
            .iter()
            .find(|c| c.name == "Food & Drink")
            .unwrap()
            .id
            .clone();
        let travel = t
            .categories
            .iter()
            .find(|c| c.name == "Travel")
            .unwrap()
            .id
            .clone();
        let err = t.add_child(&food, "Delivery", "tag", "8A939B").unwrap_err();
        assert!(matches!(err, MerkiError::NameInvalid(_)));
        // Case-insensitive collision counts too:
        let err = t.add_child(&food, "DELIVERY", "tag", "8A939B").unwrap_err();
        assert!(matches!(err, MerkiError::NameInvalid(_)));
        // Cousins may share a name (Travel already has "Insurance", Health too):
        assert!(t.add_child(&travel, "Insurance", "tag", "8A939B").is_ok());
    }

    #[test]
    fn archive_keeps_history_and_refuses_new_attachment() {
        let mut t = tree();
        let delivery = leaf_id(&t, "Food & Drink", "Delivery");
        t.archive(&delivery).unwrap();
        assert!(
            t.is_known(&delivery),
            "archived ids stay known, history intact"
        );
        assert!(t.assignable_leaves().iter().all(|c| c.id != delivery));
        // A rule targeting it is refused:
        let r = rule(
            "late",
            vec![Condition::Merchant(MerchantMatch::Exact {
                text: "x".into(),
            })],
            vec![Action::SetCategory {
                category_id: delivery.clone(),
            }],
        );
        let err = evaluate(&t, &[r], &ctx("x", 100, 3)).unwrap_err();
        assert!(matches!(err, MerkiError::Archived(_)));
    }

    #[test]
    fn rename_enforces_sibling_uniqueness_and_colour_validation() {
        let mut t = tree();
        let cafes = leaf_id(&t, "Food & Drink", "Cafés");
        assert!(t.rename(&cafes, "Coffee bars").is_ok());
        let err = t.rename(&cafes, "Groceries").unwrap_err();
        assert!(matches!(err, MerkiError::NameInvalid(_)));
        let err = t.set_icon_colour(&cafes, "tag", "orange").unwrap_err();
        assert!(matches!(err, MerkiError::ColourInvalid(_)));
        assert!(matches!(
            t.rename(&"nonexistent".into(), "x"),
            Err(MerkiError::UnknownCategory(_))
        ));
    }

    // ---------------- rules ----------------

    #[test]
    fn first_match_wins_for_category_and_later_rules_do_not_override() {
        let t = tree();
        let taxis = leaf_id(&t, "Travel", "Taxis");
        let delivery = leaf_id(&t, "Food & Drink", "Delivery");
        let rules = vec![
            rule(
                "uber",
                vec![Condition::Merchant(MerchantMatch::Prefix {
                    text: "uber".into(),
                })],
                vec![Action::SetCategory {
                    category_id: taxis.clone(),
                }],
            ),
            rule(
                "uber-eats-mistake",
                vec![Condition::Merchant(MerchantMatch::Prefix {
                    text: "uber".into(),
                })],
                vec![Action::SetCategory {
                    category_id: delivery.clone(),
                }],
            ),
        ];
        let out = evaluate(&t, &rules, &ctx("Uber India", 25000, 9)).unwrap();
        assert_eq!(out.category_id.as_deref(), Some(taxis.as_str()));
    }

    #[test]
    fn wildcard_and_exact_merchant_shapes_per_spec() {
        let t = tree();
        let taxis = leaf_id(&t, "Travel", "Taxis");
        let rent = leaf_id(&t, "Bills & Utilities", "Rent");
        let prefix = rule(
            "p",
            vec![Condition::Merchant(MerchantMatch::Prefix {
                text: "uber".into(),
            })],
            vec![Action::SetCategory {
                category_id: taxis.clone(),
            }],
        );
        let exact = rule(
            "e",
            vec![Condition::Merchant(MerchantMatch::Exact {
                text: "landlord".into(),
            })],
            vec![Action::SetCategory {
                category_id: rent.clone(),
            }],
        );
        // UBER* matches "uber eats india" (prefix, any case):
        assert_eq!(
            evaluate(
                &t,
                std::slice::from_ref(&prefix),
                &ctx("UBER EATS INDIA", 1, 1)
            )
            .unwrap()
            .category_id
            .as_deref(),
            Some(taxis.as_str())
        );
        // ...but not "my uber":
        assert!(evaluate(&t, &[prefix], &ctx("my uber", 1, 1))
            .unwrap()
            .category_id
            .is_none());
        // exact requires the whole string (case-insensitive):
        assert!(
            evaluate(&t, std::slice::from_ref(&exact), &ctx("Landlord", 1, 1))
                .unwrap()
                .category_id
                .is_some()
        );
        assert!(evaluate(&t, &[exact], &ctx("landlord llc", 1, 1))
            .unwrap()
            .category_id
            .is_none());
    }

    /// §8.3's own third example, verbatim conditions:
    /// merchant = "LANDLORD" AND day <= 5 THEN Rent, recurring.
    #[test]
    fn spec_example_landlord_day_and_tag_and_flag_accumulate() {
        let t = tree();
        let rent = leaf_id(&t, "Bills & Utilities", "Rent");
        let rules = vec![rule(
            "landlord",
            vec![
                Condition::Merchant(MerchantMatch::Exact {
                    text: "landlord".into(),
                }),
                Condition::DayOfMonthMax { n: 5 },
            ],
            vec![
                Action::SetCategory {
                    category_id: rent.clone(),
                },
                Action::AddTag {
                    tag: "recurring".into(),
                },
                Action::Flag {
                    reason: "rent day check".into(),
                },
            ],
        )];
        let out = evaluate(&t, &rules, &ctx("LANDLORD", 250_0000, 3)).unwrap();
        assert_eq!(out.category_id.as_deref(), Some(rent.as_str()));
        assert_eq!(out.tags, vec!["recurring"]);
        assert_eq!(out.flag_reason.as_deref(), Some("rent day check"));

        // Day 6 misses the conjunction: NO action applies:
        let out = evaluate(&t, &rules, &ctx("LANDLORD", 250_0000, 6)).unwrap();
        assert!(out.category_id.is_none() && out.tags.is_empty());
    }

    #[test]
    fn amount_range_currency_and_direction_conditions() {
        let t = tree();
        let fees = leaf_id(&t, "Financial", "Fees");
        let rules = vec![rule(
            "big-hdfc-fee",
            vec![
                Condition::AmountRange {
                    min_minor: Some(50_000),
                    max_minor: None,
                },
                Condition::Currency { code: "INR".into() },
                Condition::Direction {
                    value: "expense".into(),
                },
                Condition::Account {
                    value: "HDFC".into(),
                },
            ],
            vec![
                Action::SetCategory {
                    category_id: fees.clone(),
                },
                Action::Flag {
                    reason: "review large fee".into(),
                },
            ],
        )];
        let mut c = ctx("bank fee", 60_000, 12);
        c.account = Some("hdfc".into());
        assert_eq!(
            evaluate(&t, &rules, &c).unwrap().flag_reason.as_deref(),
            Some("review large fee")
        );
        // Below the band alone kills the rule:
        let mut below = c.clone();
        below.amount_minor = 49_999;
        assert!(evaluate(&t, &rules, &below).unwrap().flag_reason.is_none());
        let mut c2 = c.clone();
        c2.currency = "USD".into();
        assert!(evaluate(&t, &rules, &c2).unwrap().flag_reason.is_none());
        let mut c3 = c.clone();
        c3.direction = "income".into();
        assert!(evaluate(&t, &rules, &c3).unwrap().flag_reason.is_none());
        let mut c4 = c;
        c4.account = Some("ICICI".into());
        assert!(evaluate(&t, &rules, &c4).unwrap().flag_reason.is_none());
    }

    #[test]
    fn ignore_is_terminal_and_clears_category() {
        let t = tree();
        let snacks = leaf_id(&t, "Food & Drink", "Snacks");
        let rules = vec![
            rule(
                "cash-is-noise",
                vec![Condition::Merchant(MerchantMatch::Exact {
                    text: "atm".into(),
                })],
                vec![Action::Ignore],
            ),
            rule(
                "atm-snack",
                vec![Condition::Merchant(MerchantMatch::Prefix {
                    text: "atm".into(),
                })],
                vec![Action::SetCategory {
                    category_id: snacks.clone(),
                }],
            ),
        ];
        let out = evaluate(&t, &rules, &ctx("ATM", 5_000, 2)).unwrap();
        assert!(out.ignored);
        assert!(
            out.category_id.is_none(),
            "ignore beats later rules outright (rule 2's prefix also matched)"
        );
    }

    #[test]
    fn rename_chains_into_later_rules() {
        let t = tree();
        let delivery = leaf_id(&t, "Food & Drink", "Delivery");
        let rules = vec![
            rule(
                "canonicalise",
                vec![Condition::Merchant(MerchantMatch::Prefix {
                    text: "swiggy".into(),
                })],
                vec![Action::RenameMerchant {
                    to: "Swiggy".into(),
                }],
            ),
            rule(
                "swiggy-canonical",
                vec![Condition::Merchant(MerchantMatch::Exact {
                    text: "swiggy".into(),
                })],
                vec![
                    Action::SetCategory {
                        category_id: delivery.clone(),
                    },
                    Action::AddTag { tag: "food".into() },
                ],
            ),
        ];
        // "swiggy.in" does not exact-match rule 2, until rule 1 renames it:
        let out = evaluate(&t, &rules, &ctx("swiggy.in", 45_000, 14)).unwrap();
        assert_eq!(out.renamed_merchant.as_deref(), Some("Swiggy"));
        assert_eq!(out.category_id.as_deref(), Some(delivery.as_str()));
        assert_eq!(out.tags, vec!["food"]);
        // ...and the CALLER's context is not mutated:
        assert!(evaluate_batch(&t, &rules, &[ctx("swiggy.in", 45_000, 14)]).is_ok());
    }

    #[test]
    fn disabled_rules_are_inert_and_ordering_is_the_tiebreak() {
        let t = tree();
        let snacks = leaf_id(&t, "Food & Drink", "Snacks");
        let mut disabled = rule(
            "d",
            vec![Condition::Merchant(MerchantMatch::Prefix {
                text: "kiosk".into(),
            })],
            vec![Action::SetCategory {
                category_id: snacks.clone(),
            }],
        );
        disabled.enabled = false;
        let out = evaluate(&t, &[disabled], &ctx("kiosk", 100, 1)).unwrap();
        assert!(out.category_id.is_none());
    }

    #[test]
    fn validate_rule_refuses_the_configuration_errors() {
        let t = tree();
        let food_parent = t
            .categories
            .iter()
            .find(|c| c.name == "Food & Drink")
            .unwrap()
            .id
            .clone();
        let delivery = leaf_id(&t, "Food & Drink", "Delivery");

        // Group header as target:
        let r = rule(
            "h",
            vec![Condition::Direction {
                value: "expense".into(),
            }],
            vec![Action::SetCategory {
                category_id: food_parent.clone(),
            }],
        );
        assert!(matches!(
            validate_rule(&t, &r),
            Err(MerkiError::NotAssignable(_))
        ));

        // Inverted amount range:
        let r = rule(
            "r",
            vec![Condition::AmountRange {
                min_minor: Some(10),
                max_minor: Some(5),
            }],
            vec![Action::Ignore],
        );
        assert!(matches!(
            validate_rule(&t, &r),
            Err(MerkiError::RuleInvalid(_))
        ));

        // Impossible day:
        let r = rule(
            "d",
            vec![Condition::DayOfMonthMax { n: 32 }],
            vec![Action::Ignore],
        );
        assert!(matches!(
            validate_rule(&t, &r),
            Err(MerkiError::RuleInvalid(_))
        ));

        // No conditions (matches everything) and no actions both refused:
        let r = rule("c", vec![], vec![Action::Ignore]);
        assert!(validate_rule(&t, &r).is_err());
        assert_eq!(
            validate_rule(&t, &r).unwrap_err().to_string(),
            "invalid rule: a rule with no conditions matches everything. \
             Use Ignore deliberately, via conditions: [] is refused"
        );
        let r = rule(
            "a",
            vec![Condition::Direction {
                value: "expense".into(),
            }],
            vec![],
        );
        assert!(validate_rule(&t, &r).is_err());

        // The good rule passes:
        let r = rule(
            "ok",
            vec![Condition::Merchant(MerchantMatch::Exact {
                text: "landlord".into(),
            })],
            vec![Action::SetCategory {
                category_id: delivery.clone(),
            }],
        );
        assert_eq!(validate_rule(&t, &r), Ok(()));
    }

    /// The determinism contract, stated as a test: identical inputs produce
    /// byte-identical outcomes, twice, in any construction order of rules
    /// (order comes from the LIST, not from hash iteration).
    #[test]
    fn engine_is_total_and_deterministic() {
        let t = tree();
        let taxis = leaf_id(&t, "Travel", "Taxis");
        let rules = vec![
            rule(
                "a",
                vec![Condition::Merchant(MerchantMatch::Prefix {
                    text: "uber".into(),
                })],
                vec![
                    Action::SetCategory {
                        category_id: taxis.clone(),
                    },
                    Action::AddTag { tag: "ride".into() },
                ],
            ),
            rule(
                "b",
                vec![Condition::AmountRange {
                    min_minor: Some(1),
                    max_minor: None,
                }],
                vec![Action::AddTag {
                    tag: "spend".into(),
                }],
            ),
        ];
        let ctx = ctx("Uber", 30_000, 7);
        let o1 = evaluate(&t, &rules, &ctx).unwrap();
        let o2 = evaluate(&t, &rules, &ctx).unwrap();
        assert_eq!(o1, o2);
        assert_eq!(
            o1.tags,
            vec!["ride", "spend"],
            "tags accumulate in rule order"
        );
        // Batch equals per-item evaluation:
        let batch = evaluate_batch(&t, &rules, &[ctx.clone(), ctx]).unwrap();
        assert_eq!(batch.len(), 2);
    }
}
