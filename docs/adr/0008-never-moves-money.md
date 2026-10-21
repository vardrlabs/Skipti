# ADR-0008: SKIPTI records debts and never moves money

Status: Accepted

## Context

The instant funds flow through the system, the product becomes a money
transmitter in the US, a Payment Aggregator under RBI rules in India, and a
PSD2-regulated entity in the EU. That is a licensed business, not a
compliance checklist.

## Decision

Settle-up computes the amount, records a signed Settlement in the ledger, and
hands off via deep link:

- India: UPI (`upi://pay?...`)
- US: Venmo / Cash App / PayPal.me deep links
- EU: SEPA payment request QR (EPC069-12)
- Elsewhere: copy bank details, or mark as paid

SKIPTI never sees an account number and never touches funds.

## Consequences

- No bank aggregation (no Plaid), no credit/BNPL, no investment advice, no
  custody — ever. These are recorded as design constraints in
  CONTRIBUTING.md so no well-meaning contributor adds a "just hold the
  balance for a second" feature that changes the project's legal character.
- Any PR that would make SKIPTI hold or move funds is closed on sight.
