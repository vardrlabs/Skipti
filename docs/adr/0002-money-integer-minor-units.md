# ADR-0002: Money as integer minor units, never floating point

Status: Accepted

## Context

`0.1 + 0.2 !== 0.3` is not a curiosity here; it is a bug report from a user
whose group balance is off by two paise and who no longer trusts the app.
Minor-unit exponents also vary by currency: USD and INR are 2, JPY and KRW
are 0, BHD/KWD/OMR are 3. Hardcoding 100 breaks in exactly the markets we
ship to.

## Decision

Money is an integer count of minor units plus an ISO 4217 code:

```
struct Money { amount: i128, currency: Currency }
```

- No `f32`/`f64` anywhere in the money path; CI fails the build on either.
- Arithmetic on `Money` is only legal inside `norn`; elsewhere it is a build
  failure (enforced by module privacy plus a lint).
- Never sum across currencies without an explicit rate and timestamp.
- Locale formatting includes en-IN lakh/crore grouping.

## Consequences

- All display formatting derives from integers; rounding at the display
  layer can never hide an arithmetic bug.
- i128 gives headroom far beyond any realistic ledger total.
- Every conversion to float is a reviewed boundary (export cells, charts)
  with an explicit test that round-trips exactly through minor units.
