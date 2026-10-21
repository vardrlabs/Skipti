# ADR-0006: Recovery Kit instead of password reset

Status: Accepted

## Context

"Email me a reset link" means the server can regenerate access, which means
the server has access. Password reset quietly destroys E2EE in most products.

## Decision

No password reset exists. A Recovery Kit — 12 words from the BIP-39 list,
132 bits, checksummed — is generated at setup, before the user has data to
lose. The flow requires confirming three random words back before
proceeding; there is no skip button. Downloadable as PDF, printable, never
emailed, never synced, never screenshotted into the photo library by the app.
Re-viewable later behind a fresh biometric prompt. One nudge at 7 days if
never viewed again, then silence — nagging trains people to ignore the app.

The docs must state plainly that this is not a cryptocurrency wallet.

Default unlock mode is Passphrase + escrow: MK wrapped by a passphrase-derived
KEK stored server-side. The escrow blob is useless without the passphrase, so
the server still cannot open it.

## Consequences

- Lost device + forgotten passphrase + no kit = unrecoverable. This is
  stated honestly at setup rather than hidden behind a support promise we
  could not keep without breaking guarantee 7.
- Support cannot "help" with recovery; this is a feature.
