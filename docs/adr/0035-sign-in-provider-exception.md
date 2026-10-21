# ADR-0035: Guarantee #6 amended — the sign-in provider you choose

Status: Accepted (founder-decided 2026-09-14; amends GUARANTEES #6 and the
"single network origin" clause of ADR-0013)

## Context

GUARANTEES #6 promised: "The app talks to your chosen sync backend and nothing
else." ADR-0007 and SPEC §4.2 have always listed Google Sign-In and Sign in with
Apple as sign-in methods, and D-63 built the relay's Apple verification. The two
promises were never reconciled. Any Google or Apple sign-in puts that company's
servers in the sign-in path, and no wording of the old #6 allows it.

BUILD-LOG §4 recorded Google Sign-In as "implemented nowhere". On 2026-08-29 the
founder confirmed it should be built, because Android users largely do not hold
Apple IDs. On 2026-09-14 he decided that Google Sign-In ships on **both iOS and
Android**, and that whatever has to change to allow it should change.

No released build of SKIPTI ever made the old promise: the app is unpublished.
Amending #6 before the first release reverses nothing anyone relied on.
GUARANTEES.md says a guarantee changes by a fork, not a quiet edit. This ADR is
the loud version: the guarantee's text changes visibly, with a dated note in
GUARANTEES.md, and the exception is narrow and listed in full below.

## Decision

GUARANTEES #6 now reads:

> **No third-party network origin — except the sign-in provider you choose.**
> The app talks to your chosen sync backend and nothing else. No CDN, no fonts,
> no crash reporter, no attribution SDK. The one exception: if you tap Sign in
> with Google or Sign in with Apple, that company's sign-in screen, shown by your
> phone's system, talks to that company. It learns that you signed in to SKIPTI.
> It never sees your ledger, which stays encrypted (#7). If you never tap those
> buttons, the app never contacts Google or Apple.

These constraints keep the exception narrow. Each is binding; breaking one is a
defect even if every test passes.

1. **Only at an explicit tap.** Never at launch, never in the background, never to
   refresh a session. SKIPTI sessions are the relay's own tokens (SPEC §4.3); the
   app keeps no Google or Apple token after sign-in.
2. **Only through the system's sign-in surface.**
   - iOS, Google: `ASWebAuthenticationSession` running the OAuth 2.0
     authorization-code flow with PKCE.
   - iOS, Apple: `ASAuthorizationController`.
   - Android, Google: Credential Manager, which Google Play services carries out.
   - **No embedded Google SDK on iOS.**
3. **The app's own requests go to the relay alone.** Google's and Apple's
   endpoints are reached only by the system sign-in surface and, server-side, by
   the relay. On iOS the relay exchanges the authorization code. On both
   platforms the relay verifies the ID token: signature against the provider's
   published keys, issuer, audience, expiry, and a **single-use nonce the relay
   issued**, so a leaked token cannot be replayed.
4. **Minimum scope: `openid` only.** No email, no profile, no contacts. The relay
   uses only the `sub` claim, as D-63 already does for Apple. Email is not a field
   on the claims type, so it cannot reach a log, a response or a storage row.
5. **Libraries.** The iOS app takes no new dependency. The Android `play`
   flavour takes Google's `com.google.android.libraries.identity.googleid` and
   `androidx.credentials:credentials-play-services-auth`, the Credential Manager
   integration, justified under CONTRIBUTING rule 4 in the commit that adds them.
   The **`direct` flavour takes neither and shows no Google button**: F-Droid and
   GitHub builds do not carry Google Play services libraries (ADR-0010). This
   asymmetry is recorded in BUILD-LOG §4, not hidden.
6. **Authentication stays separate from encryption** (SPEC §4.2). A Google or
   Apple account yields a session that reaches ciphertext, never a key. Adding a
   device still requires approval from an existing device (D-123). Sign-in
   identities are namespaced (`google:` / `apple:`) and **never linked to another
   identity by email**; automatic linking by email is a classic account-takeover
   route.
7. **Nothing else rides the exception.** No analytics, crash reporting, fonts,
   exchange rates, or "sign in to…" prompts (GUARANTEES #5 is unchanged).
   Exchange rates must arrive through the relay as signed sets.

## Consequences

- GUARANTEES #6, SPEC §1.3, SPEC §4.2's Google row, the SPEC §15 standing rule,
  README's summary, THREAT_MODEL.md, ADR-0007 and ADR-0013 change in the same
  commit as this ADR.
- THREAT_MODEL.md gains an honest-limit section for signing in with Google or
  Apple. Its A8 answer ("no third-party SDKs") now names the one permitted
  exception.
- App Store Review Guideline 4.8 / SPEC §12.2: offering Google on iOS makes Sign in
  with Apple mandatory there, so both ship together on iOS.
- Store privacy declarations must match: the provider's user ID is collected and
  linked to the account, for app functionality only; nothing is shared with third
  parties.
- SPEC §14.6 claims filter: the new text is true given §2 (it says what the
  provider learns). It stays true against a malicious operator (A3), who gains
  nothing from a `sub` beyond what the account's existence already reveals. And
  it states its own limit in the same sentence.
- This ADR does not generalise. Any further exception to #6 needs its own ADR of
  this kind, a founder decision, and a visible amendment note in GUARANTEES.md.
