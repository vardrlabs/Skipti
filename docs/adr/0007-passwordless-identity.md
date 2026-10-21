# ADR-0007: Passwordless identity — passkeys and OTP only

Status: Accepted

## Context

Passwords bring a password database, password reset, credential stuffing
surface, and the bcrypt-versus-argon debate. None of it is necessary.

## Decision

No password field exists anywhere in the product.

- Primary: Passkey/WebAuthn (phishing-resistant, no shared secret server-side).
- Sign in with Apple where any third-party sign-in is offered (App Store
  requirement); private relay email, never the real one.
- Google Sign-In with minimum scopes (`openid`; `email` only if needed).
  ADR-0035 (2026-09-14) settles how: `openid` only, the system sign-in
  surface only, and GUARANTEES #6's sign-in exception.
- Email OTP: 6 digits, 10-minute TTL, single use, 5 attempts then lock.
- Phone OTP: session authentication only; it can never authorise device
  addition, key rotation, or escrow unwrap (SIM swap is a real attack).

Authentication identity is completely separate from encryption identity:
signing in with a compromised OAuth account yields ciphertext and a
passphrase prompt, nothing more. This is stated in onboarding copy because
users assume the opposite.

Sessions: 15-minute in-memory access tokens; rotating refresh tokens with
reuse detection that revokes the whole family on replay; every session listed
as a revocable device.

## Consequences

- Enumeration-safe auth paths: identical bodies and timing for "no such
  account" vs "wrong code", constant-time comparison, 250 ms floor.
- If passkey adoption proves too aggressive for some users, the fallback is
  email OTP — not passwords.
