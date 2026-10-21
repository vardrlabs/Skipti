use crate::money::{Currency, Money, MoneyError};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FxRate {
    pub from: Currency,
    pub to: Currency,
    pub num: i128,
    pub den: i128,
    pub timestamp_ms: u64,
}

impl FxRate {
    pub fn new(
        from: Currency,
        to: Currency,
        num: i128,
        den: i128,
        timestamp_ms: u64,
    ) -> Result<Self, MoneyError> {
        if num <= 0 || den <= 0 {
            return Err(MoneyError::InvalidInput("rate must be positive"));
        }
        Ok(FxRate {
            from,
            to,
            num,
            den,
            timestamp_ms,
        })
    }

    pub fn identity(c: Currency, timestamp_ms: u64) -> Self {
        FxRate {
            from: c,
            to: c,
            num: 1,
            den: 1,
            timestamp_ms,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Conversion {
    pub original: Money,
    pub converted: Money,
    pub rate_num: i128,
    pub rate_den: i128,
    pub rate_timestamp_ms: u64,
}

pub fn div_round_half_up(numerator: i128, denominator: i128) -> i128 {
    debug_assert!(denominator > 0);
    let sign = numerator.signum();
    let n = numerator.abs();
    let q = n / denominator;
    let r = n % denominator;
    sign * (if 2 * r >= denominator { q + 1 } else { q })
}

pub fn convert(amount: Money, rate: FxRate) -> Result<Conversion, MoneyError> {
    if rate.from != amount.currency {
        return Err(MoneyError::MismatchedCurrencies {
            a: amount.currency.code(),
            b: rate.from.code(),
        });
    }
    let scaled = amount
        .amount
        .checked_mul(rate.num)
        .ok_or(MoneyError::Overflow)?;
    let out = div_round_half_up(scaled, rate.den);
    Ok(Conversion {
        original: amount,
        converted: Money::new(out, rate.to),
        rate_num: rate.num,
        rate_den: rate.den,
        rate_timestamp_ms: rate.timestamp_ms,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rate_inr_usd(ts: u64) -> FxRate {
        FxRate::new(Currency::INR, Currency::USD, 12_000, 1_000_000, ts).unwrap()
    }

    #[test]
    fn conversion_freezes_original_rate_and_timestamp() {
        let conv = convert(Money::new(830_000, Currency::INR), rate_inr_usd(1)).unwrap();
        assert_eq!(conv.original, Money::new(830_000, Currency::INR));
        assert_eq!(conv.rate_num, 12_000);
        assert_eq!(conv.rate_den, 1_000_000);
        assert_eq!(conv.rate_timestamp_ms, 1);
    }

    #[test]
    fn later_table_updates_never_change_a_frozen_record() {
        let conv = convert(Money::new(100, Currency::INR), rate_inr_usd(1)).unwrap();
        let moved = FxRate::new(Currency::INR, Currency::USD, 20_000, 1_000_000, 2).unwrap();
        let after = convert(conv.original, moved).unwrap();
        assert_ne!(after.converted, conv.converted);
        assert_eq!(conv.converted, Money::new(1, Currency::USD));
    }

    #[test]
    fn rounding_is_deterministic_half_up_on_magnitude() {
        assert_eq!(div_round_half_up(5, 10), 1);
        assert_eq!(div_round_half_up(4, 10), 0);
        assert_eq!(div_round_half_up(-5, 10), -1);
        assert_eq!(div_round_half_up(-4, 10), 0);
    }

    #[test]
    fn mismatched_rate_currency_is_rejected() {
        let usd_rate = FxRate::new(Currency::USD, Currency::EUR, 9_200, 10_000, 1).unwrap();
        assert!(convert(Money::new(100, Currency::INR), usd_rate).is_err());
    }

    #[test]
    fn zero_and_negative_amounts_convert() {
        let conv = convert(Money::zero(Currency::INR), rate_inr_usd(1)).unwrap();
        assert_eq!(conv.converted.amount, 0);
        let neg = convert(Money::new(-83_000, Currency::INR), rate_inr_usd(1)).unwrap();
        assert_eq!(neg.converted.amount, -996);
    }
}
