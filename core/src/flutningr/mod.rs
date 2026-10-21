//! flutningr, the portage layer: ledger state moving in and out of the app.
//!
//! Three §9 jobs live here, all over one canonical [`LedgerSnapshot`]:
//!
//! * JSON backup export/import (§9.2: "complete, lossless, schema-versioned:
//!   the format that makes the guarantee real"). The envelope carries its
//!   format tag and schema version as the FIRST fields of the document, the
//!   file-format analogue of skrin's PRAGMA user_version: a reader refuses an
//!   unknown version before it interprets a single record.
//! * Flat CSV export (§9.2: "one flat file, RFC 4180, UTF-8 with BOM").
//!   Deliberately a VIEW, not the backup: settlements and shares render as
//!   plain rows; losslessness is JSON's job and this module says so where it
//!   matters.
//! * Foreign-sheet import (§9.5) as ONE shared dry-run engine ([`plan`]:
//!   proposals / row rejections with spreadsheet-line reasons / amount+date+
//!   merchant duplicate flags / all-or-nothing apply) configured by PER-APP
//!   PROFILES (`splitwise`: Splitwise's group sheet by column names; `budgetbakers`:
//!   Wallet by BudgetBakers' statement by fixed column indices). The profile,
//!   not the parser, is the unit §9.5's list grows along: adding another app
//!   must touch none of the engine.
//!
//! Money discipline is inherited, not reimplemented: every decimal string
//! that enters from a foreign format goes through the SAME parser the add-
//! expense flow uses (`ffi::money_parse_minor`, D-51/D-49), which refuses
//! over-precise input instead of rounding it. This module adds no second
//! parser and no float anywhere (ADR-0002).

pub mod budgetbakers;
pub mod csvio;
pub mod jsonio;
pub mod ledger_csv;
pub mod plan;
pub mod splitwise;

// The §9 entry points, at the module root where the shells find them:
pub use budgetbakers::{plan_import as plan_budgetbakers_import, BudgetBakersOptions};
pub use jsonio::{export_json, import_json};
pub use ledger_csv::export_flat_csv;
pub use plan::{
    apply_plan, CurrencyTotal, DuplicateAgainst, DuplicateHit, ImportPlan, MemberBinding,
    RowRejection,
};
pub use splitwise::{plan_import as plan_splitwise_import, SplitwiseOptions};

/// The per-app configuration of the shared engine: one variant per foreign
/// format §9.5 lists and the tree pins from public evidence. Adding an app
/// means adding a variant + its profile module: the engine, duplicate rule,
/// totals and all-or-nothing apply are untouched, which is the point.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ImportProfile {
    SplitwiseGroup { options: SplitwiseOptions },
    BudgetBakersWallet { options: BudgetBakersOptions },
}

/// THE DRY RUN over whichever sheet the profile names, sharing everything
/// below the layout layer.
pub fn plan_import(
    csv_text: &str,
    profile: &ImportProfile,
    existing: &LedgerSnapshot,
) -> Result<ImportPlan, PortError> {
    match profile {
        ImportProfile::SplitwiseGroup { options } => {
            plan_splitwise_import(csv_text, options, existing)
        }
        ImportProfile::BudgetBakersWallet { options } => {
            plan_budgetbakers_import(csv_text, options, existing)
        }
    }
}

use crate::money::Currency;
use serde::{Deserialize, Serialize};

/// The envelope's self-description, checked BEFORE any record is interpreted
/// (see [`jsonio`]). Bumped whenever a record shape changes; readers refuse
/// anything NEWER rather than guess (D-39's newer-vault rule, file edition)
/// and CLIMB anything older through the explicit per-version upgrades in
/// [`jsonio`]: a backup that stops importing after an app update is not a
/// backup (§9.2).
pub const SCHEMA_VERSION: u64 = 4;
/// The oldest generation this build still imports (D-90): files from here
/// through `SCHEMA_VERSION` are UPGRADED on read one explicit step at a
/// time ([`jsonio::upgrade_step`]); everything below (including
/// never-published 0, so no migration from a version nobody ever wrote)
/// and everything above refuses as `UnsupportedSchemaVersion`. Raising this
/// value retires old backups for good and needs its own recorded decision.
pub const SCHEMA_OLDEST_VERSION: u64 = 1;
/// First key in every exported document. A file that does not open with this
/// tag is not ours and is refused on sight.
pub const FORMAT_TAG: &str = "skipti-ledger";

// ---------------------------------------------------------------------------
// Snapshot types: the complete ledger state, ported
// ---------------------------------------------------------------------------

/// One group, exactly what the app persists for it (D-51): settlement
/// currency fixed at creation, members by participant id.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct PortGroup {
    pub id: String,
    pub name: String,
    pub settlement_currency: String,
    pub members: Vec<String>,
}

/// The frozen FX conversion (§5.2), same shape as the FFI's ConversionInput:
/// exact rational rate, no floats, rate timestamp frozen into the record.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct PortConversion {
    pub converted_minor: i64,
    pub converted_currency: String,
    pub rate_num: i64,
    pub rate_den: i64,
    pub rate_timestamp_ms: u64,
}

impl From<&crate::ffi::ConversionInput> for PortConversion {
    fn from(c: &crate::ffi::ConversionInput) -> Self {
        PortConversion {
            converted_minor: c.converted_minor,
            converted_currency: c.converted_currency.clone(),
            rate_num: c.rate_num,
            rate_den: c.rate_den,
            rate_timestamp_ms: c.rate_timestamp_ms,
        }
    }
}

impl From<&PortConversion> for crate::ffi::ConversionInput {
    fn from(c: &PortConversion) -> Self {
        crate::ffi::ConversionInput {
            converted_minor: c.converted_minor,
            converted_currency: c.converted_currency.clone(),
            rate_num: c.rate_num,
            rate_den: c.rate_den,
            rate_timestamp_ms: c.rate_timestamp_ms,
        }
    }
}

/// One participant's slice of an expense, signed in principle (a negative
/// share is representable) but always summing to the expense total.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct ExpenseShare {
    pub id: String,
    pub amount_minor: i64,
}

/// One expense, exactly what the app persists for it: payer, integer minor
/// units, currency, optional group, optional frozen conversion, the full
/// share decomposition, plus the §5.7 provenance pair (note + timestamp)
/// that makes an export human-auditable.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct PortExpense {
    pub payer: String,
    pub paid_minor: i64,
    pub currency_code: String,
    /// `None` = personal (§5.3 boundary).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub conversion: Option<PortConversion>,
    pub shares: Vec<ExpenseShare>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    /// UTC epoch millis. Splitwise imports carry date-only precision, so the
    /// importer pins these to UTC midnight: deterministic, never local-clock.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timestamp_ms: Option<u64>,
    /// §8.1's category (a merki category id, 32 hex). `None` =
    /// uncategorised: the honest default; a blank beats a guess.
    /// Validated for SHAPE here; tree membership is the app's tree to
    /// enforce (the id is meaningless outside its tree).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
}

/// One settlement (§5.8): `from` discharged `amount_minor` of
/// `currency_code` toward `to`. Directional and positive by model; a
/// reversed settlement is recorded with swapped endpoints, not a sign.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct PortSettlement {
    pub from: String,
    pub to: String,
    pub amount_minor: i64,
    pub currency_code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
}

/// One redistribution (D-210): when a person with a balance is removed from a
/// group, `from`'s balance in `currency_code` moves to the people in `shares`.
/// Each share has the balance's sign (negative when `from` is owed money,
/// positive when `from` owes), is nonzero, and the shares sum to the balance.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct PortRedistribution {
    pub from: String,
    pub currency_code: String,
    pub group: String,
    pub shares: Vec<ExpenseShare>,
}

/// Your own name and picture (D-211), as the ledger holds them: a name, and
/// either an emoji or a JPEG as lowercase hex (never both). Every field may be
/// absent. Validated with the same rules the `Avatar` record and a member's
/// name keep ([`validate_snapshot`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct PortProfile {
    pub name: Option<String>,
    pub emoji: Option<String>,
    pub photo_hex: Option<String>,
}

/// The whole ledger, as exported and as imported. Field order here is the
/// wire order of the JSON envelope; the version keys are prepended by the
/// envelope itself ([`jsonio`]).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct LedgerSnapshot {
    #[serde(default)]
    pub groups: Vec<PortGroup>,
    #[serde(default)]
    pub expenses: Vec<PortExpense>,
    #[serde(default)]
    pub settlements: Vec<PortSettlement>,
    #[serde(default)]
    pub redistributions: Vec<PortRedistribution>,
    /// D-211: your name and picture. Absent in every schema-3 and older file.
    #[serde(default)]
    pub profile: Option<PortProfile>,
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// Every failure the portage layer can produce. Distinct cases exist where a
/// shell must react differently: a wrong version tag means "update the app",
/// a malformed document means "the file is corrupt", a row rejection means
/// "everything else is fine".
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum PortError {
    /// Not valid JSON at all, or not shaped like our envelope.
    MalformedJson(String),
    /// Valid JSON, but not a SKIPTI ledger export.
    WrongFormatTag(String),
    /// A SKIPTI export, but written by a different schema generation.
    UnsupportedSchemaVersion {
        found: u64,
        supported: u64,
    },
    /// Parses cleanly and still must not enter the ledger: broken sums,
    /// unknown currencies, inconsistent frozen conversions, bad ids.
    InvalidLedger(String),
    /// CSV bytes violate RFC 4180 (unterminated quote, stray quote…).
    CsvSyntax(String),
    /// Parseable CSV that is not the sheet layout we were told to expect.
    SheetStructure(String),
    UnmappedMembers(Vec<String>),
}

impl std::fmt::Display for PortError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PortError::MalformedJson(m) => write!(f, "malformed JSON document: {m}"),
            PortError::WrongFormatTag(m) => write!(f, "not a SKIPTI ledger export: {m}"),
            PortError::UnsupportedSchemaVersion { found, supported } => {
                write!(
                    f,
                    "export schema version {found} unsupported; this build reads {supported}"
                )
            }
            PortError::InvalidLedger(m) => write!(f, "ledger state rejected: {m}"),
            PortError::CsvSyntax(m) => write!(f, "CSV syntax error: {m}"),
            PortError::SheetStructure(m) => write!(f, "unexpected sheet layout: {m}"),
            PortError::UnmappedMembers(names) => {
                write!(
                    f,
                    "no participant bound for member(s): {}",
                    names.join(", ")
                )
            }
        }
    }
}

impl std::error::Error for PortError {}

// ---------------------------------------------------------------------------
// Dates: date-only inputs become UTC-midnight timestamps
// ---------------------------------------------------------------------------

/// The four layouts foreign sheets actually use. Explicitness beats
/// detection in a money path: the caller names the layout, core refuses
/// anything that does not match it exactly.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DateFormat {
    /// `YYYY-MM-DD`, zero-padded, strictly 4-2-2.
    IsoYmd,
    /// `M/D/YYYY`: US order, 1 to 2 digit month/day, 4-digit year.
    UsMdy,
    /// `D/M/YYYY`: day-first order, 1 to 2 digit day/month, 4-digit year.
    EuDmy,
    /// `YYYY-MM-DD HH:MM:SS`, space separator, zero-padded, seconds
    /// included (leap second 60 refused). Pinned from independent consumers
    /// of BudgetBakers Wallet exports (2026); strictness is deliberate: an
    /// unexpected shape must be REJECTED so the profile can be revisited,
    /// never auto-guessed into a wrong timestamp.
    IsoYmdHms,
}

/// Days since 1970-01-01 for a validated civil date (Hinnant's days_from_civil;
/// pure integer arithmetic, no float, no time-crate dependency).
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (m as i64 + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

fn is_leap_year(y: i64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

fn days_in_month(y: i64, m: u32) -> u32 {
    match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap_year(y) => 29,
        2 => 28,
        _ => 0,
    }
}

fn parse_ymd(text: &str, fmt: DateFormat) -> Result<(i64, u32, u32), PortError> {
    let reject = || PortError::SheetStructure(format!("date {text:?} does not match {fmt:?}"));
    // For the datetime layout the date part is the first 10 chars and the
    // remainder must be exactly " HH:MM:SS", validated by the caller of this
    // helper (`parse_date_to_midnight_ms`), which owns the time-of-day math.
    let (date_part, time_part): (&str, Option<&str>) = match fmt {
        DateFormat::IsoYmdHms => {
            if text.len() != 19 || !text.is_char_boundary(10) || text.as_bytes()[10] != b' ' {
                return Err(reject());
            }
            (&text[..10], Some(&text[11..]))
        }
        _ => (text, None),
    };
    if let Some(time) = time_part {
        validate_hms(time).map_err(|_| reject())?;
    }
    let text = date_part;
    let (y_str, m_str, d_str) = match fmt {
        DateFormat::IsoYmd | DateFormat::IsoYmdHms => {
            let b = text.as_bytes();
            if b.len() != 10 || b[4] != b'-' || b[7] != b'-' {
                return Err(reject());
            }
            (&text[..4], &text[5..7], &text[8..10])
        }
        DateFormat::UsMdy | DateFormat::EuDmy => {
            let parts: Vec<&str> = text.split('/').collect();
            if parts.len() != 3 {
                return Err(reject());
            }
            let (a, b, y) = (parts[0], parts[1], parts[2]);
            if y.len() != 4 || !(1..=2).contains(&a.len()) || !(1..=2).contains(&b.len()) {
                return Err(reject());
            }
            match fmt {
                DateFormat::UsMdy => (y, a, b),
                _ => (y, b, a),
            }
        }
    };
    let all_digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    if !(all_digits(y_str) && all_digits(m_str) && all_digits(d_str)) {
        return Err(reject());
    }
    let y: i64 = y_str.parse().map_err(|_| reject())?;
    let m: u32 = m_str.parse().map_err(|_| reject())?;
    let d: u32 = d_str.parse().map_err(|_| reject())?;
    if !(1..=12).contains(&m) || d < 1 || d > days_in_month(y, m) {
        return Err(reject());
    }
    Ok((y, m, d))
}

/// Strict `HH:MM:SS`, zero-padded, ASCII digits. Leap second 60 refused:
/// this parses recorded wall-clock timestamps, not astronomical time.
fn validate_hms(time: &str) -> Result<(u32, u32, u32), ()> {
    let bytes = time.as_bytes();
    if bytes.len() != 8 || bytes[2] != b':' || bytes[5] != b':' {
        return Err(());
    }
    let two = |s: &str| -> Result<u32, ()> {
        if s.len() == 2 && s.bytes().all(|b| b.is_ascii_digit()) {
            s.parse().map_err(|_| ())
        } else {
            Err(())
        }
    };
    let (h, m, s) = (&time[..2], &time[3..5], &time[6..8]);
    let (h, m, s) = (two(h)?, two(m)?, two(s)?);
    if h > 23 || m > 59 || s > 59 {
        return Err(());
    }
    Ok((h, m, s))
}

/// Parses a date-only cell into UTC-midnight epoch milliseconds.
/// Deterministic across devices and time zones by construction.
pub fn parse_date_to_midnight_ms(text: &str, fmt: DateFormat) -> Result<u64, PortError> {
    let full = parse_datetime_to_ms(text, fmt)?;
    // Date-only layouts already land on midnight; the datetime layout floors
    // to 00:00 UTC of the recorded day.
    Ok((full / 86_400_000) * 86_400_000)
}

/// Parses a cell into the FULL epoch-millisecond timestamp its layout
/// denotes: the three date-only layouts denote UTC midnight, while
/// [`DateFormat::IsoYmdHms`] keeps the recorded time of day.
pub fn parse_datetime_to_ms(text: &str, fmt: DateFormat) -> Result<u64, PortError> {
    let text = text.trim();
    let (y, m, d) = parse_ymd(text, fmt)?;
    let days = days_from_civil(y, m, d);
    let mut ms = days
        .checked_mul(86_400_000)
        .ok_or_else(|| PortError::SheetStructure(format!("date {text:?} out of range")))?;
    if fmt == DateFormat::IsoYmdHms {
        // parse_ymd has validated the exact " HH:MM:SS" tail; read it back
        // for the arithmetic rather than threading a triple through both fns.
        let (h, mi, s) = validate_hms(&text[11..]).expect("time-of-day validated by parse_ymd");
        ms += (h as i64 * 3_600 + mi as i64 * 60 + s as i64) * 1_000;
    }
    u64::try_from(ms)
        .map_err(|_| PortError::SheetStructure(format!("date {text:?} predates the epoch")))
}

/// Inverse of [`days_from_civil`] (Hinnant's civil_from_days).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m as u32, d as u32)
}

/// Renders epoch milliseconds as ISO 8601 UTC ("2026-01-15T13:30:00Z"),
/// second precision, the flat CSV's date role (§11.3: dates are numeric
/// data, never locale prose). Pure integer arithmetic.
pub fn timestamp_ms_to_iso8601(ms: u64) -> String {
    let days = ms / 86_400_000;
    let rem = ms % 86_400_000;
    let (y, m, d) = civil_from_days(days as i64);
    let h = rem / 3_600_000;
    let mi = (rem % 3_600_000) / 60_000;
    let s = (rem % 60_000) / 1000;
    format!("{y:04}-{m:02}-{d:02}T{h:02}:{mi:02}:{s:02}Z")
}

// ---------------------------------------------------------------------------
// Decimal rendering: the export-side twin of money_parse_minor
// ---------------------------------------------------------------------------

/// Renders minor units as a plain decimal string with exactly the currency's
/// fractional digits ("1234", INR → "12.34"; KWD → "1.234"; JPY → "1234").
/// Pure integer arithmetic: the write-side counterpart of D-51's read-side
/// refusals. No grouping separators, no symbols: those belong to display
/// formatting, not to data files.
pub fn minor_to_plain_decimal(minor: i64, exponent: u8) -> String {
    let exp = exponent as u32;
    let scale = 10i128.pow(exp);
    let abs = (minor as i128).abs();
    let int_part = abs / scale;
    let frac = abs % scale;
    let sign = if minor < 0 { "-" } else { "" };
    if exp == 0 {
        format!("{sign}{int_part}")
    } else {
        format!("{sign}{int_part}.{frac:0width$}", width = exp as usize)
    }
}

// ---------------------------------------------------------------------------
// Validation: nothing exports, imports, or applies without passing this
// ---------------------------------------------------------------------------

fn require_participant(id: &str, what: &str) -> Result<(), PortError> {
    participant_id(id, what).map(|_| ())
}

fn participant_id(id: &str, what: &str) -> Result<crate::hlutr::ParticipantId, PortError> {
    crate::ffi::parse_participant(id)
        .map_err(|e| PortError::InvalidLedger(format!("{what} id invalid: {e}")))
}

fn require_currency(code: &str, what: &str) -> Result<Currency, PortError> {
    Currency::parse(code).map_err(|e| PortError::InvalidLedger(format!("{what}: {e}")))
}

/// D-211: a profile is held to the rules the records keep. The picture goes
/// through `straumr::groups::encode` on a `GroupOp::Avatar` (a JPEG as
/// lowercase hex of at most `PICTURE_MAX_BYTES`, or one emoji, never both) and
/// the name through the member-name check.
fn validate_profile(p: &PortProfile) -> Result<(), PortError> {
    if let Some(name) = &p.name {
        crate::straumr::groups::check_member_name(name)
            .map_err(|e| PortError::InvalidLedger(format!("profile name: {e:?}")))?;
    }
    crate::straumr::groups::encode(&crate::straumr::groups::GroupOp::Avatar {
        emoji: p.emoji.clone(),
        photo: p.photo_hex.clone(),
    })
    .map_err(|e| PortError::InvalidLedger(format!("profile picture: {e:?}")))?;
    Ok(())
}

/// Structural and monetary integrity of a snapshot. Runs on EVERY path into
/// or out of this module: export validates before writing, import validates
/// after parsing, apply validates the merged result. A hand-edited or
/// corrupted file therefore fails closed with a typed reason, never enters
/// the ledger.
pub fn validate_snapshot(s: &LedgerSnapshot) -> Result<(), PortError> {
    for g in &s.groups {
        require_participant(&g.id, "group")?;
        if g.name.trim().is_empty() {
            return Err(PortError::InvalidLedger("group name empty".into()));
        }
        require_currency(&g.settlement_currency, "group settlement currency")?;
        if g.members.is_empty() {
            return Err(PortError::InvalidLedger(format!(
                "group {} has no members",
                g.id
            )));
        }
        let mut seen = std::collections::BTreeSet::new();
        for m in &g.members {
            require_participant(m, "group member")?;
            if !seen.insert(m.as_bytes()) {
                return Err(PortError::InvalidLedger(format!(
                    "group {} lists member {m} twice",
                    g.id
                )));
            }
        }
    }
    for e in &s.expenses {
        require_participant(&e.payer, "payer")?;
        let currency = require_currency(&e.currency_code, "expense currency")?;
        if e.paid_minor < 0 {
            return Err(PortError::InvalidLedger(format!(
                "expense total {} is negative",
                e.paid_minor
            )));
        }
        if let Some(group) = &e.group {
            require_participant(group, "expense group")?;
        }
        if let Some(category) = &e.category {
            require_participant(category, "expense category")?;
        }
        if e.shares.is_empty() {
            return Err(PortError::InvalidLedger("expense has no shares".into()));
        }
        let mut sum: i128 = 0;
        for sh in &e.shares {
            require_participant(&sh.id, "share holder")?;
            sum += sh.amount_minor as i128;
        }
        if sum != e.paid_minor as i128 {
            return Err(PortError::InvalidLedger(format!(
                "shares sum to {sum}, total is {}",
                e.paid_minor
            )));
        }
        if let Some(c) = &e.conversion {
            let target = require_currency(&c.converted_currency, "conversion currency")?;
            // The frozen conversion must be EXACTLY what core would have
            // produced from the rational rate (half-up on magnitude, fx::convert):
            // a hand-edited file cannot smuggle in a re-priced amount.
            let rate = crate::fx::FxRate::new(
                currency,
                target,
                c.rate_num as i128,
                c.rate_den as i128,
                c.rate_timestamp_ms,
            )
            .map_err(|err| PortError::InvalidLedger(format!("frozen FX rate invalid: {err}")))?;
            let conversion = crate::fx::convert(
                crate::money::Money::new(e.paid_minor as i128, currency),
                rate,
            )
            .map_err(|err| PortError::InvalidLedger(format!("frozen conversion invalid: {err}")))?;
            if conversion.converted.amount != c.converted_minor as i128 {
                return Err(PortError::InvalidLedger(format!(
                    "frozen conversion records {}, but the rate yields {}",
                    c.converted_minor, conversion.converted.amount
                )));
            }
        }
    }
    for st in &s.settlements {
        require_participant(&st.from, "settlement sender")?;
        require_participant(&st.to, "settlement receiver")?;
        if st.from == st.to {
            return Err(PortError::InvalidLedger("settlement from == to".into()));
        }
        require_currency(&st.currency_code, "settlement currency")?;
        if st.amount_minor <= 0 {
            return Err(PortError::InvalidLedger(format!(
                "settlement amount {} must be positive",
                st.amount_minor
            )));
        }
        if let Some(group) = &st.group {
            require_participant(group, "settlement group")?;
        }
    }
    for r in &s.redistributions {
        require_participant(&r.group, "redistribution group")?;
        let from = participant_id(&r.from, "redistribution person")?;
        require_currency(&r.currency_code, "redistribution currency")?;
        let mut shares = Vec::with_capacity(r.shares.len());
        for sh in &r.shares {
            shares.push((
                participant_id(&sh.id, "redistribution share holder")?,
                sh.amount_minor as i128,
            ));
        }
        // The rules the record itself keeps (norn::redistribution::check):
        // shares nonempty, nonzero, of one sign, summing to the balance,
        // `from` not among them, nobody twice.
        let balance: i128 = shares.iter().map(|(_, v)| v).sum();
        crate::norn::redistribution::check(balance, from, &shares).map_err(|e| {
            PortError::InvalidLedger(format!("redistribution shares are not valid: {e:?}"))
        })?;
        // No settlement-currency check: a person can owe in any currency the
        // group used, and settlements carry no such check either.
    }
    if let Some(p) = &s.profile {
        validate_profile(p)?;
    }
    Ok(())
}

/// Returns the snapshot in canonical order: each collection sorted by its own
/// serialized form, giving one byte-stable representation per state. Export
/// writes only canonical output, so two equal states export byte-identically
/// regardless of the order they accumulated in.
pub fn canonical_sorted(s: &LedgerSnapshot) -> LedgerSnapshot {
    fn sorted_by_serial<T, F>(items: Vec<T>, serial: F) -> Vec<T>
    where
        F: Fn(&T) -> String,
    {
        let mut keyed: Vec<(String, T)> = items.into_iter().map(|t| (serial(&t), t)).collect();
        keyed.sort_by(|a, b| a.0.cmp(&b.0));
        keyed.into_iter().map(|(_, t)| t).collect()
    }
    LedgerSnapshot {
        groups: sorted_by_serial(s.groups.clone(), |g| {
            serde_json::to_string(g).expect("snapshot types serialize")
        }),
        expenses: sorted_by_serial(s.expenses.clone(), |e| {
            serde_json::to_string(e).expect("snapshot types serialize")
        }),
        settlements: sorted_by_serial(s.settlements.clone(), |x| {
            serde_json::to_string(x).expect("snapshot types serialize")
        }),
        redistributions: {
            let mut v = s.redistributions.clone();
            v.sort_by(|a, b| {
                (&a.group, &a.from, &a.currency_code)
                    .cmp(&(&b.group, &b.from, &b.currency_code))
                    .then_with(|| {
                        let key = |r: &PortRedistribution| -> Vec<(String, i64)> {
                            r.shares
                                .iter()
                                .map(|x| (x.id.clone(), x.amount_minor))
                                .collect()
                        };
                        key(a).cmp(&key(b))
                    })
            });
            v
        },
        profile: s.profile.clone(),
    }
}

/// D-210: one group's slice of a snapshot, for the read-only "Export this
/// group" of a removed or deleted group: the group itself and every expense,
/// settlement and redistribution of it. Personal expenses and settlements
/// (no group) and every other group are dropped.
pub fn snapshot_for_group(s: &LedgerSnapshot, group: &str) -> LedgerSnapshot {
    LedgerSnapshot {
        groups: s.groups.iter().filter(|g| g.id == group).cloned().collect(),
        expenses: s
            .expenses
            .iter()
            .filter(|e| e.group.as_deref() == Some(group))
            .cloned()
            .collect(),
        settlements: s
            .settlements
            .iter()
            .filter(|x| x.group.as_deref() == Some(group))
            .cloned()
            .collect(),
        redistributions: s
            .redistributions
            .iter()
            .filter(|r| r.group == group)
            .cloned()
            .collect(),
        // Your picture is yours, not the group's.
        profile: None,
    }
}

/// D-248: the "Export Just me" slice of a snapshot, the twin of
/// [`snapshot_for_group`]: every personal (group-less) expense and
/// settlement. Every group, every group's records and every redistribution
/// (each names a group) are dropped, and so is the profile, as for a group's
/// slice: the export is a ledger, not you.
pub fn snapshot_personal(s: &LedgerSnapshot) -> LedgerSnapshot {
    LedgerSnapshot {
        groups: Vec::new(),
        expenses: s
            .expenses
            .iter()
            .filter(|e| e.group.is_none())
            .cloned()
            .collect(),
        settlements: s
            .settlements
            .iter()
            .filter(|x| x.group.is_none())
            .cloned()
            .collect(),
        redistributions: Vec::new(),
        profile: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ADA: &str = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0";
    const BOB: &str = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

    #[test]
    fn decimal_rendering_is_exact_per_exponent() {
        assert_eq!(minor_to_plain_decimal(1234, 2), "12.34");
        assert_eq!(minor_to_plain_decimal(5, 2), "0.05");
        assert_eq!(minor_to_plain_decimal(-1240, 2), "-12.40");
        assert_eq!(minor_to_plain_decimal(1234567, 3), "1234.567");
        assert_eq!(minor_to_plain_decimal(42, 0), "42");
        assert_eq!(minor_to_plain_decimal(-7, 0), "-7");
        assert_eq!(minor_to_plain_decimal(0, 2), "0.00");
        // i64::MIN must not overflow the magnitude path.
        assert_eq!(minor_to_plain_decimal(i64::MIN, 2), "-92233720368547758.08");
    }

    #[test]
    fn iso_dates_pin_against_independent_epoch_values() {
        // Values computed independently (Python datetime), not by this code.
        assert_eq!(
            parse_date_to_midnight_ms("1970-01-01", DateFormat::IsoYmd),
            Ok(0)
        );
        assert_eq!(
            parse_date_to_midnight_ms("2026-08-26", DateFormat::IsoYmd),
            Ok(20_691 * 86_400_000)
        );
        assert_eq!(
            parse_date_to_midnight_ms("2024-02-29", DateFormat::IsoYmd),
            Ok(19_782 * 86_400_000)
        );
        assert_eq!(
            parse_date_to_midnight_ms("2100-03-01", DateFormat::IsoYmd),
            Ok(47_541 * 86_400_000)
        );
    }

    #[test]
    fn slash_formats_agree_with_iso_on_the_same_day() {
        let iso = parse_date_to_midnight_ms("2026-01-15", DateFormat::IsoYmd);
        assert_eq!(iso, Ok(20_468 * 86_400_000));
        assert_eq!(
            parse_date_to_midnight_ms("1/15/2026", DateFormat::UsMdy),
            iso
        );
        assert_eq!(
            parse_date_to_midnight_ms("15/1/2026", DateFormat::EuDmy),
            iso
        );
        assert_eq!(
            parse_date_to_midnight_ms(" 2026-01-15 ", DateFormat::IsoYmd),
            iso
        );
    }

    #[test]
    fn datetime_layout_pins_against_independent_values() {
        // Independently computed epoch values (Python datetime), not by this code.
        let day = 20_468 * 86_400_000; // 2026-01-15 UTC midnight
        let fmt = DateFormat::IsoYmdHms;
        assert_eq!(
            parse_datetime_to_ms("2026-01-15 13:30:00", fmt),
            Ok(day + 48_600_000)
        );
        assert_eq!(parse_datetime_to_ms("2026-01-15 00:00:00", fmt), Ok(day));
        // The same cell read as a DAY floors to its midnight:
        assert_eq!(
            parse_date_to_midnight_ms("2026-01-15 13:30:00", fmt),
            Ok(day)
        );
        // Strictness: only the pinned layout, nothing adjacent:
        for bad in [
            "2026-01-15",
            "2026-01-15T13:30:00",
            "2026-01-15 13:30",
            "2026-01-15 13:30:60",
            "2026-1-15 13:30:00",
            "2026-01-15  13:30:00",
        ] {
            assert!(parse_datetime_to_ms(bad, fmt).is_err(), "{bad} must refuse");
        }
    }

    #[test]
    fn iso8601_rendering_pins_against_independent_values() {
        assert_eq!(timestamp_ms_to_iso8601(0), "1970-01-01T00:00:00Z");
        assert_eq!(
            timestamp_ms_to_iso8601(20_691 * 86_400_000),
            "2026-08-26T00:00:00Z"
        );
        // A mid-day timestamp keeps its time, truncated to seconds:
        assert_eq!(
            timestamp_ms_to_iso8601(20_468 * 86_400_000 + 48_600_123),
            "2026-01-15T13:30:00Z"
        );
    }

    #[test]
    fn impossible_dates_are_refused_not_wrapped() {
        assert!(parse_date_to_midnight_ms("2025-02-29", DateFormat::IsoYmd).is_err());
        assert!(parse_date_to_midnight_ms("2100-02-29", DateFormat::IsoYmd).is_err()); // century, not leap
        assert!(parse_date_to_midnight_ms("2026-13-01", DateFormat::IsoYmd).is_err());
        assert!(parse_date_to_midnight_ms("2026-00-10", DateFormat::IsoYmd).is_err());
        assert!(parse_date_to_midnight_ms("2026-02-30", DateFormat::IsoYmd).is_err());
        // Wrong layout for the chosen format is refused even though it parses in another:
        assert!(parse_date_to_midnight_ms("15/1/2026", DateFormat::UsMdy).is_err());
        assert!(parse_date_to_midnight_ms("2026-1-5", DateFormat::IsoYmd).is_err());
        // not zero-padded
    }

    fn sample_snapshot() -> LedgerSnapshot {
        LedgerSnapshot {
            groups: vec![PortGroup {
                id: "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0".into(),
                name: "Goa Trip".into(),
                settlement_currency: "INR".into(),
                members: vec![ADA.into(), BOB.into()],
            }],
            expenses: vec![],
            settlements: vec![],
            redistributions: vec![],
            profile: None,
        }
    }

    #[test]
    fn validation_rejects_broken_sums_and_bad_ids() {
        let mut s = sample_snapshot();
        s.expenses.push(PortExpense {
            payer: ADA.into(),
            paid_minor: 500,
            currency_code: "INR".into(),
            group: None,
            conversion: None,
            shares: vec![
                ExpenseShare {
                    id: ADA.into(),
                    amount_minor: 300,
                },
                ExpenseShare {
                    id: BOB.into(),
                    amount_minor: 199,
                }, // sums to 499
            ],
            note: None,
            timestamp_ms: None,
            category: None,
        });
        assert!(matches!(
            validate_snapshot(&s),
            Err(PortError::InvalidLedger(_))
        ));
        s.expenses[0].shares[1].amount_minor = 200;
        assert_eq!(validate_snapshot(&s), Ok(()));

        s.expenses[0].payer = "nothex".into();
        assert!(matches!(
            validate_snapshot(&s),
            Err(PortError::InvalidLedger(_))
        ));
    }

    fn redistribution(shares: &[(&str, i64)]) -> PortRedistribution {
        PortRedistribution {
            from: "d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4".into(),
            currency_code: "INR".into(),
            group: "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0".into(),
            shares: shares
                .iter()
                .map(|(id, a)| ExpenseShare {
                    id: (*id).into(),
                    amount_minor: *a,
                })
                .collect(),
        }
    }

    #[test]
    fn validation_holds_a_redistribution_to_its_rules() {
        let (one, two, three) = (
            "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
            "a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2",
            "a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3",
        );
        let mut s = sample_snapshot();
        // Owed money (negative shares) and money owed (positive) both pass.
        s.redistributions = vec![redistribution(&[
            (one, -33_334),
            (two, -33_333),
            (three, -33_333),
        ])];
        assert_eq!(validate_snapshot(&s), Ok(()));
        s.redistributions = vec![redistribution(&[(one, 500), (two, 501)])];
        assert_eq!(validate_snapshot(&s), Ok(()));
        let refused = |shares: &[(&str, i64)]| {
            let mut s = sample_snapshot();
            s.redistributions = vec![redistribution(shares)];
            assert!(
                matches!(validate_snapshot(&s), Err(PortError::InvalidLedger(_))),
                "{shares:?}"
            );
        };
        // No shares.
        refused(&[]);
        // Mixed signs, so no single-signed balance.
        refused(&[(one, 500), (two, -300)]);
        // A zero share.
        refused(&[(one, 0), (two, 500)]);
        // Shares netting to zero.
        refused(&[(one, 500), (two, -500)]);
        // The person among their own shares.
        refused(&[("d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4", -500)]);
        // The same member twice.
        refused(&[(one, -500), (one, -500)]);
        // Not 32 hex.
        refused(&[("nothex", -500)]);
        // A bad person, group or currency.
        let mut s = sample_snapshot();
        s.redistributions = vec![redistribution(&[(one, -500)])];
        s.redistributions[0].from = "nothex".into();
        assert!(validate_snapshot(&s).is_err());
        s.redistributions[0] = redistribution(&[(one, -500)]);
        s.redistributions[0].group = "nothex".into();
        assert!(validate_snapshot(&s).is_err());
        s.redistributions[0] = redistribution(&[(one, -500)]);
        s.redistributions[0].currency_code = "ZZZ9".into();
        assert!(validate_snapshot(&s).is_err());
        // A balance in a currency other than the group's settlement currency
        // is redistributed in that currency: the sample group settles in INR,
        // and a USD redistribution in it validates, as a USD settlement does.
        s.redistributions[0] = redistribution(&[(one, -500)]);
        s.redistributions[0].currency_code = "USD".into();
        assert_eq!(s.groups[0].id, s.redistributions[0].group);
        assert_eq!(s.groups[0].settlement_currency, "INR");
        assert_eq!(validate_snapshot(&s), Ok(()));
        // And a group not in the snapshot changes nothing.
        s.redistributions[0].group = "e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0".into();
        assert_eq!(validate_snapshot(&s), Ok(()));
    }

    #[test]
    fn one_groups_snapshot_holds_only_that_group() {
        let (ga, gb) = (
            "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0",
            "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1",
        );
        let group = |id: &str, name: &str| PortGroup {
            id: id.into(),
            name: name.into(),
            settlement_currency: "INR".into(),
            members: vec![ADA.into(), BOB.into()],
        };
        let expense = |g: Option<&str>, minor: i64| PortExpense {
            payer: ADA.into(),
            paid_minor: minor,
            currency_code: "INR".into(),
            group: g.map(str::to_string),
            conversion: None,
            shares: vec![ExpenseShare {
                id: ADA.into(),
                amount_minor: minor,
            }],
            note: None,
            timestamp_ms: None,
            category: None,
        };
        let settle = |g: Option<&str>, minor: i64| PortSettlement {
            from: BOB.into(),
            to: ADA.into(),
            amount_minor: minor,
            currency_code: "INR".into(),
            group: g.map(str::to_string),
        };
        let redistribute = |g: &str, from: &str, to: &str| PortRedistribution {
            from: from.into(),
            currency_code: "INR".into(),
            group: g.into(),
            shares: vec![ExpenseShare {
                id: to.into(),
                amount_minor: -500,
            }],
        };
        let all = LedgerSnapshot {
            groups: vec![group(ga, "A"), group(gb, "B")],
            expenses: vec![
                expense(Some(ga), 100),
                expense(Some(gb), 200),
                expense(None, 300),
            ],
            settlements: vec![settle(Some(ga), 10), settle(Some(gb), 20), settle(None, 30)],
            redistributions: vec![redistribute(ga, BOB, ADA), redistribute(gb, ADA, BOB)],
            profile: Some(PortProfile {
                name: Some("Tim".into()),
                emoji: Some("\u{1F98A}".into()),
                photo_hex: None,
            }),
        };
        assert_eq!(validate_snapshot(&all), Ok(()));
        let a = snapshot_for_group(&all, ga);
        assert_eq!(a.groups, vec![group(ga, "A")]);
        assert_eq!(a.expenses, vec![expense(Some(ga), 100)]);
        assert_eq!(a.settlements, vec![settle(Some(ga), 10)]);
        assert_eq!(a.redistributions, vec![redistribute(ga, BOB, ADA)]);
        // Your picture is yours, not the group's.
        assert_eq!(a.profile, None);
        assert_eq!(validate_snapshot(&a), Ok(()));
        // A group the snapshot does not hold gives an empty snapshot.
        assert_eq!(
            snapshot_for_group(&all, "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"),
            LedgerSnapshot::default()
        );
    }

    /// D-248: the Just me slice keeps every group-less expense and
    /// settlement and nothing of any group; the profile stays out.
    #[test]
    fn the_just_me_snapshot_holds_only_personal_records() {
        let ga = "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0";
        let expense = |g: Option<&str>, minor: i64| PortExpense {
            payer: ADA.into(),
            paid_minor: minor,
            currency_code: "INR".into(),
            group: g.map(str::to_string),
            conversion: None,
            shares: vec![ExpenseShare {
                id: ADA.into(),
                amount_minor: minor,
            }],
            note: None,
            timestamp_ms: None,
            category: None,
        };
        let settle = |g: Option<&str>, minor: i64| PortSettlement {
            from: BOB.into(),
            to: ADA.into(),
            amount_minor: minor,
            currency_code: "INR".into(),
            group: g.map(str::to_string),
        };
        let all = LedgerSnapshot {
            groups: vec![PortGroup {
                id: ga.into(),
                name: "A".into(),
                settlement_currency: "INR".into(),
                members: vec![ADA.into(), BOB.into()],
            }],
            expenses: vec![
                expense(Some(ga), 100),
                expense(None, 300),
                expense(None, 400),
            ],
            settlements: vec![settle(Some(ga), 10), settle(None, 30)],
            redistributions: vec![PortRedistribution {
                from: BOB.into(),
                currency_code: "INR".into(),
                group: ga.into(),
                shares: vec![ExpenseShare {
                    id: ADA.into(),
                    amount_minor: -500,
                }],
            }],
            profile: Some(PortProfile {
                name: Some("Tim".into()),
                emoji: None,
                photo_hex: None,
            }),
        };
        assert_eq!(validate_snapshot(&all), Ok(()));
        let mine = snapshot_personal(&all);
        assert_eq!(
            mine,
            LedgerSnapshot {
                expenses: vec![expense(None, 300), expense(None, 400)],
                settlements: vec![settle(None, 30)],
                ..LedgerSnapshot::default()
            }
        );
        assert_eq!(validate_snapshot(&mine), Ok(()));
        // A ledger with only groups gives an empty slice.
        let mut groups_only = all.clone();
        groups_only.expenses.retain(|e| e.group.is_some());
        groups_only.settlements.retain(|x| x.group.is_some());
        assert_eq!(snapshot_personal(&groups_only), LedgerSnapshot::default());
    }

    #[test]
    fn validation_refuses_an_unrepresentable_settlement() {
        let mut s = sample_snapshot();
        s.settlements.push(PortSettlement {
            from: ADA.into(),
            to: ADA.into(), // paying yourself
            amount_minor: 100,
            currency_code: "INR".into(),
            group: None,
        });
        assert!(matches!(
            validate_snapshot(&s),
            Err(PortError::InvalidLedger(_))
        ));
    }
}
