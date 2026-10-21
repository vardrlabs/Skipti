# ADR-0017: Capture produces proposals in a review inbox, never auto-committed entries

Status: Accepted

## Context

A finance app that silently invents entries is worse than one that misses
them. One UPI payment can generate three notifications (bank app, UPI app,
bank SMS); naive capture triples every expense.

## Decision

Every captured item — SMS, notification, OCR, share sheet — lands as a
proposal in a review inbox with a badge. One tap accepts; one tap
categorises; one swipe discards.

Deduplication: key `(amount, currency, ±90 s window, last4 or reference)`;
precedence reference number > bank app > UPI app > SMS; collapse into ONE
proposal showing all contributing sources; never dedup across different
amounts even if close.

OTP and balance-enquiry patterns are dropped by a pre-filter that runs
before any parsing. Recurring-transaction detection also proposes, never
auto-inserts.

## Consequences

The whole capture subsystem is removable without diminishing the ledger:
the inbox also accepts manual entry, share sheet, OCR, and import.
