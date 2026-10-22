//! The FFI surface shells build against (ADR-0025).
//!
//! Everything here is ONE shared translation layer: plain Rust functions
//! that validate inputs and map to/from core types. The UniFFI exports are
//! declarative annotations on these functions; the wasm-bindgen exports
//! call exactly the same functions. Behaviour cannot drift between the two
//! surfaces without editing shared code, which the tests in this file pin.
//!
//! Boundary rules (ADR-0025):
//! - Money crosses as i64 minor units + ISO code; adapters check ranges
//!   before core arithmetic. Core stays i128 (ADR-0002: no floats anywhere).
//! - Participant ids cross as 32-char lowercase hex, decoded with typed
//!   errors, never a panic into the host.
//! - skrin is native-only (SQLCipher cannot target wasm32); the web shell's
//!   store is SPEC §10.2's separate IndexedDB build.

use crate::flutningr::{
    apply_plan, plan_import, ImportPlan, ImportProfile, LedgerSnapshot, PortError,
};
use crate::format::{self};
use crate::hlutr::{self, ItemLine, ParticipantId, SplitError, SplitSpec};
use crate::lykill;
use crate::money::{Currency, Money, MoneyError};

// ---------------------------------------------------------------------------
// Errors: every fallible entry point returns one of these, never a panic
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum CoreError {
    UnknownCurrency(String),
    MismatchedCurrencies {
        a: String,
        b: String,
    },
    Overflow,
    /// Malformed boundary input: bad hex, wrong lengths, empty strings.
    InvalidInput(String),
    EmptyParticipants,
    DuplicateParticipant,
    UnknownParticipant,
    NonPositiveWeight,
    NegativeTotal,
    PercentMismatch {
        got_bps: u64,
    },
    ExactSumMismatch {
        expected: i64,
        got: i64,
    },
    DeltasExceedTotal,
    LineAmountNonPositive,
    EmptyLineAssignment,
    TotalMismatch {
        expected: i64,
        got: i64,
    },
}

impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CoreError::UnknownCurrency(c) => write!(f, "unknown ISO 4217 code: {c}"),
            CoreError::MismatchedCurrencies { a, b } => {
                write!(f, "cannot combine {a} and {b} without an explicit rate")
            }
            CoreError::Overflow => write!(f, "amount out of range"),
            CoreError::InvalidInput(m) => write!(f, "invalid input: {m}"),
            CoreError::EmptyParticipants => write!(f, "at least one participant is required"),
            CoreError::DuplicateParticipant => write!(f, "participant listed more than once"),
            CoreError::UnknownParticipant => write!(f, "split references a non-member"),
            CoreError::NonPositiveWeight => write!(f, "shares and percentages must be positive"),
            CoreError::NegativeTotal => write!(f, "expense total must be non-negative"),
            CoreError::PercentMismatch { got_bps } => {
                write!(f, "percentages must sum to 10000 bps, got {got_bps}")
            }
            CoreError::ExactSumMismatch { expected, got } => {
                write!(f, "exact amounts sum to {got}, total is {expected}")
            }
            CoreError::DeltasExceedTotal => write!(f, "adjustments exceed the expense total"),
            CoreError::LineAmountNonPositive => write!(f, "itemised lines need positive amounts"),
            CoreError::EmptyLineAssignment => write!(f, "itemised line has no assignees"),
            CoreError::TotalMismatch { expected, got } => {
                write!(f, "shares sum to {got}, total is {expected}")
            }
        }
    }
}

impl std::error::Error for CoreError {}

impl From<MoneyError> for CoreError {
    fn from(e: MoneyError) -> Self {
        match e {
            MoneyError::MismatchedCurrencies { a, b } => CoreError::MismatchedCurrencies {
                a: a.to_string(),
                b: b.to_string(),
            },
            MoneyError::UnknownCurrency(c) => CoreError::UnknownCurrency(c),
            MoneyError::Overflow => CoreError::Overflow,
            MoneyError::InvalidInput(m) => CoreError::InvalidInput(m.to_string()),
        }
    }
}

impl From<crate::straumr::check::CheckError> for CoreError {
    fn from(e: crate::straumr::check::CheckError) -> Self {
        use crate::straumr::check::CheckError;
        match e {
            CheckError::UnknownCurrency(c) => CoreError::UnknownCurrency(c),
            CheckError::Negative => CoreError::NegativeTotal,
            CheckError::TooLarge => CoreError::Overflow,
            CheckError::NoShares => CoreError::EmptyParticipants,
            CheckError::DuplicateShare => CoreError::DuplicateParticipant,
            CheckError::SumMismatch { expected, got } => CoreError::TotalMismatch {
                expected: clamp_i64(expected),
                got: clamp_i64(got),
            },
            other @ (CheckError::Id(_)
            | CheckError::Conversion(_)
            | CheckError::SelfSettlement
            | CheckError::NonPositiveSettlement
            | CheckError::TextTooLong) => CoreError::InvalidInput(other.to_string()),
        }
    }
}

impl From<SplitError> for CoreError {
    fn from(e: SplitError) -> Self {
        match e {
            SplitError::EmptyParticipants => CoreError::EmptyParticipants,
            SplitError::DuplicateParticipant => CoreError::DuplicateParticipant,
            SplitError::UnknownParticipant => CoreError::UnknownParticipant,
            SplitError::NonPositiveWeight => CoreError::NonPositiveWeight,
            SplitError::NegativeTotal => CoreError::NegativeTotal,
            SplitError::PercentMustSumToTenThousand(got) => CoreError::PercentMismatch {
                got_bps: got as u64,
            },
            SplitError::ExactSumMismatch { expected, got } => CoreError::ExactSumMismatch {
                expected: clamp_i64(expected),
                got: clamp_i64(got),
            },
            SplitError::DeltasExceedTotal => CoreError::DeltasExceedTotal,
            SplitError::LineAmountNonPositive => CoreError::LineAmountNonPositive,
            SplitError::EmptyLineAssignment => CoreError::EmptyLineAssignment,
            SplitError::TotalMismatch { expected, got } => CoreError::TotalMismatch {
                expected: clamp_i64(expected),
                got: clamp_i64(got),
            },
            SplitError::Currency(m) => m.into(),
        }
    }
}

/// Saturating narrowing for error *reporting* only; real values crossing the
/// boundary go through checked conversions that return Overflow instead.
fn clamp_i64(v: i128) -> i64 {
    i64::try_from(v).unwrap_or(if v > 0 { i64::MAX } else { i64::MIN })
}

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LocaleTag {
    EnIn,
    EnUs,
}

impl From<LocaleTag> for format::Locale {
    fn from(l: LocaleTag) -> Self {
        match l {
            LocaleTag::EnIn => format::Locale::EnIN,
            LocaleTag::EnUs => format::Locale::EnUS,
        }
    }
}

/// Parses locale tags from string form (the wasm surface's encoding).
pub fn locale_from_tag(tag: &str) -> Result<LocaleTag, CoreError> {
    match tag {
        "en-IN" | "EnIn" | "EnIN" => Ok(LocaleTag::EnIn),
        "en-US" | "EnUs" => Ok(LocaleTag::EnUs),
        other => Err(CoreError::InvalidInput(format!("unknown locale {other:?}"))),
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct IdAmount {
    pub id: String,
    pub amount_minor: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct IdBps {
    pub id: String,
    pub bps: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct IdWeight {
    pub id: String,
    pub weight: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LineInput {
    pub amount_minor: i64,
    pub participants: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SplitSpecInput {
    Equal,
    Exact {
        entries: Vec<IdAmount>,
    },
    Percentage {
        entries: Vec<IdBps>,
    },
    Shares {
        entries: Vec<IdWeight>,
    },
    Adjustment {
        entries: Vec<IdAmount>,
    },
    Itemised {
        lines: Vec<LineInput>,
        tax_minor: i64,
        tip_minor: i64,
    },
    PayerOnly {
        payer: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ShareOut {
    pub id: String,
    pub minor_units: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TransferOut {
    pub from: String,
    pub to: String,
    pub amount_minor: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExpenseInput {
    pub payer: String,
    pub paid_minor: i64,
    pub currency_code: String,
    pub shares: Vec<IdAmount>,
    /// `None` = personal (§5.3: a personal transaction never belongs to a
    /// group); `Some(id)` = the group it belongs to.
    pub group: Option<String>,
    /// §5.2: the FX conversion FROZEN at entry. Original amount/currency
    /// stay in `paid_minor`/`currency_code`, and this carries the converted
    /// result, the rational rate used, and the rate's own timestamp. A
    /// February trip never re-prices in March. `None` = entered in the
    /// settlement currency, no conversion.
    pub conversion: Option<ConversionInput>,
}

/// The frozen FX conversion (§5.2). `rate_num/rate_den` is the exact
/// rational rate (no floats, ADR-0002): converted = original × num / den,
/// rounded half-up on magnitude by core.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConversionInput {
    pub converted_minor: i64,
    pub converted_currency: String,
    pub rate_num: i64,
    pub rate_den: i64,
    pub rate_timestamp_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SettlementInput {
    pub from: String,
    pub to: String,
    pub amount_minor: i64,
    pub currency_code: String,
    pub group: Option<String>,
}

/// D-210: a removed person's balance moved onto chosen members (norn::redistribution).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RedistributionInput {
    pub group: String,
    pub from: String,
    pub currency_code: String,
    pub shares: Vec<IdAmount>,
}

/// D-210: typed shares against the balance they must add up to.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RedistributionTally {
    pub assigned_minor: i64,
    pub left_minor: i64,
    pub ok: bool,
}

/// One currency's balance line (§11.4): entries sorted by magnitude
/// descending, ties broken by participant id ascending, plus the ≤ n−1
/// simplification transfers.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CurrencyBalanceOut {
    pub currency_code: String,
    pub entries: Vec<ShareOut>,
    pub transfers: Vec<TransferOut>,
}

// ---------------------------------------------------------------------------
// Shared translation layer: both surfaces call ONLY these
// ---------------------------------------------------------------------------

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 0xF) as usize] as char);
    }
    s
}

pub(crate) fn parse_participant(s: &str) -> Result<ParticipantId, CoreError> {
    if s.len() != 32 || !s.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(CoreError::InvalidInput(
            "participant id must be 32 hex chars".to_string(),
        ));
    }
    let mut out = [0u8; 16];
    for (i, chunk) in s.as_bytes().chunks(2).enumerate() {
        let hi = (chunk[0] as char).to_digit(16).unwrap_or(0) as u8;
        let lo = (chunk[1] as char).to_digit(16).unwrap_or(0) as u8;
        out[i] = (hi << 4) | lo;
    }
    Ok(out)
}

fn narrow(v: i128) -> Result<i64, CoreError> {
    i64::try_from(v).map_err(|_| CoreError::Overflow)
}

/// D-259: a sum of a group's records as a shell may hold it: within i64 AND
/// not i64::MIN, whose magnitude no i64 holds (Swift's `abs(Int64.min)`
/// traps, Kotlin's `-Long.MIN_VALUE` stays negative). One member's records
/// can reach either bound (9,224 records of `check::MAX_AMOUNT_MINOR`), so a
/// balance or settled sum outside it is `Overflow`, never a value.
pub(crate) fn narrow_sum(v: i128) -> Result<i64, CoreError> {
    if v.unsigned_abs() > i64::MAX as u128 {
        return Err(CoreError::Overflow);
    }
    narrow(v)
}

pub(crate) fn money_make(minor: i64, code: &str) -> Result<Money, CoreError> {
    Ok(Money::new(minor as i128, Currency::parse(code)?))
}

/// Entry-time conversion (§5.2): original minor units × rational rate,
/// rounded half-up on magnitude by fx::convert. The rate crosses as an
/// exact fraction, no floats (ADR-0002).
pub(crate) fn convert_minor(
    original_minor: i64,
    from_code: &str,
    to_code: &str,
    rate_num: i64,
    rate_den: i64,
) -> Result<i64, CoreError> {
    let rate = crate::fx::FxRate::new(
        Currency::parse(from_code)?,
        Currency::parse(to_code)?,
        rate_num as i128,
        rate_den as i128,
        0,
    )
    .map_err(CoreError::from)?;
    let conversion = crate::fx::convert(money_make(original_minor, from_code)?, rate)?;
    narrow(conversion.converted.amount)
}

/// Formats minor units for display through core/src/format.rs. This is THE
/// formatting path for every shell: reimplementing it in Swift or Kotlin
/// would drift from the tests that pin D-09.
pub fn money_format(minor: i64, code: &str, locale: LocaleTag) -> Result<String, CoreError> {
    Ok(format::format_money(
        money_make(minor, code)?,
        locale.into(),
    ))
}

/// Parses a decimal amount STRING into minor units, the entry-path
/// counterpart to money_format. Amounts enter as text and must become
/// integer minor units WITHOUT touching a float (ADR-0002): "0.1" → 10,
/// "1.005" in KWD (3 decimals) → 1005, "1.005" in INR (2 decimals) is
/// REFUSED rather than silently rounded, "10.05" in JPY (0 decimals) is
/// refused, grouping separators like "1,234" are refused (the entry field
/// is raw digits and one dot, nothing else).
pub fn money_parse_minor(digits: &str, code: &str) -> Result<i64, CoreError> {
    let currency = Currency::parse(code)?;
    let exp = currency.exponent() as usize;
    let text = digits.trim();
    if text.is_empty() {
        return Err(CoreError::InvalidInput("empty amount".to_string()));
    }
    let (negative, unsigned) = match text.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, text.strip_prefix('+').unwrap_or(text)),
    };
    if unsigned.is_empty() {
        return Err(CoreError::InvalidInput("no digits in amount".to_string()));
    }
    let (int_part, frac_part) = match unsigned.split_once('.') {
        Some((i, f)) => (i, f),
        None => (unsigned, ""),
    };
    if (!int_part.is_empty() && !int_part.bytes().all(|b| b.is_ascii_digit()))
        || (!frac_part.is_empty() && !frac_part.bytes().all(|b| b.is_ascii_digit()))
    {
        return Err(CoreError::InvalidInput(format!(
            "amount must be digits and one dot: {digits:?}"
        )));
    }
    if frac_part.len() > exp {
        return Err(CoreError::InvalidInput(format!(
            "{code} has {exp} decimal places; {digits:?} has {}",
            frac_part.len()
        )));
    }
    let int_value: i128 = if int_part.is_empty() {
        0
    } else {
        int_part.parse::<i128>().map_err(|_| CoreError::Overflow)?
    };
    let mut padded = frac_part.to_string();
    while padded.len() < exp {
        padded.push('0');
    }
    let frac_value: i128 = if padded.is_empty() {
        0
    } else {
        padded.parse::<i128>().map_err(|_| CoreError::Overflow)?
    };
    let value = int_value
        .checked_mul(10i128.pow(exp as u32))
        .and_then(|v| v.checked_add(frac_value))
        .ok_or(CoreError::Overflow)?;
    let signed = if negative { -value } else { value };
    narrow(signed)
}

/// D-248 (decision 184; fix wave, decision 203): parses an amount a person
/// TYPED into an entry field on a phone whose decimal mark is `mark` ("." or
/// ","; anything else is refused, the shells pass one of the two).
///
/// - On a "." phone a ',' is never a decimal mark: any ',' is refused (an
///   English keyboard's comma is a grouping separator, and "1,500" read as
///   1.500 would be a silent 1000x misread in KWD, BHD, IQD, JOD, LYD, OMR and
///   TND, the review's I1). Everything else is the strict rule.
/// - On a "," phone (de, fr, es, it, pt-BR, id...) text with exactly one ','
///   and no '.' reads the comma as the decimal mark ("12,50" is 12.50). A '.'
///   followed by exactly three digits ("1.234") is refused as ambiguous: that
///   phone groups thousands with a dot. Other dots follow the strict rule
///   ("12.50" still reads, so core's own entry forms round-trip).
///
/// Grouping ("1,234.56", "1.234,56", "1,234,567") stays refused, too many
/// decimals are refused, never rounded, and text with no digit at all ("," or
/// ".") is refused. Import keeps `money_parse_minor` itself: a CSV cell
/// "1,234" must stay refused, never become 1.234.
pub fn money_parse_typed(text: &str, code: &str, mark: &str) -> Result<i64, CoreError> {
    let comma_phone = typed_mark(mark)? == ',';
    // The strict rule reads a lone "." as 0 (older than D-248; import keeps
    // it). A field holding only a mark has no amount in it: refuse it.
    if !text.bytes().any(|b| b.is_ascii_digit()) {
        return Err(CoreError::InvalidInput("no digits in amount".to_string()));
    }
    let commas = text.bytes().filter(|b| *b == b',').count();
    if !comma_phone {
        // The strict rule refuses every ','.
        return money_parse_minor(text, code);
    }
    if commas == 1 && !text.contains('.') {
        return money_parse_minor(&text.replacen(',', ".", 1), code);
    }
    let trimmed = text.trim();
    if let Some((_, after)) = trimmed.split_once('.') {
        if after.len() == 3 && after.bytes().all(|b| b.is_ascii_digit()) {
            return Err(CoreError::InvalidInput(
                "a dot and three digits could be grouping on this phone".to_string(),
            ));
        }
    }
    money_parse_minor(text, code)
}

/// The two decimal marks the typed parser reads (decision 203).
fn typed_mark(mark: &str) -> Result<char, CoreError> {
    match mark {
        "." => Ok('.'),
        "," => Ok(','),
        _ => Err(CoreError::InvalidInput(
            "decimal mark must be '.' or ','".to_string(),
        )),
    }
}

/// D-248 fix wave (decision 203, review M3 and M6): `minor` in the form a
/// person types on a phone whose decimal mark is `mark`: the whole part, the
/// mark, and the currency's own decimals, no grouping and no symbol (EUR 1250
/// on a "," phone is "12,50"; JPY 1250 is "1250" on either; KWD 1250 is
/// "1.250" on a "." phone and "1,250" on a "," one). Built from the parts, so
/// no shell swaps a character in a formatted amount; `money_parse_typed`
/// with the same mark reads it back exactly (the prefills of Edit, Settle
/// up's Another amount and Redistribute, and the words' example).
pub fn money_typed_digits(minor: i64, code: &str, mark: &str) -> Result<String, CoreError> {
    let mark = typed_mark(mark)?;
    let exp = Currency::parse(code)?.exponent() as u32;
    let scale = 10i128.pow(exp);
    let abs = (minor as i128).abs();
    let (whole, frac) = (abs / scale, abs % scale);
    let sign = if minor < 0 { "-" } else { "" };
    Ok(if exp == 0 {
        format!("{sign}{whole}")
    } else {
        format!("{sign}{whole}{mark}{frac:0width$}", width = exp as usize)
    })
}

/// Parses a manually-entered decimal rate ("83.25") into the exact
/// rational the record freezes (8325/100). Same discipline as
/// money_parse_minor: digits and one dot, no floats, denominator capped
/// at 10^9 so a fat-fingered entry cannot explode the arithmetic.
pub fn fx_rate_parse_decimal(decimal: &str) -> Result<(i64, i64), CoreError> {
    let text = decimal.trim();
    if text.is_empty() {
        return Err(CoreError::InvalidInput("empty rate".to_string()));
    }
    let (int_part, frac_part) = match text.split_once('.') {
        Some((i, f)) => (i, f),
        None => (text, ""),
    };
    if (!int_part.is_empty() && !int_part.bytes().all(|b| b.is_ascii_digit()))
        || (!frac_part.is_empty() && !frac_part.bytes().all(|b| b.is_ascii_digit()))
    {
        return Err(CoreError::InvalidInput(format!(
            "rate must be digits and one dot: {decimal:?}"
        )));
    }
    if frac_part.len() > 9 {
        return Err(CoreError::InvalidInput(
            "rate supports at most 9 decimal places".to_string(),
        ));
    }
    let int_value: i64 = if int_part.is_empty() {
        0
    } else {
        int_part
            .parse()
            .map_err(|_| CoreError::InvalidInput("rate integer part too large".to_string()))?
    };
    let mut den: i64 = 1;
    let mut num: i64 = int_value;
    for ch in frac_part.chars() {
        den = den.checked_mul(10).ok_or(CoreError::Overflow)?;
        num = num
            .checked_mul(10)
            .and_then(|n| n.checked_add(ch.to_digit(10).unwrap_or(0) as i64))
            .ok_or(CoreError::Overflow)?;
    }
    if num <= 0 {
        return Err(CoreError::InvalidInput("rate must be positive".to_string()));
    }
    Ok((num, den))
}

/// §11.4's inline decomposition format: sign ALWAYS printed (§11.2: colour
/// is never the only carrier), no currency symbol (the micro-label carries
/// it: "SETTLED IN INR"), grouped integer, fractional digits only when
/// non-zero. Goldens pin "+2,100" / "-1,480" / "-1,240.50" / "+700".
pub fn money_format_decomposition(
    minor: i64,
    code: &str,
    locale: LocaleTag,
) -> Result<String, CoreError> {
    let money = money_make(minor, code)?;
    let exp = money.currency.exponent();
    let scale = 10i128.pow(exp as u32);
    let sign = if money.amount < 0 { "-" } else { "+" };
    let abs = money.amount.abs();
    let int_part = abs / scale;
    let frac = abs % scale;
    let grouped = format::group_integer(&int_part.to_string(), locale.into());
    Ok(if frac == 0 || exp == 0 {
        format!("{sign}{grouped}")
    } else {
        format!("{sign}{grouped}.{frac:0width$}", width = exp as usize)
    })
}

/// §5.3's Group: an id, a name, members, and a settlement currency FIXED
/// at creation (§5.2), displayed everywhere, and the currency the next
/// session freezes FX rates against. Core generates the id; persistence
/// is the shell's (same vault loop as the ledger).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupWire {
    pub id: String,
    pub name: String,
    pub settlement_currency: String,
    pub members: Vec<String>,
}

/// A manual rate override as an exact fraction (no floats).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FxRateFraction {
    pub num: i64,
    pub den: i64,
}

/// Creates a group: id generated here (16 random bytes), name and
/// settlement currency fixed for the group's life, members validated and
/// deduped.
pub fn create_group(
    name: &str,
    settlement_currency: &str,
    members: &[String],
) -> Result<GroupWire, CoreError> {
    let name = name.trim();
    if name.is_empty() {
        return Err(CoreError::InvalidInput("group needs a name".to_string()));
    }
    // D-235 (decision 110 a): a group's name keeps the free-text bound.
    crate::straumr::check::check_text(name)?;
    let currency = Currency::parse(settlement_currency)?;
    if members.is_empty() {
        return Err(CoreError::EmptyParticipants);
    }
    let mut parsed: Vec<ParticipantId> = members
        .iter()
        .map(|m| parse_participant(m))
        .collect::<Result<_, _>>()?;
    parsed.sort_unstable();
    let count = parsed.len();
    parsed.dedup();
    if parsed.len() != count {
        return Err(CoreError::DuplicateParticipant);
    }
    Ok(GroupWire {
        id: hex_encode(&lykill::random_bytes(16)),
        name: name.to_string(),
        settlement_currency: currency.code().to_string(),
        members: members.to_vec(),
    })
}

/// A group member as the shells show them (D-134). The name is a label the
/// group's creator typed ("add by name, link later"); it proves nothing about
/// who someone is. Linking a name to a person's device is a later,
/// cryptographic act (D-123's approval and key wrapping).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MemberWire {
    pub id: String,
    pub name: String,
}

/// A group with its named members (D-134). `members` lists exactly the ids of
/// `group.members`, in the same order, and `self_id` is one of them. Every
/// function here checks that on the way in, so the split and balance code
/// that reads `group.members` can never disagree with the names on screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NamedGroupWire {
    pub group: GroupWire,
    pub self_id: String,
    pub members: Vec<MemberWire>,
}

/// Longest member name, in characters rather than bytes, so names in
/// Devanagari or Tamil get the same room as Latin ones. The relay clamps
/// device labels at the same 64.
pub const MEMBER_NAME_MAX_CHARS: usize = 64;

/// Participant ids are minted in core, like group ids: 16 random bytes, hex.
pub fn new_participant_id() -> String {
    hex_encode(&lykill::random_bytes(16))
}

/// A member name as stored: trimmed, non-empty, at most
/// [`MEMBER_NAME_MAX_CHARS`] characters, and no control characters (names
/// render inside balance lines). Over-long names are refused, never cut.
pub fn member_name(raw: &str) -> Result<String, CoreError> {
    let name = raw.trim();
    if name.is_empty() {
        return Err(CoreError::InvalidInput("member needs a name".to_string()));
    }
    if name.chars().count() > MEMBER_NAME_MAX_CHARS {
        return Err(CoreError::InvalidInput(format!(
            "member name is longer than {MEMBER_NAME_MAX_CHARS} characters"
        )));
    }
    if name.chars().any(char::is_control) {
        return Err(CoreError::InvalidInput(
            "member name contains a control character".to_string(),
        ));
    }
    Ok(name.to_string())
}

/// True when a member other than `self_id` (and other than `except`) already
/// has `name`, compared case-insensitively. You may share a name with
/// someone, since "(You)" tells you apart; two OTHER members with one name is
/// a typo more often than two real people, who can be "Rahul K" and "Rahul S".
fn other_member_named(
    members: &[MemberWire],
    self_id: &str,
    except: Option<&str>,
    name: &str,
) -> bool {
    let wanted = name.to_lowercase();
    members.iter().any(|m| {
        m.id != self_id && Some(m.id.as_str()) != except && m.name.to_lowercase() == wanted
    })
}

fn duplicate_name() -> CoreError {
    CoreError::InvalidInput("another member already has that name".to_string())
}

/// Validates a named group loaded from storage (or built by a shell's
/// migration): the ids agree with `group.members` in order, `self_id` is a
/// member, every name is valid, and no two other members share a name.
pub fn named_group(
    group: GroupWire,
    self_id: &str,
    members: Vec<MemberWire>,
) -> Result<NamedGroupWire, CoreError> {
    let ids: Vec<&str> = members.iter().map(|m| m.id.as_str()).collect();
    let group_ids: Vec<&str> = group.members.iter().map(String::as_str).collect();
    if ids != group_ids {
        return Err(CoreError::InvalidInput(
            "member names do not match the group's members".to_string(),
        ));
    }
    parse_participant(self_id)?;
    if !ids.contains(&self_id) {
        return Err(CoreError::UnknownParticipant);
    }
    let mut checked: Vec<MemberWire> = Vec::with_capacity(members.len());
    for m in members {
        let name = member_name(&m.name)?;
        if m.id != self_id && other_member_named(&checked, self_id, None, &name) {
            return Err(duplicate_name());
        }
        checked.push(MemberWire { id: m.id, name });
    }
    Ok(NamedGroupWire {
        group,
        self_id: self_id.to_string(),
        members: checked,
    })
}

/// Creates a group with you first and everyone else added by name; their ids
/// are minted here. `create_group` still validates the group name, the
/// settlement currency and id uniqueness.
pub fn create_named_group(
    name: &str,
    settlement_currency: &str,
    self_member: MemberWire,
    other_names: &[String],
) -> Result<NamedGroupWire, CoreError> {
    parse_participant(&self_member.id)?;
    let mut members = vec![MemberWire {
        id: self_member.id.clone(),
        name: member_name(&self_member.name)?,
    }];
    for raw in other_names {
        let name = member_name(raw)?;
        if other_member_named(&members, &self_member.id, None, &name) {
            return Err(duplicate_name());
        }
        members.push(MemberWire {
            id: new_participant_id(),
            name,
        });
    }
    let ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
    let group = create_group(name, settlement_currency, &ids)?;
    Ok(NamedGroupWire {
        group,
        self_id: self_member.id,
        members,
    })
}

/// Adds someone by name; their id is minted here.
pub fn add_member(named: NamedGroupWire, raw_name: &str) -> Result<NamedGroupWire, CoreError> {
    let NamedGroupWire {
        mut group,
        self_id,
        mut members,
    } = named_group(named.group, &named.self_id, named.members)?;
    let name = member_name(raw_name)?;
    if other_member_named(&members, &self_id, None, &name) {
        return Err(duplicate_name());
    }
    let id = new_participant_id();
    group.members.push(id.clone());
    members.push(MemberWire { id, name });
    Ok(NamedGroupWire {
        group,
        self_id,
        members,
    })
}

/// Renames one member. Renaming yourself is exempt from the duplicate rule;
/// renaming anyone else is not.
pub fn rename_member(
    named: NamedGroupWire,
    member_id: &str,
    raw_name: &str,
) -> Result<NamedGroupWire, CoreError> {
    let NamedGroupWire {
        group,
        self_id,
        mut members,
    } = named_group(named.group, &named.self_id, named.members)?;
    let index = members
        .iter()
        .position(|m| m.id == member_id)
        .ok_or(CoreError::UnknownParticipant)?;
    let name = member_name(raw_name)?;
    if member_id != self_id && other_member_named(&members, &self_id, Some(member_id), &name) {
        return Err(duplicate_name());
    }
    members[index].name = name;
    Ok(NamedGroupWire {
        group,
        self_id,
        members,
    })
}

/// §11.4's balance line for ONE viewer. `entries` are the OTHER
/// participants in the viewer's frame of reference (positive means money
/// comes TO you, negative means you owe), taken from the pairwise
/// who-owes-whom matrix, which is correct even in multi-payer groups where
/// negating group nets would not be. Since D-218 the matrix has its
/// zero-net loops cancelled (norn::cancel_cycles), so an entry never shows a
/// debt that only goes round in a circle; every net is unchanged. The
/// worked example in §11.4 ("you are owed ₹4,280.00" with RAHUL +2,100 /
/// PRIYA +1,480 / AMIT +700) is a verbatim golden for this function.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BalanceLineOut {
    pub currency_code: String,
    pub viewer_net_minor: i64,
    pub entries: Vec<ShareOut>,
    /// §5.8's plan: at most n−1 transfers that settle every net position
    /// (norn::simplify, deterministic tie-breaks). The settle-up screen
    /// renders this; it never computes amounts itself.
    pub transfers: Vec<TransferOut>,
}

/// Viewer-framed balance lines, one per currency present. `removed` lists
/// the people removed from `group` (D-210): like a redistributed person, one
/// at 0 in a currency is collapsed out of that currency's pairwise views. A
/// personal line (no group) does not read it; a malformed id is refused.
pub fn balance_line(
    expenses: &[ExpenseInput],
    settlements: &[SettlementInput],
    redistributions: &[RedistributionInput],
    removed: &[String],
    viewer: &str,
    group: Option<&str>,
) -> Result<Vec<BalanceLineOut>, CoreError> {
    let viewer_id = parse_participant(viewer)?;
    let group_id = match group {
        None => None,
        Some(hex) => Some(parse_participant(hex)?),
    };
    // D-210: only this line's group's redistributions are read at all (a
    // personal line has none), so one malformed record in another group
    // cannot fail this group's line or the overview built from it.
    let redistributions: Vec<RedistributionInput> = redistributions
        .iter()
        .filter(|r| match group_id {
            Some(id) => parse_participant(&r.group)
                .map(|g| g == id)
                .unwrap_or(false),
            None => false,
        })
        .cloned()
        .collect();
    let redistributions = redistributions.as_slice();
    let mut settlements = settlements.to_vec();
    settlements.extend(redistribution_settlements(redistributions)?);
    // §5.3 scoping: a group line counts only that group's records;
    // personal records (group None) are excluded from it.
    let scoped_expenses: Vec<ExpenseInput> = expenses
        .iter()
        .filter(|e| match (&e.group, group_id) {
            (Some(g), Some(id)) => parse_participant(g).map(|raw| raw == id).unwrap_or(false),
            (None, None) => true,
            _ => false,
        })
        .cloned()
        .collect();
    let scoped_settlements: Vec<SettlementInput> = settlements
        .iter()
        .filter(|x| match (&x.group, group_id) {
            (Some(g), Some(id)) => parse_participant(g).map(|raw| raw == id).unwrap_or(false),
            (None, None) => true,
            _ => false,
        })
        .cloned()
        .collect();
    let ledger = build_ledger(&scoped_expenses, &scoped_settlements)?;
    let bal = crate::norn::balances(&ledger);
    let mut pairwise = crate::norn::pairwise(&ledger);
    // D-210: a person whose balance was redistributed, or who was removed
    // from the group, and whose net in a currency is 0 is taken out of that
    // currency's pairwise views, so no card says a removed person owes or is
    // owed. Scoped like their settlements; a person with a balance (records
    // after the redistribution, or a removal with money still to move) stays
    // in. Each person is collapsed once, in ascending id order, so the
    // result never depends on the order the lists arrive in.
    if group_id.is_some() {
        let mut gone: std::collections::BTreeSet<(String, ParticipantId)> =
            std::collections::BTreeSet::new();
        // Already filtered to this line's group above.
        for r in redistributions {
            gone.insert((r.currency_code.clone(), parse_participant(&r.from)?));
        }
        let currencies: Vec<String> = pairwise.keys().cloned().collect();
        for hex in removed {
            let person = parse_participant(hex)?;
            for code in &currencies {
                gone.insert((code.clone(), person));
            }
        }
        for (code, person) in gone {
            let net = bal.net.get(&(code.clone(), person)).copied().unwrap_or(0);
            if net != 0 {
                continue;
            }
            if let Some(matrix) = pairwise.get_mut(&code) {
                // Net 0 was just established and the matrix nets equal the
                // balances (norn::pairwise, kept by every collapse), so the
                // person's pairwise debts and credits are equal and collapse
                // cannot refuse.
                let collapsed = crate::norn::collapse(matrix, person);
                debug_assert!(collapsed, "a net-0 person always collapses");
            }
        }
    }

    // D-218: a directed loop in the pairwise view (A owes B, B owes C, C
    // owes A, as after a Settle all that skips a middle person) is nobody's
    // debt. Each is cancelled by its smallest edge, after the collapse above
    // (so a removed person's debts are routed first). Nets, the plan and
    // totals are unchanged, and no pair appears that was not already there.
    for matrix in pairwise.values_mut() {
        crate::norn::cancel_cycles(matrix);
    }

    let mut currencies: Vec<String> = bal
        .net
        .keys()
        .map(|(c, _)| c.clone())
        .chain(pairwise.keys().cloned())
        .collect();
    currencies.sort();
    currencies.dedup();

    let mut out = Vec::with_capacity(currencies.len());
    for code in currencies {
        let viewer_net = bal
            .net
            .get(&(code.clone(), viewer_id))
            .copied()
            .unwrap_or(0);
        let matrix = pairwise.get(&code);

        // Everyone the viewer touches in this currency.
        let mut others: std::collections::BTreeSet<ParticipantId> =
            std::collections::BTreeSet::new();
        for ((_, p), _) in bal.net.iter().filter(|((c, _), _)| *c == code) {
            if *p != viewer_id {
                others.insert(*p);
            }
        }
        if let Some(matrix) = matrix {
            for (a, b) in matrix.keys() {
                if *a == viewer_id {
                    others.insert(*b);
                }
                if *b == viewer_id {
                    others.insert(*a);
                }
            }
        }

        let mut entries: Vec<ShareOut> = others
            .into_iter()
            .map(|p| {
                // Positive = p owes the viewer: money coming to you. Both
                // directions are read, though norn::pairwise and
                // norn::collapse leave at most one per pair.
                let they_owe = matrix
                    .and_then(|m| m.get(&(p, viewer_id)))
                    .copied()
                    .unwrap_or(0);
                let you_owe = matrix
                    .and_then(|m| m.get(&(viewer_id, p)))
                    .copied()
                    .unwrap_or(0);
                Ok(ShareOut {
                    id: hex_encode(&p),
                    minor_units: narrow_sum(they_owe - you_owe)?,
                })
            })
            .collect::<Result<Vec<_>, CoreError>>()?;
        entries.retain(|e| e.minor_units != 0);
        entries.sort_by(|a, b| {
            b.minor_units
                .abs()
                .cmp(&a.minor_units.abs())
                .then(a.id.cmp(&b.id))
        });

        let net_pairs: Vec<(ParticipantId, i128)> = bal
            .net
            .iter()
            .filter(|((c, _), _)| *c == code)
            .map(|((_, p), a)| (*p, *a))
            .collect();
        let transfers: Vec<TransferOut> = crate::norn::simplify(&net_pairs)
            .into_iter()
            .map(|t| {
                Ok(TransferOut {
                    from: hex_encode(&t.from),
                    to: hex_encode(&t.to),
                    amount_minor: narrow_sum(t.amount)?,
                })
            })
            .collect::<Result<Vec<_>, CoreError>>()?;

        out.push(BalanceLineOut {
            currency_code: code,
            viewer_net_minor: narrow_sum(viewer_net)?,
            entries,
            transfers,
        });
    }
    Ok(out)
}

/// D-221: how much of one group's money has moved, per currency (the
/// Settled card). `settled_minor` is the sum of the group's settlements in
/// that currency (a redistribution moves balances but is not a payment);
/// `outstanding_minor` is the sum of every member's positive net, the same
/// ledger `balance_line` builds with the group's redistributions applied;
/// `basis_points` is floor(settled x 10000 / (settled + outstanding)), 10000
/// when both are 0; `settled` is outstanding == 0. Integer only (ADR-0002).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SettledProgressOut {
    pub currency_code: String,
    pub settled_minor: i64,
    pub outstanding_minor: i64,
    pub basis_points: u32,
    pub settled: bool,
}

/// D-221: settled progress per currency of `group`, sorted by code. A
/// currency appears when the group has an expense or a settlement in it.
/// Scoped as `balance_line` scopes a group line: only that group's
/// expenses, settlements and redistributions are read; a malformed group id
/// is refused. `basis_points` is always within 0..=10000, even for a
/// (never expected) negative settlement amount.
pub fn settled_progress(
    expenses: &[ExpenseInput],
    settlements: &[SettlementInput],
    redistributions: &[RedistributionInput],
    group: &str,
) -> Result<Vec<SettledProgressOut>, CoreError> {
    let group_id = parse_participant(group)?;
    let in_group = |g: &str| {
        parse_participant(g)
            .map(|raw| raw == group_id)
            .unwrap_or(false)
    };
    let scoped_expenses: Vec<ExpenseInput> = expenses
        .iter()
        .filter(|e| e.group.as_deref().is_some_and(in_group))
        .cloned()
        .collect();
    let mut scoped_settlements: Vec<SettlementInput> = settlements
        .iter()
        .filter(|s| s.group.as_deref().is_some_and(in_group))
        .cloned()
        .collect();
    // The payments end here; what follows is the group's redistributions,
    // which move balances but are not money anybody paid.
    let paid_count = scoped_settlements.len();
    let redistributions: Vec<RedistributionInput> = redistributions
        .iter()
        .filter(|r| in_group(&r.group))
        .cloned()
        .collect();
    scoped_settlements.extend(redistribution_settlements(&redistributions)?);
    let ledger = build_ledger(&scoped_expenses, &scoped_settlements)?;
    let bal = crate::norn::balances(&ledger);

    // Currency -> settled so far. A currency is listed when the group has an
    // expense (in its effective, possibly converted, currency) or a payment
    // in it; a redistribution alone never adds one.
    let mut paid: std::collections::BTreeMap<String, i128> = std::collections::BTreeMap::new();
    for e in &ledger.expenses {
        paid.entry(e.effective().currency.code().to_string())
            .or_insert(0);
    }
    for s in &ledger.settlements[..paid_count] {
        *paid
            .entry(s.amount.currency.code().to_string())
            .or_insert(0) += s.amount.amount;
    }

    let mut out = Vec::with_capacity(paid.len());
    for (code, settled) in paid {
        let outstanding: i128 = bal
            .net
            .iter()
            .filter(|((c, _), v)| *c == code && **v > 0)
            .map(|(_, v)| *v)
            .sum();
        let whole = settled + outstanding;
        let basis_points = if whole == 0 {
            10_000
        } else {
            // A negative settlement (a bad record: money_make takes any i64)
            // would leave 0..=10000, so the meter is clamped.
            (settled * 10_000).div_euclid(whole).clamp(0, 10_000)
        };
        out.push(SettledProgressOut {
            currency_code: code,
            settled_minor: narrow_sum(settled)?,
            outstanding_minor: narrow_sum(outstanding)?,
            basis_points: u32::try_from(basis_points).map_err(|_| CoreError::Overflow)?,
            settled: outstanding == 0,
        });
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// Spoken money (§11.7): screen readers must announce "minus one thousand two
// hundred and forty rupees", never "dash one two four zero". The spoken form
// is a FORMAT: it belongs in core, pinned by goldens, not in a shell.
// ---------------------------------------------------------------------------

const ONES: [&str; 20] = [
    "zero",
    "one",
    "two",
    "three",
    "four",
    "five",
    "six",
    "seven",
    "eight",
    "nine",
    "ten",
    "eleven",
    "twelve",
    "thirteen",
    "fourteen",
    "fifteen",
    "sixteen",
    "seventeen",
    "eighteen",
    "nineteen",
];
const TENS: [&str; 10] = [
    "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
];
/// D-235 (core audit L2): up to quintillion, so every i64 amount has words
/// (i64::MAX is about 9.2 quintillion). With billion the largest, a group
/// past 999 billion reached `words_under_1000` and indexed `ONES` out of
/// bounds: a panic, an empty VoiceOver label.
const SCALES: [(&str, u64); 6] = [
    ("quintillion", 1_000_000_000_000_000_000),
    ("quadrillion", 1_000_000_000_000_000),
    ("trillion", 1_000_000_000_000),
    ("billion", 1_000_000_000),
    ("million", 1_000_000),
    ("thousand", 1_000),
];

fn words_under_1000(n: u64) -> String {
    debug_assert!(n < 1000);
    let hundreds = n / 100;
    let rest = n % 100;
    let mut parts: Vec<String> = Vec::new();
    if hundreds > 0 {
        parts.push(ONES[hundreds as usize].to_string());
        parts.push("hundred".to_string());
    }
    if rest > 0 {
        if hundreds > 0 {
            parts.push("and".to_string());
        }
        if rest < 20 {
            parts.push(ONES[rest as usize].to_string());
        } else {
            let t = TENS[(rest / 10) as usize];
            let o = rest % 10;
            parts.push(if o == 0 {
                t.to_string()
            } else {
                format!("{t}-{}", ONES[o as usize])
            });
        }
    }
    parts.join(" ")
}

fn words(n: u64) -> String {
    if n == 0 {
        return "zero".to_string();
    }
    let mut parts: Vec<String> = Vec::new();
    let mut rest = n;
    for (name, value) in SCALES {
        let group = rest / value;
        if group > 0 {
            parts.push(format!("{} {name}", words_under_1000(group)));
            rest %= value;
        }
    }
    if rest > 0 {
        // British "and" before a trailing group under one hundred, but only
        // when a higher group precedes it: "one thousand and one", never
        // "and twelve" for a bare 12.
        if !parts.is_empty() && rest < 100 {
            parts.push("and".to_string());
        }
        parts.push(words_under_1000(rest));
    }
    parts.join(" ")
}

/// (major singular, major plural, subunit singular, subunit plural).
/// Curated for the currencies the shells will meet first; anything else
/// falls back to its ISO code spoken as-is (VoiceOver reads short uppercase
/// strings letter-by-letter), with fractions as "point <digits>". D-46.
fn currency_names(code: &str) -> Option<(&'static str, &'static str, &'static str, &'static str)> {
    match code {
        "INR" => Some(("rupee", "rupees", "paisa", "paise")),
        "USD" | "CAD" | "AUD" | "NZD" | "SGD" | "HKD" => {
            Some(("dollar", "dollars", "cent", "cents"))
        }
        "EUR" => Some(("euro", "euros", "cent", "cents")),
        "GBP" => Some(("pound", "pounds", "penny", "pence")),
        "CHF" => Some(("franc", "francs", "centime", "centimes")),
        "JPY" => Some(("yen", "yen", "yen", "yen")),
        "KWD" | "BHD" => Some(("dinar", "dinars", "fulus", "fulus")),
        _ => None,
    }
}

fn spoken_digits(frac: &str) -> String {
    frac.chars()
        .map(|d| ONES[d.to_digit(10).unwrap_or(0) as usize])
        .collect::<Vec<_>>()
        .join(" ")
}

/// The VoiceOver form of an amount (§11.7): "minus one thousand two hundred
/// and forty rupees", "twelve rupees and forty paise", "one hundred yen".
/// Zero-fraction amounts name no subunit; known currencies get proper
/// major/subunit names with singular/plural, unknown ones fall back to
/// their ISO code with "point <digits>" fractions.
pub fn money_spoken(minor: i64, code: &str, _locale: LocaleTag) -> Result<String, CoreError> {
    let money = money_make(minor, code)?;
    let exp = money.currency.exponent();
    let scale = 10i128.pow(exp as u32);
    let sign = if money.amount < 0 { "minus " } else { "" };
    let abs = money.amount.abs();
    let int_part = abs / scale;
    let frac = abs % scale; // already counts subunits

    let body = match currency_names(money.currency.code()) {
        Some((maj_s, maj_p, sub_s, sub_p)) => {
            if int_part == 0 && frac > 0 {
                // Subunit-only: ₹0.01 is "one paisa", not "zero rupees and
                // one paisa".
                let sub = if frac == 1 { sub_s } else { sub_p };
                format!("{} {sub}", words(frac as u64))
            } else if frac == 0 {
                let major = if int_part == 1 { maj_s } else { maj_p };
                format!("{} {major}", words(int_part as u64))
            } else {
                let major = if int_part == 1 { maj_s } else { maj_p };
                let sub = if frac == 1 { sub_s } else { sub_p };
                format!(
                    "{} {major} and {} {sub}",
                    words(int_part as u64),
                    words(frac as u64)
                )
            }
        }
        None => {
            if frac == 0 || exp == 0 {
                format!("{} {code}", words(int_part as u64))
            } else {
                let frac_str = format!("{frac:0width$}", width = exp as usize);
                format!(
                    "{} {code} point {}",
                    words(int_part as u64),
                    spoken_digits(&frac_str)
                )
            }
        }
    };
    Ok(format!("{sign}{body}"))
}

fn to_core_spec(spec: &SplitSpecInput) -> Result<SplitSpec, CoreError> {
    Ok(match spec {
        SplitSpecInput::Equal => SplitSpec::Equal,
        SplitSpecInput::Exact { entries } => SplitSpec::Exact(
            entries
                .iter()
                .map(|e| Ok((parse_participant(&e.id)?, e.amount_minor as i128)))
                .collect::<Result<Vec<_>, CoreError>>()?,
        ),
        SplitSpecInput::Percentage { entries } => SplitSpec::Percentage(
            entries
                .iter()
                .map(|e| Ok((parse_participant(&e.id)?, e.bps as u128)))
                .collect::<Result<Vec<_>, CoreError>>()?,
        ),
        SplitSpecInput::Shares { entries } => SplitSpec::Shares(
            entries
                .iter()
                .map(|e| Ok((parse_participant(&e.id)?, e.weight)))
                .collect::<Result<Vec<_>, CoreError>>()?,
        ),
        SplitSpecInput::Adjustment { entries } => SplitSpec::Adjustment(
            entries
                .iter()
                .map(|e| Ok((parse_participant(&e.id)?, e.amount_minor as i128)))
                .collect::<Result<Vec<_>, CoreError>>()?,
        ),
        SplitSpecInput::Itemised {
            lines,
            tax_minor,
            tip_minor,
        } => SplitSpec::Itemised {
            lines: lines
                .iter()
                .map(|l| {
                    Ok(ItemLine {
                        amount: l.amount_minor as i128,
                        participants: l
                            .participants
                            .iter()
                            .map(|s| parse_participant(s))
                            .collect::<Result<Vec<_>, CoreError>>()?,
                    })
                })
                .collect::<Result<Vec<_>, CoreError>>()?,
            tax: *tax_minor as i128,
            tip: *tip_minor as i128,
        },
        SplitSpecInput::PayerOnly { payer } => SplitSpec::PayerOnly(parse_participant(payer)?),
    })
}

/// Computes one expense's split against the roster. Pure, deterministic,
/// and identical on every platform (largest-remainder ties break by id).
pub fn split_compute(
    total_minor: i64,
    currency_code: &str,
    members: &[String],
    spec: &SplitSpecInput,
) -> Result<Vec<ShareOut>, CoreError> {
    let total = money_make(total_minor, currency_code)?;
    let roster: Vec<ParticipantId> = members
        .iter()
        .map(|s| parse_participant(s))
        .collect::<Result<_, _>>()?;
    let result = hlutr::compute(&to_core_spec(spec)?, total, &roster)?;
    result
        .shares
        .into_iter()
        .map(|(id, amount)| {
            Ok(ShareOut {
                id: hex_encode(&id),
                minor_units: narrow(amount)?,
            })
        })
        .collect()
}

fn build_ledger(
    expenses: &[ExpenseInput],
    settlements: &[SettlementInput],
) -> Result<crate::norn::Ledger, CoreError> {
    use crate::norn::{ExpenseRecord, Ledger, SettlementRecord};
    let mut ledger = Ledger::default();
    // Internal record ids are synthetic and unique so norn's
    // latest-version selection sees every entry the shell passed in.
    // Every entry validates: nothing is silently dropped at the boundary.
    for (n, e) in expenses.iter().enumerate() {
        let mut id = [0u8; 16];
        id[..8].copy_from_slice(&(n as u64 + 1).to_be_bytes());
        let group = match &e.group {
            None => None,
            Some(hex) => Some(parse_participant(hex)?),
        };
        let conversion = match &e.conversion {
            None => None,
            Some(c) => {
                let rate = crate::fx::FxRate::new(
                    money_make(e.paid_minor, &e.currency_code)?.currency,
                    Currency::parse(&c.converted_currency)?,
                    c.rate_num as i128,
                    c.rate_den as i128,
                    c.rate_timestamp_ms,
                )
                .map_err(CoreError::from)?;
                Some(crate::fx::convert(
                    money_make(e.paid_minor, &e.currency_code)?,
                    rate,
                )?)
            }
        };
        ledger.expenses.push(ExpenseRecord {
            id,
            version: 1,
            group,
            payer: parse_participant(&e.payer)?,
            paid: money_make(e.paid_minor, &e.currency_code)?,
            conversion,
            shares: e
                .shares
                .iter()
                .map(|s| Ok((parse_participant(&s.id)?, s.amount_minor as i128)))
                .collect::<Result<Vec<_>, CoreError>>()?,
            timestamp_ms: 0,
        });
    }
    for (n, s) in settlements.iter().enumerate() {
        let mut id = [0u8; 16];
        id[..8].copy_from_slice(&(n as u64 + 1).to_be_bytes());
        let settlement_group = match &s.group {
            None => [0u8; 16],
            Some(hex) => parse_participant(hex)?,
        };
        ledger.settlements.push(SettlementRecord {
            id,
            group: settlement_group,
            from: parse_participant(&s.from)?,
            to: parse_participant(&s.to)?,
            amount: money_make(s.amount_minor, &s.currency_code)?,
            timestamp_ms: 0,
        });
    }
    Ok(ledger)
}

/// §11.4's balance line: per-currency net positions sorted by magnitude
/// descending (ties by participant id ascending) with simplification
/// transfers. FX conversion is deliberately absent: single-currency
/// balances for now (ADR-0025 §4).
pub fn balances_compute(
    expenses: &[ExpenseInput],
    settlements: &[SettlementInput],
    redistributions: &[RedistributionInput],
) -> Result<Vec<CurrencyBalanceOut>, CoreError> {
    let mut settlements = settlements.to_vec();
    settlements.extend(redistribution_settlements(redistributions)?);
    let ledger = build_ledger(expenses, &settlements)?;
    let bal = crate::norn::balances(&ledger);
    let mut currencies: Vec<String> = bal.net.keys().map(|(c, _)| c.clone()).collect();
    currencies.sort();
    currencies.dedup();

    let mut out = Vec::with_capacity(currencies.len());
    for code in currencies {
        let mut pairs: Vec<(ParticipantId, i128)> = bal
            .net
            .iter()
            .filter(|((c, _), _)| *c == code)
            .map(|((_, p), a)| (*p, *a))
            .collect();
        // Magnitude descending, ties by ascending id: the shape the UI
        // renders, fixed HERE so no shell re-sorts differently.
        pairs.sort_by(|a, b| b.1.abs().cmp(&a.1.abs()).then(a.0.cmp(&b.0)));
        let entries: Vec<ShareOut> = pairs
            .iter()
            .map(|(id, amt)| {
                Ok(ShareOut {
                    id: hex_encode(id),
                    minor_units: narrow(*amt)?,
                })
            })
            .collect::<Result<Vec<_>, CoreError>>()?;
        let transfers: Vec<TransferOut> = crate::norn::simplify(&pairs)
            .into_iter()
            .map(|t| {
                Ok(TransferOut {
                    from: hex_encode(&t.from),
                    to: hex_encode(&t.to),
                    amount_minor: narrow(t.amount)?,
                })
            })
            .collect::<Result<Vec<_>, CoreError>>()?;
        out.push(CurrencyBalanceOut {
            currency_code: code,
            entries,
            transfers,
        });
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// UniFFI surface: annotations over the shared layer, nothing more
// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn skipti_core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[uniffi::export]
pub fn ffi_money_format(
    minor_units: i64,
    currency_code: String,
    locale: LocaleTag,
) -> Result<String, CoreError> {
    money_format(minor_units, &currency_code, locale)
}

#[uniffi::export]
pub fn ffi_money_format_decomposition(
    minor_units: i64,
    currency_code: String,
    locale: LocaleTag,
) -> Result<String, CoreError> {
    money_format_decomposition(minor_units, &currency_code, locale)
}

#[uniffi::export]
pub fn ffi_settlement_payload(
    from: String,
    to: String,
    amount_minor: i64,
    currency_code: String,
    timestamp_ms: u64,
    group: Option<String>,
) -> Result<Vec<u8>, CoreError> {
    settlement_payload(
        &from,
        &to,
        amount_minor,
        &currency_code,
        timestamp_ms,
        group.as_deref(),
    )
}

/// See `sync_key_generate`: 36 random bytes born in core (iOS's device id source).
#[uniffi::export]
pub fn ffi_sync_key_generate() -> Vec<u8> {
    sync_key_generate()
}

/// ADR-0034: the device's next HLC stamp, core's tick rule, the one the
/// convergence gate legs prove, applied to the shell's persisted state.
/// The device id rides in the RECORD header; the tick math needs only the
/// wall clock and the state. No artificial latency anywhere: `counter`
/// gives the total order within one millisecond (ADR-0034, rule 1).
#[derive(uniffi::Record)]
pub struct HlcStamp {
    pub millis: u64,
    pub counter: u32,
}

#[uniffi::export]
pub fn ffi_hlc_next(wall_millis: u64, state_millis: u64, state_counter: u32) -> HlcStamp {
    let mut hlc = crate::straumr::Hlc {
        millis: state_millis,
        counter: state_counter,
        device_id: [0u8; 16],
    };
    // D-237 (the Fable audit, A2): each stamp steps the counter by a random
    // amount (`straumr::random_step`), so a co-member who moved this phone's
    // clock cannot predict its next record ids. A plain comment, not a doc
    // comment: UniFFI 0.32 hashes an export's docstring into its checksum
    // (CLAUDE.md 8, D-228), and the bindings stay byte-identical.
    hlc.tick_by(wall_millis, crate::straumr::random_step());
    HlcStamp {
        millis: hlc.millis,
        counter: hlc.counter,
    }
}

/// ADR-0034: merge a REMOTE record's HLC into the device's state on pull,
/// so the device's own next stamps sort after everything it has seen:
/// the receive rule core proves, applied at the shell boundary.
#[uniffi::export]
pub fn ffi_hlc_receive(
    state_millis: u64,
    state_counter: u32,
    other_millis: u64,
    other_counter: u32,
    wall_millis: u64,
) -> HlcStamp {
    let mut hlc = crate::straumr::Hlc {
        millis: state_millis,
        counter: state_counter,
        device_id: [0u8; 16],
    };
    let other = crate::straumr::Hlc {
        millis: other_millis,
        counter: other_counter,
        device_id: [0u8; 16],
    };
    hlc.receive(&other, wall_millis);
    HlcStamp {
        millis: hlc.millis,
        counter: hlc.counter,
    }
}

#[uniffi::export]
pub fn ffi_fx_rate_set_parse(
    payload_utf8: String,
    signature_hex: String,
    public_key_hex: String,
    now_ms: u64,
) -> Result<FxRateSetWire, CoreError> {
    fx_rate_set_parse(&payload_utf8, &signature_hex, &public_key_hex, now_ms)
}

#[uniffi::export]
pub fn ffi_fx_rate_parse_decimal(decimal: String) -> Result<FxRateFraction, CoreError> {
    let (num, den) = fx_rate_parse_decimal(&decimal)?;
    Ok(FxRateFraction { num, den })
}

#[uniffi::export]
pub fn ffi_create_group(
    name: String,
    settlement_currency: String,
    members: Vec<String>,
) -> Result<GroupWire, CoreError> {
    create_group(&name, &settlement_currency, &members)
}

#[uniffi::export]
pub fn ffi_new_participant_id() -> String {
    new_participant_id()
}

#[uniffi::export]
pub fn ffi_member_name(raw: String) -> Result<String, CoreError> {
    member_name(&raw)
}

#[uniffi::export]
pub fn ffi_named_group(
    group: GroupWire,
    self_id: String,
    members: Vec<MemberWire>,
) -> Result<NamedGroupWire, CoreError> {
    named_group(group, &self_id, members)
}

#[uniffi::export]
pub fn ffi_create_named_group(
    name: String,
    settlement_currency: String,
    self_member: MemberWire,
    other_names: Vec<String>,
) -> Result<NamedGroupWire, CoreError> {
    create_named_group(&name, &settlement_currency, self_member, &other_names)
}

#[uniffi::export]
pub fn ffi_add_member(named: NamedGroupWire, name: String) -> Result<NamedGroupWire, CoreError> {
    add_member(named, &name)
}

#[uniffi::export]
pub fn ffi_rename_member(
    named: NamedGroupWire,
    member_id: String,
    name: String,
) -> Result<NamedGroupWire, CoreError> {
    rename_member(named, &member_id, &name)
}

/// §5.2's entry-time conversion: original minor units × rational rate,
/// rounded half-up on magnitude by core. The rate crosses as an exact
/// fraction (no floats, ADR-0002) and is what gets FROZEN into the record.
#[uniffi::export]
pub fn ffi_convert_minor(
    original_minor: i64,
    from_code: String,
    to_code: String,
    rate_num: i64,
    rate_den: i64,
) -> Result<i64, CoreError> {
    convert_minor(original_minor, &from_code, &to_code, rate_num, rate_den)
}

#[uniffi::export]
pub fn ffi_money_parse_minor(digits: String, currency_code: String) -> Result<i64, CoreError> {
    money_parse_minor(&digits, &currency_code)
}

/// D-248 (decisions 184 and 203): THE parser for every amount a person types
/// (Add, Edit, exact and itemised rows, tax and tip, Settle up's other
/// amount, removal's settle-first), given the phone's decimal mark ("." or
/// ","): a ',' is the decimal mark only on a "," phone. Import never calls it
/// (see `money_parse_typed`).
#[uniffi::export]
pub fn ffi_money_parse_typed(
    text: String,
    currency_code: String,
    decimal_mark: String,
) -> Result<i64, CoreError> {
    money_parse_typed(&text, &currency_code, &decimal_mark)
}

/// D-248 fix wave (decision 203): minor units as a person types them on a
/// phone whose decimal mark is `decimal_mark` (see `money_typed_digits`):
/// what an entry field starts from, and the example in its words.
#[uniffi::export]
pub fn ffi_money_typed_digits(
    minor_units: i64,
    currency_code: String,
    decimal_mark: String,
) -> Result<String, CoreError> {
    money_typed_digits(minor_units, &currency_code, &decimal_mark)
}

/// D-165: every currency code core accepts, sorted: the shells' "All
/// currencies" list (SPEC §5.2), so the picker offers exactly what can be
/// stored.
#[uniffi::export]
pub fn ffi_currency_codes() -> Vec<String> {
    crate::money::all_codes()
        .into_iter()
        .map(str::to_string)
        .collect()
}

/// D-142: minor units as the plain decimal an amount FIELD takes back
/// (INR 123400 → "1234.00", JPY 1005 → "1005", KWD 1005 → "1.005"): the
/// inverse of `ffi_money_parse_minor`, so an edit form starts from the
/// stored amount without the shell doing money arithmetic (ADR-0002). No
/// grouping and no symbol, because the entry parser refuses both.
#[uniffi::export]
pub fn ffi_money_input_digits(
    minor_units: i64,
    currency_code: String,
) -> Result<String, CoreError> {
    let currency = Currency::parse(&currency_code)?;
    Ok(crate::flutningr::minor_to_plain_decimal(
        minor_units,
        currency.exponent(),
    ))
}

#[uniffi::export]
pub fn ffi_money_spoken(
    minor_units: i64,
    currency_code: String,
    locale: LocaleTag,
) -> Result<String, CoreError> {
    money_spoken(minor_units, &currency_code, locale)
}

#[uniffi::export]
pub fn ffi_split_compute(
    total_minor: i64,
    currency_code: String,
    members: Vec<String>,
    spec: SplitSpecInput,
) -> Result<Vec<ShareOut>, CoreError> {
    split_compute(total_minor, &currency_code, &members, &spec)
}

#[uniffi::export]
pub fn ffi_balances_compute(
    expenses: Vec<ExpenseInput>,
    settlements: Vec<SettlementInput>,
    redistributions: Vec<RedistributionInput>,
) -> Result<Vec<CurrencyBalanceOut>, CoreError> {
    balances_compute(&expenses, &settlements, &redistributions)
}

// ---------------------------------------------------------------------------
// The §5.2 reference-rate set: a DAILY SIGNED STATIC FILE, cached 24 h,
// working OFFLINE against the last known set. No per-transaction API call:
// that would leak spending patterns to an FX provider. The fetch boundary
// is the shell's (stubbed this session); core owns the FORMAT, the Ed25519
// verification, and the staleness model.
//
// File format (line-oriented, canonical-by-construction: the signed bytes
// ARE the payload bytes, so no JSON dependency and no canonicalisation
// question):
//
//   FX1
//   timestamp_ms=1724400000000
//   USD INR 8325 100
//   EUR INR 9012 100
//
// Rate semantics: 1 unit of FROM = num/den units of TO. The signature is
// Ed25519 over the exact payload bytes, from the operator's pinned key.
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FxRateEntryWire {
    pub from: String,
    pub to: String,
    pub num: i64,
    pub den: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FxRateSetWire {
    pub timestamp_ms: u64,
    /// now_ms − timestamp_ms (saturating).
    pub age_ms: u64,
    /// Older than the 24 h cache window (§5.2): the UI shows the
    /// staleness indicator (WATCH per §11.2) when true.
    pub stale: bool,
    pub rates: Vec<FxRateEntryWire>,
}

const FX_STALE_AFTER_MS: u64 = 24 * 60 * 60 * 1000;

/// Parses and VERIFIES a signed rate set. The Ed25519 signature must match
/// `payload_utf8` under the operator's pinned public key: an unverified
/// rate file is never returned, so a compromised fetch cannot re-price a
/// ledger. `now_ms` drives the staleness model (24 h, §5.2).
pub fn fx_rate_set_parse(
    payload_utf8: &str,
    signature_hex: &str,
    public_key_hex: &str,
    now_ms: u64,
) -> Result<FxRateSetWire, CoreError> {
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};

    let raw_key = hex_decode_32(public_key_hex)?;
    let vk = VerifyingKey::from_bytes(&raw_key)
        .map_err(|_| CoreError::InvalidInput("public key must be 32 bytes".to_string()))?;
    let sig_bytes = hex_decode_fixed::<64>(signature_hex)?;
    let sig = Signature::from_bytes(&sig_bytes);
    if !vk.verify(payload_utf8.as_bytes(), &sig).is_ok() {
        return Err(CoreError::InvalidInput(
            "rate set signature does not verify".to_string(),
        ));
    }

    let mut lines = payload_utf8.lines();
    if lines.next() != Some("FX1") {
        return Err(CoreError::InvalidInput(
            "rate set must start with FX1".to_string(),
        ));
    }
    let mut timestamp_ms: Option<u64> = None;
    let mut rates: Vec<FxRateEntryWire> = Vec::new();
    for line in lines {
        if line.trim().is_empty() {
            continue;
        }
        if let Some(ts) = line.strip_prefix("timestamp_ms=") {
            timestamp_ms = Some(
                ts.parse::<u64>()
                    .map_err(|_| CoreError::InvalidInput("bad timestamp_ms".to_string()))?,
            );
            continue;
        }
        let mut parts = line.split_whitespace();
        let (from, to, num, den) = match (parts.next(), parts.next(), parts.next(), parts.next()) {
            (Some(f), Some(t), Some(n), Some(d)) if parts.next().is_none() => (f, t, n, d),
            _ => {
                return Err(CoreError::InvalidInput(format!(
                    "malformed rate line: {line:?}"
                )))
            }
        };
        let from_c = Currency::parse(from)?;
        let to_c = Currency::parse(to)?;
        let num: i64 = num
            .parse()
            .map_err(|_| CoreError::InvalidInput("bad rate num".to_string()))?;
        let den: i64 = den
            .parse()
            .map_err(|_| CoreError::InvalidInput("bad rate den".to_string()))?;
        if num <= 0 || den <= 0 {
            return Err(CoreError::InvalidInput("rate must be positive".to_string()));
        }
        let _ = (from_c, to_c);
        rates.push(FxRateEntryWire {
            from: from.to_string(),
            to: to.to_string(),
            num,
            den,
        });
    }
    let timestamp_ms = timestamp_ms
        .ok_or_else(|| CoreError::InvalidInput("rate set missing timestamp_ms".to_string()))?;
    let age_ms = now_ms.saturating_sub(timestamp_ms);
    Ok(FxRateSetWire {
        timestamp_ms,
        age_ms,
        stale: age_ms > FX_STALE_AFTER_MS,
        rates,
    })
}

fn hex_decode_fixed<const N: usize>(hex: &str) -> Result<[u8; N], CoreError> {
    if hex.len() != N * 2 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(CoreError::InvalidInput(format!(
            "expected {} hex chars ({} bytes)",
            N * 2,
            N
        )));
    }
    let mut out = [0u8; N];
    for (i, chunk) in hex.as_bytes().chunks(2).enumerate() {
        let hi = (chunk[0] as char).to_digit(16).unwrap_or(0) as u8;
        let lo = (chunk[1] as char).to_digit(16).unwrap_or(0) as u8;
        out[i] = (hi << 4) | lo;
    }
    Ok(out)
}

fn hex_decode_32(hex: &str) -> Result<[u8; 32], CoreError> {
    hex_decode_fixed::<32>(hex)
}

/// The canonical byte payload of a settlement record (§5.8/§5.7): the
/// settlement is a RECORD like any other (typed, authored, HLC-stamped),
/// never a UI-local flag. Layout (51 bytes): 16B from ‖ 16B to ‖ 8B amount
/// BE ‖ 3B currency code ‖ 8B timestamp-ms BE. The shell seals this blob
/// through the vault's record path (RecordWire, record_type = settlement);
/// the Ed25519 signature over canonical CBOR that §5.7 requires of ALL
/// records is shared infrastructure not yet built for any record type,
/// recorded as a gap in D-48, not settle-specific.
pub fn settlement_payload(
    from: &str,
    to: &str,
    amount_minor: i64,
    code: &str,
    timestamp_ms: u64,
    group: Option<&str>,
) -> Result<Vec<u8>, CoreError> {
    // D-235 round 2 (review M1): what a phone writes keeps the rules every
    // other phone's ingest holds it to (`ffi_check_settlement`).
    crate::straumr::check::check_group(group)?;
    crate::straumr::check::check_settlement(from, to, amount_minor, code)?;
    if amount_minor <= 0 {
        return Err(CoreError::InvalidInput(
            "settlement amount must be positive".to_string(),
        ));
    }
    let from = parse_participant(from)?;
    let to = parse_participant(to)?;
    if from == to {
        return Err(CoreError::InvalidInput(
            "settlement from and to must differ".to_string(),
        ));
    }
    let currency = Currency::parse(code)?;
    let group_bytes = match group {
        None => [0u8; 16],
        Some(hex) => parse_participant(hex)?,
    };
    let mut out = Vec::with_capacity(67);
    out.extend_from_slice(&from);
    out.extend_from_slice(&to);
    out.extend_from_slice(&amount_minor.to_be_bytes());
    out.extend_from_slice(currency.code().as_bytes());
    out.extend_from_slice(&timestamp_ms.to_be_bytes());
    out.extend_from_slice(&group_bytes);
    Ok(out)
}

/// 36 random bytes, born in core (keys and ids are never born in a shell
/// language, D-66). Born as the relay's sync secret (`key(32) ‖ session(4)`);
/// since the relay went (D-192, D-200 removed its sealing), iOS takes the
/// first 16 bytes as this phone's device id (`LedgerStore.swift`).
pub fn sync_key_generate() -> Vec<u8> {
    lykill::random_bytes(36)
}

/// D-186: whether `member_id` is in any expense (paying, or in a share),
/// settlement (either side) or redistribution (the person moved, or one of
/// the people it went to) of `group`. A person with history is never taken
/// out of a group: what they are in must keep their name.
#[uniffi::export]
pub fn ffi_member_has_history(
    expenses: Vec<ExpenseInput>,
    settlements: Vec<SettlementInput>,
    redistributions: Vec<RedistributionInput>,
    group: String,
    member_id: String,
) -> bool {
    let here = |g: &Option<String>| g.as_deref() == Some(group.as_str());
    expenses
        .iter()
        .filter(|e| here(&e.group))
        .any(|e| e.payer == member_id || e.shares.iter().any(|s| s.id == member_id))
        || settlements
            .iter()
            .filter(|s| here(&s.group))
            .any(|s| s.from == member_id || s.to == member_id)
        || redistributions
            .iter()
            .filter(|r| r.group == group)
            .any(|r| r.from == member_id || r.shares.iter().any(|s| s.id == member_id))
}

#[uniffi::export]
pub fn ffi_balance_line(
    expenses: Vec<ExpenseInput>,
    settlements: Vec<SettlementInput>,
    redistributions: Vec<RedistributionInput>,
    removed: Vec<String>,
    viewer: String,
    group: Option<String>,
) -> Result<Vec<BalanceLineOut>, CoreError> {
    balance_line(
        &expenses,
        &settlements,
        &redistributions,
        &removed,
        &viewer,
        group.as_deref(),
    )
}

/// D-221: how much of the group `group` (32-hex id) has been settled, per
/// currency, sorted by currency code (the Settled card and its meter). Only
/// that group's expenses, settlements and redistributions count, and a
/// removed member's remaining balance stays outstanding until a redistribution
/// moves it. Empty when the group has no expense or settlement. Each entry's
/// `basis_points` is floor(settled x 10000 / (settled + outstanding)), clamped
/// to 0..=10000, and 10000 when both are 0; `settled` is `outstanding == 0`.
/// A malformed group id is refused with `InvalidInput`.
#[uniffi::export]
pub fn ffi_settled_progress(
    expenses: Vec<ExpenseInput>,
    settlements: Vec<SettlementInput>,
    redistributions: Vec<RedistributionInput>,
    group: String,
) -> Result<Vec<SettledProgressOut>, CoreError> {
    settled_progress(&expenses, &settlements, &redistributions, &group)
}

fn redistribution_shares(r: &RedistributionInput) -> Result<Vec<(ParticipantId, i128)>, CoreError> {
    r.shares
        .iter()
        .map(|s| Ok((parse_participant(&s.id)?, s.amount_minor as i128)))
        .collect()
}

/// Each redistribution as the settlements balances apply (norn::redistribution).
fn redistribution_settlements(
    rs: &[RedistributionInput],
) -> Result<Vec<SettlementInput>, CoreError> {
    let mut out = Vec::new();
    for r in rs {
        let from = parse_participant(&r.from)?;
        for (payer, payee, amount) in
            crate::norn::redistribution::settlements(from, &redistribution_shares(r)?)
        {
            out.push(SettlementInput {
                from: hex_encode(&payer),
                to: hex_encode(&payee),
                amount_minor: narrow(amount)?,
                currency_code: r.currency_code.clone(),
                group: Some(r.group.clone()),
            });
        }
    }
    Ok(out)
}

#[uniffi::export]
pub fn ffi_redistribute_equal(
    balance_minor: i64,
    members: Vec<String>,
) -> Result<Vec<IdAmount>, CoreError> {
    let ids = members
        .iter()
        .map(|m| parse_participant(m))
        .collect::<Result<Vec<_>, _>>()?;
    let parts = crate::norn::redistribution::equal(balance_minor as i128, &ids)
        .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    parts
        .into_iter()
        .map(|(m, v)| {
            Ok(IdAmount {
                id: hex_encode(&m),
                amount_minor: narrow(v)?,
            })
        })
        .collect()
}

/// Narrows to i64, keeping the sign on overflow (MIN below, MAX above).
fn saturate(v: i128) -> i64 {
    i64::try_from(v).unwrap_or(if v < 0 { i64::MIN } else { i64::MAX })
}

#[uniffi::export]
pub fn ffi_redistribution_tally(
    balance_minor: i64,
    from: String,
    members: Vec<String>,
    shares: Vec<IdAmount>,
) -> RedistributionTally {
    let assigned: i128 = shares.iter().map(|s| s.amount_minor as i128).sum();
    let left = balance_minor as i128 - assigned;
    let offered = shares.iter().all(|s| members.contains(&s.id));
    let ok = offered
        && match (
            parse_participant(&from),
            shares
                .iter()
                .map(|s| Ok((parse_participant(&s.id)?, s.amount_minor as i128)))
                .collect::<Result<Vec<_>, CoreError>>(),
        ) {
            (Ok(f), Ok(v)) => {
                crate::norn::redistribution::check(balance_minor as i128, f, &v).is_ok()
            }
            _ => false,
        };
    RedistributionTally {
        assigned_minor: saturate(assigned),
        left_minor: saturate(left),
        ok,
    }
}

#[uniffi::export]
pub fn ffi_encode_redistribution(r: RedistributionInput) -> Result<Vec<u8>, CoreError> {
    parse_participant(&r.group)?;
    let from = parse_participant(&r.from)?;
    Currency::parse(&r.currency_code)?;
    let shares = redistribution_shares(&r)?;
    let balance: i128 = shares.iter().map(|(_, v)| v).sum();
    crate::norn::redistribution::check(balance, from, &shares)
        .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    crate::norn::redistribution::encode(&crate::norn::redistribution::Redistribution {
        group: r.group,
        from: r.from,
        currency: r.currency_code,
        shares: r
            .shares
            .into_iter()
            .map(|s| crate::norn::redistribution::Share {
                member: s.id,
                amount: s.amount_minor,
            })
            .collect(),
    })
    .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))
}

#[uniffi::export]
pub fn ffi_decode_redistribution(blob: Vec<u8>) -> Result<RedistributionInput, CoreError> {
    let r = crate::norn::redistribution::decode(&blob)
        .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    let out = RedistributionInput {
        group: r.group,
        from: r.from,
        currency_code: r.currency,
        shares: r
            .shares
            .into_iter()
            .map(|s| IdAmount {
                id: s.member,
                amount_minor: s.amount,
            })
            .collect(),
    };
    parse_participant(&out.group)?;
    Currency::parse(&out.currency_code)?;
    let from = parse_participant(&out.from)?;
    let shares = redistribution_shares(&out)?;
    let balance: i128 = shares.iter().map(|(_, v)| v).sum();
    crate::norn::redistribution::check(balance, from, &shares)
        .map_err(|e| CoreError::InvalidInput(format!("{e:?}")))?;
    Ok(out)
}

// ---------------------------------------------------------------------------
// D-141: expense revisions (SPEC §5.7, §6.1) across the boundary. The logic
// lives in `straumr::revision`; these are its FFI shapes. Money reuses
// `ExpenseInput` (its `group` is the creation's: an edit cannot move an
// expense between groups, so a revision's money carries none).
// ---------------------------------------------------------------------------

/// A revision to write: absent fields are unchanged; `""` clears note or
/// category; `deleted: Some(true)` is the tombstone, `Some(false)` the Undo.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExpenseRevisionIn {
    /// The id of the expense (its creation record's identity).
    pub target: String,
    /// The `seen` list from the projected expense being edited.
    pub seen: Vec<String>,
    /// The editor's participant id (32 hex), for "last edited by".
    pub editor: Option<String>,
    pub money: Option<ExpenseInput>,
    pub note: Option<String>,
    pub category: Option<String>,
    pub deleted: Option<bool>,
}

/// One expense creation record, as the shell decoded it from the vault.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExpenseCreatedIn {
    pub hlc_millis: u64,
    pub hlc_counter: u32,
    pub hlc_device_id: String,
    pub expense: ExpenseInput,
    pub note: Option<String>,
    pub category: Option<String>,
    pub timestamp_ms: Option<u64>,
    pub added_by: Option<String>,
}

/// One revision record as it sits in the vault: identity plus raw blob.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExpenseRevisionRecordIn {
    pub hlc_millis: u64,
    pub hlc_counter: u32,
    pub hlc_device_id: String,
    pub blob: Vec<u8>,
    /// D-158: the record header's group id (None = personal). A revision
    /// applies only to an expense in the same scope.
    pub scope: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProjectedExpenseOut {
    pub id: String,
    pub expense: ExpenseInput,
    pub note: Option<String>,
    pub category: Option<String>,
    pub timestamp_ms: Option<u64>,
    pub added_by: Option<String>,
    pub added_at_ms: u64,
    pub deleted: bool,
    pub last_edited_at_ms: Option<u64>,
    pub last_edited_by: Option<String>,
    pub seen: Vec<String>,
    /// Empty, or the money values to choose between (SPEC §6.1).
    pub money_conflict: Vec<ExpenseInput>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ExpenseProjectionOut {
    pub expenses: Vec<ProjectedExpenseOut>,
    pub rejected: Vec<String>,
    pub pending: Vec<String>,
}

fn money_value(e: &ExpenseInput) -> crate::straumr::revision::MoneyValue {
    use crate::straumr::revision::{ConversionValue, MoneyValue, ShareValue};
    MoneyValue {
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
    }
}

fn expense_input(m: &crate::straumr::revision::MoneyValue, group: Option<String>) -> ExpenseInput {
    ExpenseInput {
        payer: m.payer.clone(),
        paid_minor: m.paid_minor,
        currency_code: m.currency_code.clone(),
        shares: m
            .shares
            .iter()
            .map(|s| IdAmount {
                id: s.id.clone(),
                amount_minor: s.amount_minor,
            })
            .collect(),
        group,
        conversion: m.conversion.as_ref().map(|c| ConversionInput {
            converted_minor: c.converted_minor,
            converted_currency: c.converted_currency.clone(),
            rate_num: c.rate_num,
            rate_den: c.rate_den,
            rate_timestamp_ms: c.rate_timestamp_ms,
        }),
    }
}

fn revision_error(e: crate::straumr::revision::RevisionError) -> CoreError {
    CoreError::InvalidInput(e.to_string())
}

fn version_id(
    millis: u64,
    counter: u32,
    device: &str,
) -> Result<crate::straumr::revision::VersionId, CoreError> {
    crate::straumr::revision::VersionId::parse(&format!("{millis}:{counter}:{device}")).ok_or_else(
        || CoreError::InvalidInput(format!("device id {device:?} is not 32 lowercase hex")),
    )
}

/// The bytes of an expense revision record (record type 0x05). Both shells
/// write revisions ONLY through this, so they write identical bytes.
#[uniffi::export]
pub fn ffi_encode_expense_revision(revision: ExpenseRevisionIn) -> Result<Vec<u8>, CoreError> {
    use crate::straumr::revision::{encode, Revision};
    encode(&Revision {
        target: revision.target,
        seen: revision.seen,
        editor: revision.editor,
        money: revision.money.as_ref().map(money_value),
        note: revision.note,
        category: revision.category,
        deleted: revision.deleted,
    })
    .map_err(revision_error)
}

/// A revision record's payload, read back. D-235 (core audit C1): `scope` is
/// the record header's group (None = personal), and new `money` comes back
/// in it: a revision's payload carries no group (an edit cannot move an
/// expense between groups, and `ffi_project_expenses` refuses one whose
/// scope is not its target's), so before this the money came back with
/// `group: None` and both shells' ingest refused every group money edit as
/// the wrong group. A scope that is not 32 lowercase hex is refused.
///
/// The payload is checked as `ffi_check_expense` checks an expense (D-235,
/// decision 103): a revision whose new money breaks the rules is an error
/// here, and `rejected` in `ffi_project_expenses`.
#[uniffi::export(default(scope = None))]
pub fn ffi_decode_expense_revision(
    blob: Vec<u8>,
    scope: Option<String>,
) -> Result<ExpenseRevisionIn, CoreError> {
    crate::straumr::check::check_group(scope.as_deref())?;
    let r = crate::straumr::revision::decode(&blob).map_err(revision_error)?;
    Ok(ExpenseRevisionIn {
        target: r.target,
        seen: r.seen,
        editor: r.editor,
        money: r.money.as_ref().map(|m| expense_input(m, scope.clone())),
        note: r.note,
        category: r.category,
        deleted: r.deleted,
    })
}

/// D-235 (decision 103): the money rules an expense record keeps, for both
/// shells' ingest (a record from a group's log that breaks them is refused
/// before it reaches the vault) and before a write. Ok, or the rule broken
/// (`InvalidInput` for an id or a conversion, `UnknownCurrency`,
/// `NegativeTotal`, `Overflow` past `straumr::check::MAX_AMOUNT_MINOR`,
/// `EmptyParticipants`, `DuplicateParticipant`, `TotalMismatch`). The group,
/// when named, must be an id; membership is not checked here.
#[uniffi::export]
pub fn ffi_check_expense(expense: ExpenseInput) -> Result<(), CoreError> {
    crate::straumr::check::check_group(expense.group.as_deref())?;
    crate::straumr::check::check_money(&money_value(&expense))?;
    Ok(())
}

/// D-249 (decision 208): [`ffi_check_expense`], then the people: the payer
/// and every share's id must be in `members`, the ledger's people as the
/// writing phone holds them (a group's members; for Just me, your own ids).
/// Both shells run it on every expense write path before anything is
/// written (Add, Edit's new money, import); ingest does not (a peer's record
/// is not refused for it: CLAUDE.md 10's cursor rule). A money rule broken
/// is that rule's error; a member that is not an id is `InvalidInput`; an
/// id not in `members` (an empty list holds nobody) is `UnknownParticipant`.
#[uniffi::export]
pub fn ffi_check_expense_members(
    expense: ExpenseInput,
    members: Vec<String>,
) -> Result<(), CoreError> {
    ffi_check_expense(expense.clone())?;
    for m in &members {
        if !crate::straumr::check::is_id(m) {
            return Err(CoreError::InvalidInput(format!(
                "member {m:?} is not 32 lowercase hex"
            )));
        }
    }
    let held: std::collections::HashSet<&str> = members.iter().map(String::as_str).collect();
    if !held.contains(expense.payer.as_str()) {
        return Err(CoreError::UnknownParticipant);
    }
    if expense.shares.iter().any(|s| !held.contains(s.id.as_str())) {
        return Err(CoreError::UnknownParticipant);
    }
    Ok(())
}

/// D-235 (decision 103): the rules a settlement record keeps, for both
/// shells' ingest: two different ids, a known currency, an amount above 0
/// and at most `straumr::check::MAX_AMOUNT_MINOR`, the group an id.
#[uniffi::export]
pub fn ffi_check_settlement(settlement: SettlementInput) -> Result<(), CoreError> {
    crate::straumr::check::check_group(settlement.group.as_deref())?;
    crate::straumr::check::check_settlement(
        &settlement.from,
        &settlement.to,
        settlement.amount_minor,
        &settlement.currency_code,
    )?;
    Ok(())
}

/// Records → the ledger people see (SPEC §5.7, §6.1). Order-independent:
/// the same records in any order give the same result.
#[uniffi::export]
pub fn ffi_project_expenses(
    created: Vec<ExpenseCreatedIn>,
    revisions: Vec<ExpenseRevisionRecordIn>,
) -> Result<ExpenseProjectionOut, CoreError> {
    use crate::straumr::revision::{project, Creation, RevisionRecord};
    let creations = created
        .iter()
        .map(|c| {
            Ok(Creation {
                id: version_id(c.hlc_millis, c.hlc_counter, &c.hlc_device_id)?,
                money: money_value(&c.expense),
                group: c.expense.group.clone(),
                note: c.note.clone(),
                category: c.category.clone(),
                timestamp_ms: c.timestamp_ms,
                added_by: c.added_by.clone(),
            })
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    let records = revisions
        .into_iter()
        .map(|r| {
            Ok(RevisionRecord {
                id: version_id(r.hlc_millis, r.hlc_counter, &r.hlc_device_id)?,
                bytes: r.blob,
                scope: r.scope,
            })
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    let p = project(&creations, &records);
    Ok(ExpenseProjectionOut {
        expenses: p
            .expenses
            .into_iter()
            .map(|e| ProjectedExpenseOut {
                expense: expense_input(&e.money, e.group.clone()),
                money_conflict: e
                    .money_conflict
                    .iter()
                    .map(|m| expense_input(m, e.group.clone()))
                    .collect(),
                id: e.id,
                note: e.note,
                category: e.category,
                timestamp_ms: e.timestamp_ms,
                added_by: e.added_by,
                added_at_ms: e.added_at_ms,
                deleted: e.deleted,
                last_edited_at_ms: e.last_edited_at_ms,
                last_edited_by: e.last_edited_by,
                seen: e.seen,
            })
            .collect(),
        rejected: p.rejected,
        pending: p.pending,
    })
}

// ---------------------------------------------------------------------------
// Groups and "you" as records (D-152; straumr::groups). Record type 0x03: group
// operations with the group's scope, "you" with personal scope. Both shells
// write these ONLY through `ffi_encode_group_op` and read them ONLY through
// `ffi_project_groups`, so they write identical bytes and see identical groups.
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupOpMemberIn {
    pub id: String,
    pub name: Option<String>,
}

/// One group operation, as a shell asks for it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum GroupOpIn {
    Create {
        group_id: String,
        name: String,
        settlement_currency: String,
        members: Vec<GroupOpMemberIn>,
    },
    AddMember {
        group_id: String,
        member: GroupOpMemberIn,
    },
    RenameMember {
        group_id: String,
        member_id: String,
        name: String,
    },
    RenameGroup {
        group_id: String,
        name: String,
    },
    /// D-186: takes a person out of a group's people (group scope).
    RemoveMember {
        group_id: String,
        member_id: String,
    },
    /// D-187: a group's picture (group scope): an emoji, a JPEG as lowercase
    /// hex, or neither for the initial.
    GroupPicture {
        group_id: String,
        emoji: Option<String>,
        photo_hex: Option<String>,
    },
    /// Who "you" are in this account. Personal scope, never a group's.
    You {
        id: String,
        name: Option<String>,
    },
    /// D-157: "member `member_id` of this group is me" (group scope).
    Claim {
        group_id: String,
        member_id: String,
        you_id: String,
        devices: Vec<ClaimDeviceIn>,
    },
    /// D-158: a group's key as this account holds it (PERSONAL scope: the
    /// account's own log only).
    GroupKey {
        group_id: String,
        epoch: u32,
        key_hex: String,
    },
    /// D-178 (ADR-0039): the account's Maximum security choice (PERSONAL
    /// scope: the account's own log only).
    Security {
        maximum: bool,
    },
    /// D-185: what one of the account's phones is called (PERSONAL scope).
    DeviceName {
        device_id: String,
        name: String,
    },
    /// D-211: your picture (PERSONAL scope): an emoji, a JPEG as lowercase
    /// hex, or neither for none.
    Avatar {
        emoji: Option<String>,
        photo_hex: Option<String>,
    },
    /// D-211: a joined person's name and preview in a group (group scope).
    /// Phones BUILD it for `shareProfile`; only the Function writes it.
    MemberProfile {
        group_id: String,
        member_id: String,
        name: String,
        emoji: Option<String>,
        thumb_hex: Option<String>,
    },
    /// D-229: in this group, `you_id` reads as the place `member_id` (group
    /// scope). Phones BUILD it for `shareAliases`; only the Function writes it.
    PlaceAlias {
        group_id: String,
        member_id: String,
        you_id: String,
    },
    /// D-230: in this group, place `member_id` is held by the account whose id
    /// is `you_id`. Phones BUILD it for `sharePlaces` and `acceptInvite`; only
    /// the Functions write it.
    HeldPlace {
        group_id: String,
        member_id: String,
        you_id: String,
    },
    /// D-244 (ADR-0043): one of the account's plain preferences, so its other
    /// phones match (PERSONAL scope: the account's own log only). The key is 1
    /// to 48 of `A-Z a-z 0-9 . -`; the value trimmed, 1 to 64 characters, no
    /// control characters; `money.default-currency` a code core knows.
    Setting {
        key: String,
        value: String,
    },
}

/// D-157: one device of a claiming account, with its agreement public key.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ClaimDeviceIn {
    pub device_id: String,
    pub agreement_public_hex: String,
}

fn group_op(op: GroupOpIn) -> crate::straumr::groups::GroupOp {
    use crate::straumr::groups::{GroupOp, OpMember};
    let member = |m: GroupOpMemberIn| OpMember {
        id: m.id,
        name: m.name,
    };
    match op {
        GroupOpIn::Create {
            group_id,
            name,
            settlement_currency,
            members,
        } => GroupOp::Create {
            group: group_id,
            name,
            settlement_currency,
            members: members.into_iter().map(member).collect(),
        },
        GroupOpIn::AddMember {
            group_id,
            member: m,
        } => GroupOp::AddMember {
            group: group_id,
            member: member(m),
        },
        GroupOpIn::RenameMember {
            group_id,
            member_id,
            name,
        } => GroupOp::RenameMember {
            group: group_id,
            member: member_id,
            name,
        },
        GroupOpIn::RenameGroup { group_id, name } => GroupOp::RenameGroup {
            group: group_id,
            name,
        },
        GroupOpIn::RemoveMember {
            group_id,
            member_id,
        } => GroupOp::RemoveMember {
            group: group_id,
            member: member_id,
        },
        GroupOpIn::GroupPicture {
            group_id,
            emoji,
            photo_hex,
        } => GroupOp::GroupPicture {
            group: group_id,
            emoji,
            photo: photo_hex,
        },
        GroupOpIn::You { id, name } => GroupOp::You { id, name },
        GroupOpIn::Claim {
            group_id,
            member_id,
            you_id,
            devices,
        } => GroupOp::Claim {
            group: group_id,
            member: member_id,
            you: you_id,
            devices: devices
                .into_iter()
                .map(|d| crate::straumr::groups::ClaimDevice {
                    id: d.device_id,
                    key: d.agreement_public_hex,
                })
                .collect(),
        },
        GroupOpIn::GroupKey {
            group_id,
            epoch,
            key_hex,
        } => GroupOp::GroupKey {
            group: group_id,
            epoch,
            key: key_hex,
        },
        GroupOpIn::Security { maximum } => GroupOp::Security { maximum },
        GroupOpIn::Avatar { emoji, photo_hex } => GroupOp::Avatar {
            emoji,
            photo: photo_hex,
        },
        GroupOpIn::MemberProfile {
            group_id,
            member_id,
            name,
            emoji,
            thumb_hex,
        } => GroupOp::MemberProfile {
            group: group_id,
            member: member_id,
            name,
            emoji,
            thumb: thumb_hex,
        },
        GroupOpIn::DeviceName { device_id, name } => GroupOp::DeviceName {
            device: device_id,
            name,
        },
        GroupOpIn::PlaceAlias {
            group_id,
            member_id,
            you_id,
        } => GroupOp::PlaceAlias {
            group: group_id,
            member: member_id,
            you: you_id,
        },
        GroupOpIn::HeldPlace {
            group_id,
            member_id,
            you_id,
        } => GroupOp::HeldPlace {
            group: group_id,
            member: member_id,
            you: you_id,
        },
        GroupOpIn::Setting { key, value } => GroupOp::Setting { key, value },
    }
}

fn claim_devices_out(devices: Vec<crate::straumr::groups::ClaimDevice>) -> Vec<ClaimDeviceIn> {
    devices
        .into_iter()
        .map(|d| ClaimDeviceIn {
            device_id: d.id,
            agreement_public_hex: d.key,
        })
        .collect()
}

fn group_op_in(op: crate::straumr::groups::GroupOp) -> GroupOpIn {
    use crate::straumr::groups::{GroupOp, OpMember};
    let member = |m: OpMember| GroupOpMemberIn {
        id: m.id,
        name: m.name,
    };
    match op {
        GroupOp::Create {
            group,
            name,
            settlement_currency,
            members,
        } => GroupOpIn::Create {
            group_id: group,
            name,
            settlement_currency,
            members: members.into_iter().map(member).collect(),
        },
        GroupOp::AddMember { group, member: m } => GroupOpIn::AddMember {
            group_id: group,
            member: member(m),
        },
        GroupOp::RenameMember {
            group,
            member,
            name,
        } => GroupOpIn::RenameMember {
            group_id: group,
            member_id: member,
            name,
        },
        GroupOp::RenameGroup { group, name } => GroupOpIn::RenameGroup {
            group_id: group,
            name,
        },
        GroupOp::RemoveMember { group, member } => GroupOpIn::RemoveMember {
            group_id: group,
            member_id: member,
        },
        GroupOp::GroupPicture {
            group,
            emoji,
            photo,
        } => GroupOpIn::GroupPicture {
            group_id: group,
            emoji,
            photo_hex: photo,
        },
        GroupOp::You { id, name } => GroupOpIn::You { id, name },
        GroupOp::Claim {
            group,
            member,
            you,
            devices,
        } => GroupOpIn::Claim {
            group_id: group,
            member_id: member,
            you_id: you,
            devices: claim_devices_out(devices),
        },
        GroupOp::GroupKey { group, epoch, key } => GroupOpIn::GroupKey {
            group_id: group,
            epoch,
            key_hex: key,
        },
        GroupOp::Security { maximum } => GroupOpIn::Security { maximum },
        GroupOp::DeviceName { device, name } => GroupOpIn::DeviceName {
            device_id: device,
            name,
        },
        GroupOp::Avatar { emoji, photo } => GroupOpIn::Avatar {
            emoji,
            photo_hex: photo,
        },
        GroupOp::MemberProfile {
            group,
            member,
            name,
            emoji,
            thumb,
        } => GroupOpIn::MemberProfile {
            group_id: group,
            member_id: member,
            name,
            emoji,
            thumb_hex: thumb,
        },
        GroupOp::PlaceAlias { group, member, you } => GroupOpIn::PlaceAlias {
            group_id: group,
            member_id: member,
            you_id: you,
        },
        GroupOp::HeldPlace { group, member, you } => GroupOpIn::HeldPlace {
            group_id: group,
            member_id: member,
            you_id: you,
        },
        GroupOp::Setting { key, value } => GroupOpIn::Setting { key, value },
    }
}

/// The bytes of a group operation record (record type 0x03).
#[uniffi::export]
pub fn ffi_encode_group_op(op: GroupOpIn) -> Result<Vec<u8>, CoreError> {
    crate::straumr::groups::encode(&group_op(op))
        .map_err(|e| CoreError::InvalidInput(e.to_string()))
}

#[uniffi::export]
pub fn ffi_decode_group_op(blob: Vec<u8>) -> Result<GroupOpIn, CoreError> {
    crate::straumr::groups::decode(&blob)
        .map(group_op_in)
        .map_err(|e| CoreError::InvalidInput(e.to_string()))
}

/// One group operation record as it sits in the vault.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupOpRecordIn {
    pub hlc_millis: u64,
    pub hlc_counter: u32,
    pub hlc_device_id: String,
    pub blob: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProjectedMemberOut {
    pub id: String,
    /// None for a member whose name was never written (shown as a placeholder).
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProjectedGroupOut {
    /// `group.members` lists the ids in the same order as `members`.
    pub group: GroupWire,
    pub members: Vec<ProjectedMemberOut>,
    /// The earliest create's identity, `millis:counter:device`.
    pub created: String,
    /// D-157: members linked to an account, in member order.
    pub claims: Vec<ClaimOut>,
    /// D-186: members taken out of the group, in member order. They stay in
    /// `group.members` and `members`, so anything they are in still
    /// balances; the shells leave them out of the people they offer.
    pub removed: Vec<String>,
    /// D-187: the group's picture; None for the initial.
    pub picture: Option<GroupPictureOut>,
    /// D-211: members with a marked profile, in member order.
    pub profiles: Vec<MemberProfileOut>,
    /// D-230: the marked held places, member order, the latest per member.
    pub held: Vec<HeldPlaceOut>,
    /// D-229: this account's place in the group, the raw member id the group
    /// knows it by; None with no "you" or no place.
    pub place: Option<String>,
    /// D-229: write as this id in the group's records (payer, shares, added
    /// by, editor, settlement ends, redistribution ids, renames and removals
    /// of yourself) through `ffi_write_as_expense` and its twins. None means
    /// "you" is readable there and records are written as before ONLY when
    /// `place_lost` is false.
    pub you_as: Option<String>,
    /// D-229: the account's own "you" ids the group's other members cannot
    /// read (no member, no alias), sorted; empty when there is no place.
    pub unaliased_yous: Vec<String>,
    /// D-229 decision 45: the account had a place here and has none now:
    /// another account's claim holds one of its ids (D-227 decision 36 seen
    /// from the creator's phone), or its own claim is contested. Whatever it
    /// writes here would read as someone else or nobody: the group is
    /// read-only. False with no "you".
    pub place_lost: bool,
}

/// D-211: a joined person's preview, under the id they are shown by.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MemberProfileOut {
    pub member_id: String,
    pub emoji: Option<String>,
    pub thumb_hex: Option<String>,
}

/// D-230: a place the server marked as held, raw ids.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HeldPlaceOut {
    pub member_id: String,
    pub you_id: String,
}

/// D-187: a group's picture: an emoji or a JPEG as lowercase hex.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupPictureOut {
    pub emoji: Option<String>,
    pub photo_hex: Option<String>,
}

/// D-157: a member an account claimed. `member_id` is the id the group knows
/// them by; `you_id` equal to this account's "you" means it is you.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ClaimOut {
    pub member_id: String,
    pub you_id: String,
    pub devices: Vec<ClaimDeviceIn>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct YouOut {
    pub id: String,
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupsProjectionOut {
    /// The account's one "you": the earliest `you` record's id.
    pub you: Option<YouOut>,
    /// Other ids some device wrote as "you": the same person, to be merged.
    pub other_you_ids: Vec<String>,
    pub groups: Vec<ProjectedGroupOut>,
    pub rejected: Vec<String>,
    pub pending: Vec<String>,
    /// D-157: claims on a member another account claimed first (record ids).
    pub contested: Vec<String>,
    /// D-158: every group key the account holds, sorted by group, epoch and
    /// first written. Seal with the first key of a group's highest epoch;
    /// open by trying each key of the group.
    pub keys: Vec<GroupKeyOut>,
    /// D-158: other people who joined a group through an invite: in
    /// `group_id`, `from_id` (their "you") reads as `to_id` (the member they
    /// claimed). Pass them to the merges with `you` and `other_you_ids`.
    pub aliases: Vec<IdAlias>,
    /// D-178 (ADR-0039): the account's Maximum security choice, the latest
    /// written; off when never written.
    pub maximum_security: bool,
    /// D-185: each of the account's phones that has a name, sorted by device.
    pub device_names: Vec<DeviceNameOut>,
    /// D-211: the account's picture, the latest written; None when never
    /// written or cleared.
    pub avatar: Option<GroupPictureOut>,
    /// D-244 (ADR-0043): the account's settings, the latest write per key,
    /// sorted by key; every valid key, known to the shell or not.
    pub settings: Vec<SettingOut>,
}

/// D-244: one setting as the account holds it. `record_id` is the winning
/// record's identity, `millis:counter:device` (as `rejected` and `created`):
/// a shell remembers the one it applied; `device_id` is that record's device,
/// so a shell knows its own write.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SettingOut {
    pub key: String,
    pub value: String,
    pub record_id: String,
    pub device_id: String,
}

/// D-185: one phone's name.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceNameOut {
    pub device_id: String,
    pub name: String,
}

/// D-158: in one group, one id reads as another.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct IdAlias {
    pub group_id: String,
    pub from_id: String,
    pub to_id: String,
}

/// D-158: one group key the account holds.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupKeyOut {
    pub group_id: String,
    pub epoch: u32,
    pub key_hex: String,
}

/// Records → groups and "you" (D-152). Order-independent: the same records in
/// any order give the same result.
#[uniffi::export]
pub fn ffi_project_groups(records: Vec<GroupOpRecordIn>) -> Result<GroupsProjectionOut, CoreError> {
    use crate::straumr::groups::{project, OpRecord};
    let records = records
        .into_iter()
        .map(|r| {
            Ok(OpRecord {
                id: version_id(r.hlc_millis, r.hlc_counter, &r.hlc_device_id)?,
                bytes: r.blob,
            })
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    let p = project(&records);
    Ok(GroupsProjectionOut {
        you: p.you.map(|y| YouOut {
            id: y.id,
            name: y.name,
        }),
        other_you_ids: p.other_you_ids,
        groups: p
            .groups
            .into_iter()
            .map(|g| ProjectedGroupOut {
                group: GroupWire {
                    id: g.id,
                    name: g.name,
                    settlement_currency: g.settlement_currency,
                    members: g.members.iter().map(|m| m.id.clone()).collect(),
                },
                members: g
                    .members
                    .into_iter()
                    .map(|m| ProjectedMemberOut {
                        id: m.id,
                        name: m.name,
                    })
                    .collect(),
                created: g.created,
                claims: g
                    .claims
                    .into_iter()
                    .map(|c| ClaimOut {
                        member_id: c.member,
                        you_id: c.you,
                        devices: claim_devices_out(c.devices),
                    })
                    .collect(),
                removed: g.removed,
                picture: g.picture.map(|p| GroupPictureOut {
                    emoji: p.emoji,
                    photo_hex: p.photo,
                }),
                profiles: g
                    .profiles
                    .into_iter()
                    .map(|p| MemberProfileOut {
                        member_id: p.member,
                        emoji: p.emoji,
                        thumb_hex: p.thumb,
                    })
                    .collect(),
                held: g
                    .held
                    .into_iter()
                    .map(|h| HeldPlaceOut {
                        member_id: h.member,
                        you_id: h.you,
                    })
                    .collect(),
                place: g.place,
                you_as: g.you_as,
                unaliased_yous: g.unaliased_yous,
                place_lost: g.place_lost,
            })
            .collect(),
        rejected: p.rejected,
        pending: p.pending,
        contested: p.contested,
        keys: p
            .keys
            .into_iter()
            .map(|k| GroupKeyOut {
                group_id: k.group,
                epoch: k.epoch,
                key_hex: k.key,
            })
            .collect(),
        aliases: p
            .aliases
            .into_iter()
            .map(|a| IdAlias {
                group_id: a.group,
                from_id: a.from,
                to_id: a.to,
            })
            .collect(),
        maximum_security: p.maximum_security,
        device_names: p
            .device_names
            .into_iter()
            .map(|(device_id, name)| DeviceNameOut { device_id, name })
            .collect(),
        avatar: p.avatar.map(|a| GroupPictureOut {
            emoji: a.emoji,
            photo_hex: a.photo,
        }),
        settings: p
            .settings
            .into_iter()
            .map(|s| SettingOut {
                key: s.key,
                value: s.value,
                record_id: s.id.render(),
                device_id: s.id.device,
            })
            .collect(),
    })
}

// ---------------------------------------------------------------------------
// QR codes (D-159): the invite link drawn as a QR code by both shells, from
// one encoder (iOS has CoreImage's; Android has none built in).
// ---------------------------------------------------------------------------

/// A QR code: `size` x `size` modules, row-major, `true` = dark. The shells
/// draw it with a quiet zone of four modules.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct QrOut {
    pub size: u32,
    pub modules: Vec<bool>,
}

/// `text` as a QR code (byte mode, error correction M). Refuses text longer
/// than version 6 holds (106 bytes); an invite link is 77.
#[uniffi::export]
pub fn ffi_qr_encode(text: String) -> Result<QrOut, CoreError> {
    let code = crate::qr::encode(text.as_bytes())
        .map_err(|_| CoreError::InvalidInput("too long for a QR code".into()))?;
    Ok(QrOut {
        size: code.size as u32,
        modules: code.modules,
    })
}

/// D-154: one "you" per account. The ids some device wrote as "you" read as
/// the account's "you" in the expenses people see: payer, shares (added up,
/// in the first one's place), "added by", "last edited by", and each value in
/// a money conflict. The records are untouched; the shells call this on the
/// projection, with `ffi_project_groups`' `you` and `other_you_ids`.
#[uniffi::export]
pub fn ffi_merge_you_expenses(
    expenses: Vec<ProjectedExpenseOut>,
    you_id: String,
    other_ids: Vec<String>,
    aliases: Vec<IdAlias>,
) -> Result<Vec<ProjectedExpenseOut>, CoreError> {
    let yous = yous_of(&you_id, &other_ids, &aliases)?;
    if yous.is_single() {
        return Ok(expenses);
    }
    let money = |e: ExpenseInput| -> ExpenseInput {
        let group = e.group.clone();
        let shares = yous
            .shares_in(
                group.as_deref(),
                &e.shares
                    .iter()
                    .map(|s| (s.id.clone(), s.amount_minor))
                    .collect::<Vec<_>>(),
            )
            .into_iter()
            .map(|(id, amount_minor)| IdAmount { id, amount_minor })
            .collect();
        ExpenseInput {
            payer: yous.id_in(group.as_deref(), &e.payer),
            shares,
            ..e
        }
    };
    Ok(expenses
        .into_iter()
        .map(|e| {
            let group = e.expense.group.clone();
            ProjectedExpenseOut {
                expense: money(e.expense),
                money_conflict: e.money_conflict.into_iter().map(money).collect(),
                added_by: e.added_by.map(|id| yous.id_in(group.as_deref(), &id)),
                last_edited_by: e.last_edited_by.map(|id| yous.id_in(group.as_deref(), &id)),
                ..e
            }
        })
        .collect())
}

/// D-154: the settlements people see with one "you". A settlement between two
/// of your own ids is not a payment and leaves the projected ledger (its
/// record stays); core would refuse it in balances and export.
#[uniffi::export]
pub fn ffi_merge_you_settlements(
    settlements: Vec<SettlementInput>,
    you_id: String,
    other_ids: Vec<String>,
    aliases: Vec<IdAlias>,
) -> Result<Vec<SettlementInput>, CoreError> {
    let yous = yous_of(&you_id, &other_ids, &aliases)?;
    if yous.is_single() {
        return Ok(settlements);
    }
    Ok(settlements
        .into_iter()
        .filter_map(|s| {
            yous.settlement_in(s.group.as_deref(), &s.from, &s.to)
                .map(|(from, to)| SettlementInput { from, to, ..s })
        })
        .collect())
}

/// D-210: the redistributions people see with one "you", read in their own
/// group as settlements read their two ends: `from` and every share id map
/// through the account's ids; shares that become one person are added (all
/// shares have one sign, so a sum is never zero); shares come out ordered by
/// id. A share that becomes `from` is a payment to oneself, which moves no
/// balance, so it is dropped with its amount. If that leaves no share the
/// whole record was a payment to oneself and is dropped, as
/// `ffi_merge_you_settlements` drops a self-to-self payment; an empty record
/// would no longer be one core can check.
#[uniffi::export]
pub fn ffi_merge_you_redistributions(
    redistributions: Vec<RedistributionInput>,
    you_id: String,
    other_ids: Vec<String>,
    aliases: Vec<IdAlias>,
) -> Result<Vec<RedistributionInput>, CoreError> {
    let yous = yous_of(&you_id, &other_ids, &aliases)?;
    if yous.is_single() {
        return Ok(redistributions);
    }
    Ok(redistributions
        .into_iter()
        .filter_map(|r| {
            let group = Some(r.group.as_str());
            let from = yous.id_in(group, &r.from);
            let mut shares: Vec<IdAmount> = yous
                .shares_in(
                    group,
                    &r.shares
                        .iter()
                        .map(|s| (s.id.clone(), s.amount_minor))
                        .collect::<Vec<_>>(),
                )
                .into_iter()
                .filter(|(id, _)| *id != from)
                .map(|(id, amount_minor)| IdAmount { id, amount_minor })
                .collect();
            if shares.is_empty() {
                return None;
            }
            shares.sort_by(|a, b| a.id.cmp(&b.id));
            Some(RedistributionInput { from, shares, ..r })
        })
        .collect())
}

/// D-229: an expense as it is WRITTEN into a group where this account's "you"
/// is not readable (`you_as` of `ffi_project_groups`): every id in `mine` (the
/// account's "you" and `other_you_ids`) becomes `place`, in the payer and the
/// shares; shares that become one are added, in the first one's position, so
/// the shares still sum to the amount. Every other id and field is untouched.
/// `place` and `mine` must be ids (`InvalidInput` otherwise).
#[uniffi::export]
pub fn ffi_write_as_expense(
    expense: ExpenseInput,
    place: String,
    mine: Vec<String>,
) -> Result<ExpenseInput, CoreError> {
    let yous = write_as(&place, &mine)?;
    let written = ExpenseInput {
        payer: yous.id(&expense.payer),
        shares: write_shares(&yous, &expense.shares)?,
        ..expense
    };
    // D-235 round 2 (review M1): never hand back what ingest would refuse.
    ffi_check_expense(written.clone())?;
    Ok(written)
}

/// D-229: a redistribution as it is written where "you" is not readable:
/// `from` and the share ids map as `ffi_write_as_expense` maps them, shares
/// that become one are added in the first one's position. A share that would
/// land on `from` (a balance moved to the person it is taken from) is refused
/// with `InvalidInput`, as core's own check refuses one.
#[uniffi::export]
pub fn ffi_write_as_redistribution(
    r: RedistributionInput,
    place: String,
    mine: Vec<String>,
) -> Result<RedistributionInput, CoreError> {
    let yous = write_as(&place, &mine)?;
    let from = yous.id(&r.from);
    let shares = write_shares(&yous, &r.shares)?;
    if shares.iter().any(|s| s.id == from) {
        return Err(CoreError::InvalidInput(
            "a balance cannot move to the person it is taken from".into(),
        ));
    }
    Ok(RedistributionInput { from, shares, ..r })
}

/// D-229: every id in `mine` reads as `place` (D-154's merge, run towards
/// the place instead of "you"). The ids are checked as `yous_of` checks them.
fn write_as(place: &str, mine: &[String]) -> Result<crate::straumr::identity::Yous, CoreError> {
    parse_participant(place)?;
    for id in mine {
        parse_participant(id)?;
    }
    Ok(crate::straumr::identity::Yous::new(place, mine))
}

/// Shares through `write_as`: those that become one are added, in the first
/// one's position. The amounts come from the caller before any validation,
/// so a sum past i64 is refused (`InvalidInput`), never wrapped.
fn write_shares(
    yous: &crate::straumr::identity::Yous,
    shares: &[IdAmount],
) -> Result<Vec<IdAmount>, CoreError> {
    let mut out: Vec<IdAmount> = Vec::with_capacity(shares.len());
    for s in shares {
        let id = yous.id(&s.id);
        match out.iter_mut().find(|held| held.id == id) {
            Some(held) => {
                held.amount_minor =
                    held.amount_minor
                        .checked_add(s.amount_minor)
                        .ok_or_else(|| {
                            CoreError::InvalidInput("shares add up past the limit".into())
                        })?;
            }
            None => out.push(IdAmount {
                id,
                amount_minor: s.amount_minor,
            }),
        }
    }
    Ok(out)
}

fn yous_of(
    you_id: &str,
    other_ids: &[String],
    aliases: &[IdAlias],
) -> Result<crate::straumr::identity::Yous, CoreError> {
    parse_participant(you_id)?;
    for id in other_ids {
        parse_participant(id)?;
    }
    for a in aliases {
        parse_participant(&a.group_id)?;
        parse_participant(&a.from_id)?;
        parse_participant(&a.to_id)?;
    }
    let aliases: Vec<(String, String, String)> = aliases
        .iter()
        .map(|a| (a.group_id.clone(), a.from_id.clone(), a.to_id.clone()))
        .collect();
    Ok(crate::straumr::identity::Yous::new(you_id, other_ids).with_aliases(&aliases))
}

// ---------------------------------------------------------------------------
// flutningr over the FFI (§9): the ledger's export/import/plan surface.
// Thin annotations over the tested module: no logic here, ever (ADR-0025).
// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn ffi_export_ledger_json(snapshot: LedgerSnapshot) -> Result<String, PortError> {
    crate::flutningr::export_json(&snapshot)
}

#[uniffi::export]
pub fn ffi_import_ledger_json(text: String) -> Result<LedgerSnapshot, PortError> {
    crate::flutningr::import_json(&text)
}

/// D-210: one group's slice of a snapshot (see `flutningr::snapshot_for_group`).
#[uniffi::export]
pub fn ffi_snapshot_for_group(snapshot: LedgerSnapshot, group: String) -> LedgerSnapshot {
    crate::flutningr::snapshot_for_group(&snapshot, &group)
}

/// D-248: the Just me slice of a snapshot, for "Export Just me" (see
/// `flutningr::snapshot_personal`).
#[uniffi::export]
pub fn ffi_snapshot_personal(snapshot: LedgerSnapshot) -> LedgerSnapshot {
    crate::flutningr::snapshot_personal(&snapshot)
}

#[uniffi::export]
pub fn ffi_export_ledger_csv(snapshot: LedgerSnapshot) -> Result<String, PortError> {
    crate::flutningr::export_flat_csv(&snapshot)
}

/// The §9.5 dry run over a foreign sheet. `profile` names the app and
/// carries its configuration: member bindings for Splitwise group sheets,
/// the importing user's own participant id for BudgetBakers statements.
#[uniffi::export]
pub fn ffi_plan_import(
    csv_text: String,
    profile: ImportProfile,
    existing: LedgerSnapshot,
) -> Result<ImportPlan, PortError> {
    plan_import(&csv_text, &profile, &existing)
}

// ---------------------------------------------------------------------------
// merki over the FFI (§8.1/§8.3): category tree + deterministic rules.
// Thin annotations over the tested module: no logic here, ever (ADR-0025).
// ---------------------------------------------------------------------------

#[uniffi::export]
pub fn ffi_category_tree_default() -> crate::merki::CategoryTree {
    crate::merki::CategoryTree::with_defaults()
}

#[uniffi::export]
pub fn ffi_category_add_parent(
    tree: crate::merki::CategoryTree,
    name: String,
    icon: String,
    colour: String,
) -> Result<String, crate::merki::MerkiError> {
    let mut t = tree;
    t.add_parent(&name, &icon, &colour)
}

#[uniffi::export]
pub fn ffi_category_add_child(
    tree: crate::merki::CategoryTree,
    parent: String,
    name: String,
    icon: String,
    colour: String,
) -> Result<String, crate::merki::MerkiError> {
    let mut t = tree;
    t.add_child(&parent, &name, &icon, &colour)
}

#[uniffi::export]
pub fn ffi_category_rename(
    tree: crate::merki::CategoryTree,
    id: String,
    new_name: String,
) -> Result<crate::merki::CategoryTree, crate::merki::MerkiError> {
    let mut t = tree;
    t.rename(&id, &new_name)?;
    Ok(t)
}

#[uniffi::export]
pub fn ffi_category_archive(
    tree: crate::merki::CategoryTree,
    id: String,
) -> Result<crate::merki::CategoryTree, crate::merki::MerkiError> {
    let mut t = tree;
    t.archive(&id)?;
    Ok(t)
}

#[uniffi::export]
pub fn ffi_category_set_icon_colour(
    tree: crate::merki::CategoryTree,
    id: String,
    icon: String,
    colour: String,
) -> Result<crate::merki::CategoryTree, crate::merki::MerkiError> {
    let mut t = tree;
    t.set_icon_colour(&id, &icon, &colour)?;
    Ok(t)
}

#[uniffi::export]
pub fn ffi_rule_new_id() -> String {
    crate::merki::new_id()
}

#[uniffi::export]
pub fn ffi_rule_validate(
    tree: crate::merki::CategoryTree,
    rule: crate::merki::Rule,
) -> Result<(), crate::merki::MerkiError> {
    crate::merki::validate_rule(&tree, &rule)
}

/// THE ENGINE, over the FFI: ordered rules, first-match per action kind,
/// `ignore` terminal. Deterministic by construction: no clock, no entropy.
#[uniffi::export]
pub fn ffi_rules_evaluate(
    tree: crate::merki::CategoryTree,
    rules: Vec<crate::merki::Rule>,
    context: crate::merki::RuleContext,
) -> Result<crate::merki::RuleOutcome, crate::merki::MerkiError> {
    crate::merki::evaluate(&tree, &rules, &context)
}

/// §8.3's history preview: batch evaluation, pure, one outcome per record.
#[uniffi::export]
pub fn ffi_rules_evaluate_batch(
    tree: crate::merki::CategoryTree,
    rules: Vec<crate::merki::Rule>,
    contexts: Vec<crate::merki::RuleContext>,
) -> Result<Vec<crate::merki::RuleOutcome>, crate::merki::MerkiError> {
    crate::merki::evaluate_batch(&tree, &rules, &contexts)
}

#[uniffi::export]
pub fn ffi_merchant_table_bundled() -> crate::merki::auto::MerchantTable {
    crate::merki::auto::bundled_table()
}

/// §8.2's operator update path: parse + Ed25519-verify a signed merchant
/// table. An unverified file is never returned (same rule as the FX set).
#[uniffi::export]
pub fn ffi_merchant_table_parse_signed(
    payload: String,
    signature_hex: String,
    public_key_hex: String,
) -> Result<crate::merki::auto::MerchantTable, crate::merki::MerkiError> {
    crate::merki::auto::merchant_table_parse_signed(&payload, &signature_hex, &public_key_hex)
}

/// THE §8.2 pipeline over the FFI: user rules first, then the bundled/
/// signed merchant table, then None: uncategorised, honestly.
#[uniffi::export]
pub fn ffi_auto_categorise(
    tree: crate::merki::CategoryTree,
    table: crate::merki::auto::MerchantTable,
    rules: Vec<crate::merki::Rule>,
    context: crate::merki::RuleContext,
) -> Result<Option<String>, crate::merki::MerkiError> {
    crate::merki::auto::auto_categorise(&tree, &table, &rules, &context)
}

#[uniffi::export]
pub fn ffi_apply_import_plan(
    existing: LedgerSnapshot,
    plan: ImportPlan,
) -> Result<LedgerSnapshot, PortError> {
    apply_plan(&existing, &plan)
}

// ---------------------------------------------------------------------------
// Native-only store lifecycle (skrin). Absent from wasm by cfg: the web
// shell brings its own IndexedDB store per SPEC §10.2.
// ---------------------------------------------------------------------------

#[cfg(not(target_arch = "wasm32"))]
pub mod store {
    use super::*;
    use crate::skrin::{self, Skrin, SkrinError};
    use std::path::Path;
    use std::sync::{Mutex, MutexGuard};

    #[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
    pub enum StoreError {
        Closed,
        Sqlite(String),
        WrongKey,
        NewerSchema { found: i64, latest: i64 },
        Keystore(String),
        InvalidInput(String),
        Internal(String),
    }

    impl std::fmt::Display for StoreError {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            match self {
                StoreError::Closed => write!(f, "vault is closed"),
                StoreError::Sqlite(e) => write!(f, "sqlite: {e}"),
                StoreError::WrongKey => write!(f, "vault key does not open this store"),
                StoreError::NewerSchema { found, latest } => {
                    write!(f, "vault schema v{found} newer than supported v{latest}")
                }
                StoreError::Keystore(e) => write!(f, "keystore: {e}"),
                StoreError::InvalidInput(m) => write!(f, "invalid input: {m}"),
                StoreError::Internal(m) => write!(f, "internal: {m}"),
            }
        }
    }

    impl std::error::Error for StoreError {}

    /// D-235 (core audit L3): a keystore that throws something other than a
    /// `StoreError` (Android's `check`, `require`, an unwrap failure) reaches
    /// core as an unexpected callback error. Without this conversion uniffi
    /// panics on it and the shell sees an internal error; with it, the shell
    /// sees `StoreError.Keystore` and classifies it as a keystore failure.
    impl From<uniffi::UnexpectedUniFFICallbackError> for StoreError {
        fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
            StoreError::Keystore(e.reason)
        }
    }

    impl From<SkrinError> for StoreError {
        fn from(e: SkrinError) -> Self {
            match e {
                SkrinError::Io(e) => StoreError::Sqlite(e.to_string()),
                SkrinError::Sqlite(e) => StoreError::Sqlite(e),
                SkrinError::WrongKey => StoreError::WrongKey,
                SkrinError::NewerSchema { found, latest } => {
                    StoreError::NewerSchema { found, latest }
                }
                SkrinError::Keystore(e) => StoreError::Keystore(e),
                SkrinError::InvalidInput(m) => StoreError::InvalidInput(m.to_string()),
            }
        }
    }

    impl StoreError {
        fn into_skrin(self) -> SkrinError {
            SkrinError::Keystore(self.to_string())
        }
    }

    /// What the shell supplies (ADR-0025 §5): the platform keystore
    /// contract, implemented in Swift/Kotlin. `key` is always exactly
    /// 32 bytes. There is deliberately no way to export the key anywhere
    /// except back into memory. A-17 holds by construction.
    #[uniffi::export(callback_interface)]
    pub trait SkiptiKeystore: Send + Sync {
        fn provision(&self, key: Vec<u8>) -> Result<(), StoreError>;
        /// `None` before first provisioning.
        fn load(&self) -> Result<Option<Vec<u8>>, StoreError>;
        fn destroy(&self) -> Result<(), StoreError>;
    }

    struct KeystoreBridge {
        inner: Box<dyn SkiptiKeystore>,
    }

    impl KeystoreBridge {
        fn check_key(key: &[u8]) -> Result<skrin::VaultKey, SkrinError> {
            skrin::VaultKey::from_slice(key)
                .ok_or(SkrinError::InvalidInput("keystore key must be 32 bytes"))
        }
    }

    impl skrin::Keystore for KeystoreBridge {
        fn provision(&self, key: &[u8; 32]) -> Result<(), SkrinError> {
            // D-236: core cannot zero this copy: UniFFI's own lowering frees
            // the Vec, the buffer copies it grows and the RustBuffer on
            // Rust's heap without zeroing, and the shell keeps its own copy
            // after that: residue.
            self.inner
                .provision(key.to_vec())
                .map_err(StoreError::into_skrin)
        }
        fn load(&self) -> Result<Option<skrin::VaultKey>, SkrinError> {
            match self.inner.load().map_err(StoreError::into_skrin)? {
                None => Ok(None),
                Some(bytes) => {
                    // D-236: the shell's bytes are zeroed when they drop,
                    // whether they make a key or are refused as one.
                    let bytes = zeroize::Zeroizing::new(bytes);
                    Ok(Some(Self::check_key(&bytes)?))
                }
            }
        }
        fn destroy(&self) -> Result<(), SkrinError> {
            self.inner.destroy().map_err(StoreError::into_skrin)
        }
    }

    /// One ledger record across the boundary: header fields flattened plus
    /// the opaque blob. Device ids are hex; the blob is raw bytes.
    #[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
    pub struct RecordWire {
        pub version: u16,
        pub record_type: u8,
        /// `None` = personal scope.
        pub group_id: Option<String>,
        pub epoch: u32,
        pub hlc_millis: u64,
        pub hlc_counter: u32,
        pub hlc_device_id: String,
        pub author_device_id: String,
        pub nonce_session: Vec<u8>,
        pub flags: u8,
        pub blob: Vec<u8>,
    }

    fn wire_header(rec: &RecordWire) -> Result<crate::straumr::RecordHeader, StoreError> {
        use crate::straumr::{Hlc, RecordHeader, RecordType, Scope};
        let record_type = RecordType::from_u8(rec.record_type).ok_or_else(|| {
            StoreError::InvalidInput(format!("unknown record type {}", rec.record_type))
        })?;
        let scope = match &rec.group_id {
            None => Scope::Personal,
            Some(hex) => Scope::Group(parse_participant(hex).map_err(|e| {
                StoreError::InvalidInput(match e {
                    CoreError::InvalidInput(m) => m,
                    other => other.to_string(),
                })
            })?),
        };
        if rec.nonce_session.len() != 4 {
            return Err(StoreError::InvalidInput(
                "nonce_session must be 4 bytes".into(),
            ));
        }
        let mut nonce = [0u8; 4];
        nonce.copy_from_slice(&rec.nonce_session);
        Ok(RecordHeader {
            version: rec.version,
            record_type,
            scope,
            epoch: rec.epoch,
            hlc: Hlc {
                millis: rec.hlc_millis,
                counter: rec.hlc_counter,
                device_id: parse_strict_16(&rec.hlc_device_id)?,
            },
            author_device_id: parse_strict_16(&rec.author_device_id)?,
            nonce_session: nonce,
            flags: rec.flags,
        })
    }

    fn parse_strict_16(hex: &str) -> Result<[u8; 16], StoreError> {
        parse_participant(hex).map_err(|e| StoreError::InvalidInput(e.to_string()))
    }

    fn header_wire(header: &crate::straumr::RecordHeader, blob: Vec<u8>) -> RecordWire {
        use crate::straumr::Scope;
        RecordWire {
            version: header.version,
            record_type: header.record_type.as_u8(),
            group_id: match header.scope {
                Scope::Personal => None,
                Scope::Group(g) => Some(hex_encode(&g)),
            },
            epoch: header.epoch,
            hlc_millis: header.hlc.millis,
            hlc_counter: header.hlc.counter,
            hlc_device_id: hex_encode(&header.hlc.device_id),
            author_device_id: hex_encode(&header.author_device_id),
            nonce_session: header.nonce_session.to_vec(),
            flags: header.flags,
            blob,
        }
    }

    /// An opened vault. Dropping the last reference closes the database;
    /// `close()` does it explicitly and makes later use a typed error.
    #[derive(uniffi::Object)]
    pub struct SkiptiVault {
        inner: Mutex<Option<Skrin>>,
    }

    impl std::fmt::Debug for SkiptiVault {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.debug_struct("SkiptiVault")
                .field("open", &self.inner.lock().map(|g| g.is_some()).is_ok())
                .finish_non_exhaustive()
        }
    }

    impl SkiptiVault {
        fn guard(&self) -> Result<MutexGuard<'_, Option<Skrin>>, StoreError> {
            self.inner
                .lock()
                .map_err(|_| StoreError::Internal("vault mutex poisoned".into()))
        }
    }

    #[uniffi::export]
    impl SkiptiVault {
        /// Opens (or creates) the vault, taking/creating its key through
        /// the supplied keystore. A wrong key is WrongKey, never a re-key.
        #[uniffi::constructor]
        pub fn open(path: String, keystore: Box<dyn SkiptiKeystore>) -> Result<Self, StoreError> {
            let bridge = KeystoreBridge { inner: keystore };
            let skrin_store = Skrin::open(Path::new(&path), &bridge)?;
            Ok(SkiptiVault {
                inner: Mutex::new(Some(skrin_store)),
            })
        }

        pub fn schema_version(&self) -> Result<i64, StoreError> {
            let g = self.guard()?;
            let store = g.as_ref().ok_or(StoreError::Closed)?;
            Ok(store.schema_version()?)
        }

        pub fn put_record(&self, record: RecordWire) -> Result<(), StoreError> {
            let header = wire_header(&record)?;
            let g = self.guard()?;
            let store = g.as_ref().ok_or(StoreError::Closed)?;
            Ok(store.put_record(&header, &record.blob)?)
        }

        pub fn all_records(&self) -> Result<Vec<RecordWire>, StoreError> {
            let g = self.guard()?;
            let store = g.as_ref().ok_or(StoreError::Closed)?;
            Ok(store
                .all_records()?
                .into_iter()
                .map(|(header, blob)| header_wire(&header, blob))
                .collect())
        }

        pub fn kv_set(&self, key: String, value: Vec<u8>) -> Result<(), StoreError> {
            let g = self.guard()?;
            let store = g.as_ref().ok_or(StoreError::Closed)?;
            Ok(store.kv_set(&key, &value)?)
        }

        pub fn kv_get(&self, key: String) -> Result<Option<Vec<u8>>, StoreError> {
            let g = self.guard()?;
            let store = g.as_ref().ok_or(StoreError::Closed)?;
            Ok(store.kv_get(&key)?)
        }

        /// Removes EVERY record (never the kv projection, never key material):
        /// the lost-device restore's first half: the device simulates total
        /// data loss, then rebuilds records + projection from the relay. The
        /// keystore gate above this call is what makes it safe to expose.
        pub fn clear_records(&self) -> Result<(), StoreError> {
            let g = self.guard()?;
            let store = g.as_ref().ok_or(StoreError::Closed)?;
            store.clear_records()?;
            Ok(())
        }

        /// D-210: forgets one group on this phone, erasing its records and no
        /// other group's and none of the personal ledger. `group` is the
        /// group's 32-hex id; anything else is refused.
        pub fn forget_group(&self, group: String) -> Result<(), StoreError> {
            let id = parse_strict_16(&group)?;
            let g = self.guard()?;
            let store = g.as_ref().ok_or(StoreError::Closed)?;
            store.delete_group_records(&id)?;
            Ok(())
        }

        /// Explicit close. Subsequent operations answer Closed.
        ///
        /// Named close_vault, deliberately NOT `close`: UniFFI generates an
        /// AutoCloseable `close()` on the Kotlin object for pointer release,
        /// and the API method's own `close()` produced conflicting overloads
        /// there (caught the first time the committed Kotlin ever compiled,
        /// D-91; the freshness gate diffs bytes and never ran kotlinc). The
        /// two closes mean different things and keep different names:
        /// closeVault() is the §-semantic operation returning Result;
        /// AutoCloseable.close() is pure resource release.
        pub fn close_vault(&self) -> Result<(), StoreError> {
            let mut g = self.guard()?;
            match g.take() {
                Some(_) => Ok(()),
                None => Err(StoreError::Closed),
            }
        }
    }

    /// ADR-0034: one record, packed to its canonical bytes:
    /// `canonical header ‖ blob`. The header's canonical
    /// form is straumr's own (the byte layout the record path is built on);
    /// the blob rides opaque.
    #[uniffi::export]
    pub fn ffi_record_pack(record: RecordWire) -> Result<Vec<u8>, StoreError> {
        let header = wire_header(&record)?;
        let mut out = header.canonical_bytes();
        out.extend_from_slice(&record.blob);
        Ok(out)
    }

    /// ADR-0034: the inverse of [Self::ffi_record_pack]. A truncated buffer
    /// (shorter than the fixed canonical header) is a typed error, never a
    /// short read pretending to be a record.
    #[uniffi::export]
    pub fn ffi_record_unpack(packed: Vec<u8>) -> Result<RecordWire, StoreError> {
        use crate::straumr::RecordHeader;
        const HEADER_LEN: usize = 73; // version(2) type(1) scope(1+16) epoch(4)
                                      //                               hlc(8+4+16) author(16) nonce(4) flags(1)
        if packed.len() < HEADER_LEN {
            return Err(StoreError::InvalidInput(format!(
                "packed record shorter than its header: {} < {}",
                packed.len(),
                HEADER_LEN
            )));
        }
        let header = RecordHeader::from_canonical_bytes(&packed[..HEADER_LEN])
            .map_err(|e| StoreError::InvalidInput(format!("record header: {e:?}")))?;
        Ok(header_wire(&header, packed[HEADER_LEN..].to_vec()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pid(n: u8) -> String {
        let mut bytes = [0u8; 16];
        bytes[15] = n;
        hex_encode(&bytes)
    }

    // ---- D-210: redistribution across the boundary ----

    fn grp() -> String {
        "c".repeat(32)
    }

    fn spent(payer: u8, minor: i64, shares: &[(u8, i64)]) -> ExpenseInput {
        ExpenseInput {
            payer: pid(payer),
            paid_minor: minor,
            currency_code: "INR".into(),
            shares: shares
                .iter()
                .map(|(p, a)| IdAmount {
                    id: pid(*p),
                    amount_minor: *a,
                })
                .collect(),
            group: Some(grp()),
            conversion: None,
        }
    }

    #[test]
    fn a_redistribution_moves_the_balance_and_every_view_agrees() {
        // Sam (4) owes 1000.00: Prithvi (1) paid 1000.00 for Sam alone.
        let expenses = vec![spent(1, 100000, &[(4, 100000)])];
        let shares = ffi_redistribute_equal(-100000, vec![pid(3), pid(1), pid(2)]).unwrap();
        assert_eq!(
            shares,
            vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: -33334
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: -33333
                },
                IdAmount {
                    id: pid(3),
                    amount_minor: -33333
                },
            ]
        );
        let r = RedistributionInput {
            group: grp(),
            from: pid(4),
            currency_code: "INR".into(),
            shares,
        };
        let lines = ffi_balance_line(
            expenses.clone(),
            vec![],
            vec![r.clone()],
            vec![],
            pid(4),
            Some(grp()),
        )
        .unwrap();
        assert_eq!(
            lines.iter().map(|l| l.viewer_net_minor).sum::<i64>(),
            0,
            "Sam ends at zero"
        );
        let all = ffi_balances_compute(expenses.clone(), vec![], vec![r.clone()]).unwrap();
        let net = |id: String| {
            all[0]
                .entries
                .iter()
                .find(|e| e.id == id)
                .map(|e| e.minor_units)
                .unwrap_or(0)
        };
        assert_eq!(
            (net(pid(1)), net(pid(2)), net(pid(3)), net(pid(4))),
            (66666, -33333, -33333, 0)
        );
        assert!(ffi_member_has_history(
            vec![],
            vec![],
            vec![r.clone()],
            grp(),
            pid(3)
        ));
        assert!(ffi_member_has_history(
            vec![],
            vec![],
            vec![r],
            grp(),
            pid(4)
        ));
        assert!(!ffi_member_has_history(
            vec![],
            vec![],
            vec![],
            grp(),
            pid(4)
        ));
    }

    #[test]
    fn a_redistribution_in_another_group_does_not_touch_this_one() {
        let r = RedistributionInput {
            group: "e".repeat(32),
            from: pid(4),
            currency_code: "INR".into(),
            shares: vec![IdAmount {
                id: pid(1),
                amount_minor: -500,
            }],
        };
        let lines = ffi_balance_line(vec![], vec![], vec![r], vec![], pid(1), Some(grp())).unwrap();
        assert!(lines.is_empty());
    }

    // ---- D-221: settled progress (goldens: .drafts/d221/settled.py, settled.out) ----
    // People: p = pid(1), s = pid(2), m = pid(3), r = pid(4), x = pid(5).
    // Group goa = grp(), the other group flat = "e" x 32, personal = None.

    fn flat() -> String {
        "e".repeat(32)
    }

    fn sp_ex(
        payer: u8,
        paid: i64,
        shares: &[(u8, i64)],
        cur: &str,
        group: Option<String>,
    ) -> ExpenseInput {
        ExpenseInput {
            payer: pid(payer),
            paid_minor: paid,
            currency_code: cur.into(),
            shares: shares
                .iter()
                .map(|(p, a)| IdAmount {
                    id: pid(*p),
                    amount_minor: *a,
                })
                .collect(),
            group,
            conversion: None,
        }
    }

    fn sp_st(from: u8, to: u8, amount: i64, group: Option<String>) -> SettlementInput {
        SettlementInput {
            from: pid(from),
            to: pid(to),
            amount_minor: amount,
            currency_code: "INR".into(),
            group,
        }
    }

    fn sp_goa() -> Vec<ExpenseInput> {
        let g = Some(grp());
        vec![
            sp_ex(
                1,
                1_200_000,
                &[(1, 300_000), (2, 300_000), (3, 300_000), (4, 300_000)],
                "INR",
                g.clone(),
            ),
            sp_ex(
                4,
                480_000,
                &[(1, 120_000), (2, 120_000), (3, 120_000), (4, 120_000)],
                "INR",
                g.clone(),
            ),
            sp_ex(
                3,
                160_000,
                &[(1, 40_000), (2, 40_000), (3, 40_000), (4, 40_000)],
                "INR",
                g,
            ),
        ]
    }

    fn sp_out(
        code: &str,
        settled: i64,
        outstanding: i64,
        bp: u32,
        done: bool,
    ) -> SettledProgressOut {
        SettledProgressOut {
            currency_code: code.into(),
            settled_minor: settled,
            outstanding_minor: outstanding,
            basis_points: bp,
            settled: done,
        }
    }

    fn sp_run(e: Vec<ExpenseInput>, s: Vec<SettlementInput>) -> Vec<SettledProgressOut> {
        ffi_settled_progress(e, s, vec![], grp()).unwrap()
    }

    #[test]
    fn settled_progress_case_a_goa_one_payment() {
        let got = sp_run(sp_goa(), vec![sp_st(2, 1, 200_000, Some(grp()))]);
        assert_eq!(got, vec![sp_out("INR", 200_000, 560_000, 2631, false)]);
    }

    #[test]
    fn settled_progress_case_b_goa_no_payment() {
        let got = sp_run(sp_goa(), vec![]);
        assert_eq!(got, vec![sp_out("INR", 0, 760_000, 0, false)]);
    }

    #[test]
    fn settled_progress_case_c_goa_all_paid() {
        let g = Some(grp());
        let got = sp_run(
            sp_goa(),
            vec![
                sp_st(2, 1, 460_000, g.clone()),
                sp_st(3, 1, 280_000, g.clone()),
                sp_st(3, 4, 20_000, g),
            ],
        );
        assert_eq!(got, vec![sp_out("INR", 760_000, 0, 10000, true)]);
    }

    #[test]
    fn settled_progress_case_d_overpaid() {
        let got = sp_run(
            vec![sp_ex(
                1,
                10_000,
                &[(1, 5_000), (2, 5_000)],
                "INR",
                Some(grp()),
            )],
            vec![sp_st(2, 1, 8_000, Some(grp()))],
        );
        assert_eq!(got, vec![sp_out("INR", 8_000, 3_000, 7272, false)]);
    }

    #[test]
    fn settled_progress_case_e_two_currencies() {
        let mut e = sp_goa();
        e.push(sp_ex(
            2,
            10_000,
            &[(2, 5_000), (1, 5_000)],
            "USD",
            Some(grp()),
        ));
        assert_eq!(
            sp_run(e, vec![]),
            vec![
                sp_out("INR", 0, 760_000, 0, false),
                sp_out("USD", 0, 5_000, 0, false)
            ]
        );
    }

    #[test]
    fn settled_progress_case_f_other_group_and_personal_ignored() {
        let mut e = sp_goa();
        e.push(sp_ex(5, 99_000, &[(1, 99_000)], "INR", Some(flat())));
        e.push(sp_ex(1, 7_000, &[(2, 7_000)], "INR", None));
        let got = sp_run(
            e,
            vec![
                sp_st(2, 1, 200_000, Some(grp())),
                sp_st(1, 5, 5_000, Some(flat())),
            ],
        );
        assert_eq!(got, vec![sp_out("INR", 200_000, 560_000, 2631, false)]);
    }

    #[test]
    fn settled_progress_case_g_only_your_own_share() {
        let got = sp_run(
            vec![sp_ex(1, 5_000, &[(1, 5_000)], "INR", Some(grp()))],
            vec![],
        );
        assert_eq!(got, vec![sp_out("INR", 0, 0, 10000, true)]);
    }

    #[test]
    fn settled_progress_case_h_no_records_in_the_group() {
        let got = sp_run(
            vec![sp_ex(1, 5_000, &[(2, 5_000)], "INR", Some(flat()))],
            vec![],
        );
        assert_eq!(got, vec![]);
    }

    /// p paid 90,000 shared equally by p, s and r: nets p +60,000,
    /// s -30,000, r -30,000. r's -30,000 is redistributed onto p and s at
    /// -15,000 each, i.e. the settlements r -> p 15,000 and r -> s 15,000:
    /// r +30,000 = 0, p 60,000 - 15,000 = +45,000, s -30,000 - 15,000 =
    /// -45,000. Outstanding is the positive side, 45,000 (60,000 without
    /// the redistribution); nothing was paid, so settled is 0 and the
    /// basis points 0.
    #[test]
    fn settled_progress_ignores_redistributions_as_payments() {
        let e = vec![sp_ex(
            1,
            90_000,
            &[(1, 30_000), (2, 30_000), (4, 30_000)],
            "INR",
            Some(grp()),
        )];
        let r = RedistributionInput {
            group: grp(),
            from: pid(4),
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: -15_000,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: -15_000,
                },
            ],
        };
        let got = ffi_settled_progress(e, vec![], vec![r], grp()).unwrap();
        assert_eq!(got, vec![sp_out("INR", 0, 45_000, 0, false)]);
    }

    #[test]
    fn settled_progress_refuses_a_malformed_group() {
        let got = ffi_settled_progress(sp_goa(), vec![], vec![], "not hex".into());
        assert!(matches!(got, Err(CoreError::InvalidInput(_))), "{got:?}");
    }

    // Final fix wave C1 goldens: .drafts/d221/settled.py cases I1 to L.

    /// p paid 20,000 for p and s (10,000 each); s "pays" p -4,000 (a bad
    /// record, but money_make takes any i64). Nets: p +14,000, s -14,000, so
    /// outstanding 14,000, settled -4,000, whole 10,000, raw floor
    /// -4,000 basis points. Clamped to 0 (it used to fail u32::try_from and
    /// take the group page down).
    #[test]
    fn settled_progress_clamps_a_negative_settlement_to_zero() {
        let got = sp_run(
            vec![sp_ex(
                1,
                20_000,
                &[(1, 10_000), (2, 10_000)],
                "INR",
                Some(grp()),
            )],
            vec![sp_st(2, 1, -4_000, Some(grp()))],
        );
        assert_eq!(got, vec![sp_out("INR", -4_000, 14_000, 0, false)]);
    }

    /// s paid 50 for p; s -> p of -100: s +50 - 100 = -50, p -50 + 100 =
    /// +50. Outstanding 50, settled -100, whole -50, raw 20,000 basis
    /// points (above 100%). Clamped to 10,000.
    #[test]
    fn settled_progress_clamps_a_negative_whole_to_a_full_meter() {
        let got = sp_run(
            vec![sp_ex(2, 50, &[(1, 50)], "INR", Some(grp()))],
            vec![sp_st(2, 1, -100, Some(grp()))],
        );
        assert_eq!(got, vec![sp_out("INR", -100, 50, 10_000, false)]);
    }

    fn sp_shared_three() -> Vec<ExpenseInput> {
        vec![sp_ex(
            1,
            90_000,
            &[(1, 30_000), (2, 30_000), (4, 30_000)],
            "INR",
            Some(grp()),
        )]
    }

    fn sp_redistribution(group: String) -> RedistributionInput {
        RedistributionInput {
            group,
            from: pid(4),
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: -15_000,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: -15_000,
                },
            ],
        }
    }

    /// The same redistribution as the test above, filed under ANOTHER group:
    /// it must not move this group's balances. p +60,000 stays outstanding
    /// (45,000 if the filter let it through).
    #[test]
    fn settled_progress_ignores_another_groups_redistribution() {
        let got = ffi_settled_progress(
            sp_shared_three(),
            vec![],
            vec![sp_redistribution(flat())],
            grp(),
        )
        .unwrap();
        assert_eq!(got, vec![sp_out("INR", 0, 60_000, 0, false)]);
    }

    /// s paid USD 5,000 frozen at 83.25 into INR 416,250, shared 208,125 each
    /// with p. The expense lands in INR only (no USD row): s +208,125, p
    /// -208,125. p pays s 100,000 INR: outstanding 108,125, settled 100,000,
    /// whole 208,125, floor(1,000,000,000 / 208,125) = 4,804 (4,804.8).
    #[test]
    fn settled_progress_follows_a_converted_expense_into_its_currency() {
        let e = vec![ExpenseInput {
            payer: pid(2),
            paid_minor: 5_000,
            currency_code: "USD".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: 208_125,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: 208_125,
                },
            ],
            group: Some(grp()),
            conversion: Some(ConversionInput {
                converted_minor: 416_250,
                converted_currency: "INR".into(),
                rate_num: 8_325,
                rate_den: 100,
                rate_timestamp_ms: 1_724_400_000_000,
            }),
        }];
        let got = sp_run(e, vec![sp_st(1, 2, 100_000, Some(grp()))]);
        assert_eq!(got, vec![sp_out("INR", 100_000, 108_125, 4_804, false)]);
    }

    /// r has been removed from the group but still owes 30,000 (core takes no
    /// removed list: a removed person stays in the nets until a redistribution
    /// moves them to zero). p +60,000, s -30,000, r -30,000; s pays p 30,000:
    /// p +30,000, s 0, r -30,000. Outstanding 30,000, settled 30,000, 5,000
    /// basis points, not settled.
    #[test]
    fn settled_progress_keeps_a_removed_persons_debt_outstanding() {
        let got = sp_run(sp_shared_three(), vec![sp_st(2, 1, 30_000, Some(grp()))]);
        assert_eq!(got, vec![sp_out("INR", 30_000, 30_000, 5_000, false)]);
    }

    #[test]
    fn the_core_version_is_0_3_0() {
        assert_eq!(skipti_core_version(), "0.3.0");
    }

    #[test]
    fn the_group_snapshot_crosses_the_boundary() {
        let snapshot = LedgerSnapshot {
            groups: vec![crate::flutningr::PortGroup {
                id: grp(),
                name: "Goa".into(),
                settlement_currency: "INR".into(),
                members: vec![pid(1), pid(2)],
            }],
            ..LedgerSnapshot::default()
        };
        assert_eq!(ffi_snapshot_for_group(snapshot.clone(), grp()), snapshot);
        assert_eq!(
            ffi_snapshot_for_group(snapshot, "e".repeat(32)),
            LedgerSnapshot::default()
        );
    }

    #[test]
    fn the_personal_snapshot_crosses_the_boundary() {
        let mine = crate::flutningr::PortExpense {
            payer: pid(1),
            paid_minor: 300,
            currency_code: "INR".into(),
            group: None,
            conversion: None,
            shares: vec![crate::flutningr::ExpenseShare {
                id: pid(1),
                amount_minor: 300,
            }],
            note: Some("lunch".into()),
            timestamp_ms: None,
            category: None,
        };
        let theirs = crate::flutningr::PortExpense {
            group: Some(grp()),
            ..mine.clone()
        };
        let snapshot = LedgerSnapshot {
            groups: vec![crate::flutningr::PortGroup {
                id: grp(),
                name: "Goa".into(),
                settlement_currency: "INR".into(),
                members: vec![pid(1), pid(2)],
            }],
            expenses: vec![theirs, mine.clone()],
            ..LedgerSnapshot::default()
        };
        assert_eq!(
            ffi_snapshot_personal(snapshot),
            LedgerSnapshot {
                expenses: vec![mine],
                ..LedgerSnapshot::default()
            }
        );
    }

    #[test]
    fn a_malformed_redistribution_in_another_group_does_not_stop_this_one() {
        let bad = RedistributionInput {
            group: "e".repeat(32),
            from: "not hex".into(),
            currency_code: "INR".into(),
            shares: vec![IdAmount {
                id: pid(1),
                amount_minor: -500,
            }],
        };
        let expenses = vec![spent(1, 900, &[(1, 450), (2, 450)])];
        let line = ffi_balance_line(
            expenses.clone(),
            vec![],
            vec![bad.clone()],
            vec![],
            pid(1),
            Some(grp()),
        )
        .unwrap();
        assert_eq!(line[0].viewer_net_minor, 450);
        // The same record in this group's own scope still fails loudly.
        let own = RedistributionInput {
            group: grp(),
            ..bad
        };
        assert!(
            ffi_balance_line(expenses, vec![], vec![own], vec![], pid(1), Some(grp())).is_err()
        );
    }

    #[test]
    fn the_tally_says_what_is_left_and_when_it_adds_up() {
        let s = |v: &[(u8, i64)]| {
            v.iter()
                .map(|(p, a)| IdAmount {
                    id: pid(*p),
                    amount_minor: *a,
                })
                .collect::<Vec<_>>()
        };
        let members = vec![pid(1), pid(2), pid(3)];
        assert_eq!(
            ffi_redistribution_tally(
                -100000,
                pid(4),
                members.clone(),
                s(&[(1, -50000), (2, -30000), (3, -20000)])
            ),
            RedistributionTally {
                assigned_minor: -100000,
                left_minor: 0,
                ok: true
            }
        );
        assert_eq!(
            ffi_redistribution_tally(
                -100000,
                pid(4),
                members.clone(),
                s(&[(1, -50000), (2, -45000)])
            ),
            RedistributionTally {
                assigned_minor: -95000,
                left_minor: -5000,
                ok: false
            }
        );
        // Someone not offered (5) or the person themselves (4): never ok.
        assert!(!ffi_redistribution_tally(-500, pid(4), members.clone(), s(&[(5, -500)])).ok);
        assert!(!ffi_redistribution_tally(-500, pid(4), members, s(&[(4, -500)])).ok);
        // Overflow keeps the sign: i128 sums past i64 saturate to MIN or MAX.
        let t = ffi_redistribution_tally(
            i64::MIN,
            pid(4),
            vec![pid(1), pid(2)],
            s(&[(1, i64::MIN), (2, i64::MIN)]),
        );
        assert_eq!((t.assigned_minor, t.left_minor), (i64::MIN, i64::MAX));
        assert!(!t.ok);
        let t = ffi_redistribution_tally(
            i64::MAX,
            pid(4),
            vec![pid(1), pid(2)],
            s(&[(1, i64::MAX), (2, i64::MAX)]),
        );
        assert_eq!((t.assigned_minor, t.left_minor), (i64::MAX, i64::MIN + 1));
        assert!(!t.ok);
    }

    #[test]
    fn a_redistribution_of_money_owed_brings_everyone_to_zero() {
        // .drafts/c3/golden.py "owed, odd cents": Sam (4) paid 1000 for 1, 2, 3
        // (334, 333, 333). Sam +1000, the others -334, -333, -333. Sam's +1000
        // goes equally onto 1, 2, 3 (+334, +333, +333): everyone ends at 0.
        let expenses = vec![ExpenseInput {
            payer: pid(4),
            paid_minor: 1000,
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: 334,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: 333,
                },
                IdAmount {
                    id: pid(3),
                    amount_minor: 333,
                },
            ],
            group: Some(grp()),
            conversion: None,
        }];
        let shares = ffi_redistribute_equal(1000, vec![pid(3), pid(1), pid(2)]).unwrap();
        assert_eq!(
            shares.iter().map(|s| s.amount_minor).collect::<Vec<_>>(),
            vec![334, 333, 333]
        );
        let r = RedistributionInput {
            group: grp(),
            from: pid(4),
            currency_code: "INR".into(),
            shares,
        };
        let net = |lines: &[CurrencyBalanceOut], id: String| {
            lines
                .iter()
                .flat_map(|l| l.entries.iter())
                .find(|e| e.id == id)
                .map(|e| e.minor_units)
                .unwrap_or(0)
        };
        let before = ffi_balances_compute(expenses.clone(), vec![], vec![]).unwrap();
        assert_eq!(
            (
                net(&before, pid(1)),
                net(&before, pid(2)),
                net(&before, pid(3)),
                net(&before, pid(4))
            ),
            (-334, -333, -333, 1000)
        );
        let after = ffi_balances_compute(expenses, vec![], vec![r]).unwrap();
        for p in 1..=4u8 {
            assert_eq!(net(&after, pid(p)), 0, "pid {p} ends at zero");
        }
    }

    #[test]
    fn a_redistributed_person_is_collapsed_out_of_the_pairwise_views() {
        // Derived by hand. Prithvi (1) paid 100000 for Sam (4); Sam's -100000
        // goes equally onto Prithvi, Rahul (2) and Asha (3): -33334, -33333,
        // -33333, applied as Sam -> Prithvi 33334, Sam -> Rahul 33333,
        // Sam -> Asha 33333. The matrix is then (Sam, Prithvi) 66666,
        // (Rahul, Sam) 33333, (Asha, Sam) 33333, and Sam's net is 0.
        // Collapsing Sam: debtors Rahul 33333, Asha 33333; creditor Prithvi
        // 66666; so Rahul -> Prithvi 33333 and Asha -> Prithvi 33333.
        let expenses = vec![spent(1, 100_000, &[(4, 100_000)])];
        let r = RedistributionInput {
            group: grp(),
            from: pid(4),
            currency_code: "INR".into(),
            shares: ffi_redistribute_equal(-100_000, vec![pid(1), pid(2), pid(3)]).unwrap(),
        };
        let line = |viewer: u8| {
            let lines = balance_line(
                &expenses,
                &[],
                std::slice::from_ref(&r),
                &[],
                &pid(viewer),
                Some(&grp()),
            )
            .unwrap();
            assert_eq!(lines.len(), 1);
            lines.into_iter().next().unwrap()
        };
        let entry = |id: u8, minor: i64| ShareOut {
            id: pid(id),
            minor_units: minor,
        };
        let prithvi = line(1);
        assert_eq!(prithvi.viewer_net_minor, 66_666);
        assert_eq!(prithvi.entries, vec![entry(2, 33_333), entry(3, 33_333)]);
        let sam = line(4);
        assert_eq!(sam.viewer_net_minor, 0);
        assert!(sam.entries.is_empty(), "{:?}", sam.entries);
        let rahul = line(2);
        assert_eq!(rahul.entries, vec![entry(1, -33_333)]);
        // The plan reads nets, which the collapse leaves alone: nets
        // Prithvi +66666, Rahul -33333, Asha -33333; simplify takes the
        // tied debtors in id order.
        let plan = |t: &TransferOut| (t.from.clone(), t.to.clone(), t.amount_minor);
        let want = vec![(pid(2), pid(1), 33_333), (pid(3), pid(1), 33_333)];
        for l in [&prithvi, &sam, &rahul] {
            assert_eq!(l.transfers.iter().map(plan).collect::<Vec<_>>(), want);
        }
        // A redistribution in another group is out of scope: Sam's debt to
        // Prithvi stays on Sam's card in this group.
        let elsewhere = RedistributionInput {
            group: "d".repeat(32),
            ..r.clone()
        };
        let other = balance_line(&expenses, &[], &[elsewhere], &[], &pid(4), Some(&grp())).unwrap();
        assert_eq!(other[0].entries, vec![entry(1, -100_000)]);
        // Sam is also a removed person: collapsed once, the same views.
        let both = balance_line(
            &expenses,
            &[],
            std::slice::from_ref(&r),
            &[pid(4)],
            &pid(1),
            Some(&grp()),
        )
        .unwrap();
        assert_eq!(both, vec![prithvi.clone()]);
    }

    /// D-210 (final review C1), derived by hand. Asha (3) paid 10000 for
    /// Sam (4); Sam paid 10000 for Prithvi (1). Nets: Asha +10000, Sam 0,
    /// Prithvi -10000, so Sam's removal moves nothing and writes no
    /// redistribution. Pairwise: (Sam, Asha) 10000 and (Prithvi, Sam) 10000.
    /// Collapsing the removed Sam: debtor Prithvi 10000, creditor Asha
    /// 10000, so (Prithvi, Asha) 10000. Prithvi owes Asha 10000; no card
    /// names Sam.
    #[test]
    fn a_removed_person_at_zero_is_collapsed_out_of_the_pairwise_views() {
        let expenses = vec![
            spent(3, 10_000, &[(4, 10_000)]),
            spent(4, 10_000, &[(1, 10_000)]),
        ];
        let line = |viewer: u8, removed: &[String]| {
            let lines =
                balance_line(&expenses, &[], &[], removed, &pid(viewer), Some(&grp())).unwrap();
            assert_eq!(lines.len(), 1);
            lines.into_iter().next().unwrap()
        };
        let entry = |id: u8, minor: i64| ShareOut {
            id: pid(id),
            minor_units: minor,
        };
        // Not removed: Sam sits between them on both cards.
        assert_eq!(line(1, &[]).entries, vec![entry(4, -10_000)]);
        assert_eq!(line(3, &[]).entries, vec![entry(4, 10_000)]);
        let removed = vec![pid(4)];
        let prithvi = line(1, &removed);
        assert_eq!(prithvi.viewer_net_minor, -10_000);
        assert_eq!(prithvi.entries, vec![entry(3, -10_000)]);
        let asha = line(3, &removed);
        assert_eq!(asha.viewer_net_minor, 10_000);
        assert_eq!(asha.entries, vec![entry(1, 10_000)]);
        let sam = line(4, &removed);
        assert_eq!(sam.viewer_net_minor, 0);
        assert!(sam.entries.is_empty(), "{:?}", sam.entries);
        // The plan reads nets, untouched: Prithvi pays Asha.
        let plan = |t: &TransferOut| (t.from.clone(), t.to.clone(), t.amount_minor);
        for l in [&prithvi, &asha, &sam] {
            assert_eq!(
                l.transfers.iter().map(plan).collect::<Vec<_>>(),
                vec![(pid(1), pid(3), 10_000)]
            );
        }
    }

    // ---- D-218: zero-net loops are cancelled out of the pairwise view ----
    //
    // Goldens re-derived independently in .drafts/d218/cycles.py (L1, L2,
    // G2, G6). Each edge (d, c) below is an expense paid by c with d's share.

    fn owes(debtor: u8, creditor: u8, minor: i64) -> ExpenseInput {
        spent(creditor, minor, &[(debtor, minor)])
    }

    fn paid_back(from: u8, to: u8, minor: i64) -> SettlementInput {
        SettlementInput {
            from: pid(from),
            to: pid(to),
            amount_minor: minor,
            currency_code: "INR".into(),
            group: Some(grp()),
        }
    }

    fn entry_of(id: u8, minor: i64) -> ShareOut {
        ShareOut {
            id: pid(id),
            minor_units: minor,
        }
    }

    fn inr_line(
        expenses: &[ExpenseInput],
        settlements: &[SettlementInput],
        removed: &[String],
        viewer: u8,
    ) -> BalanceLineOut {
        let lines = balance_line(
            expenses,
            settlements,
            &[],
            removed,
            &pid(viewer),
            Some(&grp()),
        )
        .unwrap();
        assert_eq!(lines.len(), 1);
        lines.into_iter().next().unwrap()
    }

    /// L1 (.drafts/d218/cycles.py): B paid 1000 for A, C paid 1000 for B,
    /// and Settle all had A pay C 1000. Every net is 0; the raw pairwise
    /// view is the loop A -> B -> C -> A (A saw B -1000 and C +1000 before
    /// D-218). Nobody has a card now.
    #[test]
    fn balance_line_hides_a_zero_net_loop_after_settle_all() {
        let expenses = vec![owes(1, 2, 1000), owes(2, 3, 1000)];
        let settlements = vec![paid_back(1, 3, 1000)];
        for viewer in [1, 2, 3] {
            let line = inr_line(&expenses, &settlements, &[], viewer);
            assert_eq!(line.viewer_net_minor, 0, "viewer {viewer}");
            assert!(
                line.entries.is_empty(),
                "viewer {viewer}: {:?}",
                line.entries
            );
            assert!(line.transfers.is_empty(), "viewer {viewer}");
        }
    }

    /// L2 (.drafts/d218/cycles.py): A owes B 1000, B owes C 600, and A pays
    /// C 600. Nets: A -400, B +400, C 0. The loop A -> B -> C -> A cancels
    /// by 600, leaving A owes B 400. The plan is what `norn::simplify`
    /// gives on the same nets.
    #[test]
    fn balance_line_keeps_the_rest_of_a_partially_settled_loop() {
        let expenses = vec![owes(1, 2, 1000), owes(2, 3, 600)];
        let settlements = vec![paid_back(1, 3, 600)];
        let a = inr_line(&expenses, &settlements, &[], 1);
        let b = inr_line(&expenses, &settlements, &[], 2);
        let c = inr_line(&expenses, &settlements, &[], 3);
        assert_eq!(
            (a.viewer_net_minor, &a.entries[..]),
            (-400, &[entry_of(2, -400)][..])
        );
        assert_eq!(
            (b.viewer_net_minor, &b.entries[..]),
            (400, &[entry_of(1, 400)][..])
        );
        assert_eq!(c.viewer_net_minor, 0);
        assert!(c.entries.is_empty(), "{:?}", c.entries);
        let plan = |t: &TransferOut| (t.from.clone(), t.to.clone(), t.amount_minor);
        let want: Vec<_> = crate::norn::simplify(&[
            (parse_participant(&pid(1)).unwrap(), -400),
            (parse_participant(&pid(2)).unwrap(), 400),
        ])
        .into_iter()
        .map(|t| {
            (
                hex_encode(&t.from),
                hex_encode(&t.to),
                narrow(t.amount).unwrap(),
            )
        })
        .collect();
        assert_eq!(want, vec![(pid(1), pid(2), 400)]);
        for l in [&a, &b, &c] {
            assert_eq!(l.transfers.iter().map(plan).collect::<Vec<_>>(), want);
        }
    }

    /// Every entry on a card sums to the viewer's net: cancelling loops
    /// moves no money. G2 (two loops sharing an edge, cancelled to nothing)
    /// and L2, for every viewer.
    #[test]
    fn balance_line_entries_sum_to_the_viewer_net() {
        let g2 = vec![
            owes(1, 2, 10),
            owes(1, 3, 10),
            owes(2, 4, 10),
            owes(3, 4, 10),
            owes(4, 1, 20),
        ];
        for viewer in [1, 2, 3, 4] {
            let line = inr_line(&g2, &[], &[], viewer);
            let sum: i64 = line.entries.iter().map(|e| e.minor_units).sum();
            assert_eq!(sum, line.viewer_net_minor, "G2 viewer {viewer}");
            assert_eq!(line.viewer_net_minor, 0, "G2 viewer {viewer}");
            assert!(line.entries.is_empty(), "G2 viewer {viewer}");
        }
        let l2 = vec![owes(1, 2, 1000), owes(2, 3, 600)];
        let settled = vec![paid_back(1, 3, 600)];
        for viewer in [1, 2, 3] {
            let line = inr_line(&l2, &settled, &[], viewer);
            let sum: i64 = line.entries.iter().map(|e| e.minor_units).sum();
            assert_eq!(sum, line.viewer_net_minor, "L2 viewer {viewer}");
        }
    }

    /// G6 (.drafts/d218/cycles.py): the raw matrix (1,4) 1, (1,5) 3, (3,1) 1,
    /// (4,5) 3, (5,3) 1 has person 3 at net 0 and a loop 1 -> 4 -> 5 -> 3 -> 1
    /// through them. 3 is removed: the collapse comes FIRST and gives
    /// (1,4) 1, (1,5) 2, (4,5) 3 (no loop left). Cancelling first would give
    /// (1,5) 3, (4,5) 2 instead, so this pins the order.
    #[test]
    fn a_removed_person_is_collapsed_before_loops_are_cancelled() {
        let expenses = vec![
            owes(1, 4, 1),
            owes(1, 5, 3),
            owes(3, 1, 1),
            owes(4, 5, 3),
            owes(5, 3, 1),
        ];
        let removed = vec![pid(3)];
        let one = inr_line(&expenses, &[], &removed, 1);
        assert_eq!(one.viewer_net_minor, -3);
        assert_eq!(one.entries, vec![entry_of(5, -2), entry_of(4, -1)]);
        let four = inr_line(&expenses, &[], &removed, 4);
        assert_eq!(four.viewer_net_minor, -2);
        assert_eq!(four.entries, vec![entry_of(5, -3), entry_of(1, 1)]);
        let five = inr_line(&expenses, &[], &removed, 5);
        assert_eq!(five.viewer_net_minor, 5);
        assert_eq!(five.entries, vec![entry_of(4, 3), entry_of(1, 2)]);
    }

    /// D-210: the collapse is per currency and only for a net of 0. Sam (4)
    /// is at 0 in INR (as above) but owes Asha (3) 500 in EUR, so the EUR
    /// card keeps Sam; a personal line ignores the list (removal is a
    /// group's); a malformed id is refused.
    #[test]
    fn a_removed_person_with_a_balance_stays_on_the_cards() {
        let eur = ExpenseInput {
            currency_code: "EUR".into(),
            ..spent(3, 500, &[(4, 500)])
        };
        let expenses = vec![
            spent(3, 10_000, &[(4, 10_000)]),
            spent(4, 10_000, &[(1, 10_000)]),
            eur,
        ];
        let removed = vec![pid(4)];
        let asha = balance_line(&expenses, &[], &[], &removed, &pid(3), Some(&grp())).unwrap();
        let by_code = |code: &str| {
            asha.iter()
                .find(|l| l.currency_code == code)
                .unwrap()
                .entries
                .iter()
                .map(|e| (e.id.clone(), e.minor_units))
                .collect::<Vec<_>>()
        };
        assert_eq!(by_code("INR"), vec![(pid(1), 10_000)]);
        assert_eq!(by_code("EUR"), vec![(pid(4), 500)]);
        // Personal records, the same shape: the list is not read.
        let personal: Vec<ExpenseInput> = expenses[..2]
            .iter()
            .map(|e| ExpenseInput {
                group: None,
                ..e.clone()
            })
            .collect();
        let mine = balance_line(&personal, &[], &[], &removed, &pid(1), None).unwrap();
        assert_eq!(mine[0].entries.len(), 1);
        assert_eq!(mine[0].entries[0].id, pid(4));
        assert!(balance_line(
            &expenses,
            &[],
            &[],
            &["sam".to_string()],
            &pid(3),
            Some(&grp())
        )
        .is_err());
    }

    #[test]
    fn the_redistribution_bytes_cross_the_boundary() {
        let r = RedistributionInput {
            group: grp(),
            from: pid(4),
            currency_code: "INR".into(),
            shares: vec![IdAmount {
                id: pid(1),
                amount_minor: -1,
            }],
        };
        assert_eq!(
            ffi_decode_redistribution(ffi_encode_redistribution(r.clone()).unwrap()).unwrap(),
            r
        );
        assert!(ffi_encode_redistribution(RedistributionInput {
            shares: vec![],
            ..r.clone()
        })
        .is_err());
        assert!(ffi_decode_redistribution(b"{}".to_vec()).is_err());
        // An otherwise valid blob naming a currency that does not exist is refused.
        let blob = ffi_encode_redistribution(r).unwrap();
        let bad = String::from_utf8(blob)
            .unwrap()
            .replace("\"INR\"", "\"XXX\"")
            .into_bytes();
        assert!(String::from_utf8_lossy(&bad).contains("XXX"));
        assert!(ffi_decode_redistribution(bad).is_err());
    }

    // ---- D-141: expense revisions across the boundary ----

    fn inr(payer: u8, amount: i64) -> ExpenseInput {
        ExpenseInput {
            payer: pid(payer),
            paid_minor: amount,
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: amount / 2,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: amount - amount / 2,
                },
            ],
            group: None,
            conversion: None,
        }
    }

    fn created(millis: u64, device: u8, amount: i64) -> ExpenseCreatedIn {
        let mut expense = inr(1, amount);
        expense.group = Some(pid(9));
        ExpenseCreatedIn {
            hlc_millis: millis,
            hlc_counter: 0,
            hlc_device_id: pid(device),
            expense,
            note: Some("dinner".into()),
            category: None,
            timestamp_ms: Some(millis),
            added_by: Some(pid(1)),
        }
    }

    fn revision(target: &str, seen: Vec<String>) -> ExpenseRevisionIn {
        ExpenseRevisionIn {
            target: target.into(),
            seen,
            editor: Some(pid(2)),
            money: None,
            note: None,
            category: None,
            deleted: None,
        }
    }

    fn revision_record(millis: u64, device: u8, rev: ExpenseRevisionIn) -> ExpenseRevisionRecordIn {
        ExpenseRevisionRecordIn {
            hlc_millis: millis,
            hlc_counter: 0,
            hlc_device_id: pid(device),
            blob: ffi_encode_expense_revision(rev).unwrap(),
            scope: Some(pid(9)),
        }
    }

    fn group_record(millis: u64, device: u8, op: GroupOpIn) -> GroupOpRecordIn {
        GroupOpRecordIn {
            hlc_millis: millis,
            hlc_counter: 0,
            hlc_device_id: pid(device),
            blob: ffi_encode_group_op(op).unwrap(),
        }
    }

    fn op_member(id: u8, name: Option<&str>) -> GroupOpMemberIn {
        GroupOpMemberIn {
            id: pid(id),
            name: name.map(str::to_string),
        }
    }

    /// D-152: every kind crosses the boundary and comes back unchanged.
    #[test]
    fn every_group_op_round_trips_the_boundary() {
        let ops = vec![
            GroupOpIn::Create {
                group_id: pid(9),
                name: "Goa".into(),
                settlement_currency: "INR".into(),
                members: vec![op_member(1, Some("Tim")), op_member(2, None)],
            },
            GroupOpIn::AddMember {
                group_id: pid(9),
                member: op_member(3, Some("Priya")),
            },
            GroupOpIn::RenameMember {
                group_id: pid(9),
                member_id: pid(2),
                name: "Rahul".into(),
            },
            GroupOpIn::RenameGroup {
                group_id: pid(9),
                name: "Goa 2026".into(),
            },
            GroupOpIn::You {
                id: pid(1),
                name: None,
            },
        ];
        for op in ops {
            let back = ffi_decode_group_op(ffi_encode_group_op(op.clone()).unwrap()).unwrap();
            assert_eq!(back, op);
        }
        // Negative: core refuses, the shell cannot write it.
        assert!(matches!(
            ffi_encode_group_op(GroupOpIn::You {
                id: "nope".into(),
                name: None
            }),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// D-186: a removal crosses the boundary and shows in `removed`, the
    /// member still in the group; history is any expense (paying or in a
    /// share) or settlement (either side) of THAT group.
    #[test]
    fn a_removal_crosses_the_boundary_and_history_is_the_groups() {
        let op = GroupOpIn::RemoveMember {
            group_id: pid(9),
            member_id: pid(2),
        };
        assert_eq!(
            ffi_decode_group_op(ffi_encode_group_op(op.clone()).unwrap()).unwrap(),
            op
        );
        let p = ffi_project_groups(vec![
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Goa".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, Some("Tim")), op_member(2, Some("Rahul"))],
                },
            ),
            group_record(2000, 1, op),
        ])
        .unwrap();
        assert_eq!(p.groups[0].group.members, vec![pid(1), pid(2)]);
        assert_eq!(p.groups[0].removed, vec![pid(2)]);

        let expense = |payer: u8, share: u8, group: u8| ExpenseInput {
            payer: pid(payer),
            paid_minor: 1000,
            currency_code: "INR".into(),
            shares: vec![IdAmount {
                id: pid(share),
                amount_minor: 1000,
            }],
            group: Some(pid(group)),
            conversion: None,
        };
        let paid = |from: u8, to: u8, group: u8| SettlementInput {
            from: pid(from),
            to: pid(to),
            amount_minor: 500,
            currency_code: "INR".into(),
            group: Some(pid(group)),
        };
        let has = |e: Vec<ExpenseInput>, s: Vec<SettlementInput>, m: u8| {
            ffi_member_has_history(e, s, vec![], pid(9), pid(m))
        };
        assert!(has(vec![expense(3, 1, 9)], vec![], 3), "paid");
        assert!(has(vec![expense(1, 3, 9)], vec![], 3), "in a share");
        assert!(has(vec![], vec![paid(3, 1, 9)], 3), "paid someone");
        assert!(has(vec![], vec![paid(1, 3, 9)], 3), "was paid");
        assert!(
            !has(vec![expense(3, 3, 8)], vec![paid(3, 1, 8)], 3),
            "another group's"
        );
        assert!(
            !has(vec![expense(1, 2, 9)], vec![paid(1, 2, 9)], 3),
            "never in it"
        );
        assert!(!has(vec![], vec![], 3));
    }

    /// D-187: a picture crosses the boundary and projects on its group; a
    /// PNG is refused (the shells send JPEG).
    #[test]
    fn a_group_picture_crosses_the_boundary() {
        let photo = format!("ffd8ffe0{}", "ab".repeat(100));
        let op = GroupOpIn::GroupPicture {
            group_id: pid(9),
            emoji: None,
            photo_hex: Some(photo.clone()),
        };
        assert_eq!(
            ffi_decode_group_op(ffi_encode_group_op(op.clone()).unwrap()).unwrap(),
            op
        );
        let p = ffi_project_groups(vec![
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Goa".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, Some("Tim"))],
                },
            ),
            group_record(2000, 1, op),
        ])
        .unwrap();
        assert_eq!(
            p.groups[0].picture,
            Some(GroupPictureOut {
                emoji: None,
                photo_hex: Some(photo)
            })
        );
        assert!(matches!(
            ffi_encode_group_op(GroupOpIn::GroupPicture {
                group_id: pid(9),
                emoji: None,
                photo_hex: Some("89504e470d0a1a0a".into()),
            }),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// D-185: a phone's name crosses the boundary, projects as the latest
    /// write for that phone, and a name core would refuse cannot be written.
    #[test]
    fn a_phones_name_crosses_the_boundary() {
        let named = |device: u8, name: &str| GroupOpIn::DeviceName {
            device_id: pid(device),
            name: name.into(),
        };
        let op = named(4, "iPhone 17");
        assert_eq!(
            ffi_decode_group_op(ffi_encode_group_op(op.clone()).unwrap()).unwrap(),
            op
        );
        let p = ffi_project_groups(vec![
            group_record(1000, 1, named(4, "iPhone 16")),
            group_record(2000, 1, named(4, "iPhone 17")),
            group_record(1500, 2, named(3, "Pixel 8")),
        ])
        .unwrap();
        assert_eq!(
            p.device_names,
            vec![
                DeviceNameOut {
                    device_id: pid(3),
                    name: "Pixel 8".into()
                },
                DeviceNameOut {
                    device_id: pid(4),
                    name: "iPhone 17".into()
                },
            ]
        );
        assert!(p.groups.is_empty() && !p.maximum_security);
        assert!(matches!(
            ffi_encode_group_op(named(4, " spaced")),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// D-211: your picture and a joined person's profile cross the boundary
    /// and project: the avatar on the account, the profile on its group.
    #[test]
    fn an_avatar_and_a_member_profile_cross_the_boundary() {
        let avatar = GroupOpIn::Avatar {
            emoji: None,
            photo_hex: Some("ffd8ff00".into()),
        };
        let profile = GroupOpIn::MemberProfile {
            group_id: pid(9),
            member_id: pid(2),
            name: "Rahul K".into(),
            emoji: None,
            thumb_hex: Some("ffd8ff01".into()),
        };
        for op in [avatar.clone(), profile.clone()] {
            assert_eq!(
                ffi_decode_group_op(ffi_encode_group_op(op.clone()).unwrap()).unwrap(),
                op
            );
        }
        let p = ffi_project_groups(vec![
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Goa".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, Some("Tim")), op_member(2, Some("Ra"))],
                },
            ),
            group_record(2000, 2, profile),
            group_record(3000, 1, avatar),
        ])
        .unwrap();
        assert_eq!(
            p.groups[0].profiles,
            vec![MemberProfileOut {
                member_id: pid(2),
                emoji: None,
                thumb_hex: Some("ffd8ff01".into()),
            }]
        );
        assert_eq!(p.groups[0].members[1].name.as_deref(), Some("Rahul K"));
        assert_eq!(
            p.avatar,
            Some(GroupPictureOut {
                emoji: None,
                photo_hex: Some("ffd8ff00".into())
            })
        );
        assert!(matches!(
            ffi_encode_group_op(GroupOpIn::Avatar {
                emoji: Some("🦊".into()),
                photo_hex: Some("ffd8ff00".into()),
            }),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// D-244 (ADR-0043): a setting crosses the boundary, projects as the
    /// latest write per key with its record's identity and device, and a
    /// setting core would refuse cannot be written.
    #[test]
    fn a_setting_crosses_the_boundary() {
        let set = |key: &str, value: &str| GroupOpIn::Setting {
            key: key.into(),
            value: value.into(),
        };
        let theme = set("appearance.theme", "norse");
        let bytes = ffi_encode_group_op(theme.clone()).unwrap();
        assert_eq!(
            bytes,
            br#"{"op":"setting","key":"appearance.theme","value":"norse"}"#.to_vec()
        );
        assert_eq!(ffi_decode_group_op(bytes).unwrap(), theme);
        let p = ffi_project_groups(vec![
            group_record(1000, 1, set("appearance.theme", "vardr")),
            group_record(3000, 2, theme),
            group_record(2000, 1, set("money.default-currency", "EUR")),
        ])
        .unwrap();
        assert_eq!(
            p.settings,
            vec![
                SettingOut {
                    key: "appearance.theme".into(),
                    value: "norse".into(),
                    record_id: format!("3000:0:{}", pid(2)),
                    device_id: pid(2),
                },
                SettingOut {
                    key: "money.default-currency".into(),
                    value: "EUR".into(),
                    record_id: format!("2000:0:{}", pid(1)),
                    device_id: pid(1),
                },
            ]
        );
        assert!(p.groups.is_empty() && p.rejected.is_empty() && p.avatar.is_none());
        for bad in [
            set("", "x"),
            set("a b", "x"),
            set("k", " x"),
            set("k", "a\nb"),
            set("money.default-currency", "XYZ"),
        ] {
            assert!(
                matches!(
                    ffi_encode_group_op(bad.clone()),
                    Err(CoreError::InvalidInput(_))
                ),
                "{bad:?}"
            );
        }
    }

    /// The projection's groups are ones core itself accepts: fed to
    /// `ffi_named_group` with the projected "you", they validate, including
    /// two others who both wrote "Rahul" offline.
    #[test]
    fn projected_groups_validate_as_named_groups() {
        let p = ffi_project_groups(vec![
            group_record(
                900,
                1,
                GroupOpIn::You {
                    id: pid(1),
                    name: Some("Tim".into()),
                },
            ),
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Goa".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, Some("Tim"))],
                },
            ),
            group_record(
                2000,
                1,
                GroupOpIn::AddMember {
                    group_id: pid(9),
                    member: op_member(2, Some("Rahul")),
                },
            ),
            group_record(
                2000,
                2,
                GroupOpIn::AddMember {
                    group_id: pid(9),
                    member: op_member(3, Some("Rahul")),
                },
            ),
        ])
        .unwrap();
        let you = p.you.clone().unwrap();
        assert_eq!(you.id, pid(1));
        let g = p.groups[0].clone();
        assert_eq!(g.group.members, vec![pid(1), pid(2), pid(3)]);
        let members = g
            .members
            .iter()
            .map(|m| MemberWire {
                id: m.id.clone(),
                name: m.name.clone().unwrap(),
            })
            .collect();
        let named = ffi_named_group(g.group.clone(), you.id, members).unwrap();
        let shown: Vec<String> = named.members.iter().map(|m| m.name.clone()).collect();
        assert_eq!(shown, vec!["Tim", "Rahul", "Rahul (2)"]);
    }

    fn projected(expense: ExpenseInput) -> ProjectedExpenseOut {
        ProjectedExpenseOut {
            id: format!("1000:0:{}", pid(7)),
            expense,
            note: None,
            category: None,
            timestamp_ms: None,
            added_by: Some(pid(2)),
            added_at_ms: 1000,
            deleted: false,
            last_edited_at_ms: None,
            last_edited_by: Some(pid(2)),
            seen: vec![],
            money_conflict: vec![],
        }
    }

    /// D-154: an expense paid by another "you", split between it, you and
    /// Anna, reads as paid by you with your two shares added; the total is
    /// unchanged, and "added by" / "last edited by" follow.
    #[test]
    fn another_you_in_an_expense_reads_as_you() {
        let e = ExpenseInput {
            payer: pid(2),
            paid_minor: 900,
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(2),
                    amount_minor: 300,
                },
                IdAmount {
                    id: pid(5),
                    amount_minor: 300,
                },
                IdAmount {
                    id: pid(1),
                    amount_minor: 300,
                },
            ],
            group: Some(pid(9)),
            conversion: None,
        };
        let mut p = projected(e.clone());
        p.money_conflict = vec![e];
        let out = ffi_merge_you_expenses(vec![p], pid(1), vec![pid(2)], vec![]).unwrap();
        let m = &out[0];
        assert_eq!(m.expense.payer, pid(1));
        assert_eq!(
            m.expense.shares,
            vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: 600
                },
                IdAmount {
                    id: pid(5),
                    amount_minor: 300
                },
            ]
        );
        assert_eq!(m.money_conflict[0].shares, m.expense.shares);
        assert_eq!(m.added_by.as_deref(), Some(pid(1).as_str()));
        assert_eq!(m.last_edited_by.as_deref(), Some(pid(1).as_str()));
        // Balances agree: the merged expense is one core accepts.
        ffi_balances_compute(vec![m.expense.clone()], vec![], vec![]).unwrap();
    }

    #[test]
    fn a_settlement_between_your_two_ids_leaves_the_ledger() {
        let s = |from: u8, to: u8| SettlementInput {
            from: pid(from),
            to: pid(to),
            amount_minor: 500,
            currency_code: "INR".into(),
            group: None,
        };
        let out = ffi_merge_you_settlements(
            vec![s(2, 1), s(2, 5), s(5, 6)],
            pid(1),
            vec![pid(2)],
            vec![],
        )
        .unwrap();
        assert_eq!(out, vec![s(1, 5), s(5, 6)]);
    }

    /// D-210: a redistribution read with one "you". Values derived by hand.
    fn moved(from: u8, shares: &[(u8, i64)]) -> RedistributionInput {
        RedistributionInput {
            group: pid(9),
            from: pid(from),
            currency_code: "INR".into(),
            shares: shares
                .iter()
                .map(|(id, a)| IdAmount {
                    id: pid(*id),
                    amount_minor: *a,
                })
                .collect(),
        }
    }

    fn alias(group: u8, from: u8, to: u8) -> IdAlias {
        IdAlias {
            group_id: pid(group),
            from_id: pid(from),
            to_id: pid(to),
        }
    }

    /// (a) The removed person's own phone: the group moved their claimed
    /// place (4), which this account reads as its "you" (1), whether the
    /// place came as another "you" id or as an alias in the group.
    #[test]
    fn the_removed_persons_phone_reads_the_moved_balance_as_their_own() {
        let record = moved(4, &[(2, -300), (3, -200)]);
        let want = vec![moved(1, &[(2, -300), (3, -200)])];
        assert_eq!(
            ffi_merge_you_redistributions(vec![record.clone()], pid(1), vec![pid(4)], vec![])
                .unwrap(),
            want
        );
        assert_eq!(
            ffi_merge_you_redistributions(vec![record], pid(1), vec![], vec![alias(9, 4, 1)])
                .unwrap(),
            want
        );
        // Balances accept it: 1 pays 2 300 and 3 200, so 1 is at +500.
        let lines = ffi_balances_compute(vec![], vec![], want).unwrap();
        assert_eq!(
            lines[0]
                .entries
                .iter()
                .find(|e| e.id == pid(1))
                .unwrap()
                .minor_units,
            500
        );
    }

    /// (b) A remover who ticked themselves: on their phone their place (5)
    /// reads as their "you" (1); on a third phone their "you" (8) reads, in
    /// this group only, as their place (5). Shares come out ordered by id.
    #[test]
    fn a_remover_who_ticked_themselves_reads_as_one_person() {
        assert_eq!(
            ffi_merge_you_redistributions(
                vec![moved(4, &[(5, -300), (2, -200)])],
                pid(1),
                vec![pid(5)],
                vec![]
            )
            .unwrap(),
            vec![moved(4, &[(1, -300), (2, -200)])]
        );
        assert_eq!(
            ffi_merge_you_redistributions(
                vec![moved(4, &[(8, -300), (2, -200)])],
                pid(7),
                vec![],
                vec![alias(9, 8, 5)]
            )
            .unwrap(),
            vec![moved(4, &[(2, -200), (5, -300)])]
        );
        // An alias of another group does not apply here (only reordered).
        assert_eq!(
            ffi_merge_you_redistributions(
                vec![moved(4, &[(8, -300), (2, -200)])],
                pid(7),
                vec![],
                vec![alias(6, 8, 5)]
            )
            .unwrap(),
            vec![moved(4, &[(2, -200), (8, -300)])]
        );
    }

    /// (c) Two shares that are one person are summed: 5 is a "you" of 1,
    /// so -300 + -200 = -500 for 1. A share that becomes `from` is dropped:
    /// it was a payment to oneself, which moves no balance.
    #[test]
    fn shares_that_are_one_person_are_summed() {
        assert_eq!(
            ffi_merge_you_redistributions(
                vec![moved(4, &[(5, -300), (1, -200), (2, -100)])],
                pid(1),
                vec![pid(5)],
                vec![]
            )
            .unwrap(),
            vec![moved(4, &[(1, -500), (2, -100)])]
        );
        assert_eq!(
            ffi_merge_you_redistributions(
                vec![moved(4, &[(1, 300), (2, 200)])],
                pid(1),
                vec![pid(4)],
                vec![]
            )
            .unwrap(),
            vec![moved(1, &[(2, 200)])]
        );
        // Every share becoming `from` (1 and 4 are both you) is a payment to
        // oneself: the record is dropped, so balances show nothing for it.
        let none = ffi_merge_you_redistributions(
            vec![moved(4, &[(1, 300)])],
            pid(1),
            vec![pid(4)],
            vec![],
        )
        .unwrap();
        assert!(none.is_empty());
        let lines = ffi_balances_compute(vec![], vec![], none).unwrap();
        assert!(lines.iter().all(|l| l.entries.is_empty()));
    }

    /// (d) One "you": the input comes back exactly, order included; a
    /// malformed id is refused.
    #[test]
    fn a_redistribution_merge_with_one_you_changes_nothing() {
        let r = vec![moved(4, &[(3, -1), (2, -1)])];
        assert_eq!(
            ffi_merge_you_redistributions(r.clone(), pid(1), vec![], vec![]).unwrap(),
            r
        );
        assert!(matches!(
            ffi_merge_you_redistributions(r, pid(1), vec!["nope".into()], vec![]),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// With no other "you", nothing changes; a malformed id is refused.
    #[test]
    fn the_merge_is_a_no_op_for_one_you_and_refuses_bad_ids() {
        let p = projected(ExpenseInput {
            payer: pid(2),
            paid_minor: 100,
            currency_code: "INR".into(),
            shares: vec![IdAmount {
                id: pid(2),
                amount_minor: 100,
            }],
            group: None,
            conversion: None,
        });
        assert_eq!(
            ffi_merge_you_expenses(vec![p.clone()], pid(1), vec![], vec![]).unwrap(),
            vec![p.clone()]
        );
        assert!(matches!(
            ffi_merge_you_expenses(vec![p], pid(1), vec!["nope".into()], vec![]),
            Err(CoreError::InvalidInput(_))
        ));
    }

    // ---- D-157: claims and invites across the boundary ----

    /// A claim crosses the boundary and back; the claimer's projection makes
    /// the claimed member one more "you", and D-154's merge then reads an
    /// expense the group wrote for "Rahul" as the joiner's own.
    #[test]
    fn a_claim_crosses_the_boundary_and_feeds_the_merge() {
        let claim = GroupOpIn::Claim {
            group_id: pid(9),
            member_id: pid(2),
            you_id: pid(20),
            devices: vec![ClaimDeviceIn {
                device_id: pid(30),
                agreement_public_hex: "ab".repeat(32),
            }],
        };
        assert_eq!(
            ffi_decode_group_op(ffi_encode_group_op(claim.clone()).unwrap()).unwrap(),
            claim
        );
        let p = ffi_project_groups(vec![
            group_record(
                900,
                30,
                GroupOpIn::You {
                    id: pid(20),
                    name: Some("Bob".into()),
                },
            ),
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Goa".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, Some("Tim")), op_member(2, Some("Rahul"))],
                },
            ),
            group_record(3000, 30, claim),
        ])
        .unwrap();
        assert_eq!(p.other_you_ids, vec![pid(2)]);
        assert_eq!(p.groups[0].claims.len(), 1);
        assert_eq!(p.groups[0].claims[0].member_id, pid(2));
        assert_eq!(p.groups[0].claims[0].you_id, pid(20));
        assert!(p.contested.is_empty());
        let expense = projected(ExpenseInput {
            payer: pid(1),
            paid_minor: 600,
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: 300,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: 300,
                },
            ],
            group: Some(pid(9)),
            conversion: None,
        });
        let you = p.you.unwrap().id;
        let merged =
            ffi_merge_you_expenses(vec![expense], you, p.other_you_ids, p.aliases).unwrap();
        assert_eq!(merged[0].expense.shares[1].id, pid(20));
    }

    /// D-237 (the Fable audit, A2): the next stamp is not predictable from
    /// the state: two draws differ (equal once in 2^20; eight equal, never).
    #[test]
    fn d237_the_next_stamp_is_not_predictable_from_the_state() {
        let wall = 1_790_000_000_000u64;
        let stamps: Vec<(u64, u32)> = (0..8)
            .map(|_| {
                let s = ffi_hlc_next(wall, wall, 5);
                (s.millis, s.counter)
            })
            .collect();
        assert!(stamps.iter().any(|s| *s != stamps[0]), "{stamps:?}");
        for s in &stamps {
            assert_eq!(s.0, wall);
            assert!(s.1 > 5 && s.1 <= 6 + crate::straumr::STEP_MASK, "{s:?}");
        }
    }

    /// D-158: a group key crosses the boundary both ways and is listed by
    /// the projection.
    #[test]
    fn a_group_key_crosses_the_boundary() {
        // D-200: a fixed key (ffi_group_key_generate went with the relay).
        let key = "ab".repeat(32);
        let op = GroupOpIn::GroupKey {
            group_id: pid(9),
            epoch: 0,
            key_hex: key.clone(),
        };
        assert_eq!(
            ffi_decode_group_op(ffi_encode_group_op(op.clone()).unwrap()).unwrap(),
            op
        );
        let p = ffi_project_groups(vec![group_record(1000, 1, op)]).unwrap();
        assert_eq!(
            p.keys,
            vec![GroupKeyOut {
                group_id: pid(9),
                epoch: 0,
                key_hex: key,
            }]
        );
        assert!(ffi_encode_group_op(GroupOpIn::GroupKey {
            group_id: pid(9),
            epoch: 0,
            key_hex: "zz".repeat(32),
        })
        .is_err());
    }

    /// D-158: on Tim's side, Bob's claim makes Bob's own "you" id read as
    /// Rahul in that group: an expense Bob wrote as himself lands on Rahul,
    /// shares collapse, and a settlement Bob made reads from Rahul.
    #[test]
    fn another_persons_you_reads_as_the_member_they_claimed() {
        let p = ffi_project_groups(vec![
            group_record(
                900,
                1,
                GroupOpIn::You {
                    id: pid(1),
                    name: Some("Tim".into()),
                },
            ),
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Goa".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, Some("Tim")), op_member(2, Some("Rahul"))],
                },
            ),
            group_record(
                3000,
                30,
                GroupOpIn::Claim {
                    group_id: pid(9),
                    member_id: pid(2),
                    you_id: pid(20),
                    devices: vec![ClaimDeviceIn {
                        device_id: pid(30),
                        agreement_public_hex: "ab".repeat(32),
                    }],
                },
            ),
            group_record(
                4000,
                30,
                GroupOpIn::RenameMember {
                    group_id: pid(9),
                    member_id: pid(20),
                    name: "Rahul B".into(),
                },
            ),
        ])
        .unwrap();
        assert!(p.other_you_ids.is_empty());
        assert_eq!(
            p.aliases,
            vec![IdAlias {
                group_id: pid(9),
                from_id: pid(20),
                to_id: pid(2),
            }]
        );
        assert_eq!(
            p.groups[0].members[1].name.as_deref(),
            Some("Rahul B"),
            "Bob renaming himself renames Rahul"
        );
        let bobs = projected(ExpenseInput {
            payer: pid(20),
            paid_minor: 400,
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: 200,
                },
                IdAmount {
                    id: pid(20),
                    amount_minor: 100,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: 100,
                },
            ],
            group: Some(pid(9)),
            conversion: None,
        });
        let you = p.you.clone().unwrap().id;
        let out = ffi_merge_you_expenses(
            vec![bobs.clone()],
            you.clone(),
            p.other_you_ids.clone(),
            p.aliases.clone(),
        )
        .unwrap();
        assert_eq!(out[0].expense.payer, pid(2));
        assert_eq!(
            out[0].expense.shares,
            vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: 200
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: 200
                },
            ]
        );
        ffi_balances_compute(vec![out[0].expense.clone()], vec![], vec![]).unwrap();
        // The same expense outside the group is untouched.
        let mut elsewhere = bobs;
        elsewhere.expense.group = Some(pid(8));
        let out = ffi_merge_you_expenses(
            vec![elsewhere.clone()],
            you.clone(),
            vec![],
            p.aliases.clone(),
        )
        .unwrap();
        assert_eq!(out[0].expense.payer, pid(20));
        let paid = SettlementInput {
            from: pid(20),
            to: pid(1),
            amount_minor: 200,
            currency_code: "INR".into(),
            group: Some(pid(9)),
        };
        let out = ffi_merge_you_settlements(vec![paid], you, vec![], p.aliases).unwrap();
        assert_eq!(out[0].from, pid(2));
    }

    // ---- D-229: the write split (one account, two phones) ----
    // y = pid(1) (phone Q's "you", the account's earliest), x = pid(2) (phone
    // P's), c = pid(3) (account C, the third member), d = pid(4); Old = pid(9)
    // made on P under x, New = pid(8) made on Q under y. Phones: Q 0x51, P
    // 0x50, C 0x53. Expected values re-derived in .drafts/d229/goldens.py.

    fn d229_claim(group: u8, member: u8, you: u8, millis: u64, dev: u8) -> GroupOpRecordIn {
        group_record(
            millis,
            dev,
            GroupOpIn::Claim {
                group_id: pid(group),
                member_id: pid(member),
                you_id: pid(you),
                devices: vec![ClaimDeviceIn {
                    device_id: pid(dev),
                    agreement_public_hex: "ab".repeat(32),
                }],
            },
        )
    }

    fn d229_create(group: u8, first: u8, millis: u64, dev: u8) -> GroupOpRecordIn {
        group_record(
            millis,
            dev,
            GroupOpIn::Create {
                group_id: pid(group),
                name: if group == 9 { "Old" } else { "New" }.into(),
                settlement_currency: "INR".into(),
                members: vec![op_member(first, Some("Al")), op_member(3, Some("Bo"))],
            },
        )
    }

    fn d229_you(id: u8, millis: u64, dev: u8, name: Option<&str>) -> GroupOpRecordIn {
        group_record(
            millis,
            dev,
            GroupOpIn::You {
                id: pid(id),
                name: name.map(str::to_string),
            },
        )
    }

    fn d229_alias(millis: u64) -> GroupOpRecordIn {
        group_record(
            millis,
            0x50,
            GroupOpIn::PlaceAlias {
                group_id: pid(9),
                member_id: pid(2),
                you_id: pid(1),
            },
        )
    }

    /// The group-scope records every member holds. `p_claim`: the `you` of P's
    /// claim on x in Old (Some(2) is case (ii), Some(1) case (i)).
    fn d229_group_scope(p_claim: Option<u8>) -> Vec<GroupOpRecordIn> {
        let mut out = vec![d229_create(9, 2, 2100, 0x50)];
        if let Some(you) = p_claim {
            out.push(d229_claim(9, 2, you, 2200, 0x50));
        }
        out.push(d229_create(8, 1, 3000, 0x51));
        out.push(d229_claim(9, 3, 3, 2300, 0x53));
        out.push(d229_claim(8, 3, 3, 3100, 0x53));
        out
    }

    /// Phone P of account A: A's personal records plus the group scope.
    fn d229_on_p(p_claim: Option<u8>, extra: Vec<GroupOpRecordIn>) -> GroupsProjectionOut {
        let mut recs = vec![
            d229_you(1, 1000, 0x51, Some("Al")),
            d229_you(2, 2000, 0x50, Some("Al")),
        ];
        recs.extend(d229_group_scope(p_claim));
        recs.extend(extra);
        ffi_project_groups(recs).unwrap()
    }

    /// Account C's phone: its own personal record plus the group scope (C
    /// never sees A's `you` records).
    fn d229_on_c(p_claim: Option<u8>, extra: Vec<GroupOpRecordIn>) -> GroupsProjectionOut {
        let mut recs = vec![d229_you(3, 1500, 0x53, None)];
        recs.extend(d229_group_scope(p_claim));
        recs.extend(extra);
        ffi_project_groups(recs).unwrap()
    }

    fn d229_group(p: &GroupsProjectionOut, group: u8) -> &ProjectedGroupOut {
        p.groups
            .iter()
            .find(|g| g.group.id == pid(group))
            .expect("the group")
    }

    fn d229_expense(group: u8, payer: u8, shares: &[(u8, i64)]) -> ExpenseInput {
        ExpenseInput {
            payer: pid(payer),
            paid_minor: shares.iter().map(|s| s.1).sum(),
            currency_code: "INR".into(),
            shares: shares
                .iter()
                .map(|(id, amount_minor)| IdAmount {
                    id: pid(*id),
                    amount_minor: *amount_minor,
                })
                .collect(),
            group: Some(pid(group)),
            conversion: None,
        }
    }

    /// The ORACLE of "a member of this group as that phone sees it": core's
    /// own membership rule (norn's apply_expense / apply_settlement) over the
    /// groups of `view`, after `view`'s merge (its "you", other ids, aliases).
    fn d229_oracle(
        view: &GroupsProjectionOut,
        expenses: Vec<ExpenseInput>,
        settlements: Vec<SettlementInput>,
    ) -> Result<(), crate::norn::LedgerError> {
        let you = view.you.clone().expect("a you").id;
        let merged = ffi_merge_you_expenses(
            expenses.into_iter().map(projected).collect(),
            you.clone(),
            view.other_you_ids.clone(),
            view.aliases.clone(),
        )
        .unwrap();
        let settled = ffi_merge_you_settlements(
            settlements,
            you,
            view.other_you_ids.clone(),
            view.aliases.clone(),
        )
        .unwrap();
        let expenses: Vec<ExpenseInput> = merged.into_iter().map(|e| e.expense).collect();
        let built = build_ledger(&expenses, &settled).unwrap();
        let mut ledger = crate::norn::Ledger {
            groups: view
                .groups
                .iter()
                .map(|g| crate::norn::Group {
                    id: parse_participant(&g.group.id).unwrap(),
                    settlement_currency: Currency::parse(&g.group.settlement_currency).unwrap(),
                    members: g
                        .group
                        .members
                        .iter()
                        .map(|m| parse_participant(m).unwrap())
                        .collect(),
                })
                .collect(),
            ..Default::default()
        };
        for e in built.expenses {
            ledger.apply_expense(e)?;
        }
        for s in built.settlements {
            ledger.apply_settlement(s)?;
        }
        Ok(())
    }

    /// Test 7: the three-phone golden. P writes into Old as x (its place,
    /// because y is unreadable there), Q writes into New as y, and C reads
    /// both. Unplaced, P's expense is the bug since D-153 (C cannot read y);
    /// a marked alias (Phase B) or a first claim {x, you y} (case (i)) repairs
    /// records already written.
    #[test]
    fn d229_three_phones_write_what_the_third_member_reads() {
        let p = d229_on_p(Some(2), vec![]);
        let c = d229_on_c(Some(2), vec![]);
        assert_eq!(p.you.as_ref().unwrap().id, pid(1));
        assert_eq!(p.other_you_ids, vec![pid(2)]);
        let old = d229_group(&p, 9);
        let new = d229_group(&p, 8);
        assert_eq!(
            (
                old.place.clone(),
                old.you_as.clone(),
                old.unaliased_yous.clone()
            ),
            (Some(pid(2)), Some(pid(2)), vec![pid(1)])
        );
        assert_eq!(
            (
                new.place.clone(),
                new.you_as.clone(),
                new.unaliased_yous.clone()
            ),
            (Some(pid(1)), None, vec![pid(2)])
        );
        assert!(!old.place_lost && !new.place_lost);
        let mine: Vec<String> = [p.you.clone().unwrap().id]
            .into_iter()
            .chain(p.other_you_ids.clone())
            .collect();

        // (a) P's Dinner in Old, paid by you and split with Bo, written as x.
        let dinner = d229_expense(9, 1, &[(1, 600), (3, 600)]);
        let written = ffi_write_as_expense(dinner.clone(), pid(2), mine.clone()).unwrap();
        assert_eq!(written, d229_expense(9, 2, &[(2, 600), (3, 600)]));
        assert_eq!(d229_oracle(&c, vec![written], vec![]), Ok(()));
        // (b) Q's expense in New, paid by y (you_as none there): C reads it.
        let lunch = d229_expense(8, 1, &[(1, 600), (3, 600)]);
        assert_eq!(d229_oracle(&c, vec![lunch], vec![]), Ok(()));
        // (c) The same Old expense UNPLACED: C cannot read y. The bug, pinned.
        assert_eq!(
            d229_oracle(&c, vec![dinner.clone()], vec![]),
            Err(crate::norn::LedgerError::NonMemberPayer)
        );
        let paid = SettlementInput {
            from: pid(1),
            to: pid(3),
            amount_minor: 600,
            currency_code: "INR".into(),
            group: Some(pid(9)),
        };
        assert_eq!(
            d229_oracle(&c, vec![], vec![paid.clone()]),
            Err(crate::norn::LedgerError::SettlementNonMember)
        );

        // (d) Phase B: the marked alias {Old, x, y} repairs what was written.
        let c_alias = d229_on_c(Some(2), vec![d229_alias(4000)]);
        assert_eq!(
            c_alias.aliases,
            vec![IdAlias {
                group_id: pid(9),
                from_id: pid(1),
                to_id: pid(2),
            }]
        );
        assert_eq!(d229_oracle(&c_alias, vec![dinner.clone()], vec![]), Ok(()));
        assert_eq!(d229_oracle(&c_alias, vec![], vec![paid]), Ok(()));
        // On P the alias drops y from Old's unaliased ids, and P keeps writing
        // x: a marked alias is not "readable" (0.4.0 builds cannot read it).
        let p_alias = d229_on_p(Some(2), vec![d229_alias(4000)]);
        let old = d229_group(&p_alias, 9);
        assert_eq!(
            (
                old.place.clone(),
                old.you_as.clone(),
                old.unaliased_yous.clone()
            ),
            (Some(pid(2)), Some(pid(2)), vec![])
        );

        // (e) Case (i): no {x, you x} claim; the fixed ownClaims writes
        // {x, you y}, the first claim on x, which every build reads.
        let c_first = d229_on_c(Some(1), vec![]);
        assert_eq!(d229_oracle(&c_first, vec![dinner], vec![]), Ok(()));
        let p_first = d229_on_p(Some(1), vec![]);
        let old = d229_group(&p_first, 9);
        assert_eq!(
            (
                old.place.clone(),
                old.you_as.clone(),
                old.unaliased_yous.clone()
            ),
            (Some(pid(2)), None, vec![])
        );
        // And with no claim on x at all, P still writes x.
        let p_none = d229_on_p(None, vec![]);
        assert_eq!(d229_group(&p_none, 9).you_as, Some(pid(2)));
    }

    /// A placeAlias crosses the boundary both ways, and a bad one is refused.
    #[test]
    fn d229_a_place_alias_crosses_the_boundary() {
        let op = GroupOpIn::PlaceAlias {
            group_id: pid(9),
            member_id: pid(2),
            you_id: pid(1),
        };
        let bytes = ffi_encode_group_op(op.clone()).unwrap();
        assert_eq!(
            String::from_utf8(bytes.clone()).unwrap(),
            format!(
                "{{\"op\":\"placeAlias\",\"group\":\"{}\",\"member\":\"{}\",\"you\":\"{}\"}}",
                pid(9),
                pid(2),
                pid(1)
            )
        );
        assert_eq!(ffi_decode_group_op(bytes).unwrap(), op);
        assert!(matches!(
            ffi_encode_group_op(GroupOpIn::PlaceAlias {
                group_id: pid(9),
                member_id: pid(2),
                you_id: pid(2),
            }),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// D-230: a heldPlace crosses the boundary both ways, `you == member`
    /// allowed, a bad id refused. Bytes re-derived in .drafts/d230/goldens.py.
    #[test]
    fn d230_a_held_place_crosses_the_boundary() {
        let op = GroupOpIn::HeldPlace {
            group_id: pid(9),
            member_id: pid(2),
            you_id: pid(1),
        };
        let bytes = ffi_encode_group_op(op.clone()).unwrap();
        assert_eq!(
            String::from_utf8(bytes.clone()).unwrap(),
            format!(
                "{{\"op\":\"heldPlace\",\"group\":\"{}\",\"member\":\"{}\",\"you\":\"{}\"}}",
                pid(9),
                pid(2),
                pid(1)
            )
        );
        assert_eq!(ffi_decode_group_op(bytes).unwrap(), op);
        let own = GroupOpIn::HeldPlace {
            group_id: pid(9),
            member_id: pid(2),
            you_id: pid(2),
        };
        assert_eq!(
            ffi_decode_group_op(ffi_encode_group_op(own.clone()).unwrap()).unwrap(),
            own
        );
        assert!(matches!(
            ffi_encode_group_op(GroupOpIn::HeldPlace {
                group_id: pid(9),
                member_id: "x".into(),
                you_id: pid(1),
            }),
            Err(CoreError::InvalidInput(_))
        ));
        // The decode side refuses a bad id too (the review's M5).
        let bad = format!(
            "{{\"op\":\"heldPlace\",\"group\":\"{}\",\"member\":\"x\",\"you\":\"{}\"}}",
            pid(9),
            pid(1)
        );
        assert!(matches!(
            ffi_decode_group_op(bad.into_bytes()),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// D-230: `held` reaches the shells, raw ids, member order.
    #[test]
    fn d230_held_places_reach_the_projection() {
        let recs = vec![
            d229_you(20, 900, 0x30, None),
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Goa".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, None), op_member(4, None), op_member(6, None)],
                },
            ),
            group_record(
                1101,
                0x50,
                GroupOpIn::HeldPlace {
                    group_id: pid(9),
                    member_id: pid(6),
                    you_id: pid(20),
                },
            ),
        ];
        let p = ffi_project_groups(recs).unwrap();
        assert_eq!(
            d229_group(&p, 9).held,
            vec![HeldPlaceOut {
                member_id: pid(6),
                you_id: pid(20),
            }]
        );
    }

    /// Test 8: the expense as written. Every id of the account becomes the
    /// place; collapsed shares are ADDED in the first one's position (the sum
    /// is unchanged); anyone else is untouched; a bad id is refused.
    #[test]
    fn d229_an_expense_is_written_as_the_place() {
        let mine = vec![pid(1), pid(2)];
        let e = d229_expense(9, 1, &[(1, 300), (2, 300), (3, 600)]);
        let w = ffi_write_as_expense(e.clone(), pid(2), mine.clone()).unwrap();
        assert_eq!(w, d229_expense(9, 2, &[(2, 600), (3, 600)]));
        let sum = |e: &ExpenseInput| e.shares.iter().map(|s| s.amount_minor).sum::<i64>();
        assert_eq!(sum(&w), sum(&e));
        // The first one's position: c first, then y and x added.
        let e = d229_expense(9, 3, &[(3, 100), (2, 200), (4, 50), (1, 400)]);
        assert_eq!(
            ffi_write_as_expense(e, pid(2), mine.clone()).unwrap(),
            d229_expense(9, 3, &[(3, 100), (2, 600), (4, 50)])
        );
        // Nobody of the account in it: untouched, conversion included.
        let mut other = d229_expense(9, 3, &[(3, 600), (4, 600)]);
        other.conversion = Some(ConversionInput {
            converted_minor: 1200,
            converted_currency: "INR".into(),
            rate_num: 1,
            rate_den: 1,
            rate_timestamp_ms: 7,
        });
        assert_eq!(
            ffi_write_as_expense(other.clone(), pid(2), mine.clone()).unwrap(),
            other
        );
        // Bad ids: refused, never written under a wrong identity.
        for (place, mine) in [
            ("nope".to_string(), mine.clone()),
            (pid(2), vec![pid(1), "x".repeat(32)]),
        ] {
            assert!(matches!(
                ffi_write_as_expense(e_of(), place, mine),
                Err(CoreError::InvalidInput(_))
            ));
        }
    }

    /// Minor 5: shares that collapse are added with a checked sum; past i64
    /// the write is refused, never wrapped (it runs before any validation).
    #[test]
    fn d229_collapsed_shares_past_the_limit_are_refused() {
        let mine = vec![pid(1), pid(2)];
        // D-235 round 2 (review M1): the written expense now keeps the ingest
        // bound, so the edge is the bound, and an i64::MAX total that collapses
        // cleanly is refused as past it (it was written before).
        let bound = crate::straumr::check::MAX_AMOUNT_MINOR;
        let edge = d229_expense(9, 1, &[(1, bound - 1), (2, 1)]);
        assert_eq!(
            ffi_write_as_expense(edge, pid(2), mine.clone())
                .unwrap()
                .shares,
            vec![IdAmount {
                id: pid(2),
                amount_minor: bound,
            }]
        );
        assert!(matches!(
            ffi_write_as_expense(
                d229_expense(9, 1, &[(1, i64::MAX - 1), (2, 1)]),
                pid(2),
                mine.clone()
            ),
            Err(CoreError::Overflow)
        ));
        let mut over = d229_expense(9, 1, &[(1, 1)]);
        over.shares = vec![
            IdAmount {
                id: pid(1),
                amount_minor: i64::MAX,
            },
            IdAmount {
                id: pid(2),
                amount_minor: 1,
            },
        ];
        assert!(matches!(
            ffi_write_as_expense(over, pid(2), mine.clone()),
            Err(CoreError::InvalidInput(_))
        ));
        let r = RedistributionInput {
            group: pid(9),
            from: pid(3),
            currency_code: "INR".into(),
            shares: vec![
                IdAmount {
                    id: pid(1),
                    amount_minor: i64::MIN,
                },
                IdAmount {
                    id: pid(2),
                    amount_minor: -1,
                },
            ],
        };
        assert!(matches!(
            ffi_write_as_redistribution(r, pid(2), mine),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// Decision 49, with money: J (20) joined as 6, paid a dinner, was removed
    /// (its +300 redistributed from 6 to the creator), rejoined as 7 and paid
    /// again. On the creator's phone the old dinner reads as 6, the
    /// redistribution zeroes 6, and the new dinner (written as 7) is 7's:
    /// creator -200, 7 +200, 6 settled. Hand-computed.
    #[test]
    fn d229_a_rejoin_keeps_the_balances() {
        let claim = |member: u8, millis: u64| {
            d229_claim(
                9,
                member,
                if member == 1 { 1 } else { 20 },
                millis,
                if member == 1 { 1 } else { 30 },
            )
        };
        let group = vec![
            group_record(
                1000,
                1,
                GroupOpIn::Create {
                    group_id: pid(9),
                    name: "Trip".into(),
                    settlement_currency: "INR".into(),
                    members: vec![op_member(1, Some("Al")), op_member(6, Some("Jo"))],
                },
            ),
            claim(1, 1050),
            claim(6, 1100),
            group_record(
                2000,
                1,
                GroupOpIn::RemoveMember {
                    group_id: pid(9),
                    member_id: pid(6),
                },
            ),
            group_record(
                3000,
                1,
                GroupOpIn::AddMember {
                    group_id: pid(9),
                    member: op_member(7, Some("Jo")),
                },
            ),
            claim(7, 3100),
        ];
        let mut on_j = vec![d229_you(20, 800, 30, Some("Jo"))];
        on_j.extend(group.clone());
        let j = ffi_project_groups(on_j).unwrap();
        let g = d229_group(&j, 9);
        assert_eq!(
            (g.place.clone(), g.you_as.clone(), g.place_lost),
            (Some(pid(7)), Some(pid(7)), false)
        );
        let mut on_c = vec![d229_you(1, 900, 1, Some("Al"))];
        on_c.extend(group);
        let c = ffi_project_groups(on_c).unwrap();

        let old = d229_expense(9, 20, &[(1, 300), (20, 300)]);
        let new = ffi_write_as_expense(
            d229_expense(9, 20, &[(1, 200), (20, 200)]),
            pid(7),
            [j.you.clone().unwrap().id]
                .into_iter()
                .chain(j.other_you_ids.clone())
                .collect(),
        )
        .unwrap();
        let removal = RedistributionInput {
            group: pid(9),
            from: pid(6),
            currency_code: "INR".into(),
            shares: vec![IdAmount {
                id: pid(1),
                amount_minor: 300,
            }],
        };
        let you = c.you.clone().unwrap().id;
        let expenses: Vec<ExpenseInput> = ffi_merge_you_expenses(
            vec![projected(old), projected(new)],
            you.clone(),
            c.other_you_ids.clone(),
            c.aliases.clone(),
        )
        .unwrap()
        .into_iter()
        .map(|e| e.expense)
        .collect();
        assert_eq!(expenses[0].payer, pid(6), "the old dinner reads as 6");
        assert_eq!(expenses[1].payer, pid(7));
        let redistributions = ffi_merge_you_redistributions(
            vec![removal],
            you,
            c.other_you_ids.clone(),
            c.aliases.clone(),
        )
        .unwrap();
        let balances = ffi_balances_compute(expenses, vec![], redistributions).unwrap();
        let mut nonzero: Vec<(String, i64)> = balances[0]
            .entries
            .iter()
            .filter(|e| e.minor_units != 0)
            .map(|e| (e.id.clone(), e.minor_units))
            .collect();
        nonzero.sort();
        assert_eq!(nonzero, vec![(pid(1), -200), (pid(7), 200)]);
    }

    fn e_of() -> ExpenseInput {
        d229_expense(9, 1, &[(1, 600)])
    }

    /// Test 8: a redistribution as written: `from` and the share ids map;
    /// shares that become one are added in the first one's position; a share
    /// that would land on `from` is refused, the whole-collapse case included.
    #[test]
    fn d229_a_redistribution_is_written_as_the_place() {
        let mine = vec![pid(1), pid(2)];
        let r = |from: u8, shares: &[(u8, i64)]| RedistributionInput {
            group: pid(9),
            from: pid(from),
            currency_code: "INR".into(),
            shares: shares
                .iter()
                .map(|(id, amount_minor)| IdAmount {
                    id: pid(*id),
                    amount_minor: *amount_minor,
                })
                .collect(),
        };
        assert_eq!(
            ffi_write_as_redistribution(
                r(4, &[(1, -300), (3, -200), (2, -300)]),
                pid(2),
                mine.clone()
            )
            .unwrap(),
            r(4, &[(2, -600), (3, -200)])
        );
        assert_eq!(
            ffi_write_as_redistribution(r(4, &[(3, 500)]), pid(2), mine.clone()).unwrap(),
            r(4, &[(3, 500)]),
            "nobody of the account: untouched"
        );
        // Every share collapses onto `from`.
        assert!(matches!(
            ffi_write_as_redistribution(r(1, &[(2, 300)]), pid(2), mine.clone()),
            Err(CoreError::InvalidInput(_))
        ));
        // One share of two lands on `from`.
        assert!(matches!(
            ffi_write_as_redistribution(r(1, &[(2, 300), (3, 300)]), pid(2), mine.clone()),
            Err(CoreError::InvalidInput(_))
        ));
        assert!(matches!(
            ffi_write_as_redistribution(r(4, &[(3, 500)]), "nope".into(), mine),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// Negative: a record whose device id is not 32 lowercase hex is refused
    /// at the boundary, never projected under a wrong identity.
    #[test]
    fn a_group_record_with_a_bad_device_id_is_refused() {
        let mut r = group_record(
            1000,
            1,
            GroupOpIn::You {
                id: pid(1),
                name: None,
            },
        );
        r.hlc_device_id = "ABC".into();
        assert!(matches!(
            ffi_project_groups(vec![r]),
            Err(CoreError::InvalidInput(_))
        ));
    }

    #[test]
    fn an_expense_revision_round_trips_the_boundary() {
        let target = format!("1000:0:{}", pid(1));
        let mut rev = revision(&target, vec![format!("1500:0:{}", pid(2))]);
        rev.money = Some(inr(2, 1250));
        rev.note = Some("taxi".into());
        let back =
            ffi_decode_expense_revision(ffi_encode_expense_revision(rev.clone()).unwrap(), None)
                .unwrap();
        assert_eq!(
            back, rev,
            "a personal revision: money's group is None on both sides"
        );
    }

    /// D-235 A1 (core audit C1): a GROUP money revision comes back in its
    /// record's group, so both shells' ingest (`money.group == the log's
    /// group`) takes it. Before, decode always gave `group: None` and every
    /// group money edit was refused as the wrong group on every other phone.
    #[test]
    fn d235_a_group_money_revision_round_trips_with_its_group() {
        let target = format!("1000:0:{}", pid(1));
        let mut rev = revision(&target, vec![]);
        let mut money = inr(2, 1500);
        money.group = Some(pid(9));
        rev.money = Some(money);
        let blob = ffi_encode_expense_revision(rev.clone()).unwrap();
        // The wire is unchanged (a 0.4.0 phone reads it): no group inside.
        assert!(!String::from_utf8(blob.clone()).unwrap().contains("group"));
        let back = ffi_decode_expense_revision(blob.clone(), Some(pid(9))).unwrap();
        assert_eq!(back, rev, "money comes back in the record's group");
        assert_eq!(back.money.unwrap().group, Some(pid(9)));
        // The same blob read as personal stays personal.
        let personal = ffi_decode_expense_revision(blob.clone(), None).unwrap();
        assert_eq!(personal.money.unwrap().group, None);
        // A revision without money is untouched by the scope.
        let mut note = revision(&target, vec![]);
        note.note = Some("taxi".into());
        let note_back = ffi_decode_expense_revision(
            ffi_encode_expense_revision(note.clone()).unwrap(),
            Some(pid(9)),
        )
        .unwrap();
        assert_eq!(note_back, note);
        // A scope that is not an id is refused, never echoed into money.
        for bad in ["ABC".to_string(), "0".repeat(30) + "AB", "z".repeat(32)] {
            assert!(
                matches!(
                    ffi_decode_expense_revision(blob.clone(), Some(bad.clone())),
                    Err(CoreError::InvalidInput(_))
                ),
                "{bad}"
            );
        }
    }

    /// D-235 A2: a revision whose new money breaks the rules is refused at
    /// decode, and `rejected` (not applied) in the projection: a co-member's
    /// edit cannot unbalance the ledger. Encode refuses it too.
    #[test]
    fn d235_a_revision_with_bad_money_is_refused_and_rejected() {
        let target = format!("1000:0:{}", pid(1));
        let mut good = revision(&target, vec![]);
        good.money = Some(inr(2, 1500));
        let mut blob = ffi_encode_expense_revision(good.clone()).unwrap();
        // Hand-forge the payload (a modified client): shares sum to 1.
        let text = String::from_utf8(blob.clone()).unwrap();
        assert_eq!(text.matches("\"amountMinor\":750").count(), 2);
        let forged = text.replacen("\"amountMinor\":750", "\"amountMinor\":1", 1);
        let forged = forged.replacen("\"amountMinor\":750", "\"amountMinor\":0", 1);
        assert!(matches!(
            ffi_decode_expense_revision(forged.clone().into_bytes(), Some(pid(9))),
            Err(CoreError::InvalidInput(_))
        ));
        let p = ffi_project_expenses(
            vec![created(1000, 1, 1000)],
            vec![ExpenseRevisionRecordIn {
                hlc_millis: 2000,
                hlc_counter: 0,
                hlc_device_id: pid(2),
                blob: forged.into_bytes(),
                scope: Some(pid(9)),
            }],
        )
        .unwrap();
        assert_eq!(p.rejected, vec![format!("2000:0:{}", pid(2))]);
        assert_eq!(
            p.expenses[0].expense.paid_minor, 1000,
            "the creation stands"
        );
        // The honest blob still decodes and applies.
        blob = ffi_encode_expense_revision(good).unwrap();
        assert!(ffi_decode_expense_revision(blob, Some(pid(9))).is_ok());
        // And a phone cannot WRITE bad money either.
        let mut bad = revision(&target, vec![]);
        let mut m = inr(2, 1500);
        m.shares[0].amount_minor = 1;
        bad.money = Some(m);
        assert!(matches!(
            ffi_encode_expense_revision(bad),
            Err(CoreError::InvalidInput(_))
        ));
    }

    /// D-235 round 2 (review M1): core's own write paths keep the ingest
    /// rules: a settlement's bytes and an expense written as a place.
    #[test]
    fn d235_core_write_paths_refuse_what_ingest_refuses() {
        let ok = settlement_payload(&pid(1), &pid(2), 500, "INR", 7, Some(&pid(9)));
        assert!(ok.is_ok());
        for (name, r) in [
            (
                "uppercase sender",
                settlement_payload(&d235_id("UP"), &pid(2), 500, "INR", 7, Some(&pid(9))),
            ),
            (
                "uppercase group",
                settlement_payload(&pid(1), &pid(2), 500, "INR", 7, Some(&d235_id("UP"))),
            ),
            (
                "past the bound",
                settlement_payload(
                    &pid(1),
                    &pid(2),
                    crate::straumr::check::MAX_AMOUNT_MINOR + 1,
                    "INR",
                    7,
                    None,
                ),
            ),
        ] {
            assert!(r.is_err(), "{name}");
        }
        // An expense written as a place: a broken sum is refused, the honest
        // one is written.
        let mine = vec![pid(1), pid(2)];
        let mut broken = d229_expense(9, 1, &[(1, 300), (3, 300)]);
        broken.paid_minor = 601;
        assert!(matches!(
            ffi_write_as_expense(broken, pid(2), mine.clone()),
            Err(CoreError::TotalMismatch { .. })
        ));
        assert!(
            ffi_write_as_expense(d229_expense(9, 1, &[(1, 300), (3, 300)]), pid(2), mine).is_ok()
        );
    }

    fn d235_id(name: &str) -> String {
        match name {
            "p1" => pid(1),
            "p2" => pid(2),
            "p3" => pid(3),
            "g9" => pid(9),
            "UP" => "0".repeat(30) + "AB",
            "BAD" => "zz".to_string() + &"0".repeat(30),
            "SHORT" => "abc".into(),
            other => panic!("no id {other}"),
        }
    }

    fn d235_verdict(r: Result<(), CoreError>) -> String {
        match r {
            Ok(()) => "ok".into(),
            Err(CoreError::TotalMismatch { expected, got }) => {
                format!("TotalMismatch({expected},{got})")
            }
            Err(e) => format!("{e:?}")
                .split(['(', ' ', '{'])
                .next()
                .unwrap()
                .to_string(),
        }
    }

    /// D-249 (decision 208): every `ffi_check_expense_members` verdict, pinned
    /// from `.drafts/d249/fw/members_goldens.py`, which re-derives them
    /// independently. A negative per field (payer, first share, last share),
    /// no members, a member that is not an id, the money rules first, and
    /// Just me's own ids (you and an alias) against another person.
    #[test]
    fn d249_members_goldens_match_the_independent_derivation() {
        let id = |n: &str| -> String {
            match n {
                "a" => format!("{}1", "0".repeat(31)),
                "b" => format!("{}2", "0".repeat(31)),
                "c" => format!("{}3", "0".repeat(31)),
                "d" => format!("{}4", "0".repeat(31)),
                "me" => format!("{}aa", "0".repeat(30)),
                "alias" => format!("{}bb", "0".repeat(30)),
                "UP" => format!("{}AB", "0".repeat(30)),
                other => panic!("no id {other}"),
            }
        };
        // name, payer, paid, shares, members, verdict
        type Case<'a> = (
            &'a str,
            &'a str,
            i64,
            Vec<(&'a str, i64)>,
            Vec<&'a str>,
            &'a str,
        );
        let cases: Vec<Case> = vec![
            (
                "group, all members",
                "a",
                300,
                vec![("a", 100), ("b", 200)],
                vec!["a", "b", "c"],
                "ok",
            ),
            (
                "group, payer not a member",
                "d",
                300,
                vec![("a", 100), ("b", 200)],
                vec!["a", "b", "c"],
                "UnknownParticipant",
            ),
            (
                "group, first share not a member",
                "a",
                300,
                vec![("d", 100), ("b", 200)],
                vec!["a", "b", "c"],
                "UnknownParticipant",
            ),
            (
                "group, last share not a member",
                "a",
                300,
                vec![("a", 100), ("d", 200)],
                vec!["a", "b", "c"],
                "UnknownParticipant",
            ),
            (
                "group, payer outside, shares in",
                "d",
                300,
                vec![("a", 300)],
                vec!["a", "b"],
                "UnknownParticipant",
            ),
            (
                "no members",
                "a",
                100,
                vec![("a", 100)],
                vec![],
                "UnknownParticipant",
            ),
            (
                "a member not an id",
                "a",
                100,
                vec![("a", 100)],
                vec!["a", "UP"],
                "InvalidInput",
            ),
            (
                "money first: shares do not add up",
                "d",
                300,
                vec![("a", 100), ("d", 100)],
                vec!["a"],
                "TotalMismatch(300,200)",
            ),
            (
                "just me, you",
                "me",
                1240,
                vec![("me", 1240)],
                vec!["me", "alias"],
                "ok",
            ),
            (
                "just me, an alias",
                "alias",
                500,
                vec![("alias", 500)],
                vec!["me", "alias"],
                "ok",
            ),
            (
                "just me, someone else's share",
                "me",
                900,
                vec![("me", 450), ("b", 450)],
                vec!["me", "alias"],
                "UnknownParticipant",
            ),
            (
                "just me, someone else paid",
                "b",
                900,
                vec![("me", 900)],
                vec!["me", "alias"],
                "UnknownParticipant",
            ),
            (
                "members listed twice",
                "a",
                100,
                vec![("a", 100)],
                vec!["a", "a"],
                "ok",
            ),
        ];
        assert_eq!(cases.len(), 13, "the golden table's rows");
        for (name, payer, paid, shares, members, want) in cases {
            let e = ExpenseInput {
                payer: id(payer),
                paid_minor: paid,
                currency_code: "INR".into(),
                shares: shares
                    .iter()
                    .map(|(s, a)| IdAmount {
                        id: id(s),
                        amount_minor: *a,
                    })
                    .collect(),
                group: None,
                conversion: None,
            };
            let members = members.iter().map(|m| id(m)).collect();
            assert_eq!(
                d235_verdict(ffi_check_expense_members(e, members)),
                want,
                "{name}"
            );
        }
    }

    /// D-235 A2 (decision 103): every check_expense / check_settlement
    /// verdict, pinned from `.drafts/d235/a/check_goldens.py`, which re-derives
    /// them independently (its own id, currency, bound, sum and half-up
    /// conversion rules). Each case breaks at most one rule.
    #[test]
    fn d235_check_goldens_match_the_independent_derivation() {
        let ex = |payer: &str,
                  paid: i64,
                  ccy: &str,
                  shares: &[(&str, i64)],
                  group: Option<&str>,
                  conv: Option<(i64, &str, i64, i64)>| ExpenseInput {
            payer: d235_id(payer),
            paid_minor: paid,
            currency_code: ccy.into(),
            shares: shares
                .iter()
                .map(|(id, a)| IdAmount {
                    id: d235_id(id),
                    amount_minor: *a,
                })
                .collect(),
            group: group.map(d235_id),
            conversion: conv.map(|(converted, target, num, den)| ConversionInput {
                converted_minor: converted,
                converted_currency: target.into(),
                rate_num: num,
                rate_den: den,
                rate_timestamp_ms: 1_790_000_000_000,
            }),
        };
        let st =
            |from: &str, to: &str, amount: i64, ccy: &str, group: Option<&str>| SettlementInput {
                from: d235_id(from),
                to: d235_id(to),
                amount_minor: amount,
                currency_code: ccy.into(),
                group: group.map(d235_id),
            };
        let expenses: Vec<(&str, ExpenseInput, &str)> = vec![
            (
                "plain_even_split",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 500), ("p2", 500)],
                    Some("g9"),
                    None,
                ),
                "ok",
            ),
            (
                "personal_no_group",
                ex("p1", 1000, "INR", &[("p1", 1000)], None, None),
                "ok",
            ),
            (
                "zero_total",
                ex("p1", 0, "INR", &[("p1", 0), ("p2", 0)], Some("g9"), None),
                "ok",
            ),
            (
                "at_the_bound",
                ex(
                    "p1",
                    1000000000000000,
                    "JPY",
                    &[("p2", 1000000000000000)],
                    Some("g9"),
                    None,
                ),
                "ok",
            ),
            (
                "past_the_bound",
                ex(
                    "p1",
                    1000000000000001,
                    "JPY",
                    &[("p2", 1000000000000001)],
                    Some("g9"),
                    None,
                ),
                "Overflow",
            ),
            (
                "i64_max_share",
                ex(
                    "p1",
                    0,
                    "INR",
                    &[("p1", 9223372036854775807), ("p2", 1)],
                    Some("g9"),
                    None,
                ),
                "Overflow",
            ),
            (
                "negative_total",
                ex("p1", -100, "INR", &[("p1", -100)], Some("g9"), None),
                "NegativeTotal",
            ),
            (
                "negative_share_adjustment",
                ex(
                    "p1",
                    3000,
                    "INR",
                    &[("p1", -500), ("p2", 3500)],
                    Some("g9"),
                    None,
                ),
                "NegativeTotal",
            ),
            (
                "p5_shares_sum_to_one",
                ex(
                    "p1",
                    1000000,
                    "INR",
                    &[("p1", 1), ("p2", 0)],
                    Some("g9"),
                    None,
                ),
                "TotalMismatch(1000000,1)",
            ),
            (
                "off_by_one",
                ex(
                    "p1",
                    1001,
                    "INR",
                    &[("p1", 500), ("p2", 500)],
                    Some("g9"),
                    None,
                ),
                "TotalMismatch(1001,1000)",
            ),
            (
                "no_shares",
                ex("p1", 1000, "INR", &[], Some("g9"), None),
                "EmptyParticipants",
            ),
            (
                "duplicate_holder",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 500), ("p1", 500)],
                    Some("g9"),
                    None,
                ),
                "DuplicateParticipant",
            ),
            (
                "uppercase_payer",
                ex("UP", 1000, "INR", &[("p1", 1000)], Some("g9"), None),
                "InvalidInput",
            ),
            (
                "non_hex_share",
                ex("p1", 1000, "INR", &[("BAD", 1000)], Some("g9"), None),
                "InvalidInput",
            ),
            (
                "short_share",
                ex("p1", 1000, "INR", &[("SHORT", 1000)], Some("g9"), None),
                "InvalidInput",
            ),
            (
                "unknown_currency",
                ex("p1", 1000, "XYZ", &[("p1", 1000)], Some("g9"), None),
                "UnknownCurrency",
            ),
            (
                "lowercase_currency",
                ex("p1", 1000, "inr", &[("p1", 1000)], Some("g9"), None),
                "UnknownCurrency",
            ),
            (
                "bad_group",
                ex("p1", 1000, "INR", &[("p1", 1000)], Some("UP"), None),
                "InvalidInput",
            ),
            (
                "conversion_one_to_one",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 400), ("p2", 600)],
                    Some("g9"),
                    Some((1000, "USD", 1, 1)),
                ),
                "ok",
            ),
            (
                "conversion_changes_amount",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 400), ("p2", 600)],
                    Some("g9"),
                    Some((12, "USD", 1, 83)),
                ),
                "TotalMismatch(12,1000)",
            ),
            (
                "conversion_wrong_value",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 400), ("p2", 600)],
                    Some("g9"),
                    Some((13, "USD", 1, 83)),
                ),
                "InvalidInput",
            ),
            (
                "conversion_half_up_kept",
                ex(
                    "p1",
                    5,
                    "INR",
                    &[("p1", 5)],
                    Some("g9"),
                    Some((3, "USD", 1, 2)),
                ),
                "TotalMismatch(3,5)",
            ),
            (
                "conversion_zero_rate",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 1000)],
                    Some("g9"),
                    Some((0, "USD", 0, 1)),
                ),
                "InvalidInput",
            ),
            (
                "conversion_negative",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 1000)],
                    Some("g9"),
                    Some((-5, "USD", 1, 1)),
                ),
                "NegativeTotal",
            ),
            (
                "conversion_unknown_target",
                ex(
                    "p1",
                    1000,
                    "INR",
                    &[("p1", 1000)],
                    Some("g9"),
                    Some((1000, "XYZ", 1, 1)),
                ),
                "UnknownCurrency",
            ),
        ];
        let settlements: Vec<(&str, SettlementInput, &str)> = vec![
            ("settle_ok", st("p1", "p2", 500, "INR", Some("g9")), "ok"),
            ("settle_personal", st("p1", "p2", 500, "INR", None), "ok"),
            (
                "settle_zero",
                st("p1", "p2", 0, "INR", Some("g9")),
                "InvalidInput",
            ),
            (
                "settle_negative",
                st("p1", "p2", -5, "INR", Some("g9")),
                "InvalidInput",
            ),
            (
                "settle_to_self",
                st("p1", "p1", 500, "INR", Some("g9")),
                "InvalidInput",
            ),
            (
                "settle_at_bound",
                st("p1", "p2", 1000000000000000, "INR", Some("g9")),
                "ok",
            ),
            (
                "settle_past_bound",
                st("p1", "p2", 1000000000000001, "INR", Some("g9")),
                "Overflow",
            ),
            (
                "settle_unknown_currency",
                st("p1", "p2", 500, "XYZ", Some("g9")),
                "UnknownCurrency",
            ),
            (
                "settle_bad_sender",
                st("BAD", "p2", 500, "INR", Some("g9")),
                "InvalidInput",
            ),
            (
                "settle_uppercase_receiver",
                st("p1", "UP", 500, "INR", Some("g9")),
                "InvalidInput",
            ),
            (
                "settle_bad_group",
                st("p1", "p2", 500, "INR", Some("SHORT")),
                "InvalidInput",
            ),
        ];
        assert_eq!(expenses.len(), 25);
        assert_eq!(settlements.len(), 11);
        for (name, e, want) in &expenses {
            assert_eq!(d235_verdict(ffi_check_expense(e.clone())), *want, "{name}");
            if *want == "ok" {
                // What passes the check passes export (GUARANTEES #4) and
                // balances to zero.
                let snapshot = LedgerSnapshot {
                    expenses: vec![crate::flutningr::PortExpense {
                        payer: e.payer.clone(),
                        paid_minor: e.paid_minor,
                        currency_code: e.currency_code.clone(),
                        group: e.group.clone(),
                        conversion: e.conversion.as_ref().map(Into::into),
                        shares: e
                            .shares
                            .iter()
                            .map(|s| crate::flutningr::ExpenseShare {
                                id: s.id.clone(),
                                amount_minor: s.amount_minor,
                            })
                            .collect(),
                        note: None,
                        timestamp_ms: None,
                        category: None,
                    }],
                    ..Default::default()
                };
                assert!(ffi_export_ledger_json(snapshot).is_ok(), "{name} exports");
                let lines = balances_compute(std::slice::from_ref(e), &[], &[]).unwrap();
                for line in lines {
                    let net: i128 = line.entries.iter().map(|x| x.minor_units as i128).sum();
                    assert_eq!(net, 0, "{name} balances");
                }
            }
        }
        for (name, s, want) in &settlements {
            assert_eq!(
                d235_verdict(ffi_check_settlement(s.clone())),
                *want,
                "{name}"
            );
        }
    }

    #[test]
    fn projection_keeps_the_creations_group_and_surfaces_a_money_conflict() {
        let c = created(1000, 1, 1000);
        let target = format!("1000:0:{}", pid(1));
        let mut a = revision(&target, vec![]);
        a.money = Some(inr(1, 1200));
        let mut b = revision(&target, vec![]);
        b.money = Some(inr(1, 1250));
        let p = ffi_project_expenses(
            vec![c],
            vec![revision_record(2000, 1, a), revision_record(2500, 2, b)],
        )
        .unwrap();
        let e = &p.expenses[0];
        assert_eq!(e.id, target);
        assert_eq!(e.expense.paid_minor, 1250, "last writer meanwhile");
        assert_eq!(e.expense.group, Some(pid(9)), "the creation's group");
        assert_eq!(
            e.money_conflict
                .iter()
                .map(|m| m.paid_minor)
                .collect::<Vec<_>>(),
            vec![1200, 1250]
        );
        assert!(e.money_conflict.iter().all(|m| m.group == Some(pid(9))));
        assert_eq!(e.last_edited_by, Some(pid(2)));
        assert_eq!(e.seen.len(), 2);
    }

    #[test]
    fn projection_refuses_a_malformed_device_id_and_reports_bad_revisions() {
        let mut c = created(1000, 1, 1000);
        c.hlc_device_id = "not-hex".into();
        assert!(matches!(
            ffi_project_expenses(vec![c], vec![]),
            Err(CoreError::InvalidInput(_))
        ));

        let bad = ExpenseRevisionRecordIn {
            hlc_millis: 2000,
            hlc_counter: 0,
            hlc_device_id: pid(2),
            blob: b"{}".to_vec(),
            scope: Some(pid(9)),
        };
        let p = ffi_project_expenses(vec![created(1000, 1, 1000)], vec![bad]).unwrap();
        assert_eq!(p.rejected, vec![format!("2000:0:{}", pid(2))]);
        assert_eq!(p.expenses[0].expense.paid_minor, 1000);
    }

    // ---- golden formatting vectors (D-09 pinned through the boundary) ----

    #[test]
    fn formatting_goldens_match_format_rs_exactly() {
        assert_eq!(
            money_format(123456789, "INR", LocaleTag::EnIn).unwrap(),
            "₹12,34,567.89"
        );
        assert_eq!(
            money_format(-1240, "INR", LocaleTag::EnIn).unwrap(),
            "-₹12.40"
        );
        assert_eq!(
            money_format(123456789, "USD", LocaleTag::EnUs).unwrap(),
            "$1,234,567.89"
        );
        assert_eq!(
            money_format(1234567, "JPY", LocaleTag::EnIn).unwrap(),
            "¥12,34,567"
        );
        assert_eq!(
            money_format(4200, "XCD", LocaleTag::EnUs).unwrap(),
            "XCD 42.00"
        );
        let kwd = money_format(1234567, "KWD", LocaleTag::EnIn).unwrap();
        assert_eq!(kwd, "KWD 1,234.567");
    }

    /// D-142: the edit form's amount leaves through core and comes back in
    /// through the entry parser unchanged, in every exponent. Goldens written
    /// by hand from ISO 4217's exponents, not read back from the code.
    #[test]
    fn currency_codes_are_exactly_what_the_entry_parser_accepts() {
        let codes = ffi_currency_codes();
        assert_eq!(codes.len(), 164);
        for code in &codes {
            assert_eq!(
                ffi_money_parse_minor("1".into(), code.clone()).map(|_| ()),
                Ok(())
            );
        }
        // A code the list lacks is one the parser refuses.
        assert!(!codes.contains(&"XCG".to_string()));
        assert!(ffi_money_parse_minor("1".into(), "XCG".into()).is_err());
    }

    #[test]
    fn money_input_digits_round_trip_the_entry_parser() {
        assert_eq!(
            ffi_money_input_digits(123_400, "INR".into()).unwrap(),
            "1234.00"
        );
        assert_eq!(ffi_money_input_digits(5, "INR".into()).unwrap(), "0.05");
        assert_eq!(ffi_money_input_digits(1_005, "JPY".into()).unwrap(), "1005");
        assert_eq!(
            ffi_money_input_digits(1_005, "KWD".into()).unwrap(),
            "1.005"
        );
        for (minor, code) in [
            (123_400, "INR"),
            (5, "INR"),
            (1_005, "JPY"),
            (1_005, "KWD"),
            (7, "USD"),
        ] {
            let digits = ffi_money_input_digits(minor, code.into()).unwrap();
            assert_eq!(
                money_parse_minor(&digits, code).unwrap(),
                minor,
                "{code} {digits}"
            );
        }
        assert!(
            ffi_money_input_digits(100, "XYZ".into()).is_err(),
            "an unknown currency is refused, never given a guessed exponent"
        );
    }

    #[test]
    fn money_parse_minor_never_touches_a_float() {
        // The entry path: decimal STRINGS in, integer minor units out.
        assert_eq!(money_parse_minor("12.40", "INR").unwrap(), 1_240);
        assert_eq!(money_parse_minor("0.1", "INR").unwrap(), 10);
        assert_eq!(money_parse_minor(".5", "INR").unwrap(), 50);
        assert_eq!(money_parse_minor("1005", "JPY").unwrap(), 1_005);
        assert_eq!(money_parse_minor("1.005", "KWD").unwrap(), 1_005);
        assert_eq!(money_parse_minor("0.1", "KWD").unwrap(), 100);
        assert_eq!(money_parse_minor("-12.40", "INR").unwrap(), -1_240);
        // Silent rounding is the enemy: too many decimals is an ERROR.
        assert!(money_parse_minor("1.005", "INR").is_err());
        assert!(money_parse_minor("10.05", "JPY").is_err());
        // Grouping separators and garbage never enter the ledger.
        assert!(money_parse_minor("1,234", "INR").is_err());
        assert!(money_parse_minor("12.4.0", "INR").is_err());
        assert!(money_parse_minor("", "INR").is_err());
        assert!(money_parse_minor("abc", "INR").is_err());
        assert!(money_parse_minor("12.", "INR").is_ok());
        // Overflow past i64 refuses rather than wrapping.
        assert!(money_parse_minor("99999999999999999999", "INR").is_err());
    }

    /// D-248 (decisions 184 and 203): the typed-amount parser, given the
    /// phone's decimal mark. Goldens re-derived in Python with exact Fractions
    /// (`.drafts/d248/typed_goldens.py`, run with `python3 -I`), not read back
    /// out of the code. Stage 1 pinned "1,234" KWD as 1234 with no mark: the
    /// review's I1 (1.234 KD read from what an English phone means as 1,234).
    #[test]
    fn money_parse_typed_reads_a_comma_only_on_a_comma_phone() {
        let goldens: &[(&str, &str, &str, Option<i64>)] = &[
            ("12,50", "EUR", ",", Some(1250)),
            ("12,5", "EUR", ",", Some(1250)),
            ("0,99", "EUR", ",", Some(99)),
            (",5", "EUR", ",", Some(50)),
            ("5,", "EUR", ",", Some(500)),
            (" 12,5 ", "EUR", ",", Some(1250)),
            ("1234,56", "INR", ",", Some(123456)),
            ("1,005", "KWD", ",", Some(1005)),
            ("1,234", "KWD", ",", Some(1234)),
            ("1,500", "KWD", ",", Some(1500)),
            ("0,1", "KWD", ",", Some(100)),
            ("2,25", "BHD", ",", Some(2250)),
            ("-12,40", "INR", ",", Some(-1240)),
            ("1,234", "INR", ",", None),
            ("10,5", "JPY", ",", None),
            ("1234", "JPY", ",", Some(1234)),
            ("12,505", "EUR", ",", None),
            ("1,0005", "KWD", ",", None),
            ("12.50", "EUR", ",", Some(1250)),
            ("12.5", "EUR", ",", Some(1250)),
            ("1.234", "KWD", ",", None),
            ("1.234", "EUR", ",", None),
            ("1.234", "JPY", ",", None),
            (" 1.234 ", "INR", ",", None),
            (".123", "OMR", ",", None),
            ("1.05", "KWD", ",", Some(1050)),
            ("1.2345", "KWD", ",", None),
            ("-1.234", "BHD", ",", None),
            ("1.234,56", "EUR", ",", None),
            ("1.234.567", "EUR", ",", None),
            ("1,234.56", "USD", ",", None),
            ("12.50", "EUR", ".", Some(1250)),
            ("1.005", "KWD", ".", Some(1005)),
            ("1.234", "KWD", ".", Some(1234)),
            ("1005", "JPY", ".", Some(1005)),
            ("0.1", "INR", ".", Some(10)),
            ("12,50", "EUR", ".", None),
            ("1,234", "KWD", ".", None),
            ("1,500", "KWD", ".", None),
            ("1,005", "BHD", ".", None),
            ("1,234", "INR", ".", None),
            ("1,234", "JPY", ".", None),
            ("1,234,567", "INR", ".", None),
            ("1,234.56", "USD", ".", None),
            ("2,00,000", "INR", ".", None),
            (",5", "EUR", ".", None),
            ("-12.40", "INR", ".", Some(-1240)),
            (" 7.10 ", "USD", ".", Some(710)),
            ("10.5", "JPY", ".", None),
            ("12,000", "KRW", ".", None),
            ("\u{ff11}\u{ff12},\u{ff15}", "EUR", ",", None),
            ("\u{ff11}\u{ff12}.\u{ff15}", "EUR", ".", None),
            ("12\u{66b}5", "KWD", ",", None),
            ("12\u{66b}5", "KWD", ".", None),
            ("\u{661}\u{662}", "KWD", ".", None),
            ("1 234,5", "EUR", ",", None),
            ("\u{202f}12,5\u{a0}", "EUR", ",", Some(1250)),
            ("\u{2009}12.5", "EUR", ".", Some(1250)),
            (",", "EUR", ",", None),
            (".", "EUR", ",", None),
            (",", "EUR", ".", None),
            (".", "EUR", ".", None),
            ("", "EUR", ",", None),
            ("", "EUR", ".", None),
            ("12,5a", "EUR", ",", None),
            ("12 ,50", "EUR", ",", None),
            ("99999999999999999999,00", "INR", ",", None),
            ("99999999999999999999.00", "INR", ".", None),
            ("12,50", "EUR", "\u{66b}", None),
            ("12.50", "EUR", "", None),
            ("12.50", "EUR", ".,", None),
        ];
        for (text, code, mark, want) in goldens {
            assert_eq!(
                ffi_money_parse_typed((*text).into(), (*code).into(), (*mark).into()).ok(),
                *want,
                "{code} {text:?} on a {mark:?} phone"
            );
        }
        // I1, named: a thousands comma typed on a "." phone is refused, never
        // read as 1.500 KD; the same text on a "," phone is the dinar's
        // decimals.
        assert!(money_parse_typed("1,500", "KWD", ".").is_err());
        assert_eq!(money_parse_typed("1,005", "KWD", ","), Ok(1_005));
        // Every three-decimal currency, on a "." phone.
        for code in ["BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND"] {
            assert!(money_parse_typed("1,234", code, ".").is_err(), "{code}");
            assert_eq!(money_parse_typed("1.234", code, "."), Ok(1_234), "{code}");
            assert_eq!(money_parse_typed("1,234", code, ","), Ok(1_234), "{code}");
            assert!(money_parse_typed("1.234", code, ",").is_err(), "{code}");
        }
        // The strict parser is untouched: a comma never reaches the ledger
        // through it, whatever the currency.
        assert!(money_parse_minor("12,50", "EUR").is_err());
        assert!(money_parse_minor("1,234", "KWD").is_err());
        // An unknown currency is refused, never given a guessed exponent.
        assert!(ffi_money_parse_typed("12,50".into(), "XYZ".into(), ",".into()).is_err());
        // A lone mark is no amount (the strict rule's "." as 0 is older and
        // stays for import).
        assert!(ffi_money_parse_typed(".".into(), "EUR".into(), ".".into()).is_err());
        assert_eq!(money_parse_minor(".", "EUR"), Ok(0));
        // Too large stays core's Overflow on both phones (SplitWords' words).
        assert_eq!(
            money_parse_typed("99999999999999999999,00", "INR", ","),
            Err(CoreError::Overflow)
        );
        assert_eq!(
            money_parse_typed("99999999999999999999", "INR", "."),
            Err(CoreError::Overflow)
        );
    }

    /// D-248 fix wave (decision 203): the entry form in the phone's mark,
    /// re-derived with divmod in `.drafts/d248/typed_goldens.py --digits`;
    /// each reads back exactly through the typed parser with the same mark.
    #[test]
    fn money_typed_digits_writes_the_phones_mark_and_reads_back() {
        let goldens: &[(i64, &str, &str, &str)] = &[
            (1250, "EUR", ".", "12.50"),
            (1250, "EUR", ",", "12,50"),
            (1250, "JPY", ".", "1250"),
            (1250, "JPY", ",", "1250"),
            (1250, "KWD", ".", "1.250"),
            (1250, "KWD", ",", "1,250"),
            (5, "INR", ",", "0,05"),
            (123400, "INR", ".", "1234.00"),
            (1005, "KWD", ",", "1,005"),
            (-250, "EUR", ",", "-2,50"),
            (0, "BHD", ",", "0,000"),
            (7, "USD", ".", "0.07"),
        ];
        for (minor, code, mark, want) in goldens {
            let got = ffi_money_typed_digits(*minor, (*code).into(), (*mark).into()).unwrap();
            assert_eq!(&got, want, "{code} {minor} on a {mark:?} phone");
            assert_eq!(
                money_parse_typed(&got, code, mark),
                Ok(*minor),
                "{code} {got:?}"
            );
        }
        // Every currency core knows, both marks: what a field starts from
        // reads back exactly (a KWD prefill "1.005" on a "," phone would be
        // refused as ambiguous; "1,005" is not).
        for code in crate::money::all_codes() {
            for mark in [".", ","] {
                for minor in [1_005_i64, 1_250, 7, 123_400] {
                    let digits = money_typed_digits(minor, code, mark).unwrap();
                    assert_eq!(
                        money_parse_typed(&digits, code, mark),
                        Ok(minor),
                        "{code} {digits:?}"
                    );
                    assert_eq!(
                        digits.contains(','),
                        mark == "," && !digits.chars().all(|c| c.is_ascii_digit())
                    );
                }
            }
        }
        assert!(ffi_money_typed_digits(1_250, "XYZ".into(), ".".into()).is_err());
        assert!(ffi_money_typed_digits(1_250, "EUR".into(), "\u{66b}".into()).is_err());
        // The "." form is D-142's entry form exactly.
        assert_eq!(
            money_typed_digits(123_400, "INR", ".").unwrap(),
            ffi_money_input_digits(123_400, "INR".into()).unwrap()
        );
    }

    #[test]
    fn decomposition_format_goldens_pin_the_signature_shape() {
        // §11.4's example: sign always printed, no symbol (the micro-label
        // carries the currency), fraction only when non-zero.
        assert_eq!(
            money_format_decomposition(210000, "INR", LocaleTag::EnIn).unwrap(),
            "+2,100"
        );
        assert_eq!(
            money_format_decomposition(-148000, "INR", LocaleTag::EnIn).unwrap(),
            "-1,480"
        );
        assert_eq!(
            money_format_decomposition(70000, "INR", LocaleTag::EnIn).unwrap(),
            "+700"
        );
        assert_eq!(
            money_format_decomposition(-124050, "INR", LocaleTag::EnIn).unwrap(),
            "-1,240.50",
            "non-zero fractions stay lossless"
        );
        assert_eq!(
            money_format_decomposition(700, "JPY", LocaleTag::EnIn).unwrap(),
            "+700",
            "zero-decimal currencies have no fraction to print"
        );
    }

    /// D-235 (core audit L2): words for every i64, pinned from
    /// `.drafts/d235/a/spoken_goldens.py` (an independent derivation). Before,
    /// 2e12 major units panicked (ONES out of bounds) and 1e12 read "ten
    /// hundred billion".
    #[test]
    fn d235_spoken_money_has_words_for_every_i64() {
        assert_eq!(
            money_spoken(i64::MAX, "JPY", LocaleTag::EnIn).unwrap(),
            "nine quintillion two hundred and twenty-three quadrillion three hundred and seventy-two trillion thirty-six billion eight hundred and fifty-four million seven hundred and seventy-five thousand eight hundred and seven yen"
        );
        assert_eq!(
            money_spoken(i64::MIN, "JPY", LocaleTag::EnIn).unwrap(),
            "minus nine quintillion two hundred and twenty-three quadrillion three hundred and seventy-two trillion thirty-six billion eight hundred and fifty-four million seven hundred and seventy-five thousand eight hundred and eight yen"
        );
        assert_eq!(
            money_spoken(200_000_000_000_000, "INR", LocaleTag::EnIn).unwrap(),
            "two trillion rupees"
        );
        assert_eq!(
            money_spoken(100_000_000_000_100, "INR", LocaleTag::EnIn).unwrap(),
            "one trillion and one rupees"
        );
        // Every power of ten an i64 holds, both signs, never panics.
        let mut v: i64 = 1;
        loop {
            for x in [v, -v, v.saturating_mul(2) - 1] {
                for code in ["INR", "JPY", "KWD", "XAF"] {
                    assert!(money_spoken(x, code, LocaleTag::EnIn).is_ok(), "{x} {code}");
                }
            }
            match v.checked_mul(10) {
                Some(n) => v = n,
                None => break,
            }
        }
    }

    #[test]
    fn spoken_money_goldens_pin_the_voiceover_form() {
        // THE §11.7 example, verbatim.
        assert_eq!(
            money_spoken(-124000, "INR", LocaleTag::EnIn).unwrap(),
            "minus one thousand two hundred and forty rupees"
        );
        assert_eq!(
            money_spoken(1240, "INR", LocaleTag::EnIn).unwrap(),
            "twelve rupees and forty paise"
        );
        assert_eq!(
            money_spoken(1, "INR", LocaleTag::EnIn).unwrap(),
            "one paisa",
            "1 minor unit of INR is one paisa, not one rupee"
        );
        assert_eq!(
            money_spoken(100, "INR", LocaleTag::EnIn).unwrap(),
            "one rupee"
        );
        assert_eq!(
            money_spoken(210000, "INR", LocaleTag::EnIn).unwrap(),
            "two thousand one hundred rupees"
        );
        assert_eq!(
            money_spoken(0, "USD", LocaleTag::EnUs).unwrap(),
            "zero dollars"
        );
        assert_eq!(
            money_spoken(100, "JPY", LocaleTag::EnIn).unwrap(),
            "one hundred yen"
        );
        assert_eq!(
            money_spoken(2300, "GBP", LocaleTag::EnIn).unwrap(),
            "twenty-three pounds"
        );
        assert_eq!(
            money_spoken(1001, "USD", LocaleTag::EnUs).unwrap(),
            "ten dollars and one cent",
            "1001 minor = $10.01"
        );
        // Fallback: unknown currency keeps its code; fractions read as digits.
        assert_eq!(
            money_spoken(500, "XCD", LocaleTag::EnIn).unwrap(),
            "five XCD"
        );
        assert_eq!(
            money_spoken(123, "XCD", LocaleTag::EnIn).unwrap(),
            "one XCD point two three"
        );
    }

    #[test]
    fn balance_line_pins_the_114_worked_example_verbatim() {
        // The spec's own numbers: you are owed ₹4,280.00; RAHUL +2,100,
        // PRIYA +1,480, AMIT +700. D-46 rendered these signs INVERTED
        // because the view read raw group nets. This golden is the pin.
        let ada = pid(1);
        let rahul = pid(2);
        let priya = pid(3);
        let amit = pid(4);
        let expenses = vec![ExpenseInput {
            conversion: None,
            payer: ada.clone(),
            paid_minor: 428_000,
            currency_code: "INR".into(),
            group: None,
            shares: vec![
                IdAmount {
                    id: rahul.clone(),
                    amount_minor: 210_000,
                },
                IdAmount {
                    id: priya.clone(),
                    amount_minor: 148_000,
                },
                IdAmount {
                    id: amit.clone(),
                    amount_minor: 70_000,
                },
            ],
        }];
        let lines = balance_line(&expenses, &[], &[], &[], &ada, None).unwrap();
        assert_eq!(lines.len(), 1);
        let line = &lines[0];
        assert_eq!(line.currency_code, "INR");
        assert_eq!(line.viewer_net_minor, 428_000);
        let rendered: Vec<String> = line
            .entries
            .iter()
            .map(|e| money_format_decomposition(e.minor_units, "INR", LocaleTag::EnIn).unwrap())
            .collect();
        assert_eq!(rendered, vec!["+2,100", "+1,480", "+700"]);
        // And the spoken form of a positive entry is money coming to you.
        assert_eq!(
            money_spoken(line.entries[0].minor_units, "INR", LocaleTag::EnIn).unwrap(),
            "two thousand one hundred rupees"
        );
    }

    #[test]
    fn balance_line_settlement_must_not_corrupt_other_entries() {
        // The hole that hid the pairwise double-count (D-48): every
        // balance_line golden exercised expenses only. This variant
        // settles ONE pair (amit pays what he owes) and asserts the rest
        // of the line is untouched. Under the old (to,from) += bug the
        // settled entry came back INVERTED (-700) instead of vanishing.
        let ada = pid(1);
        let rahul = pid(2);
        let amit = pid(4);
        let expenses = vec![ExpenseInput {
            conversion: None,
            payer: ada.clone(),
            paid_minor: 428_000,
            currency_code: "INR".into(),
            group: None,
            shares: vec![
                IdAmount {
                    id: rahul.clone(),
                    amount_minor: 210_000,
                },
                IdAmount {
                    id: amit.clone(),
                    amount_minor: 70_000,
                },
            ],
        }];
        let settlements = vec![SettlementInput {
            from: amit.clone(),
            to: ada.clone(),
            amount_minor: 70_000,
            currency_code: "INR".into(),
            group: None,
        }];

        let line = &balance_line(&expenses, &settlements, &[], &[], &ada, None).unwrap()[0];
        assert_eq!(
            line.viewer_net_minor, 358_000,
            "receiving a repayment REDUCES what you are still owed"
        );
        let amit_entry = line.entries.iter().find(|e| e.id == amit);
        assert_eq!(
            amit_entry, None,
            "the settled debt vanishes: it does not reappear inverted"
        );
        let rahul_entry = line.entries.iter().find(|e| e.id == rahul);
        assert_eq!(
            rahul_entry.map(|e| e.minor_units),
            Some(210_000),
            "the untouched pair is UNTOUCHED"
        );
        assert_eq!(line.transfers.len(), 1, "only rahul remains in the plan");
    }

    #[test]
    fn groups_scope_the_balance_line_and_creation_validates() {
        // §5.3: expenses belong to a group (or are personal); a group's
        // balance line counts only its own records.
        let ada = pid(1);
        let rahul = pid(2);
        let group_id = hex_encode(&lykill::random_bytes(16));
        let expenses = vec![
            ExpenseInput {
                conversion: None,
                payer: ada.clone(),
                paid_minor: 428_000,
                currency_code: "INR".into(),
                shares: vec![IdAmount {
                    id: rahul.clone(),
                    amount_minor: 210_000,
                }],
                group: Some(group_id.clone()),
            },
            // Personal: must NOT appear in the group's line.
            ExpenseInput {
                conversion: None,
                payer: rahul.clone(),
                paid_minor: 9_000,
                currency_code: "INR".into(),
                shares: vec![IdAmount {
                    id: ada.clone(),
                    amount_minor: 9_000,
                }],
                group: None,
            },
        ];
        let line = &balance_line(&expenses, &[], &[], &[], &ada, Some(&group_id)).unwrap()[0];
        assert_eq!(
            line.viewer_net_minor, 428_000,
            "group line counts the group only"
        );
        assert_eq!(line.entries.len(), 1);

        // Personal line (group None) counts only personal records.
        let personal = &balance_line(&expenses, &[], &[], &[], &ada, None).unwrap()[0];
        assert_eq!(personal.viewer_net_minor, -9_000);
        assert_eq!(personal.entries.len(), 1);

        // Creation validates: name, currency, members (non-empty, hex,
        // deduped), and generates the id.
        assert!(create_group("", "INR", std::slice::from_ref(&rahul)).is_err());
        assert!(create_group("Trip", "NOPE", std::slice::from_ref(&rahul)).is_err());
        assert!(create_group("Trip", "INR", &[]).is_err());
        assert!(create_group("Trip", "INR", &["nothex".to_string()]).is_err());
        assert!(create_group("Trip", "INR", &[rahul.clone(), rahul.clone()][..]).is_err());
        // Currency codes are UPPERCASE-strict like everywhere else (the
        // picker never offers lowercase; no silent case-folding).
        assert!(create_group(" Goa Trip ", "inr", std::slice::from_ref(&rahul)).is_err());
        let group = create_group(" Goa Trip ", "INR", std::slice::from_ref(&rahul)).unwrap();
        assert_eq!(group.name, "Goa Trip", "trimmed");
        assert_eq!(group.settlement_currency, "INR");
        assert_eq!(group.id.len(), 32);
    }

    fn me(name: &str) -> MemberWire {
        MemberWire {
            id: pid(1),
            name: name.to_string(),
        }
    }

    fn names(named: &NamedGroupWire) -> Vec<&str> {
        named.members.iter().map(|m| m.name.as_str()).collect()
    }

    #[test]
    fn participant_ids_are_minted_in_core_and_parse() {
        let a = new_participant_id();
        let b = new_participant_id();
        assert_eq!(a.len(), 32);
        assert!(parse_participant(&a).is_ok());
        assert_ne!(a, b);
    }

    #[test]
    fn member_names_are_trimmed_bounded_by_characters_and_printable() {
        assert_eq!(member_name("  Tim  ").unwrap(), "Tim");
        assert!(member_name("").is_err());
        assert!(member_name("   ").is_err());
        // 64 Devanagari characters are 192 bytes: the cap counts characters.
        let hindi_64 = "ह".repeat(64);
        assert_eq!(member_name(&hindi_64).unwrap().chars().count(), 64);
        let hindi_65 = "ह".repeat(65);
        assert!(
            member_name(&hindi_65).is_err(),
            "65 characters: refused, not cut"
        );
        assert!(member_name("Tim\nAda").is_err(), "control character inside");
        assert!(member_name("Ti\u{0007}m").is_err(), "bell character inside");
    }

    #[test]
    fn a_named_group_puts_you_first_and_mints_everyone_elses_id() {
        let named = create_named_group(
            " Goa ",
            "INR",
            me(" Tim "),
            &["Rahul".to_string(), " Priya ".to_string()],
        )
        .unwrap();
        assert_eq!(named.group.name, "Goa");
        assert_eq!(named.self_id, pid(1));
        assert_eq!(names(&named), vec!["Tim", "Rahul", "Priya"]);
        let ids: Vec<String> = named.members.iter().map(|m| m.id.clone()).collect();
        assert_eq!(named.group.members, ids, "same ids, same order");
        assert_eq!(ids[0], pid(1));
        assert!(ids[1..]
            .iter()
            .all(|id| id.len() == 32 && parse_participant(id).is_ok()));
        assert_ne!(ids[1], ids[2]);

        // A group of just you is valid; people can be added later.
        assert_eq!(
            create_named_group("Solo", "INR", me("Tim"), &[])
                .unwrap()
                .members
                .len(),
            1
        );
        // You may share a name with someone else.
        assert!(create_named_group("Trip", "INR", me("Tim"), &["tim".to_string()]).is_ok());
        // Two other members may not (case-insensitive).
        assert!(create_named_group(
            "Trip",
            "INR",
            me("Tim"),
            &["Rahul".to_string(), "RAHUL".to_string()]
        )
        .is_err());
        assert!(create_named_group(
            "Trip",
            "INR",
            MemberWire {
                id: "nothex".into(),
                name: "Tim".into()
            },
            &[]
        )
        .is_err());
        assert!(create_named_group("Trip", "INR", me(""), &[]).is_err());
        assert!(
            create_named_group("", "INR", me("Tim"), &[]).is_err(),
            "create_group still validates"
        );
    }

    #[test]
    fn adding_and_renaming_keep_the_names_and_the_ids_in_step() {
        let named = create_named_group("Goa", "INR", me("Tim"), &["Rahul".to_string()]).unwrap();
        let named = add_member(named, " Priya ").unwrap();
        assert_eq!(names(&named), vec!["Tim", "Rahul", "Priya"]);
        let ids: Vec<String> = named.members.iter().map(|m| m.id.clone()).collect();
        assert_eq!(named.group.members, ids);

        assert!(
            add_member(named.clone(), "rahul").is_err(),
            "another member's name"
        );
        assert!(
            add_member(named.clone(), "TIM").is_ok(),
            "your name is not reserved"
        );
        assert!(add_member(named.clone(), "").is_err());

        let rahul = named.members[1].id.clone();
        let renamed = rename_member(named.clone(), &rahul, "Rahul K").unwrap();
        assert_eq!(names(&renamed), vec!["Tim", "Rahul K", "Priya"]);
        assert_eq!(
            renamed.group.members, named.group.members,
            "ids never change on rename"
        );
        assert!(
            rename_member(named.clone(), &rahul, "priya").is_err(),
            "clashes with another member"
        );
        assert!(
            rename_member(named.clone(), &rahul, "RAHUL").is_ok(),
            "a case change of your own name"
        );
        assert!(
            rename_member(named.clone(), &pid(1), "Priya").is_ok(),
            "you are exempt"
        );
        assert_eq!(
            rename_member(named.clone(), &pid(9), "Nobody"),
            Err(CoreError::UnknownParticipant)
        );
    }

    #[test]
    fn a_stored_group_whose_names_and_ids_disagree_is_refused() {
        let named = create_named_group("Goa", "INR", me("Tim"), &["Rahul".to_string()]).unwrap();
        let mut reordered = named.members.clone();
        reordered.reverse();
        assert!(
            named_group(named.group.clone(), &named.self_id, reordered).is_err(),
            "order matters"
        );
        let mut missing = named.members.clone();
        missing.pop();
        assert!(named_group(named.group.clone(), &named.self_id, missing).is_err());
        assert_eq!(
            named_group(named.group.clone(), &pid(7), named.members.clone()),
            Err(CoreError::UnknownParticipant),
            "self must be a member"
        );
        let mut clash = named.members.clone();
        clash.push(MemberWire {
            id: pid(3),
            name: "rahul".into(),
        });
        let mut group = named.group.clone();
        group.members.push(pid(3));
        assert!(
            named_group(group, &named.self_id, clash).is_err(),
            "two other members named Rahul"
        );
        assert_eq!(
            named_group(named.group.clone(), &named.self_id, named.members.clone()).unwrap(),
            named
        );
    }

    #[test]
    fn balance_line_carries_the_core_simplification_plan() {
        // §5.8: SKIPTI computes the amounts; norn::simplify yields at most
        // n−1 transfers and preserves every net position.
        let ada = pid(1);
        let rahul = pid(2);
        let priya = pid(3);
        let amit = pid(4);
        let expenses = vec![ExpenseInput {
            conversion: None,
            payer: ada.clone(),
            paid_minor: 428_000,
            currency_code: "INR".into(),
            group: None,
            shares: vec![
                IdAmount {
                    id: rahul.clone(),
                    amount_minor: 210_000,
                },
                IdAmount {
                    id: priya.clone(),
                    amount_minor: 148_000,
                },
                IdAmount {
                    id: amit.clone(),
                    amount_minor: 70_000,
                },
            ],
        }];
        let line = &balance_line(&expenses, &[], &[], &[], &ada, None).unwrap()[0];
        assert!(
            line.transfers.len() <= 3,
            "n−1 transfers at most for 4 members"
        );
        let total: i64 = line.transfers.iter().map(|t| t.amount_minor).sum();
        assert_eq!(total, 428_000, "the plan moves every net position");
        // The plan's flows net to each position: rahul pays out exactly
        // what he owes; ada, the sole creditor, receives everything.
        let paid_by_rahul: i64 = line
            .transfers
            .iter()
            .filter(|t| t.from == rahul)
            .map(|t| t.amount_minor)
            .sum();
        assert_eq!(paid_by_rahul, 210_000, "rahul's outflow equals his debt");
        let received_by_ada: i64 = line
            .transfers
            .iter()
            .filter(|t| t.to == ada)
            .map(|t| t.amount_minor)
            .sum();
        assert_eq!(received_by_ada, 428_000, "ada is the single creditor");
    }

    #[test]
    fn balance_line_uses_the_pairwise_frame_in_mixed_directions() {
        // Multi-payer: amit pays for something of ada's AFTER lending the
        // group: group nets alone would invert him; the pairwise matrix
        // nets the two directions correctly.
        let ada = pid(1);
        let amit = pid(4);
        let expenses = vec![
            ExpenseInput {
                conversion: None,
                payer: ada.clone(),
                paid_minor: 70_000,
                currency_code: "INR".into(),
                group: None,
                shares: vec![IdAmount {
                    id: amit.clone(),
                    amount_minor: 70_000,
                }],
            },
            ExpenseInput {
                conversion: None,
                payer: amit.clone(),
                paid_minor: 30_000,
                currency_code: "INR".into(),
                group: None,
                shares: vec![IdAmount {
                    id: ada.clone(),
                    amount_minor: 30_000,
                }],
            },
        ];
        let line = &balance_line(&expenses, &[], &[], &[], &ada, None).unwrap()[0];
        assert_eq!(line.viewer_net_minor, 40_000, "ada is owed 700−300 net");
        assert_eq!(
            line.entries,
            vec![ShareOut {
                id: amit.clone(),
                minor_units: 40_000
            }],
            "amit owes ada the NET 400, not the gross 700"
        );
    }

    #[test]
    fn fx_rate_set_verifies_signature_and_flags_staleness() {
        // D-200: signed with a fixed Ed25519 key (lykill's relay-era DeviceKeys went).
        use ed25519_dalek::Signer;
        let device = ed25519_dalek::SigningKey::from_bytes(&[7; 32]);
        let payload = "FX1\ntimestamp_ms=1724400000000\nUSD INR 8325 100\nEUR INR 9012 100\n";
        let sig = device.sign(payload.as_bytes()).to_bytes();
        let pubkey = device.verifying_key();
        let pubkey_hex: String = pubkey
            .to_bytes()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let sig_hex: String = sig.iter().map(|b| format!("{b:02x}")).collect();

        // Fresh set: verifies, not stale.
        let fresh = fx_rate_set_parse(payload, &sig_hex, &pubkey_hex, 1_724_400_000_000).unwrap();
        assert_eq!(fresh.timestamp_ms, 1_724_400_000_000);
        assert!(!fresh.stale);
        assert_eq!(fresh.rates.len(), 2);
        assert_eq!(fresh.rates[0].from, "USD");
        assert_eq!(fresh.rates[0].num, 8_325);
        assert_eq!(fresh.rates[0].den, 100);

        // 25 hours later: same file, STALE.
        let stale = fx_rate_set_parse(
            payload,
            &sig_hex,
            &pubkey_hex,
            1_724_400_000_000 + 25 * 60 * 60 * 1000,
        )
        .unwrap();
        assert!(stale.stale);
        assert_eq!(stale.age_ms, 25 * 60 * 60 * 1000);

        // A tampered payload NEVER verifies: the fetch boundary cannot
        // re-price a ledger.
        let tampered = payload.replace("8325", "9999");
        assert!(fx_rate_set_parse(&tampered, &sig_hex, &pubkey_hex, 1_724_400_000_000).is_err());

        // The wrong key never verifies.
        let impostor = ed25519_dalek::SigningKey::from_bytes(&[8; 32]);
        let impostor_hex: String = impostor
            .verifying_key()
            .to_bytes()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        assert!(
            fx_rate_set_parse(payload, &sig_hex, &impostor_hex, 1_724_400_000_000).is_err(),
            "an impostor key must not re-price a ledger"
        );
    }

    #[test]
    fn convert_minor_exposes_the_entry_time_conversion() {
        // $50.00 at 83.25 INR/USD = ₹4,162.50.
        assert_eq!(
            convert_minor(5_000, "USD", "INR", 8_325, 100).unwrap(),
            416_250
        );
        // Half-up on magnitude: 1 minor at 2/3 rate rounds AWAY from zero.
        assert_eq!(convert_minor(1, "USD", "INR", 2, 3).unwrap(), 1);
        assert_eq!(convert_minor(-1, "USD", "INR", 2, 3).unwrap(), -1);
        // Zero-decimal target still converts exactly.
        assert_eq!(
            convert_minor(100_000, "USD", "JPY", 15_000, 100).unwrap(),
            15_000_000
        );
        // Identity conversion is legitimate (entered in the settlement
        // currency); non-positive rates are the real refusals.
        assert!(convert_minor(100, "USD", "INR", 0, 100).is_err());
        assert!(convert_minor(100, "USD", "INR", 8_325, 0).is_err());
    }

    #[test]
    fn expenses_carry_frozen_conversions_into_the_ledger() {
        // §5.2's reason: "a trip settled in February must not silently
        // re-price in March." The USD expense freezes its conversion at
        // entry; balances() lands it in the settlement currency.
        let ada = pid(1);
        let rahul = pid(2);
        let expenses = vec![ExpenseInput {
            payer: rahul.clone(),
            paid_minor: 5_000,
            currency_code: "USD".into(),
            shares: vec![IdAmount {
                id: ada.clone(),
                amount_minor: 416_250,
            }],
            group: None,
            conversion: Some(ConversionInput {
                converted_minor: 416_250,
                converted_currency: "INR".into(),
                rate_num: 8_325,
                rate_den: 100,
                rate_timestamp_ms: 1_724_400_000_000,
            }),
        }];
        let line = &balance_line(&expenses, &[], &[], &[], &ada, None).unwrap()[0];
        assert_eq!(
            line.currency_code, "INR",
            "the converted expense lands in INR"
        );
        assert_eq!(
            line.entries,
            vec![ShareOut {
                id: rahul.clone(),
                minor_units: -416_250
            }],
            "ada owes rahul the CONVERTED amount"
        );
    }

    #[test]
    fn fx_rate_parse_decimal_is_exact_and_refuses_garbage() {
        assert_eq!(fx_rate_parse_decimal("83.25").unwrap(), (8_325, 100));
        assert_eq!(fx_rate_parse_decimal("83.250").unwrap(), (83_250, 1_000));
        assert_eq!(fx_rate_parse_decimal("84").unwrap(), (84, 1));
        assert_eq!(fx_rate_parse_decimal("0.5").unwrap(), (5, 10));
        assert!(
            fx_rate_parse_decimal("1.005").is_ok(),
            "rates keep 9 decimals"
        );
        assert!(
            fx_rate_parse_decimal("0.0000000001").is_err(),
            ">9 decimals refused"
        );
        assert!(fx_rate_parse_decimal("abc").is_err());
        assert!(fx_rate_parse_decimal("1,25").is_err());
        assert!(fx_rate_parse_decimal("").is_err());
        assert!(
            fx_rate_parse_decimal("0").is_err(),
            "a zero rate prices nothing"
        );
    }

    #[test]
    fn settlement_payload_is_canonical_and_validated() {
        let from = pid(1);
        let to = pid(2);
        let bytes = settlement_payload(&from, &to, 52_000, "INR", 1_724_000_000_000, None).unwrap();
        assert_eq!(bytes.len(), 67, "51 + 16 group bytes (zeros = personal)");
        let mut raw_from = [0u8; 16];
        raw_from[15] = 1;
        let mut raw_to = [0u8; 16];
        raw_to[15] = 2;
        assert_eq!(
            &bytes[0..16],
            &raw_from[..],
            "payload carries RAW participant bytes"
        );
        assert_eq!(&bytes[16..32], &raw_to[..]);
        assert_eq!(&bytes[32..40], &52_000i64.to_be_bytes());
        assert_eq!(&bytes[40..43], b"INR");
        // Deterministic: same inputs, same bytes.
        assert_eq!(
            bytes,
            settlement_payload(&from, &to, 52_000, "INR", 1_724_000_000_000, None).unwrap()
        );
        // A group settlement carries the group id in its tail.
        let grouped = settlement_payload(
            &from,
            &to,
            52_000,
            "INR",
            1_724_000_000_000,
            Some(&hex_encode(&{
                let mut raw = [0u8; 16];
                raw[15] = 1;
                raw
            })),
        )
        .unwrap();
        let mut raw_from = [0u8; 16];
        raw_from[15] = 1;
        assert_eq!(&grouped[51..67], &raw_from[..]);
        assert_ne!(grouped, bytes);
        // The boundary rejects what a ledger must: non-positive amounts and
        // self-settlement.
        assert!(settlement_payload(&from, &to, 0, "INR", 1, None).is_err());
        assert!(settlement_payload(&from, &to, -5, "INR", 1, None).is_err());
        assert!(settlement_payload(&from, &from, 5, "INR", 1, None).is_err());
        assert!(settlement_payload(&from, &to, 5, "NOPE", 1, None).is_err());
    }

    // ---- D-123: device key agreement. The sync key crosses in an
    // envelope, wrapped by the sender's X25519 agreement key to the
    // recipient's. The group id is the SUBJECT id; epoch 0 until the
    // account device set has rotation. ----

    #[test]
    fn sync_key_generate_is_36_fresh_bytes() {
        assert_eq!(sync_key_generate().len(), 36, "key(32) ‖ session(4)");
        assert_ne!(
            sync_key_generate(),
            sync_key_generate(),
            "fresh entropy per call"
        );
    }

    #[test]
    fn hostile_inputs_produce_typed_errors_not_panics() {
        for (minor, code) in [
            (5, ""),
            (5, "inr"),
            (5, "EURO"),
            (5, "XYZQ"),
            (-3, "US"), // too short
        ] {
            assert!(money_format(minor, code, LocaleTag::EnUs).is_err());
        }
        for bad in ["", "abc", "ZZ", "0123456789abcdef", "0123456789ABCDEFzz00"] {
            assert!(parse_participant(bad).is_err(), "{bad:?} must refuse");
        }
        assert!(locale_from_tag("fr-FR").is_err());
    }

    // ---- splits: adapter output equals core output, then known literals ----

    fn run_split(
        total: i64,
        spec: &SplitSpecInput,
        members: &[String],
    ) -> Result<Vec<ShareOut>, CoreError> {
        split_compute(total, "INR", members, spec)
    }

    #[test]
    fn equal_split_matches_core_including_remainder_rule() {
        let members: Vec<String> = (1..=3).map(pid).collect();
        // 10001 / 3 → largest remainder gets the extra unit.
        let out = run_split(10001, &SplitSpecInput::Equal, &members).unwrap();
        let direct = hlutr::compute(
            &SplitSpec::Equal,
            Money::new(10001, Currency::INR),
            &members
                .iter()
                .map(|s| parse_participant(s).unwrap())
                .collect::<Vec<_>>(),
        )
        .unwrap();
        for (out_share, (core_id, core_amt)) in out.iter().zip(direct.shares.iter()) {
            assert_eq!(out_share.id, hex_encode(core_id));
            assert_eq!(out_share.minor_units as i128, *core_amt);
        }
        let sum: i64 = out.iter().map(|s| s.minor_units).sum();
        assert_eq!(sum, 10001);
        assert_eq!(
            out.iter().find(|s| s.id == pid(1)).unwrap().minor_units,
            3334
        );
    }

    #[test]
    fn exact_percentage_shares_adjustment_itemised_payer_only_happy_paths() {
        let a = pid(1);
        let b = pid(2);
        let members = vec![a.clone(), b.clone()];

        let out = run_split(
            10000,
            &SplitSpecInput::Exact {
                entries: vec![
                    IdAmount {
                        id: a.clone(),
                        amount_minor: 6000,
                    },
                    IdAmount {
                        id: b.clone(),
                        amount_minor: 4000,
                    },
                ],
            },
            &members,
        )
        .unwrap();
        assert_eq!(out.iter().map(|s| s.minor_units).sum::<i64>(), 10000);

        let out = run_split(
            9999,
            &SplitSpecInput::Percentage {
                entries: vec![
                    IdBps {
                        id: a.clone(),
                        bps: 7500,
                    },
                    IdBps {
                        id: b.clone(),
                        bps: 2500,
                    },
                ],
            },
            &members,
        )
        .unwrap();
        let sum: i64 = out.iter().map(|s| s.minor_units).sum();
        assert_eq!(sum, 9999, "largest-remainder preserves the total");

        let out = run_split(
            1200,
            &SplitSpecInput::Shares {
                entries: vec![
                    IdWeight {
                        id: a.clone(),
                        weight: 2,
                    },
                    IdWeight {
                        id: b.clone(),
                        weight: 1,
                    },
                ],
            },
            &members,
        )
        .unwrap();
        assert_eq!(out.iter().find(|s| s.id == a).unwrap().minor_units, 800);

        // Adjustment (D-04): remaining = total − Σdeltas split equally among
        // ALL members, then deltas applied. 1200 − 0 → 600 base each.
        let out = run_split(
            1200,
            &SplitSpecInput::Adjustment {
                entries: vec![
                    IdAmount {
                        id: a.clone(),
                        amount_minor: -200,
                    },
                    IdAmount {
                        id: b.clone(),
                        amount_minor: 200,
                    },
                ],
            },
            &members,
        )
        .unwrap();
        assert_eq!(out.iter().find(|s| s.id == a).unwrap().minor_units, 400);
        assert_eq!(out.iter().find(|s| s.id == b).unwrap().minor_units, 800);

        // Itemised: total = Σlines + tax + tip (D-05); tax+tip pool across
        // the UNION of line assignees.
        let c = pid(3);
        let roster = vec![a.clone(), b.clone(), c.clone()];
        let out = run_split(
            12000,
            &SplitSpecInput::Itemised {
                lines: vec![
                    LineInput {
                        amount_minor: 6000,
                        participants: vec![a.clone()],
                    },
                    LineInput {
                        amount_minor: 5000,
                        participants: vec![b.clone(), c.clone()],
                    },
                ],
                tax_minor: 900,
                tip_minor: 100,
            },
            &roster,
        )
        .unwrap();
        assert_eq!(out.iter().map(|s| s.minor_units).sum::<i64>(), 12000);

        let out = run_split(
            5000,
            &SplitSpecInput::PayerOnly { payer: a.clone() },
            &members,
        )
        .unwrap();
        assert_eq!(out.iter().find(|s| s.id == a).unwrap().minor_units, 5000);
    }

    #[test]
    fn every_split_error_variant_surfaces_as_a_typed_error() {
        let a = pid(1);
        let b = pid(2);
        let ghost = pid(9);
        let members = vec![a.clone(), b.clone()];

        assert_eq!(
            run_split(100, &SplitSpecInput::Equal, &[]),
            Err(CoreError::EmptyParticipants)
        );
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Exact {
                    entries: vec![IdAmount {
                        id: ghost.clone(),
                        amount_minor: 100
                    }]
                },
                &members
            ),
            Err(CoreError::UnknownParticipant)
        );
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Exact {
                    entries: vec![
                        IdAmount {
                            id: a.clone(),
                            amount_minor: 60
                        },
                        IdAmount {
                            id: a.clone(),
                            amount_minor: 40
                        }
                    ]
                },
                &members
            ),
            Err(CoreError::DuplicateParticipant)
        );
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Exact {
                    entries: vec![IdAmount {
                        id: a.clone(),
                        amount_minor: 99
                    }]
                },
                &members
            ),
            Err(CoreError::ExactSumMismatch {
                expected: 100,
                got: 99
            })
        );
        assert_eq!(
            run_split(-1, &SplitSpecInput::Equal, &members),
            Err(CoreError::NegativeTotal)
        );
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Percentage {
                    entries: vec![
                        IdBps {
                            id: a.clone(),
                            bps: 5000
                        },
                        IdBps {
                            id: b.clone(),
                            bps: 4000
                        }
                    ]
                },
                &members
            ),
            Err(CoreError::PercentMismatch { got_bps: 9000 })
        );
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Shares {
                    entries: vec![
                        IdWeight {
                            id: a.clone(),
                            weight: 1
                        },
                        IdWeight {
                            id: b.clone(),
                            weight: 0
                        }
                    ]
                },
                &members
            ),
            Err(CoreError::NonPositiveWeight)
        );
        // Positive adjustments may not push past the total.
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Adjustment {
                    entries: vec![IdAmount {
                        id: a.clone(),
                        amount_minor: 150
                    }]
                },
                &members
            ),
            Err(CoreError::DeltasExceedTotal)
        );
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Itemised {
                    lines: vec![LineInput {
                        amount_minor: 0,
                        participants: vec![a.clone()]
                    }],
                    tax_minor: 0,
                    tip_minor: 0
                },
                &members
            ),
            Err(CoreError::LineAmountNonPositive)
        );
        assert_eq!(
            run_split(
                100,
                &SplitSpecInput::Itemised {
                    lines: vec![LineInput {
                        amount_minor: 50,
                        participants: vec![]
                    }],
                    tax_minor: 50,
                    tip_minor: 0
                },
                &members
            ),
            Err(CoreError::EmptyLineAssignment)
        );
        // malformed member hex propagates as InvalidInput before any math
        let mut bad_members = members.clone();
        bad_members[0] = "nothex".into();
        assert!(matches!(
            run_split(100, &SplitSpecInput::Equal, &bad_members),
            Err(CoreError::InvalidInput(_))
        ));
    }

    // ---- balances: sort order, transfers, multi-currency grouping ----

    #[test]
    fn balances_are_sorted_by_magnitude_and_simplified_per_currency() {
        let ada = pid(1);
        let bob = pid(2);
        let grace = pid(3);
        let expenses = vec![
            ExpenseInput {
                conversion: None,
                payer: ada.clone(),
                paid_minor: 30000,
                currency_code: "INR".into(),
                group: None,
                shares: vec![
                    IdAmount {
                        id: ada.clone(),
                        amount_minor: 10000,
                    },
                    IdAmount {
                        id: bob.clone(),
                        amount_minor: 10000,
                    },
                    IdAmount {
                        id: grace.clone(),
                        amount_minor: 10000,
                    },
                ],
            },
            ExpenseInput {
                conversion: None,
                group: None,
                payer: grace.clone(),
                paid_minor: 5000,
                currency_code: "USD".into(),
                shares: vec![
                    IdAmount {
                        id: grace.clone(),
                        amount_minor: 1000,
                    },
                    IdAmount {
                        id: bob.clone(),
                        amount_minor: 4000,
                    },
                ],
            },
        ];
        let settlements = vec![SettlementInput {
            from: bob.clone(),
            to: ada.clone(),
            amount_minor: 2500,
            currency_code: "INR".into(),
            group: None,
        }];

        let out = balances_compute(&expenses, &settlements, &[]).unwrap();
        assert_eq!(out.len(), 2, "one block per currency");

        let inr = out.iter().find(|c| c.currency_code == "INR").unwrap();
        let amounts: Vec<i64> = inr.entries.iter().map(|e| e.minor_units).collect();
        assert_eq!(
            amounts,
            vec![17500, -10000, -7500],
            "ada +20000−2500; grace −10000; bob −10000+2500: magnitude descending"
        );
        assert_eq!(
            inr.transfers.len(),
            2,
            "two debtors settle toward the single creditor"
        );
        assert!(inr.transfers.iter().all(|t| t.to == ada));
        let total_back: i64 = inr.transfers.iter().map(|t| t.amount_minor).sum();
        assert_eq!(total_back, 17500, "transfers preserve every net position");

        let usd = out.iter().find(|c| c.currency_code == "USD").unwrap();
        assert_eq!(
            usd.entries
                .iter()
                .map(|e| e.minor_units)
                .collect::<Vec<_>>(),
            vec![-4000, 4000],
            "equal magnitudes tie-break by ascending participant id"
        );

        // Zero positions never appear.
        assert!(usd.entries.iter().all(|e| e.minor_units != 0));
    }

    #[test]
    fn balances_hostile_inputs_error_cleanly() {
        assert!(balances_compute(
            &[ExpenseInput {
                conversion: None,
                payer: "badhex".into(),
                paid_minor: 10,
                currency_code: "INR".into(),
                group: None,
                shares: vec![],
            }],
            &[],
            &[]
        )
        .is_err());
        assert!(balances_compute(
            &[ExpenseInput {
                conversion: None,
                group: None,
                payer: pid(1),
                paid_minor: 10,
                currency_code: "NOTA".into(),
                shares: vec![IdAmount {
                    id: pid(1),
                    amount_minor: 10
                }],
            }],
            &[],
            &[]
        )
        .is_err());
    }

    // ---- native store lifecycle ----

    #[cfg(not(target_arch = "wasm32"))]
    mod store_tests {
        use super::store::{
            ffi_record_pack, ffi_record_unpack, RecordWire, SkiptiKeystore, SkiptiVault, StoreError,
        };
        use super::*;
        use std::sync::Mutex;

        /// D-236: a keystore holding `key` that never frees it unzeroed
        /// itself, so the freed-block scanner sees only core's copies.
        struct CanaryKeystoreFfi {
            key: zeroize::Zeroizing<Vec<u8>>,
        }
        impl SkiptiKeystore for CanaryKeystoreFfi {
            fn provision(&self, _key: Vec<u8>) -> Result<(), StoreError> {
                unreachable!("the canary keystore is already provisioned")
            }
            fn load(&self) -> Result<Option<Vec<u8>>, StoreError> {
                Ok(Some(self.key.to_vec()))
            }
            fn destroy(&self) -> Result<(), StoreError> {
                Ok(())
            }
        }

        const CANARY: [u8; 32] = [
            0xD2, 0x36, 0xF1, 0x0B, 0x6E, 0x93, 0x27, 0xC4, 0x5D, 0xA0, 0x18, 0x7F, 0xE9, 0x42,
            0xB6, 0x0D, 0x71, 0xCA, 0x35, 0x8E, 0x24, 0xFB, 0x60, 0x9D, 0x13, 0xA7, 0x4C, 0xE5,
            0x3A, 0x88, 0x56, 0xBF,
        ];

        #[test]
        fn d236_the_keystore_bridge_frees_no_unzeroed_copy_of_the_shells_key() {
            let dir = std::env::temp_dir().join(format!(
                "skipti-d236-{}.vault",
                hex_encode(&crate::lykill::random_bytes(8))
            ));
            let path = dir.to_string_lossy().into_owned();
            let copies = crate::skrin::freed_scan::freed_copies_during(&CANARY, || {
                let vault = SkiptiVault::open(
                    path.clone(),
                    Box::new(CanaryKeystoreFfi {
                        key: zeroize::Zeroizing::new(CANARY.to_vec()),
                    }),
                )
                .expect("opens under the canary key");
                assert!(vault.schema_version().is_ok());
                // A shell key of the wrong length is refused, and the bytes
                // it handed over are zeroed on that error path too.
                let mut long = Vec::with_capacity(33); // no realloc of the canary
                long.extend_from_slice(&CANARY);
                long.push(0);
                let refused = SkiptiVault::open(
                    path.clone(),
                    Box::new(CanaryKeystoreFfi {
                        key: zeroize::Zeroizing::new(long),
                    }),
                );
                assert!(matches!(refused, Err(StoreError::InvalidInput(_))));
            });
            let _ = std::fs::remove_file(&dir);
            assert_eq!(copies, 0);
        }

        struct MemKeystoreFfi {
            slot: Mutex<Option<Vec<u8>>>,
        }
        impl MemKeystoreFfi {
            fn new() -> Self {
                MemKeystoreFfi {
                    slot: Mutex::new(None),
                }
            }
        }
        impl SkiptiKeystore for MemKeystoreFfi {
            fn provision(&self, key: Vec<u8>) -> Result<(), StoreError> {
                *self.slot.lock().unwrap() = Some(key);
                Ok(())
            }
            fn load(&self) -> Result<Option<Vec<u8>>, StoreError> {
                Ok(self.slot.lock().unwrap().clone())
            }
            fn destroy(&self) -> Result<(), StoreError> {
                *self.slot.lock().unwrap() = None;
                Ok(())
            }
        }

        fn sample_record(nonce: u8) -> RecordWire {
            RecordWire {
                version: 1,
                record_type: 1,
                group_id: None,
                epoch: 0,
                hlc_millis: 1724400000000,
                hlc_counter: 3,
                hlc_device_id: pid(7),
                author_device_id: pid(7),
                nonce_session: vec![nonce, 0, 0, 1],
                flags: 0,
                blob: vec![0xDE, 0xAD, 0xBE, 0xEF],
            }
        }

        #[test]
        fn record_pack_unpack_round_trips_and_refuses_truncation() {
            // ADR-0034's wire: pack(record) = canonical header ‖ blob, and
            // unpack is its exact inverse: field for field, byte for byte.
            let record = sample_record(0x42);
            let packed = ffi_record_pack(record.clone()).unwrap();
            assert_eq!(packed.len(), 73 + record.blob.len());
            assert_eq!(ffi_record_unpack(packed).unwrap(), record);

            // Group scope round-trips too (the scope marker lives in the
            // canonical header).
            let mut grouped = sample_record(0x43);
            grouped.group_id = Some(pid(9));
            let packed = ffi_record_pack(grouped.clone()).unwrap();
            assert_eq!(ffi_record_unpack(packed).unwrap(), grouped);

            // Truncated below the header: a typed error, never a short
            // read pretending to be a record.
            let packed = ffi_record_pack(record).unwrap();
            assert!(matches!(
                ffi_record_unpack(packed[..40].to_vec()),
                Err(StoreError::InvalidInput(_))
            ));
            // A corrupted version is refused, not misparsed.
            let mut bad = ffi_record_pack(sample_record(0x44)).unwrap();
            bad[0] = 0x09;
            assert!(matches!(
                ffi_record_unpack(bad),
                Err(StoreError::InvalidInput(_))
            ));
        }

        #[test]
        fn forgetting_a_group_erases_only_that_groups_records() {
            let mut path = std::env::temp_dir();
            path.push(format!("forget-group-{}.vault", pid(0x5E)));
            let _ = std::fs::remove_file(&path);
            let vault = SkiptiVault::open(
                path.to_string_lossy().into_owned(),
                Box::new(MemKeystoreFfi::new()),
            )
            .unwrap();
            let rec = |nonce: u8, millis: u64, group: Option<String>| {
                let mut r = sample_record(nonce);
                r.hlc_millis = millis;
                r.group_id = group;
                r
            };
            vault.put_record(rec(1, 1_000, None)).unwrap();
            vault.put_record(rec(2, 2_000, Some(pid(0xA)))).unwrap();
            vault.put_record(rec(3, 3_000, Some(pid(0xB)))).unwrap();
            vault.forget_group(pid(0xA)).unwrap();
            let left = vault.all_records().unwrap();
            assert_eq!(left.len(), 2);
            assert!(left.iter().all(|r| r.group_id != Some(pid(0xA))));
            assert!(left.iter().any(|r| r.group_id.is_none()));
            assert!(left.iter().any(|r| r.group_id == Some(pid(0xB))));
            // A malformed id is refused and nothing is erased.
            assert!(matches!(
                vault.forget_group("not hex".into()),
                Err(StoreError::InvalidInput(_))
            ));
            assert_eq!(vault.all_records().unwrap().len(), 2);
            // A closed vault answers Closed.
            vault.close_vault().unwrap();
            assert!(matches!(
                vault.forget_group(pid(0xB)),
                Err(StoreError::Closed)
            ));
            let _ = std::fs::remove_file(&path);
        }

        #[test]
        fn revision_and_redistribution_records_pack_and_type_seven_is_still_refused() {
            // D-141: 0x05 is the expense revision. D-210: 0x06 is the
            // redistribution. The next unassigned type must still be refused,
            // never misparsed as something else.
            for (seed, ty) in [(0x45u8, 5u8), (0x46, 6)] {
                let mut record = sample_record(seed);
                record.record_type = ty;
                let packed = ffi_record_pack(record.clone()).unwrap();
                assert_eq!(ffi_record_unpack(packed).unwrap(), record);
            }
            let mut unknown = sample_record(0x47);
            unknown.record_type = 7;
            assert!(matches!(
                ffi_record_pack(unknown),
                Err(StoreError::InvalidInput(_))
            ));
        }

        /// D-235 L3: uniffi's own conversion (the one the callback scaffolding
        /// runs) finds the `From` and gives a Keystore error, never a panic.
        #[test]
        fn d235_an_unexpected_keystore_exception_becomes_a_keystore_error() {
            let e = uniffi::UnexpectedUniFFICallbackError::new("IllegalStateException: no key");
            let converted: StoreError =
                uniffi::convert_unexpected_error!(e, StoreError).expect("a From exists");
            assert_eq!(
                converted,
                StoreError::Keystore("IllegalStateException: no key".into())
            );
        }

        #[test]
        fn hlc_next_and_receive_follow_the_tick_and_receive_rules() {
            // Tick within the same millisecond: the counter breaks the tie
            // (ADR-0034 rule 1: no artificial latency, ever).
            // D-237 (A2): plus a random step of at most STEP_MASK.
            let within = |s: &HlcStamp, millis: u64, lo: u32| {
                s.millis == millis && s.counter >= lo && s.counter <= lo + crate::straumr::STEP_MASK
            };
            let a = ffi_hlc_next(5_000, 5_000, 0);
            assert!(within(&a, 5_000, 1), "{} {}", a.millis, a.counter);
            // A moved-forward clock resets the counter.
            let b = ffi_hlc_next(6_000, 5_000, 7);
            assert!(within(&b, 6_000, 0), "{} {}", b.millis, b.counter);
            // A stale clock keeps the state's millis, counter advances.
            let c = ffi_hlc_next(4_000, 5_000, 2);
            assert!(within(&c, 5_000, 3), "{} {}", c.millis, c.counter);

            // Receive merges a remote stamp: the state advances past BOTH
            // and the next local stamp sorts after everything seen.
            let r = ffi_hlc_receive(5_000, 0, 9_000, 4, 5_000);
            assert_eq!((r.millis, r.counter), (9_000, 5));
            let next = ffi_hlc_next(r.millis, r.millis, r.counter);
            assert!(within(&next, 9_000, 6), "{} {}", next.millis, next.counter);
            // Wall clock ahead of both wins.
            let r2 = ffi_hlc_receive(5_000, 0, 4_000, 0, 20_000);
            assert_eq!((r2.millis, r2.counter), (20_000, 0));

            // D-235 A3 (decision 104), through the boundary the shells call:
            // a record stamped in 2100 leaves a 2026 phone's clock alone, and
            // a full counter carries instead of wrapping.
            let wall = 1_790_000_000_000;
            let r3 = ffi_hlc_receive(wall, 2, 4_102_444_800_000, 0, wall);
            assert_eq!((r3.millis, r3.counter), (wall, 2));
            let r4 = ffi_hlc_receive(wall, 0, wall, u32::MAX, wall);
            assert_eq!((r4.millis, r4.counter), (wall + 1, 0xFF << 24));
            let n4 = ffi_hlc_next(wall, wall, u32::MAX);
            assert!(
                within(&n4, wall + 1, 0xFF << 24),
                "{} {}",
                n4.millis,
                n4.counter
            );
        }

        #[test]
        fn vault_lifecycle_round_trips_records_kv_and_rejects_wrong_keys() {
            let dir = std::env::temp_dir();
            let path = dir.join(format!(
                "skipti-ffi-vault-{}.db",
                hex_encode(&crate::lykill::random_bytes(8))
            ));
            let path_str = path.to_string_lossy().into_owned();

            // Open, write one record + kv, read back.
            {
                let vault = SkiptiVault::open(path_str.clone(), Box::new(MemKeystoreFfi::new()))
                    .expect("first open provisions");
                assert_eq!(vault.schema_version().unwrap(), 2);
                vault.put_record(sample_record(0x11)).unwrap();
                vault
                    .kv_set("cursor/personal".into(), vec![1, 2, 3])
                    .unwrap();

                let records = vault.all_records().unwrap();
                assert_eq!(records.len(), 1);
                assert_eq!(records[0].hlc_device_id, pid(7));
                assert_eq!(records[0].hlc_counter, 3);
                assert_eq!(records[0].blob, vec![0xDE, 0xAD, 0xBE, 0xEF]);
                assert_eq!(records[0].group_id, None);
                assert_eq!(
                    vault.kv_get("cursor/personal".into()).unwrap(),
                    Some(vec![1, 2, 3])
                );
            } // ARC drop closes

            // A fresh empty keystore CANNOT open an existing vault: skrin
            // would provision a brand-new key and SQLCipher answers NotADb,
            // surfacing as WrongKey, never a silent re-key (D-39).
            {
                let result = SkiptiVault::open(path_str.clone(), Box::new(MemKeystoreFfi::new()));
                assert!(
                    matches!(&result, Err(StoreError::WrongKey)),
                    "expected WrongKey, got {result:?}"
                );
                drop(result);
            }

            // Close semantics on a throwaway vault: close() twice, and every
            // later operation answers Closed rather than crashing.
            {
                let tmp2 = dir.join(format!(
                    "skipti-ffi-close-{}.db",
                    hex_encode(&crate::lykill::random_bytes(8))
                ));
                let vault = SkiptiVault::open(
                    tmp2.to_string_lossy().into_owned(),
                    Box::new(MemKeystoreFfi::new()),
                )
                .unwrap();
                vault.close_vault().unwrap();
                assert_eq!(vault.close_vault(), Err(StoreError::Closed));
                assert_eq!(vault.schema_version(), Err(StoreError::Closed));
                assert_eq!(vault.put_record(sample_record(1)), Err(StoreError::Closed));
                assert_eq!(vault.all_records(), Err(StoreError::Closed));
                let _ = std::fs::remove_file(&tmp2);
            }

            let _ = std::fs::remove_file(&path);
        }
    }
}

#[cfg(test)]
mod merki_ffi_tests {
    use super::{
        ffi_auto_categorise, ffi_category_tree_default, ffi_merchant_table_bundled,
        ffi_rule_new_id, ffi_rule_validate, ffi_rules_evaluate, ffi_rules_evaluate_batch,
    };
    use crate::merki::*;

    /// The §8 FFI surface against the real generated boundary: defaults,
    /// a rule's full lifecycle, and the engine: the same objects Swift
    /// will hold.
    #[test]
    fn category_and_rules_round_trip_through_ffi_types() {
        let tree = ffi_category_tree_default();
        let delivery = tree
            .assignable_leaves()
            .iter()
            .find(|c| c.name == "Delivery")
            .unwrap()
            .id
            .clone();

        let rule = Rule {
            id: ffi_rule_new_id(),
            name: "swiggy".into(),
            enabled: true,
            conditions: vec![Condition::Merchant(MerchantMatch::Prefix {
                text: "swiggy".into(),
            })],
            actions: vec![Action::SetCategory {
                category_id: delivery.clone(),
            }],
        };
        ffi_rule_validate(tree.clone(), rule.clone()).unwrap();

        let ctx = RuleContext {
            merchant: Some("Swiggy dinner".into()),
            amount_minor: 45_000,
            currency: "INR".into(),
            day_of_month: 12,
            direction: "expense".into(),
            account: None,
            source: Some("splitwise".into()),
        };
        let out = ffi_rules_evaluate(tree.clone(), vec![rule.clone()], ctx.clone()).unwrap();
        assert_eq!(out.category_id.as_deref(), Some(delivery.as_str()));

        // Batch == per-item, and errors cross as the typed enum:
        let batch = ffi_rules_evaluate_batch(tree.clone(), vec![rule], vec![ctx]).unwrap();
        assert_eq!(batch.len(), 1);

        let bad = Rule {
            id: ffi_rule_new_id(),
            name: "header".into(),
            enabled: true,
            conditions: vec![Condition::Direction {
                value: "expense".into(),
            }],
            actions: vec![Action::SetCategory {
                category_id: tree
                    .categories
                    .iter()
                    .find(|c| c.name == "Food & Drink")
                    .unwrap()
                    .id
                    .clone(),
            }],
        };
        assert!(matches!(
            ffi_rule_validate(tree, bad),
            Err(MerkiError::NotAssignable(_))
        ));
    }

    /// §8.2 through the boundary: bundled table + pipeline, and the
    /// determinism contract holds across the FFI too.
    #[test]
    fn auto_categorise_over_the_ffi_is_deterministic() {
        let tree = ffi_category_tree_default();
        let table = ffi_merchant_table_bundled();
        assert!(table.entries.len() >= 100);
        let ctx = crate::merki::RuleContext {
            merchant: Some("Uber Eats".into()),
            amount_minor: 45_000,
            currency: "INR".into(),
            day_of_month: 9,
            direction: "expense".into(),
            account: None,
            source: None,
        };
        let a = ffi_auto_categorise(tree.clone(), table.clone(), vec![], ctx.clone()).unwrap();
        let b = ffi_auto_categorise(tree, table, vec![], ctx).unwrap();
        assert_eq!(a, b, "the same merchant classifies identically, every run");
        assert!(a.is_some(), "uber eats is in the bundled starter table");
    }
}
