//! D-235 (decision 103): the money rules every expense and settlement record
//! keeps, checked where a record ARRIVES (both shells' ingest, and revision
//! decode), so a record written by another member's phone (a modified client,
//! a buggy or older build) never reaches the ledger. Before this, nothing
//! checked a group record's money: one bad record broke export for every
//! member (GUARANTEES #4), made a group's nets stop summing to zero, and could
//! fail the Home overview for every group (core audit H1).
//!
//! A record that passes here passes export's own validation
//! (`flutningr::validate_snapshot`) and norn's `apply_expense` sums, so it
//! can neither stop an export nor unbalance a ledger. Membership is NOT
//! checked here: it depends on the group's history at the record's time,
//! which the arriving record alone cannot show.

use super::revision::MoneyValue;
use crate::money::Currency;
use std::fmt;

/// The largest amount, in minor units, any record may carry: 10^15 (ten
/// trillion rupees in paise, a quadrillion yen). Far past any real expense,
/// and small enough that thousands of records at the bound still sum
/// inside an i64, so no total a screen shows can overflow.
pub const MAX_AMOUNT_MINOR: i64 = 1_000_000_000_000_000;

/// D-235 (decision 110 a): the longest free text a record carries (an
/// expense's note, a revision's note, category and editor, a group's name),
/// in characters. No screen limits a note, so a pasted page could make a
/// record the cloud refuses (blobs at most 192 KiB). At 1,000 characters a
/// field is at most 6,000 bytes even in its worst JSON form (a control
/// character escaped as six), so every record stays far under the cap.
/// Member names keep their own 64 (`groups::MEMBER_NAME_MAX_CHARS`).
pub const TEXT_MAX_CHARS: usize = 1_000;

/// Free text no longer than [`TEXT_MAX_CHARS`] characters.
pub fn check_text(text: &str) -> Result<(), CheckError> {
    if text.chars().count() > TEXT_MAX_CHARS {
        Err(CheckError::TextTooLong)
    } else {
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CheckError {
    /// An id that is not 32 lowercase hex (what it was named in the record).
    Id(&'static str),
    UnknownCurrency(String),
    /// A negative amount (an expense total, a share, a conversion).
    Negative,
    /// An amount past [`MAX_AMOUNT_MINOR`].
    TooLarge,
    NoShares,
    DuplicateShare,
    /// The shares do not add up to the amount they split.
    SumMismatch {
        expected: i128,
        got: i128,
    },
    /// A frozen conversion core would not have produced.
    Conversion(String),
    /// A settlement between one person and themselves.
    SelfSettlement,
    /// A settlement of zero or less.
    NonPositiveSettlement,
    /// Free text past [`TEXT_MAX_CHARS`].
    TextTooLong,
}

impl fmt::Display for CheckError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CheckError::Id(what) => write!(f, "{what} id is not 32 lowercase hex"),
            CheckError::UnknownCurrency(c) => write!(f, "unknown ISO 4217 code: {c}"),
            CheckError::Negative => write!(f, "a negative amount"),
            CheckError::TooLarge => write!(f, "an amount past {MAX_AMOUNT_MINOR} minor units"),
            CheckError::NoShares => write!(f, "an expense with no shares"),
            CheckError::DuplicateShare => write!(f, "a person holds two shares"),
            CheckError::SumMismatch { expected, got } => {
                write!(f, "shares sum to {got}, total is {expected}")
            }
            CheckError::Conversion(m) => write!(f, "frozen conversion: {m}"),
            CheckError::SelfSettlement => write!(f, "a settlement from a person to themselves"),
            CheckError::NonPositiveSettlement => write!(f, "a settlement of zero or less"),
            CheckError::TextTooLong => {
                write!(f, "a text longer than {TEXT_MAX_CHARS} characters")
            }
        }
    }
}

impl std::error::Error for CheckError {}

/// 32 lowercase hex: the only id form any phone writes. Uppercase is refused
/// so that two spellings of one id can never be two people in a string
/// comparison (core audit doubt 5).
pub fn is_id(s: &str) -> bool {
    s.len() == 32
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn id(s: &str, what: &'static str) -> Result<(), CheckError> {
    if is_id(s) {
        Ok(())
    } else {
        Err(CheckError::Id(what))
    }
}

fn currency(code: &str) -> Result<Currency, CheckError> {
    Currency::parse(code).map_err(|_| CheckError::UnknownCurrency(code.to_string()))
}

fn amount(v: i64) -> Result<(), CheckError> {
    if v < 0 {
        Err(CheckError::Negative)
    } else if v > MAX_AMOUNT_MINOR {
        Err(CheckError::TooLarge)
    } else {
        Ok(())
    }
}

/// An expense's money: payer and every share holder are ids, the currency
/// is known, the total is between 0 and [`MAX_AMOUNT_MINOR`], there is at
/// least one share, no person holds two, no share is negative (no split
/// produces one: Exact refuses it, and since D-235 so does Adjustment), and
/// the shares sum to the total exactly.
///
/// A frozen conversion must be exactly what `fx::convert` gives from its own
/// rate, and the shares must ALSO sum to the converted amount: balances
/// (norn) read shares against the converted amount while export reads them
/// against the original, so a record where the two differ would either
/// unbalance a ledger or stop an export. No phone writes a conversion today
/// (both shells write none); when FX entry is built, the currency the shares
/// are in is decided there and this rule follows it.
pub fn check_money(m: &MoneyValue) -> Result<(), CheckError> {
    id(&m.payer, "payer")?;
    let paid_currency = currency(&m.currency_code)?;
    amount(m.paid_minor)?;
    if m.shares.is_empty() {
        return Err(CheckError::NoShares);
    }
    // D-235 round 2 (review M3): a set, so a record with thousands of shares
    // costs n log n, not n squared.
    let mut holders: std::collections::BTreeSet<&str> = std::collections::BTreeSet::new();
    let mut sum: i128 = 0;
    for s in &m.shares {
        id(&s.id, "share holder")?;
        if !holders.insert(s.id.as_str()) {
            return Err(CheckError::DuplicateShare);
        }
        amount(s.amount_minor)?;
        sum += s.amount_minor as i128;
    }
    if sum != m.paid_minor as i128 {
        return Err(CheckError::SumMismatch {
            expected: m.paid_minor as i128,
            got: sum,
        });
    }
    if let Some(c) = &m.conversion {
        let target = currency(&c.converted_currency)?;
        amount(c.converted_minor)?;
        let rate = crate::fx::FxRate::new(
            paid_currency,
            target,
            c.rate_num as i128,
            c.rate_den as i128,
            c.rate_timestamp_ms,
        )
        .map_err(|e| CheckError::Conversion(e.to_string()))?;
        let converted = crate::fx::convert(
            crate::money::Money::new(m.paid_minor as i128, paid_currency),
            rate,
        )
        .map_err(|e| CheckError::Conversion(e.to_string()))?;
        if converted.converted.amount != c.converted_minor as i128 {
            return Err(CheckError::Conversion(format!(
                "records {}, the rate yields {}",
                c.converted_minor, converted.converted.amount
            )));
        }
        if sum != c.converted_minor as i128 {
            return Err(CheckError::SumMismatch {
                expected: c.converted_minor as i128,
                got: sum,
            });
        }
    }
    Ok(())
}

/// A settlement: two different ids, a known currency, an amount above 0 and
/// at most [`MAX_AMOUNT_MINOR`].
pub fn check_settlement(
    from: &str,
    to: &str,
    amount_minor: i64,
    currency_code: &str,
) -> Result<(), CheckError> {
    id(from, "settlement sender")?;
    id(to, "settlement receiver")?;
    if from == to {
        return Err(CheckError::SelfSettlement);
    }
    currency(currency_code)?;
    if amount_minor <= 0 {
        return Err(CheckError::NonPositiveSettlement);
    }
    amount(amount_minor)
}

/// A record's group, when it names one: an id.
pub fn check_group(group: Option<&str>) -> Result<(), CheckError> {
    match group {
        None => Ok(()),
        Some(g) => id(g, "group"),
    }
}
