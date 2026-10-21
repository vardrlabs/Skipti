use core::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MoneyError {
    MismatchedCurrencies { a: &'static str, b: &'static str },
    UnknownCurrency(String),
    Overflow,
    InvalidInput(&'static str),
}

impl fmt::Display for MoneyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            MoneyError::MismatchedCurrencies { a, b } => {
                write!(f, "cannot combine {a} and {b} without an explicit rate")
            }
            MoneyError::UnknownCurrency(c) => write!(f, "unknown ISO 4217 code: {c}"),
            MoneyError::Overflow => write!(f, "amount overflow"),
            MoneyError::InvalidInput(m) => write!(f, "invalid input: {m}"),
        }
    }
}

impl std::error::Error for MoneyError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Currency {
    code: &'static str,
}

const ZERO_DECIMAL: &[&str] = &[
    "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "VND", "VUV",
    "XAF", "XOF", "XPF",
];

const THREE_DECIMAL: &[&str] = &["BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND"];

const TWO_DECIMAL: &[&str] = &[
    "AED", "AFN", "ALL", "AMD", "ANG", "AOA", "ARS", "AUD", "AWG", "AZN", "BAM", "BBD", "BDT",
    "BGN", "BMD", "BND", "BOB", "BRL", "BSD", "BTN", "BWP", "BYN", "BZD", "CAD", "CDF", "CHF",
    "CNY", "COP", "CRC", "CUP", "CVE", "CZK", "DKK", "DOP", "DZD", "EGP", "ERN", "ETB", "EUR",
    "FJD", "FKP", "GBP", "GEL", "GGP", "GHS", "GIP", "GMD", "GTQ", "GYD", "HKD", "HNL", "HRK",
    "HTG", "HUF", "IDR", "ILS", "IMP", "INR", "IRR", "JEP", "JMD", "KES", "KGS", "KHR", "KID",
    "KPW", "KYD", "KZT", "LAK", "LBP", "LKR", "LRD", "LSL", "MAD", "MDL", "MGA", "MKD", "MMK",
    "MNT", "MOP", "MRU", "MUR", "MVR", "MWK", "MXN", "MYR", "MZN", "NAD", "NGN", "NIO", "NOK",
    "NPR", "NZD", "PAB", "PEN", "PGK", "PHP", "PKR", "PLN", "PRB", "QAR", "RON", "RSD", "RUB",
    "SAR", "SBD", "SCR", "SDG", "SEK", "SGD", "SHP", "SLE", "SOS", "SRD", "SSP", "STN", "SVC",
    "SYP", "SZL", "THB", "TJS", "TMT", "TOP", "TRY", "TTD", "TVD", "TWD", "TZS", "UAH", "USD",
    "UYU", "UYW", "UZS", "VED", "VES", "WST", "XCD", "YER", "ZAR", "ZMW", "ZWG",
];

impl Currency {
    pub fn parse(code: &str) -> Result<Currency, MoneyError> {
        if code.len() != 3 || !code.bytes().all(|b| b.is_ascii_uppercase()) {
            return Err(MoneyError::UnknownCurrency(code.to_string()));
        }
        interned(code)
            .map(|c| Currency { code: c })
            .ok_or_else(|| MoneyError::UnknownCurrency(code.to_string()))
    }

    pub const INR: Currency = Currency { code: "INR" };
    pub const USD: Currency = Currency { code: "USD" };
    pub const EUR: Currency = Currency { code: "EUR" };
    pub const GBP: Currency = Currency { code: "GBP" };
    pub const JPY: Currency = Currency { code: "JPY" };

    pub fn code(self) -> &'static str {
        self.code
    }

    pub fn exponent(self) -> u8 {
        if ZERO_DECIMAL.contains(&self.code) {
            0
        } else if THREE_DECIMAL.contains(&self.code) {
            3
        } else {
            2
        }
    }

    pub fn minor_per_major(self) -> i128 {
        10i128.pow(self.exponent() as u32)
    }
}

/// D-165: every code `Currency::parse` accepts, sorted, so both shells'
/// "All currencies" list (SPEC §5.2: full ISO 4217 in the picker) is the
/// exact set core can store, and the twins cannot drift.
pub fn all_codes() -> Vec<&'static str> {
    let mut codes: Vec<&'static str> = ZERO_DECIMAL
        .iter()
        .chain(THREE_DECIMAL.iter())
        .chain(TWO_DECIMAL.iter())
        .copied()
        .collect();
    codes.sort_unstable();
    codes
}

fn interned(code: &str) -> Option<&'static str> {
    ZERO_DECIMAL
        .iter()
        .chain(THREE_DECIMAL.iter())
        .chain(TWO_DECIMAL.iter())
        .find(|c| **c == code)
        .copied()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Money {
    pub amount: i128,
    pub currency: Currency,
}

impl Money {
    pub fn new(amount: i128, currency: Currency) -> Money {
        Money { amount, currency }
    }

    pub fn zero(currency: Currency) -> Money {
        Money {
            amount: 0,
            currency,
        }
    }

    pub fn checked_add(self, other: Money) -> Result<Money, MoneyError> {
        self.same_currency(other)?;
        Ok(Money {
            amount: self
                .amount
                .checked_add(other.amount)
                .ok_or(MoneyError::Overflow)?,
            currency: self.currency,
        })
    }

    pub fn checked_sub(self, other: Money) -> Result<Money, MoneyError> {
        self.same_currency(other)?;
        Ok(Money {
            amount: self
                .amount
                .checked_sub(other.amount)
                .ok_or(MoneyError::Overflow)?,
            currency: self.currency,
        })
    }

    pub fn checked_mul_i128(self, qty: i128) -> Result<Money, MoneyError> {
        Ok(Money {
            amount: self.amount.checked_mul(qty).ok_or(MoneyError::Overflow)?,
            currency: self.currency,
        })
    }

    fn same_currency(&self, other: Money) -> Result<(), MoneyError> {
        if self.currency != other.currency {
            return Err(MoneyError::MismatchedCurrencies {
                a: self.currency.code(),
                b: other.currency.code(),
            });
        }
        Ok(())
    }

    pub fn signum(self) -> i32 {
        match self.amount {
            n if n > 0 => 1,
            0 => 0,
            _ => -1,
        }
    }

    pub fn abs(self) -> Money {
        Money {
            amount: self.amount.abs(),
            currency: self.currency,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exponents_cover_decimal_variants() {
        assert_eq!(Currency::JPY.exponent(), 0);
        assert_eq!(Currency::parse("KRW").unwrap().exponent(), 0);
        assert_eq!(Currency::USD.exponent(), 2);
        assert_eq!(Currency::INR.exponent(), 2);
        assert_eq!(Currency::parse("KWD").unwrap().exponent(), 3);
        assert_eq!(Currency::parse("BHD").unwrap().exponent(), 3);
        assert_eq!(Currency::parse("OMR").unwrap().exponent(), 3);
    }

    #[test]
    fn all_codes_is_every_parsable_code_sorted_once() {
        let codes = all_codes();
        // 164, counted independently from the three tables in Python.
        assert_eq!(codes.len(), 164);
        assert_eq!(&codes[..3], &["AED", "AFN", "ALL"]);
        assert_eq!(&codes[codes.len() - 3..], &["ZAR", "ZMW", "ZWG"]);
        assert!(codes.windows(2).all(|w| w[0] < w[1]), "sorted, no repeats");
        for code in &codes {
            assert_eq!(Currency::parse(code).unwrap().code(), *code);
        }
        for promoted in [
            "INR", "USD", "EUR", "GBP", "JPY", "AED", "SGD", "AUD", "CAD",
        ] {
            assert!(codes.contains(&promoted), "{promoted}");
        }
        assert!(!codes.contains(&"XYZ"));
    }

    #[test]
    fn unknown_currency_rejected() {
        assert!(Currency::parse("XYZQ").is_err());
        assert!(Currency::parse("inr").is_err());
        assert!(Currency::parse("IN").is_err());
    }

    #[test]
    fn cross_currency_arithmetic_is_a_hard_error() {
        let inr = Money::new(100, Currency::INR);
        let usd = Money::new(100, Currency::USD);
        assert_eq!(
            inr.checked_add(usd),
            Err(MoneyError::MismatchedCurrencies { a: "INR", b: "USD" })
        );
        assert_eq!(
            inr.checked_sub(usd),
            Err(MoneyError::MismatchedCurrencies { a: "INR", b: "USD" })
        );
    }

    #[test]
    fn overflow_is_checked_not_wrapping() {
        let big = Money::new(i128::MAX, Currency::INR);
        assert_eq!(big.checked_add(big), Err(MoneyError::Overflow));
    }

    #[test]
    fn integer_math_only_round_trips_minor_units() {
        let m = Money::new(12345, Currency::INR);
        assert_eq!(m.amount, 12_345);
        assert_eq!(m.currency.minor_per_major(), 100);
        let jpy = Money::new(-7, Currency::JPY);
        assert_eq!(jpy.signum(), -1);
    }
}
