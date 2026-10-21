use crate::money::{Currency, Money};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Locale {
    EnIN,
    EnUS,
}

fn group_us(int_digits: &str) -> String {
    let bytes = int_digits.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() + bytes.len() / 3);
    for (i, b) in bytes.iter().enumerate() {
        out.push(*b);
        let remaining = bytes.len() - i - 1;
        if remaining > 0 && remaining.is_multiple_of(3) {
            out.push(b',');
        }
    }
    String::from_utf8(out).expect("ascii digits")
}

fn group_in(int_digits: &str) -> String {
    let n = int_digits.len();
    if n <= 3 {
        return int_digits.to_string();
    }
    let head = &int_digits[..n - 3];
    let tail = &int_digits[n - 3..];
    let mut chunks = Vec::new();
    let hb = head.as_bytes();
    let mut i = hb.len();
    while i > 2 {
        chunks.push(String::from_utf8_lossy(&hb[i - 2..i]).into_owned());
        i -= 2;
    }
    chunks.push(String::from_utf8_lossy(&hb[..i]).into_owned());
    chunks.reverse();
    format!("{},{}", chunks.join(","), tail)
}

fn symbol(code: &str) -> Option<&str> {
    match code {
        "INR" => Some("₹"),
        "USD" => Some("$"),
        "EUR" => Some("€"),
        "GBP" => Some("£"),
        "JPY" => Some("¥"),
        _ => None,
    }
}

pub fn group_integer(int_digits: &str, locale: Locale) -> String {
    match locale {
        Locale::EnIN => group_in(int_digits),
        Locale::EnUS => group_us(int_digits),
    }
}

pub fn format_money(m: Money, locale: Locale) -> String {
    format_parts(m.amount, m.currency, locale)
}

pub fn format_parts(amount: i128, currency: Currency, locale: Locale) -> String {
    let exp = currency.exponent();
    let scale = 10i128.pow(exp as u32);
    let sign = if amount < 0 { "-" } else { "" };
    let abs = amount.abs();
    let int_part = abs / scale;
    let frac = abs % scale;

    let mut out = String::new();
    out.push_str(sign);
    match symbol(currency.code()) {
        Some(s) => out.push_str(s),
        None => {
            out.push_str(currency.code());
            out.push(' ');
        }
    }
    out.push_str(&group_integer(&int_part.to_string(), locale));
    if exp > 0 {
        out.push('.');
        out.push_str(&format!("{frac:0width$}", width = exp as usize));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::money::Currency;

    #[test]
    fn indian_grouping_lakh_crore() {
        assert_eq!(group_in("1234567"), "12,34,567");
        assert_eq!(group_in("123456789"), "12,34,56,789");
        assert_eq!(group_in("100"), "100");
        assert_eq!(group_in("1000"), "1,000");
        assert_eq!(group_in("99999"), "99,999");
    }

    #[test]
    fn inr_en_in_renders_spec_example() {
        let m = Money::new(123456789, Currency::INR);
        assert_eq!(format_money(m, Locale::EnIN), "₹12,34,567.89");
    }

    #[test]
    fn usd_en_us_uses_thousands_grouping() {
        let m = Money::new(123456789, Currency::USD);
        assert_eq!(format_money(m, Locale::EnUS), "$1,234,567.89");
    }

    #[test]
    fn jpy_has_no_fractional_part() {
        let m = Money::new(1234567, Currency::JPY);
        assert_eq!(format_money(m, Locale::EnIN), "¥12,34,567");
        assert_eq!(format_money(m, Locale::EnUS), "¥1,234,567");
    }

    #[test]
    fn three_decimal_currency_keeps_three_places() {
        let kwd = Currency::parse("KWD").unwrap();
        let m = Money::new(1234567, kwd);
        assert_eq!(format_money(m, Locale::EnIN), "KWD 1,234.567");
    }

    #[test]
    fn small_and_negative_values_format_correctly() {
        let m = Money::new(5, Currency::INR);
        assert_eq!(format_money(m, Locale::EnIN), "₹0.05");
        let neg = Money::new(-1240, Currency::INR);
        assert_eq!(format_money(neg, Locale::EnIN), "-₹12.40");
        assert_eq!(
            format_money(Money::zero(Currency::INR), Locale::EnIN),
            "₹0.00"
        );
    }

    #[test]
    fn unknown_symbol_falls_back_to_code_prefix() {
        let xcd = Currency::parse("XCD").unwrap();
        let m = Money::new(4200, xcd);
        assert_eq!(format_money(m, Locale::EnUS), "XCD 42.00");
    }
}
